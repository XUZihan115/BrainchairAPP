package com.fifteen.eegsender

import kotlin.math.min

/**
 * 推流样本的「展示用」环形缓冲。
 *
 * 推流线程每包写入，UI 线程按需快照读取。**只服务屏幕绘制**：
 * 不参与编码、不回写推流缓冲，因此开关「去噪 / 频谱 / 通道」任何显示选项
 * 都不会改变发出的 UDP 字节。
 */
class EegSampleRing(
    val channelCount: Int,
    private val capacity: Int
) {

    private val data = Array(channelCount) { FloatArray(capacity) }
    private var writePos = 0
    private var filled = 0
    private val lock = Any()

    /** 推流线程调用：写入一个采样点（每通道一个值）。 */
    fun push(values: FloatArray) {
        synchronized(lock) {
            val n = min(channelCount, values.size)
            for (c in 0 until n) {
                data[c][writePos] = values[c]
            }
            writePos = (writePos + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /**
     * UI 线程调用：把最近 [count] 个采样点按时间顺序写入 [out]。
     *
     * @return 实际写入的采样点数（数据不足 [count] 时会小于它）。
     *   [out] 的长度需 ≥ [channelCount]，每个子数组长度需 ≥ [count]。
     */
    fun snapshot(out: Array<FloatArray>, count: Int): Int {
        synchronized(lock) {
            val n = min(count, filled)
            for (c in 0 until channelCount) {
                val dst = out[c]
                var src = writePos - n
                if (src < 0) src += capacity
                var i = 0
                while (i < n) {
                    dst[i] = data[c][src]
                    src++
                    if (src >= capacity) src = 0
                    i++
                }
            }
            return n
        }
    }

    /** UI 线程调用：只取某个通道最近 [count] 个采样点（频谱用）。 */
    fun snapshotChannel(channel: Int, out: FloatArray, count: Int): Int {
        synchronized(lock) {
            if (channel < 0 || channel >= channelCount) return 0
            val n = min(count, filled)
            var src = writePos - n
            if (src < 0) src += capacity
            var i = 0
            while (i < n) {
                out[i] = data[channel][src]
                src++
                if (src >= capacity) src = 0
                i++
            }
            return n
        }
    }

    /** 已积累的采样点数（≤ capacity）。 */
    fun available(): Int = synchronized(lock) { filled }
}
