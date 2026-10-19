package com.fifteen.eegsender.protocol

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 真实脑电数据源。
 *
 * 波形来自 OpenBMI SSVEP 公开数据集的真实录制（1000 Hz / 62 通道 / 单试次 4 s），
 * 已离线抽取到 `assets/ssvep/`：
 *
 * - `segments.json` 描述采样率、通道名，以及每个方向的采样区段
 * - `segments.bin` 是 float32 小端、**时间主序** 的原始采样
 *
 * 数据在导出时按类别分组，因此同一方向的采样点在整个文件里是连续的一段，
 * 可以整段**硬回绕**循环播放。实测三种拼接方式（硬切 / 50 ms 交叉淡化 /
 * 整周期截断）对 SSVEP 判别准确率均无影响（144/144），故采用最简单的硬回绕。
 */
class EegSignalSource internal constructor(
    val sampleRate: Int,
    val channelCount: Int,
    val channelNames: List<String>,
    private val data: ByteArray,
    private val regions: Map<Int, Region>
) {

    /** 一个方向对应的连续采样区段（单位：采样点）。 */
    data class Region(val command: Int, val firstSample: Int, val sampleCount: Int)

    /** 单个采样点的字节数（时间主序下即为 通道数 × 每通道字节数）。 */
    val bytesPerSample: Int = channelCount * BYTES_PER_CHANNEL

    /** 相邻采样点之间的纳秒间隔。 */
    val samplePeriodNs: Long = 1_000_000_000L / sampleRate

    // ------------------------------------------------------------ 人为降质开关

    /**
     * 「信号干扰」强度（加噪）。0 表示不降质，可在推流过程中实时改变。
     *
     * 加噪让整体信噪比下降（正确方向与静息一起被拉低），用来验证接收端的**拒识**
     * 能力：信号差的时候它到底会不会乱下指令。
     */
    @Volatile
    var noiseAlpha: Float = 0f

    /**
     * 「SSVEP 削弱」强度（陷波）。0 表示不降质，可在推流过程中实时改变。
     *
     * 陷波精确削弱 4 个刺激频率的谐波谱线而几乎不动背景脑电，正确方向的相关值会
     * 掉到静息基线以下 —— 这是给接收端**标定「无指令」阈值**用的旋钮。
     */
    @Volatile
    var notchAlpha: Float = 0f

    /** 各方向预计算好的降质参数；只在 [prepareDegrade] 里写入，此后只读。 */
    private val degraded = ConcurrentHashMap<Int, SignalDegrade.Prepared>()

    /** 按小端读原始 float 采样。 */
    private val rawBuffer: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    /** 当前是否有降质在生效。 */
    val isDegrading: Boolean get() = noiseAlpha != 0f || notchAlpha != 0f

    /**
     * 预计算某方向的降质参数（陷波成分 + 带限 RMS）。
     *
     * 要跑 62 次 16384 点 FFT，约 0.3~1 s、约占 4 MB，**必须在后台线程调用**：
     * 在推流线程上现算会把 5 ms 的节拍打成成片丢包。已算过时直接返回 0。
     *
     * @return 本次实际耗时（毫秒）；命中缓存返回 0。
     */
    fun prepareDegrade(command: Int): Long {
        if (degraded.containsKey(command)) return 0
        synchronized(degraded) {
            if (degraded.containsKey(command)) return 0
            val region = regionOf(command)
            val startedAt = System.nanoTime()
            val count = region.sampleCount
            val samples = FloatArray(count * channelCount)
            val base = region.firstSample * channelCount
            for (i in samples.indices) {
                samples[i] = rawBuffer.getFloat((base + i) * BYTES_PER_CHANNEL)
            }
            degraded[command] = SignalDegrade.prepare(samples, channelCount, count, sampleRate)
            return (System.nanoTime() - startedAt) / 1_000_000
        }
    }

    /** 某方向的降质参数是否已经算好。 */
    fun isDegradeReady(command: Int): Boolean = degraded.containsKey(command)

    /** 某方向循环一次所需的秒数。 */
    fun durationSeconds(command: Int): Double = regionOf(command).sampleCount.toDouble() / sampleRate

    fun regionOf(command: Int): Region =
        regions[command] ?: throw IllegalArgumentException("数据集缺少「${EegCommands.name(command)}」方向")

    /**
     * 把从 [sourcePos] 开始的 [sampleCount] 个采样点拷到 [dest] 的 [destPos] 处，
     * 越界时**自动回绕**到区段开头，返回实际写入的字节数。
     *
     * 返回字节数通常等于 `sampleCount * bytesPerSample`；仅当区段采样点数不是单包
     * 采样点数的整数倍时才可能略少（本数据集为 16000 与 5，整除，不会发生）。
     */
    fun copyLoop(
        region: Region,
        sourcePos: Int,
        sampleCount: Int,
        dest: ByteArray,
        destPos: Int
    ): Int {
        val notch = notchAlpha
        val noise = noiseAlpha
        // 快路径：两个强度都是 0，或者这一段的降质参数还没预热好。
        // 宁可这一包原样输出，也不能在推流线程上现算 FFT —— 那会卡出成片丢包。
        val prepared = if (notch != 0f || noise != 0f) degraded[region.command] else null
        if (prepared == null) return copyPlain(region, sourcePos, sampleCount, dest, destPos)
        return copyDegraded(region, sourcePos, sampleCount, dest, destPos, prepared, notch, noise)
    }

    /** 不降质时的拷贝：每次命中的都是连续内存块，完全可以整段搬。 */
    private fun copyPlain(
        region: Region,
        sourcePos: Int,
        sampleCount: Int,
        dest: ByteArray,
        destPos: Int
    ): Int {
        var written = 0
        var pos = sourcePos
        var remaining = sampleCount
        while (remaining > 0) {
            val run = minOf(remaining, region.sampleCount - pos)
            if (run <= 0) break
            val from = region.firstSample * bytesPerSample + pos * bytesPerSample
            val bytes = run * bytesPerSample
            System.arraycopy(data, from, dest, destPos + written, bytes)
            written += bytes
            remaining -= run
            pos = (pos + run) % region.sampleCount
        }
        return written
    }

    /**
     * 降质拷贝：逐通道一次乘加
     *
     * `x' = x − notch · band + noise · rms · N(0,1)`
     *
     * `band` 是该段 4 个刺激频率第 1/2/3 次谐波上的成分，与原始采样一样按区段长度
     * 硬回绕，所以降质后的波形仍然可以无缝循环。整条路径上没有任何 FFT —— 强度
     * 怎么变，代价都是一次乘加，可以在推流途中随时拖动滑块。
     */
    private fun copyDegraded(
        region: Region,
        sourcePos: Int,
        sampleCount: Int,
        dest: ByteArray,
        destPos: Int,
        prepared: SignalDegrade.Prepared,
        notch: Float,
        noise: Float
    ): Int {
        val dst = ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN)
        // 每个通道每个采样点一个独立高斯数；有噪声时才取随机源
        val gauss = if (noise != 0f) ThreadLocalRandom.current() else null
        val scale = noise * prepared.rms
        val regionFrom = region.firstSample * bytesPerSample

        var written = 0
        var pos = sourcePos
        var remaining = sampleCount
        while (remaining > 0) {
            val run = minOf(remaining, region.sampleCount - pos)
            if (run <= 0) break
            var j = 0
            while (j < run) {
                val local = pos + j
                val srcBase = regionFrom + local * bytesPerSample
                val dstBase = destPos + written + j * bytesPerSample
                val bandBase = local * channelCount
                var c = 0
                while (c < channelCount) {
                    val offset = c * BYTES_PER_CHANNEL
                    var value = rawBuffer.getFloat(srcBase + offset)
                    if (notch != 0f) value -= notch * prepared.band[bandBase + c]
                    if (gauss != null) value += scale * gauss.nextGaussian().toFloat()
                    dst.putFloat(dstBase + offset, value)
                    c++
                }
                j++
            }
            written += run * bytesPerSample
            remaining -= run
            pos = (pos + run) % region.sampleCount
        }
        return written
    }

    /** 便于日志输出的一行摘要。 */
    fun summary(): String =
        "$channelCount 通道 × $sampleRate Hz，${data.size / 1024 / 1024} MB"

    companion object {
        /** 数据为 float32 小端，每通道 4 字节。 */
        const val BYTES_PER_CHANNEL = 4

        private const val ASSET_DIR = "ssvep"
        private const val MANIFEST = "segments.json"
        private const val BLOB = "segments.bin"

        /**
         * 从 assets 载入数据集。约 17 MB，需在后台线程调用。
         */
        fun load(context: Context): EegSignalSource {
            val manifestText = context.assets
                .open("$ASSET_DIR/$MANIFEST")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val json = JSONObject(manifestText)

            val sampleRate = json.getInt("sampleRate")
            val channelCount = json.getInt("channelCount")

            val namesArray = json.getJSONArray("channelNames")
            val channelNames = ArrayList<String>(namesArray.length())
            for (i in 0 until namesArray.length()) {
                channelNames.add(namesArray.getString(i))
            }

            val segments = json.getJSONArray("segments")
            val regions = LinkedHashMap<Int, Region>()
            for (i in 0 until segments.length()) {
                val seg = segments.getJSONObject(i)
                val label = seg.getString("label")
                val command = commandOfLabel(label)
                val offset = seg.getInt("sampleOffset")
                val count = seg.getInt("sampleCount")

                val previous = regions[command]
                if (previous == null) {
                    regions[command] = Region(command, offset, count)
                } else {
                    // 同一方向的多个试次已连续导出，扩展为一段
                    val end = maxOf(previous.firstSample + previous.sampleCount, offset + count)
                    regions[command] = Region(command, minOf(previous.firstSample, offset), end - minOf(previous.firstSample, offset))
                }
            }

            val data = context.assets.open("$ASSET_DIR/$BLOB").use { it.readBytes() }

            val expected = json.getInt("totalSamples") * channelCount * BYTES_PER_CHANNEL
            require(data.size == expected) {
                "波形文件长度不匹配：实际 ${data.size} 字节，期望 $expected 字节"
            }
            require(channelNames.size == channelCount) {
                "通道名数量（${channelNames.size}）与 channelCount（$channelCount）不一致"
            }

            return EegSignalSource(sampleRate, channelCount, channelNames, data, regions)
        }

        private fun commandOfLabel(label: String): Int = when (label) {
            "forward" -> EegCommands.FORWARD
            "left" -> EegCommands.LEFT
            "right" -> EegCommands.RIGHT
            "backward" -> EegCommands.BACKWARD
            "stop" -> EegCommands.STOP
            else -> throw IllegalArgumentException("未知方向标签: $label")
        }
    }
}
