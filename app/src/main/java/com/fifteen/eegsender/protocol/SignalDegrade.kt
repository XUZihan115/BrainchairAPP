package com.fifteen.eegsender.protocol

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A 端「人为降低 B 端置信度」的信号层实现。
 *
 * 用途：把「脑电质量差 / 注意力涣散」这类情况做成一个**可复现、可调**的开关，
 * 用来标定接收端（B 端）的「无指令」阈值、观察拒识行为。降质**只在信号层**发生，
 * 报文格式一个字节都不改，B 端也完全不知道有这回事。
 *
 * ## 降质路径
 *
 * UI 层合并为**单个「信号置信度」滑块**（0~100），驱动这里的陷波路径：
 * `x' = x − α · band`，精确削弱 4 个刺激频率的 1/2/3 次谐波谱线，背景脑电几乎不动，
 * 对应「注意力涣散 / 低响应者」。**这是 B 端做阈值标定时该用的旋钮**：
 * 实测它能把「正确方向相关值」压到静息基线以下，且单调、确定、可复现。
 *
 * 加噪路径 `x' = x + α · rms · N(0,1)`（整体信噪比下降，对应电极接触差/环境噪声）
 * 仍然实现着（[noiseAlphaFor]，供标定脚本与单测使用），但它是**非确定**的、还会
 * 抬升静息基线，不适合作为「置信度」的可复现刻度，故不从 UI 暴露。
 *
 * ## 为什么能实时调
 *
 * 每段的 `band`（该段的目标频段成分）与 `rms` 只在段被首次使用时预计算一次并缓存，
 * 之后任意强度的降质只是 `x − α·band` 的一次乘加 —— 拖动滑块**不需要重算任何 FFT**。
 *
 * ## 标定口径
 *
 * [NOISE_ALPHA] / [NOTCH_ALPHA] 由 `tools/calib_3s_aligned.py`（试次头对齐窗 + 普通 rfft
 * 带通）实测反查得到，参考条件是**3 秒窗、CCA 3 次谐波、11 通道枕顶区**（即
 * docs/system-architecture.md 推荐的接收端配置）。滑块 100% 恰好等于 α=0（不做任何
 * 改动）；0% 对应目标相关值 ρ≈0.30。3 秒窗下陷波翻转点（正确方向相关值跌破静息
 * 基线）在置信度 ≈10%（α≈0.79，掩码语义与本节 `masks()` 一致时）。注意 ρ 与
 * 窗口长度强相关，改用其它窗长时同一滑块会得到不同的 ρ。
 */
internal object SignalDegrade {

    /** 分析带下限（Hz）。 */
    const val BAND_LO = 4.0

    /** 分析带上限（Hz）。 */
    const val BAND_HI = 45.0

    /** 每条谐波谱线两侧各遮多宽（Hz）。 */
    const val HALF_WIDTH_HZ = 0.6

    /** 参与陷波的谐波次数。 */
    val HARMONICS = intArrayOf(1, 2, 3)

    /** 滑块刻度：下标 i 对应置信度 i×10%。 */
    private val CONFIDENCE_STEPS = 11

    /** 置信度 0..100（步长 10）对应的加噪强度 α（3 秒窗标定）。 */
    private val NOISE_ALPHA = floatArrayOf(
        8.0000f, 8.0000f, 5.5140f, 3.7695f, 2.7068f, 1.9627f,
        1.3982f, 0.9594f, 0.6199f, 0.3439f, 0.0000f
    )

    /** 置信度 0..100（步长 10）对应的陷波强度 α（3 秒窗标定；1.0 = 谱线全抹掉）。 */
    private val NOTCH_ALPHA = floatArrayOf(
        0.8194f, 0.7851f, 0.7483f, 0.7066f, 0.6597f, 0.6059f,
        0.5417f, 0.4625f, 0.3607f, 0.2192f, 0.0000f
    )

    /** 滑块 100% 时的相关值（不降质，3 秒窗实测），以及 0% 时压到的目标值。 */
    const val RHO_FULL = 0.7370f
    const val RHO_FLOOR = 0.3000f

    /** 某一段预计算好的降质参数。 */
    class Prepared(
        /** 与原始采样同布局的目标频段成分（时间主序，长度 = sampleCount × channelCount）。 */
        val band: FloatArray,
        /** 该段 4~45 Hz 带限 RMS，加噪幅度的参考基准。 */
        val rms: Float
    )

