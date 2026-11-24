package com.fifteen.eegsender

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * 时域脑电波形预览。
 *
 * 每行一个通道，顶部一条**统一振幅栏**「振幅 − 100µV +」：点一次，所有行
 * 同时放大 / 缩小（满量程档位 10→2000 µV，越小波形越大）。勾选「去噪」时，
 * 绘制前对该行做 4~45 Hz 带通（只作用于这份展示拷贝，不影响推流字节）。
 */
class EegWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 展示缓冲；由 MainActivity 在数据集载入后注入。 */
    var ring: EegSampleRing? = null

    var sampleRate: Int = 1000

    /** 显示行对应的通道名。 */
    var channels: List<String> = emptyList()
        private set

    /** 每行在 ring 内的通道下标。 */
    private var ringIndices: IntArray = IntArray(0)

    /** 是否在显示层做 4~45 Hz 带通。 */
    var denoise: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 所有行共用的满量程档位（µV）。 */
    private val ampSteps = floatArrayOf(10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f)
    private val defaultAmpIndex = 3
    private var ampIndex = defaultAmpIndex

    private val windowSeconds = 2f
    private var buf: Array<FloatArray> = emptyArray()
    private var filtered: Array<FloatArray> = emptyArray()
    private var bufLen = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1220")
        style = Paint.Style.FILL
    }
    private val rowBgPaint = Paint().apply {
        color = Color.parseColor("#101A2C")
        style = Paint.Style.FILL
    }
    private val stripPaint = Paint().apply {
        color = Color.parseColor("#0E1728")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E2C44")
        strokeWidth = 1f
    }
    private val midPaint = Paint().apply {
        color = Color.parseColor("#2E425F")
        strokeWidth = 1f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.3f)
        style = Paint.Style.STROKE
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA3BF")
        textSize = sp(11f)
    }
    private val stripLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA3BF")
        textSize = sp(12f)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C7392")
        textSize = sp(9f)
    }

    /** 通道名下方的位置注释（P=顶区 / O=枕区 / z=中线，数字奇左偶右）。 */
    private val channelHints = mapOf(
        "Pz" to "顶区中线",
        "O1" to "左枕区",
        "Oz" to "枕区中线",
        "O2" to "右枕区",
        "P1" to "左顶区",
        "P2" to "右顶区",
        "POz" to "枕顶中线",
        "PO3" to "左枕顶",
        "PO4" to "右枕顶"
    )
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF7")
        textSize = sp(12f)
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E3350")
        style = Paint.Style.FILL
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38E1C8")
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C7392")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val minusRect = RectF()
    private val plusRect = RectF()

    private val rowColors = intArrayOf(
        Color.parseColor("#9BE7FF"),
        Color.parseColor("#38E1C8"),
        Color.parseColor("#FF8A65"),
        Color.parseColor("#FFB74D"),
        Color.parseColor("#B39DDB"),
        Color.parseColor("#81C784"),
        Color.parseColor("#F06292"),
        Color.parseColor("#4FC3F7"),
        Color.parseColor("#FFD54F")
    )

    init {
        setBackgroundColor(Color.parseColor("#0B1220"))
    }

    /** 设置要显示的行（通道名 + ring 内下标）。 */
    fun setChannels(names: List<String>, indices: IntArray) {
        channels = names
        ringIndices = indices
        rebuildBuffers(force = true)
        invalidate()
    }

    private fun rebuildBuffers(force: Boolean = false) {
        val perCh = max(1, (sampleRate * windowSeconds).toInt())
        val chCount = ring?.channelCount ?: channels.size
        if (force || buf.size != chCount || (buf.isNotEmpty() && buf[0].size != perCh)) {
            buf = Array(chCount) { FloatArray(perCh) }
        }
        if (force || filtered.size != channels.size || (filtered.isNotEmpty() && filtered[0].size != perCh)) {
            filtered = Array(channels.size) { FloatArray(perCh) }
        }
        bufLen = perCh
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun controlWidth(): Float = dp(84f)

    private fun stripHeight(): Float = dp(34f)

    private fun layoutStrip(sh: Float) {
        val bh = dp(22f)
        val bw = dp(26f)
        val by = (sh - bh) / 2f
        val x0 = dp(46f)
        minusRect.set(x0, by, x0 + bw, by + bh)
        val plusLeft = x0 + bw + dp(58f)
        plusRect.set(plusLeft, by, plusLeft + bw, by + bh)
    }

    private fun drawStrip(canvas: Canvas, w: Float, sh: Float) {
        layoutStrip(sh)
        canvas.drawRect(0f, 0f, w, sh, stripPaint)
        canvas.drawLine(0f, sh, w, sh, gridPaint)
        canvas.drawText("振幅", dp(6f), sh / 2f + sp(4f), stripLabelPaint)
        canvas.drawRoundRect(minusRect, dp(4f), dp(4f), btnPaint)
        canvas.drawRoundRect(plusRect, dp(4f), dp(4f), btnPaint)
        canvas.drawText("−", minusRect.centerX(), minusRect.top + minusRect.height() * 0.72f, btnTextPaint)
        canvas.drawText("+", plusRect.centerX(), plusRect.top + plusRect.height() * 0.72f, btnTextPaint)

        valuePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            fmtAmp(ampSteps[ampIndex]),
            (minusRect.right + plusRect.left) / 2f,
            minusRect.top + minusRect.height() * 0.72f,
            valuePaint
        )
        valuePaint.textAlign = Paint.Align.LEFT
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val sh = stripHeight()
        drawStrip(canvas, w, sh)

        val n = channels.size
        if (n == 0) {
            canvas.drawText("选择上方通道后在此显示波形", w / 2f, sh + (h - sh) / 2f, emptyPaint)
            return
        }

        rebuildBuffers()
        val controlW = controlWidth()
        val rightW = max(1f, w - controlW)
        val top = sh
        val rowH = (h - sh) / n
        val r = ring

        var samples = 0
        if (r != null && ringIndices.size >= n) {
            samples = r.snapshot(buf, bufLen)
        }

        // 竖网格：每 0.5 秒一条
        val stepSec = 0.5f
        for (i in 0 until n) {
            val y0 = top + i * rowH
            val mid = y0 + rowH / 2f
            if (i % 2 == 1) {
                canvas.drawRect(controlW, y0, w, y0 + rowH, rowBgPaint)
            }
            canvas.drawLine(controlW, mid, w, mid, midPaint)
            var t = 0f
            while (t <= windowSeconds + 0.001f) {
                val x = controlW + rightW * (t / windowSeconds)
                canvas.drawLine(x, y0, x, y0 + rowH, gridPaint)
                t += stepSec
            }
        }

        if (samples > 1) {
            for (i in 0 until n) {
                drawWave(canvas, i, top + i * rowH, rowH, controlW, rightW, samples)
            }
        }

        for (i in 0 until n) {
            val yName = top + i * rowH + sp(13f)
            canvas.drawText(channels[i], dp(4f), yName, namePaint)
            channelHints[channels[i]]?.let {
                canvas.drawText(it, dp(4f), yName + sp(11f), subPaint)
            }
        }
    }

    private fun drawWave(
        canvas: Canvas,
        row: Int,
        y0: Float,
        rowH: Float,
        controlW: Float,
        rightW: Float,
        samples: Int
    ) {
        val ch = ringIndices.getOrElse(row) { row }.coerceIn(0, max(0, buf.size - 1))
        val src = if (denoise) bandpassCopy(ch, row, samples) else buf[ch]
        val amp = ampSteps[ampIndex]
        val mid = y0 + rowH / 2f
        val half = rowH * 0.42f
        // 按真实时间轴铺开：数据不足一个窗时靠右对齐，左侧留空（不画 0 直线）
        val span = max(1, bufLen - 1)
        val shift = (bufLen - samples).coerceAtLeast(0)

        path.reset()
        for (k in 0 until samples) {
            val x = controlW + rightW * ((k + shift).toFloat() / span)
            var v = src[k] / amp
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            val y = mid - v * half
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        wavePaint.color = rowColors[row % rowColors.size]
        canvas.drawPath(path, wavePaint)
    }

    /** 4~45 Hz 二阶带通（RBJ），只对展示拷贝做，且每次重绘从头算（避免跨帧状态）。 */
    private fun bandpassCopy(srcCh: Int, outRow: Int, samples: Int): FloatArray {
        val src = buf[srcCh]
        val dst = filtered[outRow]
        val fs = sampleRate.toDouble()
        val fLow = 4.0
        val fHigh = 45.0
        val f0 = Math.sqrt(fLow * fHigh)
        val q = f0 / (fHigh - fLow)
        val w0 = 2.0 * Math.PI * f0 / fs
        val alpha = Math.sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2.0 * Math.cos(w0) / a0
        val a2 = (1.0 - alpha) / a0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (k in 0 until samples) {
            val x = src[k].toDouble()
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            dst[k] = y.toFloat()
        }
        return dst
    }

    private fun fmtAmp(amp: Float): String = "${amp.toInt()}µV"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        layoutStrip(stripHeight())
        when {
            minusRect.contains(event.x, event.y) -> changeAmp(zoomIn = false)
            plusRect.contains(event.x, event.y) -> changeAmp(zoomIn = true)
        }
        return true
    }

    /** 统一调整所有行的振幅：`+` 放大（满量程减小一档），`−` 缩小。 */
    private fun changeAmp(zoomIn: Boolean) {
        ampIndex = (ampIndex + if (zoomIn) -1 else 1).coerceIn(0, ampSteps.size - 1)
        invalidate()
    }
}
