package com.fifteen.eegsender.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin

/**
 * 降质算法的正确性测试。
 *
 * 关键的 [degradedStreamMatchesNumpyReference] 用的参考向量由
 * `tools/gen_fft_vector.py` 用 numpy 独立算出来（fs=64 Hz, n=64, α=0.5, 单通道），
 * Kotlin 实现必须逐点对上 —— 这样「自己写的 FFT + 掩码」就不是自说自话了。
 */
class SignalDegradeTest {

    // ------------------------------------------------------------------ FFT 自检

    @Test
    fun fftRoundTripRestoresInput() {
        val n = 256
        val re = FloatArray(n) { (it * 37 % 101) / 101f - 0.5f }
        val im = FloatArray(n) { (it * 53 % 71) / 71f - 0.5f }
        val wantRe = re.copyOf()
        val wantIm = im.copyOf()

        Fft.transform(re, im, false)
        Fft.transform(re, im, true)

        for (i in 0 until n) {
            assertEquals("实部第 $i 点", wantRe[i].toDouble(), re[i].toDouble(), 1e-4)
            assertEquals("虚部第 $i 点", wantIm[i].toDouble(), im[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun fftOfImpulseIsFlatSpectrum() {
        val n = 64
        val re = FloatArray(n)
        val im = FloatArray(n)
        re[0] = 1f

        Fft.transform(re, im, false)

        for (i in 0 until n) {
            assertEquals(1.0, re[i].toDouble(), 1e-5)
            assertEquals(0.0, im[i].toDouble(), 1e-5)
        }
    }

    @Test
    fun nextPowerOfTwoRoundsUp() {
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(8, Fft.nextPowerOfTwo(8))
        assertEquals(16, Fft.nextPowerOfTwo(9))
        assertEquals(16384, Fft.nextPowerOfTwo(16000))
        assertEquals(8192, Fft.nextPowerOfTwo(8000))
    }

    // ------------------------------------------------------------ 与 numpy 对拍

    @Test
    fun degradedStreamMatchesNumpyReference() {
        val source = syntheticSource(SAMPLE_RATE, 1, INPUT)
        source.prepareDegrade(EegCommands.FORWARD)
        source.notchAlpha = 0.5f

        val dest = ByteArray(INPUT.size * 4)
        val written = source.copyLoop(source.regionOf(EegCommands.FORWARD), 0, INPUT.size, dest, 0)

        assertEquals(INPUT.size * 4, written)
        val got = floatsOf(dest)
        for (i in INPUT.indices) {
            assertEquals("第 $i 点", EXPECT[i].toDouble(), got[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun referenceVectorReallyRemovesStimulusLines() {
        // 参考向量之外再验一次语义：α=0.5 时目标谱线减半，带外与带内非目标不动。
        val input = DFT.of(INPUT)
        val expect = DFT.of(EXPECT)
        assertEquals(0.5, expect.magnitudeAt(12.0) / input.magnitudeAt(12.0), 0.02)
        assertEquals(0.5, expect.magnitudeAt(8.57) / input.magnitudeAt(8.57), 0.02)
        assertEquals(0.5, expect.magnitudeAt(5.45) / input.magnitudeAt(5.45), 0.02)
        // 30 Hz 在分析带内但不是刺激频率，必须原样保留
        assertEquals(1.0, expect.magnitudeAt(30.0) / input.magnitudeAt(30.0), 0.02)
        // 1 Hz 在分析带之外，同样不受影响
        assertEquals(1.0, expect.magnitudeAt(1.0) / input.magnitudeAt(1.0), 0.02)
    }

    // ----------------------------------------------------------- 带限 RMS 口径

    @Test
    fun rmsOfPureToneEqualsAmplitudeOverSqrt2() {
        val fs = 1024
        val n = 1024
        val tone = FloatArray(n) { sin(2.0 * PI * 16.0 * it / fs).toFloat() }
        val prepared = SignalDegrade.prepare(tone, 1, n, fs)
        assertEquals(0.70710678, prepared.rms.toDouble(), 1e-4)
    }

    @Test
    fun rmsIgnoresOutOfBandTone() {
        val fs = 1024
        val n = 1024
        val tone = FloatArray(n) { sin(2.0 * PI * 1.0 * it / fs).toFloat() }
        val prepared = SignalDegrade.prepare(tone, 1, n, fs)
        assertTrue("1 Hz 在 4~45 Hz 之外，不应计入 RMS：${prepared.rms}", abs(prepared.rms) < 1e-4)
    }

    @Test
    fun maskCoversEveryStimulusLine() {
        // 4 秒段、1000 Hz 下分辨率 0.061 Hz，每条谐波谱线会被 ±0.6 Hz 遮住约 20 个 bin，
        // 12 条线合起来两百多个 —— 说明掩码确实落在真实波形上，而不是只戳到一个 bin。
        val good = SignalDegrade.maskedBinCount(16000, 1000)
        assertTrue("掩码频点数异常：$good", good in 200..280)
        // 反面例子：fs=1000 / n=128 的分辨率只有 7.8 Hz，掩码只命中 1 个 bin，
        // 对拍向量用这种参数等于没测 —— 这正是 gen_fft_vector.py 改用 64 Hz 的原因。
        assertEquals(1, SignalDegrade.maskedBinCount(128, 1000))
    }

    // --------------------------------------------------------- 置信度 → 强度表

    @Test
    fun confidenceMappingHitsBothEnds() {
        assertEquals(0f, SignalDegrade.noiseAlphaFor(100), 1e-6f)
        assertEquals(0f, SignalDegrade.notchAlphaFor(100), 1e-6f)
        assertEquals(0.8204f, SignalDegrade.notchAlphaFor(0), 1e-4f)
        assertEquals(8.0f, SignalDegrade.noiseAlphaFor(0), 1e-4f)
        // 中间点线性插值：50% 正好落在表中心
        assertEquals(2.1223f, SignalDegrade.noiseAlphaFor(50), 1e-4f)
        assertEquals(0.6043f, SignalDegrade.notchAlphaFor(50), 1e-4f)
        // 越界钳位，不抛异常
        assertEquals(8.0f, SignalDegrade.noiseAlphaFor(-5), 1e-4f)
        assertEquals(0f, SignalDegrade.noiseAlphaFor(400), 1e-6f)
    }

    @Test
    fun intensityDecreasesAsConfidenceRises() {
        var previousNoise = Float.MAX_VALUE
        var previousNotch = Float.MAX_VALUE
        for (c in 0..100) {
            val noise = SignalDegrade.noiseAlphaFor(c)
            val notch = SignalDegrade.notchAlphaFor(c)
            assertTrue("加噪强度在 $c% 处回升", noise <= previousNoise + 1e-6f)
            assertTrue("陷波强度在 $c% 处回升", notch <= previousNotch + 1e-6f)
            previousNoise = noise
            previousNotch = notch
        }
        assertTrue(previousNotch >= 0f)
    }

    @Test
    fun expectedRhoSpansCalibratedRange() {
        assertEquals(0.7361f, SignalDegrade.expectedRho(100), 1e-4f)
        assertEquals(0.3000f, SignalDegrade.expectedRho(0), 1e-4f)
        assertEquals(0.51805f, SignalDegrade.expectedRho(50), 1e-4f)
    }

    // ------------------------------------------------- 数据源层面的降质接入

    @Test
    fun zeroAlphaKeepsBytesIdentical() {
        val source = syntheticSource(SAMPLE_RATE, 1, INPUT)
        source.prepareDegrade(EegCommands.FORWARD)

        val plain = ByteArray(INPUT.size * 4)
        source.copyLoop(source.regionOf(EegCommands.FORWARD), 0, INPUT.size, plain, 0)

        // 快路径：没有任何强度时，输出的就是原始字节
        assertEquals(bytesOf(INPUT).toList(), plain.toList())
    }

    @Test
    fun unpreparedRegionFallsBackToRawBytes() {
        val source = syntheticSource(SAMPLE_RATE, 1, INPUT)
        source.notchAlpha = 0.5f // 故意不预热

        assertFalse(source.isDegradeReady(EegCommands.FORWARD))
        val dest = ByteArray(INPUT.size * 4)
        source.copyLoop(source.regionOf(EegCommands.FORWARD), 0, INPUT.size, dest, 0)

        // 没预热好的段必须原样输出，绝不能在推流线程上现算 FFT
        assertEquals(bytesOf(INPUT).toList(), dest.toList())
    }

    @Test
    fun degradeWrapsAcrossRegionBoundary() {
        val source = syntheticSource(SAMPLE_RATE, 1, INPUT)
        source.prepareDegrade(EegCommands.FORWARD)
        source.notchAlpha = 0.5f

        val region = source.regionOf(EegCommands.FORWARD)
        val last = region.sampleCount - 1

        // 从最后一点起取 2 个点：第 2 个点必须回绕到区段开头
        val wrapped = ByteArray(2 * 4)
        assertEquals(8, source.copyLoop(region, last, 2, wrapped, 0))
        val got = floatsOf(wrapped)

        // 与「直接从开头取 1 个点」的结果一致
        val head = ByteArray(4)
        source.copyLoop(region, 0, 1, head, 0)
        assertEquals(EXPECT[0].toDouble(), got[1].toDouble(), 1e-4)
        assertEquals(floatsOf(head)[0].toDouble(), got[1].toDouble(), 1e-4)
        // 第一个点就是区段最后一点
        assertEquals(EXPECT[last].toDouble(), got[0].toDouble(), 1e-4)
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 用给定的采样构造一个单方向（前进）的数据源。
     *
     * 走的是与 [EegSignalSource.load] 完全相同的构造路径，只是数据换成内存里的。
     */
    private fun syntheticSource(sampleRate: Int, channelCount: Int, samples: FloatArray): EegSignalSource {
        require(samples.size % channelCount == 0)
        return EegSignalSource(
            sampleRate = sampleRate,
            channelCount = channelCount,
            channelNames = List(channelCount) { "ch$it" },
            data = bytesOf(samples),
            regions = mapOf(
                EegCommands.FORWARD to
                    EegSignalSource.Region(EegCommands.FORWARD, 0, samples.size / channelCount)
            )
        )
    }

    private fun bytesOf(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putFloat(s)
        return bytes
    }

    private fun floatsOf(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    /** 只用到少数几个频点的 DFT，用来量某条谱线的幅度。 */
    private class DFT(private val re: FloatArray, private val im: FloatArray) {

        /** 直接取最靠近该频率的 bin 的幅度；分辨率 1 Hz，够用。 */
        fun magnitudeAt(hz: Double): Double {
            val k = Math.round(hz).toInt()
            return Math.hypot(re[k].toDouble(), im[k].toDouble())
        }

        companion object {
            fun of(x: FloatArray): DFT {
                val n = x.size
                val re = FloatArray(n)
                val im = FloatArray(n)
                for (i in x.indices) re[i] = x[i]
                Fft.transform(re, im, false)
                return DFT(re, im)
            }
        }
    }

    private companion object {
        /** 参考向量的采样率；n=64 时分辨率恰好 1 Hz。 */
        const val SAMPLE_RATE = 64

        /** 由 tools/gen_fft_vector.py 生成：5 条正弦叠加的原始波形。 */
        private val INPUT = floatArrayOf(
            0.4f, 1.5745222f, 1.1129961f, 0.11556347f, -1.020286f, 0.03707059f, 0.63164988f,
            1.3737428f, -0.3428825f, -0.96063753f, -1.5122388f, 0.18193567f, 0.74169939f,
            1.0913511f, 0.21738096f, 0.32426812f, 0.90634064f, 1.1921204f, 0.55709175f,
            -1.1477399f, -1.35182f, -0.88648315f, 1.0992997f, 0.9806148f, 0.69460613f,
            -0.88701149f, -0.13137888f, 0.49979502f, 1.6856494f, 0.68540377f, -0.3577478f,
            -1.1918508f, -0.6798703f, 0.055964306f, -0.19170797f, -0.50240126f, -0.87648772f,
            0.64340075f, 1.2563073f, 1.7015227f, -0.3557168f, -1.1510993f, -1.712698f,
            -0.034279707f, 0.31439534f, 0.23067766f, -1.1013734f, -1.1637521f, -0.24571189f,
            0.82625521f, 1.0902761f, -0.049251999f, -0.32148666f, -0.53058373f, 0.51788281f,
            -0.33771405f, -0.83725009f, -2.0905172f, -0.72366294f, 0.45421164f, 1.9069377f,
            0.91835837f, -0.17055151f, -0.91973898f
        )

        /** 由 tools/gen_fft_vector.py 生成：同一波形经 α=0.5 陷波后的期望结果。 */
        private val EXPECT = floatArrayOf(
            0.43973398f, 1.0863283f, 0.73330319f, 0.30517548f, -0.50985605f, 0.20189994f,
            0.22276932f, 0.87415107f, -0.29507272f, -0.31081288f, -0.89435688f, 0.20357233f,
            0.27328688f, 0.70069075f, 0.21159131f, 0.3791133f, 0.59441184f, 0.67491739f,
            0.46331308f, -0.51995244f, -0.43702431f, -0.44293768f, 0.76557625f, 0.36570439f,
            0.48257379f, -0.60128622f, 0.10050829f, 0.17949053f, 1.019434f, 0.29662297f,
            -0.11700186f, -0.60235342f, -0.30207987f, 0.12440792f, -0.095099928f, -0.18126516f,
            -0.61701172f, 0.33463878f, 0.38062895f, 0.91127709f, -0.40809614f, -0.5215319f,
            -1.1196871f, -0.011844986f, -0.041077838f, 0.13057906f, -0.62657712f, -0.54193201f,
            -0.16094071f, 0.26880138f, 0.47043647f, -0.21831553f, -0.1512103f, -0.50297289f,
            0.23879839f, -0.48984805f, -0.43081733f, -1.311811f, -0.26412326f, 0.13127093f,
            1.1527153f, 0.50529144f, 0.120594f, -0.38335148f
        )
    }
}