    /**
     * 预计算一段波形的陷波成分与带限 RMS。
     *
     * [samples] 为时间主序（`sample[t][ch] = samples[t * channelCount + ch]`），
     * 长度至少 `sampleCount × channelCount`。整段按 2 的幂**循环补齐**（把开头若干点
     * 接到尾部），与 App「段内硬回绕」的播放方式一致，边界不会引入跳变。
     *
     * 复杂度约 `channelCount × 2 × N log2 N`，16000 点 × 62 通道约需 0.3~1 s，
     * 必须在后台线程调用。
     */
    fun prepare(samples: FloatArray, channelCount: Int, sampleCount: Int, sampleRate: Int): Prepared {
        require(channelCount > 0 && sampleCount > 0) { "通道数与采样点数必须为正" }
        require(sampleRate > 0) { "采样率必须为正" }
        require(samples.size >= channelCount * sampleCount) {
            "样本数不足：需要 ${channelCount * sampleCount}，实际 ${samples.size}"
        }

        val size = Fft.nextPowerOfTwo(sampleCount)
        val half = size / 2
        val (inBand, keep) = masks(size, sampleRate)

        val re = FloatArray(size)
        val im = FloatArray(size)
        val band = FloatArray(sampleCount * channelCount)
        var bandEnergy = 0.0

        for (c in 0 until channelCount) {
            var t = 0
            while (t < sampleCount) {
                re[t] = samples[t * channelCount + c]
                t++
            }
            while (t < size) {
                re[t] = samples[(t - sampleCount) * channelCount + c]
                t++
            }
            java.util.Arrays.fill(im, 0f)
            Fft.transform(re, im, false)

            // 4~45 Hz 带限能量：Parseval 直接从谱上取，不必再做一次逆变换。
            // 实数信号只在正频率半边求和，故能量要 ×2（DC 与 Nyquist 不在带内）。
            var e = 0.0
            var k = 1
            while (k < half) {
                if (inBand[k]) {
                    val rr = re[k].toDouble()
                    val ii = im[k].toDouble()
                    e += rr * rr + ii * ii
                }
                k++
            }
            bandEnergy += 2.0 * e

            // 只留目标谱线。正负两边必须一起处理，否则逆变换出来不再是实信号。
            var i = 0
            while (i < size) {
                val bin = if (i <= half) i else size - i
                if (!keep[bin]) {
                    re[i] = 0f
                    im[i] = 0f
                }
                i++
            }
            Fft.transform(re, im, true)

            t = 0
            while (t < sampleCount) {
                band[t * channelCount + c] = re[t]
                t++
            }
        }

        val rms = sqrt(bandEnergy / (size.toDouble() * size * channelCount)).toFloat()
        return Prepared(band, rms)
    }

    /** 置信度（0~100）对应的加噪强度。 */
    fun noiseAlphaFor(confidencePercent: Int): Float = interpolate(NOISE_ALPHA, confidencePercent)

    /** 置信度（0~100）对应的陷波强度。 */
    fun notchAlphaFor(confidencePercent: Int): Float = interpolate(NOTCH_ALPHA, confidencePercent)

    /** 送给接收端的期望相关值，用于界面上给用户一个直观刻度。 */
    fun expectedRho(confidencePercent: Int): Float {
        val c = confidencePercent.coerceIn(0, 100)
        return RHO_FLOOR + (RHO_FULL - RHO_FLOOR) * c / 100f
    }

    private fun interpolate(table: FloatArray, confidencePercent: Int): Float {
        check(table.size == CONFIDENCE_STEPS) { "标定表长度应为 $CONFIDENCE_STEPS" }
        val c = confidencePercent.coerceIn(0, 100)
        val pos = c / 10.0
        val i = pos.toInt()
        if (i >= table.size - 1) return table[table.size - 1]
        val frac = (pos - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * frac
    }

    /**
     * 生成两张掩码表，只与 FFT 长度和采样率有关。
     *
     * `inBand` 标记 4~45 Hz 分析带内的频点（用于带限 RMS）；
     * `keep` 标记要保留的目标谱线（其余全部抹掉）。掩码必须覆盖**全部 4 个候选频率**：
     * 只遮一个的话 argmax 会跑到别的方向去，那就不是「降低置信度」而是「制造错误指令」。
     */
    private fun masks(size: Int, sampleRate: Int): Pair<BooleanArray, BooleanArray> {
        val binHz = sampleRate.toDouble() / size
        val half = size / 2
        val inBand = BooleanArray(half + 1)
        for (b in 0..half) {
            val hz = b * binHz
            inBand[b] = hz >= BAND_LO && hz <= BAND_HI
        }
        val keep = BooleanArray(half + 1)
        for (cmd in intArrayOf(
            EegCommands.FORWARD, EegCommands.LEFT, EegCommands.RIGHT, EegCommands.BACKWARD
        )) {
            val f0 = EegCommands.stimulusHz(cmd) ?: continue
            for (h in HARMONICS) {
                val center = f0 * h
                val lo = ceil((center - HALF_WIDTH_HZ) / binHz).toInt().coerceAtLeast(0)
                val hi = floor((center + HALF_WIDTH_HZ) / binHz).toInt().coerceAtMost(half)
                for (b in lo..hi) {
                    if (inBand[b]) keep[b] = true
                }
            }
        }
        return inBand to keep
    }

    /** 掩码里实际被削弱的频点数，供日志自检用（正常应为十几个）。 */
    fun maskedBinCount(sampleCount: Int, sampleRate: Int): Int {
        val size = Fft.nextPowerOfTwo(sampleCount)
        return masks(size, sampleRate).second.count { it }
    }
}
