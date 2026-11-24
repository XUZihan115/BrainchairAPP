package com.fifteen.eegsender

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fifteen.eegsender.protocol.EegCommands
import com.fifteen.eegsender.protocol.EegSignalSource
import com.fifteen.eegsender.protocol.LocalNetwork
import com.fifteen.eegsender.protocol.NetDiagnostics
import com.fifteen.eegsender.protocol.SignalDegrade
import com.fifteen.eegsender.protocol.UdpDiscovery
import com.fifteen.eegsender.protocol.UdpEegSender
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 发送端主界面（极简白 + 交通灯方向键）。
 *
 * ⚠️ 本文件只重写了「界面外观」：UDP 推流、降质、自动发现、日志落盘等真实逻辑
 * 全部保持原样（见下方 beginStream / degrade / discovery / callbacks 各节）。
 *
 * 交互约定：**按住方向键 = 一直输出该方向的想象脑电，松手才结束**。
 * 按下瞬间起流，以 1000 Hz 采样率、按真实采集设备的报文格式持续推送，松手
 * （ACTION_UP / ACTION_CANCEL）时补发一个 STREAM_STOP 作为「本次想象到此为止」
 * 的显式标签。按住期间长度不设限，只有 [MAX_HOLD_SECONDS] 这个兜底上限。
 *
 * 按住期间可以随时拖动「信号置信度」滑块（0~100 = 置信度，100 = 原样发送）：
 * 陷波强度是**每个报文重新读一次**的 volatile 字段，改一下立刻出现在码流里
 * （延迟 ≤ 一个包 = 5 ms），不需要重开流。
 *
 * 「停」键推的是真实静息段（无 SSVEP 刺激），对应接收端的 stop 指令。
 *
 * 目标地址有两种给法：
 * - **IP 留空** → 先在局域网内广播 DISCOVER 自动找到接收端（见 [UdpDiscovery]）；
 * - **填了 IP** → 定向直发，不做任何发现。
 */
class MainActivity : AppCompatActivity(), UdpEegSender.Listener {

    // ---- 极简白 + 红蓝对比 令牌（与 B 端保持一致）----
    private val BLUE = Color.parseColor("#2563EB")
    private val RED = Color.parseColor("#EF4444")
    private val GREEN = Color.parseColor("#22C55E")
    private val AMBER = Color.parseColor("#F59E0B")
    private val INK = Color.parseColor("#1F2937")
    private val MUTED = Color.parseColor("#6B7280")
    private val LINE = Color.parseColor("#E5E7EB")
    private val SOFT = Color.parseColor("#F5F7FA")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvStream: TextView
    private lateinit var tvLog: TextView
    private lateinit var sbConfidence: SeekBar
    private lateinit var tvConfidenceValue: TextView
    private lateinit var tvDegradeState: TextView

    /** 五盏方向灯（数据未载入完成前整体禁用）。 */
    private val commandButtons = ArrayList<View>()
    private val lamps = HashMap<Int, TextView>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = ArrayDeque<String>()

    private var source: EegSignalSource? = null
    private var sender: UdpEegSender? = null
    private var senderKey: String? = null

    /** 当前正在推流的方向；-1 表示空闲。 */
    private var activeCommand = -1

