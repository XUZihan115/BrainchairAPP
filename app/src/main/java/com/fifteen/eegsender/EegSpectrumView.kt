package com.fifteen.eegsender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.fifteen.eegsender.protocol.Fft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/**
 * 频谱视图：对**所有勾选通道**分别做 4096 点 FFT，功率按通道取平均，
 * 再换算成 dB 画 4~20 Hz 平均谱，并在 4 个刺激频率处画刻度线。
 *
 * 相比单通道原始谱，平均谱能压低各通道独立的背景噪声，4096 点（0.24 Hz/格）
 * 也足以把 5.45 与 6.67 Hz 分开；dB 显示避免 8~13 Hz 的 alpha 包把其他峰压平。
 *
 * 只读取展示缓冲、不参与推流；勾选/取消不影响发出的字节。
 */
class EegSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var ring: EegSampleRing? = null
    var sampleRate: Int = 1000

    /** 参与平均的 ring 通道下标（勾选的通道）。 */
    var ringChannels: IntArray = IntArray(0)
        set(value) {
            if (!field.contentEquals(value)) {
                field = value
                ensureBuffers(value.size)
                invalidate()
            }
        }

    private val n = 4096
    private var chBuf: Array<FloatArray> = emptyArray()
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val power = FloatArray(n / 2)

    /** 缓存的平均谱是否有效；数据变化后需要重算。 */
    private var powerValid = false
    private var lastCalcMs = 0L
    private var usedChannels = 0

    private val fMin = 4f
    private val fMax = 20f
    private val marks = floatArrayOf(5.45f, 6.67f, 8.57f, 12.00f)
    private val markLabels = arrayOf("5.45", "6.67", "8.57", "12.0")

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0B1220")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E2C44")
        strokeWidth = 1f
    }
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38E1C8")
        strokeWidth = dp(1.6f)
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3338E1C8")
        style = Paint.Style.FILL
    }
    private val markPaint = Paint().apply {
        color = Color.parseColor("#88FFB74D")
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA3BF")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B829F")
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C7392")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private val padL = dp(8f)
    private val padR = dp(8f)
    private val padT = dp(18f)
    private val padB = dp(16f)

    private fun ensureBuffers(channels: Int) {
        if (chBuf.size != channels) {
            chBuf = Array(channels) { FloatArray(n) }
            powerValid = false
        }
    }

    /** 多通道各自 FFT 后取平均功率（只保留线性功率，绘制时再转 dB）。 */
    private fun computeAveragePower(): Boolean {
        val r = ring ?: return false
        val chs = ringChannels
        if (chs.isEmpty()) return false
        ensureBuffers(chs.size)

        var minGot = n
        for (i in chs.indices) {
            val got = r.snapshotChannel(chs[i], chBuf[i], n)
            if (got < minGot) minGot = got
        }
        // 少于 2/3 窗长时谱形不稳，先不画
        if (minGot < n * 2 / 3) return false

        java.util.Arrays.fill(power, 0f)
        var used = 0
        for (i in chs.indices) {
            val s = chBuf[i]
            for (k in 0 until n) {
                val win = 0.5 - 0.5 * cos(2.0 * PI * k / (n - 1))
                re[k] = (s[k] * win).toFloat()
                im[k] = 0f
            }
            Fft.transform(re, im, false)
            for (k in 0 until n / 2) {
                power[k] += re[k] * re[k] + im[k] * im[k]
            }
            used++
        }
        if (used == 0) return false
        for (k in 0 until n / 2) power[k] /= used
        usedChannels = used
        powerValid = true
        lastCalcMs = SystemClock.elapsedRealtime()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val r = ring
        if (r == null || ringChannels.isEmpty()) {
            canvas.drawText("勾选通道后显示平均谱", w / 2f, h / 2f, hintPaint)
            return
        }

        // 平均谱计算较重（多通道 4096 点 FFT），限到约 8 帧/秒
        val now = SystemClock.elapsedRealtime()
        val stale = !powerValid || now - lastCalcMs > 120L
        if (stale) {
            if (!computeAveragePower()) {
                canvas.drawText("等待数据…", w / 2f, h / 2f, hintPaint)
                return
            }
        }

        val fs = sampleRate.toFloat()
        val binHz = fs / n
        val kMin = ceil(fMin / binHz).toInt().coerceIn(1, n / 2 - 2)
        val kMax = floor(fMax / binHz).toInt().coerceIn(kMin + 1, n / 2 - 1)

        // 线性功率 -> dB，按带内极值归一
        var minDb = Double.MAX_VALUE
        var maxDb = -Double.MAX_VALUE
        for (k in kMin..kMax) {
            val db = 10.0 * log10(power[k].toDouble() + 1e-12)
            if (db < minDb) minDb = db
            if (db > maxDb) maxDb = db
        }
        val span = max(1e-6, maxDb - minDb)

        val plotW = max(1f, w - padL - padR)
        val plotH = max(1f, h - padT - padB)

        fun fToX(f: Float): Float = padL + plotW * (f - fMin) / (fMax - fMin)
        fun kToY(k: Int): Float {
            val db = 10.0 * log10(power[k].toDouble() + 1e-12)
            val t = ((db - minDb) / span).toFloat().coerceIn(0f, 1f)
            return padT + plotH * (1f - t)
        }

        // 横向网格（每 2 Hz）
        var f = fMin
        while (f <= fMax + 0.001f) {
            val x = fToX(f)
            canvas.drawLine(x, padT, x, padT + plotH, gridPaint)
            f += 2f
        }
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, gridPaint)

        // 平均谱折线
        path.reset()
        for (k in kMin..kMax) {
            val x = fToX(k * binHz)
            val y = kToY(k)
            if (k == kMin) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, specPaint)

        // 折线下方浅填充
        path.reset()
        for (k in kMin..kMax) {
            val x = fToX(k * binHz)
            val y = kToY(k)
            if (k == kMin) {
                path.moveTo(x, padT + plotH)
                path.lineTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.lineTo(fToX(kMax * binHz), padT + plotH)
        path.close()
        canvas.drawPath(path, fillPaint)

        // 4 条刺激频率刻度线
        for (i in marks.indices) {
            val mf = marks[i]
            if (mf < fMin || mf > fMax) continue
            val x = fToX(mf)
            canvas.drawLine(x, padT, x, padT + plotH, markPaint)
            canvas.drawText(markLabels[i], x, padT - dp(5f), textPaint)
        }

        // 通道数标注
        canvas.drawText("${usedChannels}通道平均 dB", padL, h - dp(4f), tagPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("20 Hz", padL + plotW, h - dp(4f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }
}
