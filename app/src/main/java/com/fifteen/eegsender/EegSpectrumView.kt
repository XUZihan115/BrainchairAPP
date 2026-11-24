package com.fifteen.eegsender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.fifteen.eegsender.protocol.Fft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 频谱视图：对当前选中通道做 1024 点 FFT，画 4~20 Hz 功率谱，
 * 并在 4 个刺激频率（5.45 / 6.67 / 8.57 / 12.00 Hz）处画刻度线。
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
    var ringChannel: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val n = 1024
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val sample = FloatArray(n)
    private val power = FloatArray(n / 2)

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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val r = ring
        if (r == null) {
            canvas.drawText("等待数据…", w / 2f, h / 2f, hintPaint)
            return
        }

        val got = r.snapshotChannel(ringChannel, sample, n)
        if (got < n / 4) {
            canvas.drawText("等待数据…", w / 2f, h / 2f, hintPaint)
            return
        }

        // Hann 窗 + FFT
        for (i in 0 until n) {
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            re[i] = (sample[i] * win).toFloat()
            im[i] = 0f
        }
        Fft.transform(re, im, false)

        val fs = sampleRate.toFloat()
        val binHz = fs / n
        val kMin = ceil(fMin / binHz).toInt().coerceIn(1, n / 2 - 1)
        val kMax = floor(fMax / binHz).toInt().coerceIn(kMin + 1, n / 2 - 1)

        for (k in 0 until n / 2) {
            power[k] = re[k] * re[k] + im[k] * im[k]
        }
        var maxP = 1e-9f
        for (k in kMin..kMax) {
            if (power[k] > maxP) maxP = power[k]
        }

        val plotW = max(1f, w - padL - padR)
        val plotH = max(1f, h - padT - padB)

        fun fToX(f: Float): Float = padL + plotW * (f - fMin) / (fMax - fMin)
        fun pToY(p: Float): Float = padT + plotH * (1f - p / maxP)

        // 横向网格
        var f = fMin
        while (f <= fMax + 0.001f) {
            val x = fToX(f)
            canvas.drawLine(x, padT, x, padT + plotH, gridPaint)
            f += 2f
        }
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, gridPaint)

        // 功率谱折线（带下方浅填充）
        path.reset()
        for (k in kMin..kMax) {
            val fk = k * binHz
            val x = fToX(fk)
            val y = pToY(power[k])
            if (k == kMin) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, specPaint)

        path.reset()
        for (k in kMin..kMax) {
            val fk = k * binHz
            val x = fToX(fk)
            val y = pToY(power[k])
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

        // 坐标说明
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("4 Hz", padL, h - dp(4f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("20 Hz", padL + plotW, h - dp(4f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }
}