    /**
     * 降质参数预热线程。每段的 band / rms 要跑 62 次 16384 点 FFT（约 0.5 秒），
     * 只能在后台算：在 UI 线程算会卡住界面，在推流线程算会卡出成片丢包。
     */
    private val degradeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eeg-degrade-prep").apply { isDaemon = true }
    }

    /** 降质参数是否正在后台预热（只在主线程读写）。 */
    private var degradePreparing = false

    /** 推流/发现令牌：每次「点按」自增，页面销毁时再自增。后台线程会读，故 volatile。 */
    @Volatile
    private var streamToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        buildUi()

        tvStream.text = getString(R.string.stream_idle)
        setStatus(getString(R.string.status_loading), R.color.warn)
        showLastBreath()
        logEnvironment()
        loadDataset()
    }

    // ============================================================ 界面构建（仅外观）
    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }

        // ---- 标题 + 状态胶囊 ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "BrainChair · 发射端"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        tvStatus = TextView(this).apply {
            text = getString(R.string.status_loading)
            textSize = 12f
            setTextColor(BLUE)
            gravity = Gravity.CENTER
            background = pill(Color.parseColor("#EFF6FF"), BLUE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        header.addView(tvStatus)
        root.addView(header)

        // ---- 接收端地址（IP 留空 = 自动发现）----
        val addrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etIp = editText("接收端 IPv4（留空自动发现）", "", InputTypeText)
        etPort = editText("端口", "9000", InputTypeNumber)
        addrRow.addView(etIp, LinearLayout.LayoutParams(0, -2, 2f))
        addrRow.addView(etPort, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(addrRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // ---- 码流状态行 ----
        tvStream = TextView(this).apply {
            text = getString(R.string.stream_idle)
            textSize = 12f
            setTextColor(BLUE)
            typeface = Typeface.MONOSPACE
        }
        root.addView(tvStream, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- 交通灯：四个方向箭头 + 中间圆形停止灯 ----
        root.addView(buildLampPad(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // ---- 置信度滑块（蓝轨 + 红 thumbs，红蓝对比）----
        val confHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        confHead.addView(TextView(this).apply {
            text = "信号置信度"
            textSize = 13f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        tvConfidenceValue = TextView(this).apply {
            text = getString(R.string.degrade_value_full)
            textSize = 12f
            setTextColor(BLUE)
            typeface = Typeface.MONOSPACE
        }
        confHead.addView(tvConfidenceValue)
        root.addView(confHead, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        sbConfidence = SeekBar(this).apply {
            max = MAX_CONFIDENCE
            progress = MAX_CONFIDENCE
            progressTintList = android.content.res.ColorStateList.valueOf(BLUE)
            thumbTintList = android.content.res.ColorStateList.valueOf(RED)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(LINE)
        }
        root.addView(sbConfidence, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        tvDegradeState = TextView(this).apply {
            text = getString(R.string.degrade_off)
            textSize = 11f
            setTextColor(MUTED)
        }
        root.addView(tvDegradeState, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // ---- 日志（折叠）----
        val logBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val clearLog = smallButton("清空") { logLines.clear(); tvLog.text = "" }
        val logBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        logBar.addView(clearLog)
        logBody.addView(logBar)
        tvLog = TextView(this).apply {
            text = ""; textSize = 11f; setTextColor(MUTED); typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        logBody.addView(tvLog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(section("发送日志", logBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        bindDegradeControls()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** 十字灯组：上=前进(绿)，下=后退(蓝)，左/右=转向(琥珀)，中=停止(红)。 */
    private fun buildLampPad(): View {
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // 上：前进
        pad.addView(lampCell(EegCommands.FORWARD, "»", 90f, "前进", GREEN))
        // 中排：左 / 停 / 右
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mid.addView(lampCell(EegCommands.LEFT, "»", 0f, "左转", AMBER))
        mid.addView(lampCell(EegCommands.STOP, "■", 0f, "停", RED, round = true))
        mid.addView(lampCell(EegCommands.RIGHT, "»", 180f, "右转", AMBER))
        pad.addView(mid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        // 下：后退
        pad.addView(lampCell(EegCommands.BACKWARD, "»", -90f, "后退", BLUE),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        return pad
    }

    /**
     * 一盏灯。熄灭时是浅灰灯罩 + 灰箭头；按住时用 StateListDrawable 切成亮色描边，
     * 并在 touch 里给箭头加白热字色 + 彩色辉光（贴近真实按钮灯）。
     * rotation 让 "»" 指向对应方向：前进朝上 90°、后退朝下 -90°、左 0°、右 180°。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun lampCell(command: Int, glyph: String, angle: Float, label: String, color: Int, round: Boolean = false): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val lamp = TextView(this).apply {
            text = glyph
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B7C0CC"))
            typeface = Typeface.DEFAULT_BOLD
            rotation = angle
            background = lampFace(color, round)
            if (round) {
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
            } else {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(56))
            }
        }
        cell.addView(lamp)
        cell.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(MUTED); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        lamps[command] = lamp
        bindTap(lamp, command, color)
        return cell
    }

    // ---- UI 辅助 ----
    private val InputTypeText = android.text.InputType.TYPE_CLASS_TEXT
    private val InputTypeNumber = android.text.InputType.TYPE_CLASS_NUMBER

    private fun pill(bg: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(bg); cornerRadius = dp(999).toFloat(); setStroke(dp(1), stroke)
    }
    private fun lampFace(color: Int, round: Boolean) = StateListDrawable().apply {
        val radius = if (round) dp(999).toFloat() else dp(12).toFloat()
        val n = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.WHITE, SOFT)).apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setStroke(dp(1), LINE)
        }
        val p = GradientDrawable().apply {
            setColor(Color.parseColor("#F8FAFC")); cornerRadius = radius; setStroke(dp(2), color)
        }
        addState(intArrayOf(android.R.attr.state_pressed), p)
        addState(intArrayOf(), n)
    }
    private fun editText(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint; setText(value); isSingleLine = true; inputType = type
        textSize = 13f; setTextColor(INK); setHintTextColor(MUTED)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE)
        }
    }
    private fun smallButton(label: String, action: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(BLUE); gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(9).toFloat(); setStroke(dp(1), LINE)
        }
        setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener(action)
    }
    private fun section(title: String, body: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE)
            }
        }
        val head = TextView(this).apply {
            text = "$title   ⌄"; textSize = 14f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        box.addView(head); body.visibility = View.GONE; box.addView(body)
        return box
    }

    // ============================================================ 真实逻辑（保持原样）

    override fun onStart() {
        super.onStart()
        appendLog("LIFE onStart")
    }

    override fun onResume() {
        super.onResume()
        appendLog("LIFE onResume")
    }

    override fun onPause() {
        appendLog("LIFE onPause")
        super.onPause()
    }

    override fun onStop() {
        // 界面不可见时把流收掉：此刻手指多半已经离开，ACTION_UP 永远不会到达，
        // 不主动打断就会一直发到 MAX_HOLD_SECONDS。用 interrupt()（不补收尾报文）：
        // 接收端不该把「App 退到后台」当成一次正常的想象结束。
        if (sender?.isStreaming() == true) {
            sender?.interrupt()
            appendLog("⏸ 界面进入后台 → 打断当前推流（不发收尾报文）")
        }
        streamToken++ // 作废可能还在跑的自动发现
        appendLog("LIFE onStop")
        super.onStop()
    }

    override fun onDestroy() {
        // 作废可能还在跑的自动发现，避免它回来时触碰已销毁的界面
        streamToken++
        sender?.shutdown()
        sender = null
        degradeExecutor.shutdownNow()
        appendLog("LIFE onDestroy")
        super.onDestroy()
    }

    // ------------------------------------------------------------ 人为降质开关

    /**
     * 拖动只改一个 float，立刻生效。真正的 band / rms 预计算在数据集载入后一次性完成。
     */
    private fun bindDegradeControls() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyDegrade()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        sbConfidence.setOnSeekBarChangeListener(listener)
        applyDegrade()
    }

    /** 把置信度滑块换算成陷波强度写进数据源，并刷新界面文案。 */
    private fun applyDegrade() {
        if (!::sbConfidence.isInitialized) return

        val confidence = sbConfidence.progress
        val data = source

        // 100% 时强度恰好为 0 —— 走「原样拷贝」快路径，一个字节都不动。
        data?.noiseAlpha = 0f
        data?.notchAlpha = SignalDegrade.notchAlphaFor(confidence)

        tvConfidenceValue.text = degradeValueText(confidence)

        if (confidence >= MAX_CONFIDENCE) {
            updateDegradeState()
            return
        }
        startDegradePreheat()
    }

    private fun degradeValueText(confidence: Int): String = getString(
        R.string.degrade_value,
        confidence,
        SignalDegrade.expectedRho(confidence)
    )

    /** 后台把 5 个方向的降质参数都算好，只跑一次（算过的方向会被缓存）。 */
    private fun startDegradePreheat() {
        val data = source ?: return
        if (degradePreparing) return

        if ((0 until EegCommands.COUNT).all { data.isDegradeReady(it) }) {
            updateDegradeState()
            return
        }

        degradePreparing = true
        tvDegradeState.text = getString(R.string.degrade_preparing)
        appendLog("开始预处理降质参数（5 个方向，各约 0.5 s，只需一次）…")

        degradeExecutor.execute {
            var failure: String? = null
            var ready = 0
            for (command in 0 until EegCommands.COUNT) {
                val used = runCatching { data.prepareDegrade(command) }.getOrElse { error ->
                    failure = error.message ?: error.javaClass.simpleName
                    0L
                }
                if (data.isDegradeReady(command)) ready++
                val line = "  降质预热：${EegCommands.name(command)} 用时 ${used} ms"
                mainHandler.post { appendLog(line) }
            }

            val message = failure
            val done = ready
            mainHandler.post {
                degradePreparing = false
                if (message != null) {
                    tvDegradeState.text = getString(R.string.degrade_failed, message)
                    appendLog("降质参数计算失败：$message")
                } else {
                    updateDegradeState()
                    appendLog("降质参数已就绪（$done/${EegCommands.COUNT} 个方向）")
                }
            }
        }
    }

    private fun updateDegradeState() {
        val data = source ?: return
        if (degradePreparing) {
            tvDegradeState.text = getString(R.string.degrade_preparing)
            return
        }
        if (::sbConfidence.isInitialized && sbConfidence.progress >= MAX_CONFIDENCE) {
            tvDegradeState.text = getString(R.string.degrade_off_ready)
            return
        }
        val ready = (0 until EegCommands.COUNT).count { data.isDegradeReady(it) }
        tvDegradeState.text = getString(R.string.degrade_ready, ready, EegCommands.COUNT)
    }

    /** 把当前降质状态压成一行，写进发送日志。 */
    private fun degradeSummary(command: Int): String? {
        if (!::sbConfidence.isInitialized) return null
        val confidence = sbConfidence.progress
        if (confidence >= MAX_CONFIDENCE) return null

        val ready = source?.isDegradeReady(command) == true
        val suffix = if (ready) "" else "（该方向尚未预热，本次原样输出）"
        return String.format(
            Locale.US,
            "置信度 %d%%（陷波 α=%.3f）%s",
            confidence,
            SignalDegrade.notchAlphaFor(confidence),
            suffix
        )
    }

    // ---------------------------------------------------------------- 触摸绑定

    /**
     * 把某盏灯绑定成「按住一直发、松手收尾」。用 OnTouchListener 在按下瞬间起流，
     * 同时点亮灯的辉光；抬手（含 ACTION_CANCEL）收尾并熄灭。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTap(view: View, command: Int, color: Int) {
        commandButtons.add(view)
        view.isEnabled = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    lightLamp(command, true, color)
                    beginStream(command)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    lightLamp(command, false, color)
                    endStream(command)
                    true
                }
                else -> false
            }
        }
    }

    /** 点亮/熄灭一盏灯：白热字色 + 彩色辉光。 */
    private fun lightLamp(command: Int, on: Boolean, color: Int) {
        val lamp = lamps[command] ?: return
        if (on) {
            lamp.setTextColor(Color.WHITE)
            lamp.setShadowLayer(dp(14).toFloat(), 0f, 0f, color)
        } else {
            lamp.setTextColor(Color.parseColor("#B7C0CC"))
            lamp.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        lamp.invalidate()
    }

    /**
     * 松手收尾：让当前这路流补发 STREAM_STOP 并退出。
     * 两道守卫针对多点触控：还在推流 且 推的就是我这一路。
     */
    private fun endStream(command: Int) {
        val active = sender ?: return
        if (!active.isStreaming()) return
        if (active.currentCommand() != command) return

        appendLog("▲ 松手 → 收尾「${EegCommands.name(command)}」，补发 STREAM_STOP")
        val t0 = System.currentTimeMillis()
        active.release()
        appendLog("  release() 已返回（耗时 ${System.currentTimeMillis() - t0} ms）")
    }

    // ------------------------------------------------------------ 环境自检

    /** 启动时把「设备 / 系统 / 网卡 / 局域网权限」打进日志。 */
    private fun logEnvironment() {
        appendLog("BUILD_TAG=${BUILD_TAG}")
        appendLog(
            "设备 ${Build.MANUFACTURER} ${Build.MODEL}｜Android ${Build.VERSION.RELEASE}" +
                "（API ${Build.VERSION.SDK_INT}）｜targetSdk ${applicationInfo.targetSdkVersion}"
        )

        val targets = runCatching { UdpDiscovery().describeTargets() }.getOrDefault(emptyList())
        if (targets.isEmpty()) {
            appendLog("  没有可用网卡：请先连接 WiFi / 打开热点")
        } else {
            for (line in targets) {
                appendLog("  网卡 $line")
            }
        }
        appendLog("  ${localNetworkPermissionText()}")
    }

    /** 局域网访问权限的状态；未授予时尽力申请一次，绝不阻塞推流。 */
    private fun localNetworkPermissionText(): String {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_MIN_API) {
            return "局域网权限：API ${Build.VERSION.SDK_INT} < $LOCAL_NETWORK_MIN_API，本系统无此限制"
        }
        val granted = runCatching {
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION)
        }.getOrDefault(PackageManager.PERMISSION_DENIED)
        if (granted == PackageManager.PERMISSION_GRANTED) {
            return "局域网权限：已授予"
        }
        runCatching {
            requestPermissions(arrayOf(LOCAL_NETWORK_PERMISSION), REQUEST_LOCAL_NETWORK)
        }
        return "局域网权限：未授予，已尝试申请（无弹窗则到 设置→应用→权限 手动开启）"
    }

    // ------------------------------------------------------------ 推流启停控制

    private fun beginStream(command: Int) {
        val data = source
        if (data == null) {
            setStatus(getString(R.string.status_not_ready), R.color.warn)
            return
        }

        // 任意时刻只允许一路流。
        val activeNow = sender
        if (activeNow != null && activeNow.isStreaming()) {
            val runningCmd = activeNow.currentCommand()
            if (runningCmd == command) {
                appendLog("「${EegCommands.name(command)}」正在推流中，重复按下忽略")
                return
            }
            appendLog(
                "⇄ 打断「${EegCommands.name(runningCmd)}」→ 切换到「${EegCommands.name(command)}」"
            )
            activeNow.interrupt()
            activeCommand = -1
        } else if (activeCommand != -1) {
            if (activeCommand == command) {
                appendLog("「${EegCommands.name(command)}」正在发现接收端，重复点按忽略")
                return
            }
            appendLog(
                "⇄ 打断发现中的「${EegCommands.name(activeCommand)}」" +
                    " → 切换到「${EegCommands.name(command)}」"
            )
            streamToken++
            activeCommand = -1
        }

        val hz = EegCommands.stimulusHz(command)
        val stimulusText =
            if (hz != null) String.format(Locale.US, "%.2f Hz 刺激", hz) else "静息（无刺激）"
        appendLog("▼ 按住 ${EegCommands.name(command)}｜$stimulusText（松手才结束，兜底上限 ${MAX_HOLD_SECONDS} s）")
        degradeSummary(command)?.let { appendLog("  降质：$it") }

        val host = etIp.text.toString().trim()
        if (host.isEmpty()) {
            beginAutoDiscovery(command, data)
            return
        }

        // 定向直发必须先校验地址，拦住「假成功」。
        val target = LocalNetwork.parseIpv4(host)
        if (target == null) {
            setStatus(getString(R.string.status_bad_ip), R.color.error)
            appendLog("  IP 格式不对：「$host」不是 IPv4 地址")
            appendLog("  应为 4 段 0-255 的数字（例如 10.14.166.234）；或留空改用自动发现。")
            return
        }

        val subnets = LocalNetwork.subnets()
        if (subnets.isNotEmpty() && subnets.none { it.contains(target) }) {
            setStatus(getString(R.string.status_ip_off_lan), R.color.error)
            appendLog("  $host 与本机不在同一网段：报文会被交给网关丢弃，接收端收不到。")
            appendLog("  本机网段：${LocalNetwork.describe(subnets)}")
            appendLog("  请核对地址是否打错；拿不准就留空用自动发现。")
            return
        }

        val port = etPort.text.toString().trim().toIntOrNull() ?: DEFAULT_PORT
        if (port !in 1..65535) {
            setStatus(getString(R.string.status_bad_port), R.color.error)
            return
        }
        appendLog("  目标 $host:$port（定向直发）")
        startStreaming(host, port, command, data)
    }

    /** IP 留空时的路径：广播 DISCOVER，拿到接收端 IP 与数据端口再推流。 */
    private fun beginAutoDiscovery(command: Int, data: EegSignalSource) {
        val token = ++streamToken
        activeCommand = command
        setStatus(getString(R.string.status_discovering), R.color.warn)
        appendLog("  IP 留空 → 正在广播发现接收端（UDP $DISCOVERY_PORT）…")

        Thread {
            val discovery = UdpDiscovery(
                discoveryPort = DISCOVERY_PORT,
                cancelled = { token != streamToken }
            )
            for (line in discovery.describeTargets()) {
                val shown = line
                mainHandler.post { if (token == streamToken) appendLog("  广播出口 $shown") }
            }
            val found = discovery.discover()

            mainHandler.post {
                if (token != streamToken) return@post

                if (found == null) {
                    activeCommand = -1
                    setStatus(getString(R.string.status_discover_failed), R.color.error)
                    val why = discovery.lastError
                    if (why != null) appendLog("  广播发送失败：${NetDiagnostics.explain(why)}")
                    appendLog("  未发现接收端：请确认接收端已启动，并在 UDP $DISCOVERY_PORT 上应答；")
                    appendLog("  或在 IP 框里直接填接收端地址，改用定向直发。")
                    return@post
                }

                val label = if (found.name.isEmpty()) "（未命名）" else found.name
                appendLog("  已发现接收端 $label｜${found.endpoint}")
                etIp.setText(found.address)
                appendLog("  地址已填入 IP 框，下次可直接定向直发（想再自动发现就把 IP 清空）")
                startStreaming(found.address, found.dataPort, command, data)
            }
        }.apply {
            name = "eeg-discovery"
            isDaemon = true
        }.start()
    }

    /** 真正启动推流；定向直发与自动发现两条路径都汇到这里。 */
    private fun startStreaming(host: String, port: Int, command: Int, data: EegSignalSource) {
        val active = obtainSender(host, port, data)
        activeCommand = command

        setStatus(getString(R.string.status_streaming, EegCommands.name(command)), R.color.warn)
        if (!active.start(command, MAX_HOLD_SECONDS * data.sampleRate.toLong())) {
            activeCommand = -1
            appendLog("推流线程启动失败")
        }
    }

    /** 复用发送器；目标地址变化时重建。 */
    private fun obtainSender(host: String, port: Int, data: EegSignalSource): UdpEegSender {
        val key = "$host:$port"
        val existing = sender
        if (existing != null && senderKey == key) return existing
        existing?.shutdown()
        val created = UdpEegSender(host, port, data, this)
        sender = created
        senderKey = key
        return created
    }

    // ------------------------------------------------------------ 数据载入

    private fun loadDataset() {
        appendLog("正在载入真实脑电数据（assets/ssvep）…")
        Thread {
            val result = runCatching { EegSignalSource.load(applicationContext) }
            mainHandler.post {
                result.onSuccess { loaded ->
                    source = loaded
                    commandButtons.forEach { it.isEnabled = true }
                    setStatus(getString(R.string.status_ready), R.color.ok)
                    appendLog("数据集就绪：${loaded.summary()}")

                    val names = loaded.channelNames
                    val shown = if (names.size <= 12) names else names.take(12) + "…"
                    appendLog("  通道：${shown.joinToString(", ")}")

                    for (c in 0 until EegCommands.COUNT) {
                        val region = loaded.regionOf(c)
                        val hz = EegCommands.stimulusHz(c)
                        val tag = if (hz != null) String.format(Locale.US, "%.2f Hz", hz) else "静息"
                        appendLog(
                            "  ${EegCommands.name(c)}｜$tag｜${region.sampleCount} 采样点" +
                                String.format(Locale.US, "（回绕一次 %.1f s）", loaded.durationSeconds(c))
                        )
                    }
                    appendLog(getString(R.string.hint_trial))
                    appendLog(
                        "  建议单次按住不少于 ${TRIAL_SECONDS} s：接收端要凑满一个" +
                            "${TRIAL_SECONDS} s 窗才出判决（信号是 5 ms 级的，判决不是）。"
                    )

                    startDegradePreheat()
                }.onFailure { error ->
                    setStatus(getString(R.string.status_load_failed), R.color.error)
                    appendLog("数据集载入失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.apply {
            name = "eeg-dataset-load"
            isDaemon = true
        }.start()
    }

    // ------------------------------------------------------------ 推流回调

    override fun onStreamStarted(command: Int, samplesPerPacket: Int, packetsPerSecond: Int) {
        mainHandler.post {
            appendLog(
                "  STREAM_INFO 已发送｜每包 $samplesPerPacket 采样点" +
                    "｜$packetsPerSecond 包/秒｜开始推流"
            )
        }
    }

    override fun onStreamProgress(
        command: Int,
        elapsedMs: Long,
        packets: Long,
        sampleIndex: Long,
        bytesSent: Long
    ) {
        val rateKb = if (elapsedMs > 0) bytesSent * 1000.0 / elapsedMs / 1024.0 else 0.0
        val text = String.format(
            Locale.US,
            "「%s」已推 %.1f s｜%d 采样点｜%d 包｜%.2f MB｜%.0f kB/s",
            EegCommands.name(command),
            elapsedMs / 1000.0,
            sampleIndex,
            packets,
            bytesSent / 1048576.0,
            rateKb
        )
        mainHandler.post { tvStream.text = text }
    }

    override fun onStreamStopped(
        command: Int,
        reason: Int,
        lastSampleIndex: Long,
        packets: Long,
        bytesSent: Long
    ) {
        mainHandler.post {
            val text = String.format(
                Locale.US,
                "「%s」推流结束｜共 %.1f s｜%d 采样点｜%d 包｜%.2f MB",
                EegCommands.name(command),
                lastSampleIndex / sourceHz().toDouble(),
                lastSampleIndex,
                packets,
                bytesSent / 1048576.0
            )
            val takenOver = sender?.isStreaming() == true
            if (takenOver || command != activeCommand) {
                appendLog("⤫ 上一路「${EegCommands.name(command)}」已结束（被新试次接替）")
                return@post
            }
            activeCommand = -1
            setStatus(getString(R.string.status_idle), R.color.ok)
            appendLog(
                String.format(
                    Locale.US,
                    "▲ 试次结束「%s」｜已补发 STREAM_STOP（reason=0x%02X）",
                    EegCommands.name(command),
                    reason
                )
            )
            appendLog("  $text")
            tvStream.text = getString(R.string.stream_idle)
        }
    }

    override fun onStreamFailed(command: Int, message: String) {
        mainHandler.post {
            if (command != activeCommand) {
                appendLog("⤫ 上一路「${EegCommands.name(command)}」已结束（被新试次接替）：$message")
                return@post
            }
            activeCommand = -1
            setStatus(getString(R.string.status_failed, message), R.color.error)
            appendLog("推流失败（${EegCommands.name(command)}）：$message")
            tvStream.text = getString(R.string.stream_idle)
        }
    }

    // ------------------------------------------------------------ 界面小工具

    /** 采样率；数据集还没载入时退回 1000（只用于把采样点数换算成秒）。 */
    private fun sourceHz(): Long = (source?.sampleRate ?: 1000).toLong()

    private fun setStatus(text: String, colorRes: Int) {
        tvStatus.text = text
        tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun appendLog(line: String) {
        val stamped = "[${timeFormat.format(Date())}] $line"
        logLines.addLast(stamped)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        tvLog.text = logLines.reversed().joinToString("\n")
        persistLine(stamped)
    }

    /** 把日志行落盘：进程即使被系统静默杀掉，下次启动也能看到最后一刻发生了什么。 */
    private fun persistLine(line: String) {
        runCatching {
            val file = File(filesDir, LAST_BREATH_FILE)
            file.appendText(line + "\n")
            if (file.length() > LAST_BREATH_MAX_BYTES) {
                val tail = file.readLines().takeLast(LAST_BREATH_KEEP_LINES).joinToString("\n")
                file.writeText(tail + "\n")
            }
        }
    }

    /** 启动时把「上次进程结束前」的最后几行捞回界面。 */
    private fun showLastBreath() {
        val lines = runCatching {
            File(filesDir, LAST_BREATH_FILE).readLines().takeLast(LAST_BREATH_SHOWN)
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        logLines.addLast("───── 本次会话 ─────")
        lines.asReversed().forEach { logLines.addLast(it) }
        logLines.addLast("───── 上次会话（进程结束前）─────")
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        tvLog.text = logLines.reversed().joinToString("\n")
    }

    /** 未捕获异常兜底落盘：区分「App 自己崩溃」与「被系统杀掉」。 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            runCatching {
                File(filesDir, LAST_BREATH_FILE).appendText(
                    ">>> UNCAUGHT ${throwable.javaClass.simpleName} @ " +
                        "${timeFormat.format(Date())} on ${thread.name} <<<\n$sw"
                )
            }
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                throwable.printStackTrace(System.err)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private companion object {
        const val DEFAULT_PORT = 9000
        const val TRIAL_SECONDS = 3
        const val MAX_HOLD_SECONDS = 60
        const val DISCOVERY_PORT = 9001
        const val MAX_LOG_LINES = 120
        const val BUILD_TAG = "NIO-NOSCROLL-20261021"
        const val LAST_BREATH_FILE = "last_breath.log"
        const val LAST_BREATH_MAX_BYTES = 64 * 1024
        const val LAST_BREATH_KEEP_LINES = 300
        const val LAST_BREATH_SHOWN = 30
        const val MAX_CONFIDENCE = 100
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_MIN_API = 36
        const val REQUEST_LOCAL_NETWORK = 0x4C4E
    }
}
