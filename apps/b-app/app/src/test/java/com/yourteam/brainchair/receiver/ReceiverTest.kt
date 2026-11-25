package com.yourteam.brainchair.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.NetworkInterface
import com.yourteam.brainchair.signal.RecognitionConfig
import com.yourteam.brainchair.signal.SignalTiming
import com.yourteam.brainchair.control.*

@SuppressLint("SetTextI18n")
class ReceiverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    // 极简白 + 红蓝对比
    private val BLUE = Color.parseColor("#2563EB")
    private val RED = Color.parseColor("#EF4444")
    private val INK = Color.parseColor("#1F2937")
    private val MUTED = Color.parseColor("#6B7280")
    private val LINE = Color.parseColor("#E5E7EB")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun Float.dp() = dp(this.toInt())

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var logs: TextView
    private lateinit var recognition: TextView
    private lateinit var unity: TextView
    private lateinit var commandText: TextView
    private lateinit var confText: TextView
    private lateinit var confBar: View
    private lateinit var unlockCard: View
    private val padKeys = mutableMapOf<ControlCommand, TextView>()

    private var lastReceived = 0L
    private var lastAt = 0L
    private var lastSession: StreamSession? = null
    private var pps = 0.0
    private var frozen = false
    private val refresh = object : Runnable {
        override fun run() { if (!frozen) render(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        // ---- 标题 + 状态胶囊 ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "BrainChair · 操控端"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            text = "未监听"
            textSize = 12f
            setTextColor(BLUE)
            gravity = Gravity.CENTER
            background = pill(Color.parseColor("#EFF6FF"), BLUE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        header.addView(status)
        root.addView(header)

        // ---- 数值行：速度 / 反馈 / 包速率 / 模式 ----
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedMetric = metric(metrics, "速度", "—")
        feedMetric = metric(metrics, "9002 反馈", "—")
        ppsMetric = metric(metrics, "包 / 秒", "0")
        modeMetric = metric(metrics, "模式", "—")
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // ---- 主控区：左指令+急停 / 右 D-pad ----
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commandText = TextView(this).apply {
            text = "待机"
            textSize = 26f
            setTextColor(BLUE)
            typeface = Typeface.DEFAULT_BOLD
        }
        left.addView(commandText)
        confText = TextView(this).apply { text = "EEG —"; textSize = 12f; setTextColor(MUTED) }
        left.addView(confText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        confBar = View(this).apply { background = barBg(BLUE) }
        left.addView(confBar, LinearLayout.LayoutParams(dp(120), dp(5)).apply {
            topMargin = dp(6); bottomMargin = dp(12)
        })

        val stopBtn = TextView(this).apply {
            text = "急 停"; textSize = 17f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = circleBtn(RED, Color.parseColor("#B91C1C"))
            setOnClickListener { ReceiverService.emergencyStop(this@ReceiverActivity) }
        }
        left.addView(stopBtn, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(8) })
        val normalStop = smallButton("普通停止") { ReceiverService.controls?.ordinaryStop() }
        left.addView(normalStop, LinearLayout.LayoutParams(dp(120), -2))
        main.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        // D-pad 九宫格（手柄质感）
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun padRow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val r1 = padRow(); val r2 = padRow(); val r3 = padRow()
        fun addPad(row: LinearLayout, label: String, cmd: ControlCommand?, span: Boolean = false) {
            val key = TextView(this).apply {
                text = label; textSize = 15f; setTextColor(INK)
                gravity = Gravity.CENTER; background = padKey()
                if (cmd != null) setOnClickListener {
                    if (ReceiverService.controls?.manual(cmd) != true)
                        Toast.makeText(this@ReceiverActivity, "运动许可不足：检查 9002 反馈、安全存储、急停/故障及速度", Toast.LENGTH_SHORT).show()
                }
            }
            padKeys[cmd ?: ControlCommand.STOP] = key
            row.addView(key, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                leftMargin = if (span) dp(0) else dp(3); rightMargin = if (span) dp(0) else dp(3)
            })
        }
        addPad(r1, "↑", ControlCommand.FORWARD)
        addPad(r2, "←", ControlCommand.LEFT)
        addPad(r2, "■", ControlCommand.STOP)
        addPad(r2, "→", ControlCommand.RIGHT)
        addPad(r3, "↓", ControlCommand.BACKWARD)
        pad.addView(r1); pad.addView(r2); pad.addView(r3)
        main.addView(pad, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(main, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // ---- 急停解除卡（锁定时显示）----
        unlockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(Color.parseColor("#FEF2F2"), RED)
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        (unlockCard as LinearLayout).addView(TextView(this).apply {
            text = "急停已锁定 · Unity 按 Z 后在本端确认解除"
            textSize = 12f; setTextColor(Color.parseColor("#991B1B"))
        })
        (unlockCard as LinearLayout).addView(smallButtonRed("确认 Unity Z 解除") {
            val ok = ReceiverService.controls?.releaseEmergency() == true
            Toast.makeText(this, if (ok) "解除请求已接收，以本地锁定显示为准" else "未解除：需安全存储就绪、Unity 急停确认、Z 解除及新鲜静止无故障状态", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(unlockCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // ---- 模式切换 ----
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeBtn(label: String, m: ControlMode) = smallButton(label) {
            val c = ReceiverService.controls
            if (c == null) Toast.makeText(this@ReceiverActivity, "请先开始监听", Toast.LENGTH_SHORT).show() else c.switchMode(m)
        }
        modeRow.addView(modeBtn("手动接管", ControlMode.MANUAL), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        modeRow.addView(modeBtn("脑控驱动", ControlMode.EEG), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        root.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- 红蓝对比滑块：速度(蓝) / 宽限(红) ----
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val savedUnity = ReceiverService.unitySettings(this)
        val savedGrace = ReceiverService.controlSettings(this).first.rejectionGraceMs
        val speedLabel = TextView(this).apply { text = "速度 ${savedUnity.second}"; textSize = 12f; setTextColor(BLUE) }
        val seekSpeed = SeekBar(this).apply {
            max = 100; progress = savedUnity.second; progressTintList = null
        }
        seekSpeed.thumb = thumb(BLUE)
        val graceLabel = TextView(this).apply { text = "拒识宽限 ${savedGrace}ms"; textSize = 12f; setTextColor(RED) }
        val seekGrace = SeekBar(this).apply { max = 1000; progress = savedGrace.toInt() }
        seekGrace.thumb = thumb(RED)
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { speedLabel.text = "速度 $p" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekGrace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { graceLabel.text = "拒识宽限 ${p}ms" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val speedCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedCol.addView(speedLabel); speedCol.addView(seekSpeed, LinearLayout.LayoutParams(-1, -2))
        val graceCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        graceCol.addView(graceLabel); graceCol.addView(seekGrace, LinearLayout.LayoutParams(-1, -2))
        sliders.addView(speedCol, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        sliders.addView(graceCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(sliders, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- 监听开关 + 保存 ----
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listenBtn = smallButtonBlue("开始监听") {
            val intent = Intent(this, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
        val stopListenBtn = smallButton("停止监听") { stopService(Intent(this, ReceiverService::class.java)) }
        actionRow.addView(listenBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        actionRow.addView(stopListenBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        root.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- 折叠：连接配置 ----
        val connBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val ipInput = editText("Unity 电脑 IPv4", savedUnity.first, InputType.TYPE_TEXT_VARIATION_URI)
        val speedInput = editText("配置速度 0–100", savedUnity.second.toString(), InputType.TYPE_CLASS_NUMBER)
        connBody.addView(ipInput, LinearLayout.LayoutParams(-1, -2))
        connBody.addView(speedInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        connBody.addView(smallButton("保存 Unity 地址与速度") {
            try {
                ReceiverService.configureUnity(this@ReceiverActivity, ipInput.text.toString(), speedInput.text.toString().toInt())
                Toast.makeText(this@ReceiverActivity, "已应用并保存；切换配置先停止监听", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this@ReceiverActivity, e.message ?: "配置无效", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        connBody.addView(smallButton("应用滑块速度与宽限") {
            try {
                ReceiverService.configureUnity(this@ReceiverActivity, ipInput.text.toString(), seekSpeed.progress)
                ReceiverService.configureControl(this@ReceiverActivity, seekGrace.progress.toLong(), ReceiverService.controlSettings(this@ReceiverActivity).second)
                ipInput.setText(ipInput.text.toString()); speedInput.setText(seekSpeed.progress.toString())
                Toast.makeText(this@ReceiverActivity, "滑块值已应用", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this@ReceiverActivity, e.message ?: "应用失败", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(section("连接配置", connBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- 折叠：判决参数 ----
        val cfg = ReceiverService.config
        val cfgBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val etAbs = editText("绝对门 0–1", cfg.absolute.toString(), InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etDiff = editText("差值门 0–1", cfg.difference.toString(), InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etVotes = editText("投票窗 1–10", cfg.votes.toString(), InputType.TYPE_CLASS_NUMBER)
        val etDeb = editText("去抖 ms 0–2000", cfg.debounceMs.toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(etAbs, etDiff, etVotes, etDeb).forEachIndexed { i, v -> cfgBody.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(6) }) }
        cfgBody.addView(smallButton("应用判决参数") {
            try {
                ReceiverService.configure(RecognitionConfig(etAbs.text.toString().toDouble(), etDiff.text.toString().toDouble(), etVotes.text.toString().toInt(), etDeb.text.toString().toLong()))
                Toast.makeText(this@ReceiverActivity, "参数已生效，判决已重置", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this@ReceiverActivity, "门限 0–1，投票 1–10，去抖 0–2000ms", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        recognition = TextView(this).apply { text = "等待识别"; textSize = 12f; setTextColor(MUTED) }
        cfgBody.addView(recognition, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(section("判决参数与识别状态", cfgBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- 折叠：联调安全 ----
        val sim = ReceiverService.simulationSafetySettings(this)
        val simBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val cbStartup = CheckBox(this).apply { text = "启动时自动急停锁定（下次生效）"; isChecked = sim.startupEstop; textSize = 13f }
        val cbFeedback = CheckBox(this).apply { text = "9002 失联强制停车"; isChecked = sim.feedbackTimeoutStop; textSize = 13f }
        val cbDiag = CheckBox(this).apply { text = "记录联调诊断"; isChecked = ReceiverService.controlSettings(this@ReceiverActivity).second; textSize = 13f }
        simBody.addView(cbStartup); simBody.addView(cbFeedback); simBody.addView(cbDiag)
        simBody.addView(smallButton("保存联调安全设置") {
            ReceiverService.configureSimulationSafety(this@ReceiverActivity, SimulationSafetySettings(cbStartup.isChecked, cbFeedback.isChecked))
            ReceiverService.configureControl(this@ReceiverActivity, seekGrace.progress.toLong(), cbDiag.isChecked)
            Toast.makeText(this@ReceiverActivity, "已保存；策略变更先停止监听", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        simBody.addView(smallButton("重试安全存储（保持急停）") { ReceiverService.controls?.retrySafetyStorage() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(section("联调安全设置（仅 Unity 模拟）", simBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- 折叠：诊断 ----
        val diagBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        unity = TextView(this).apply { text = "Unity 尚未配置"; textSize = 12f; setTextColor(MUTED) }
        details = TextView(this).apply { text = ""; textSize = 12f; setTextColor(MUTED) }
        diagBody.addView(unity); diagBody.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(section("诊断与链路", diagBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- 折叠：日志 ----
        val logBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        logs = TextView(this).apply { text = ""; textSize = 11f; setTextColor(MUTED) }
        logs.setTextIsSelectable(true)
        val logBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pauseBtn = smallButton("暂停显示") { frozen = !frozen; (it as TextView).text = if (frozen) "恢复显示" else "暂停显示" }
        logBar.addView(pauseBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        logBar.addView(smallButton("复制日志") { copyLogs() }, LinearLayout.LayoutParams(0, -2, 1f))
        logBody.addView(logBar)
        logBody.addView(logs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(section("接收判决日志", logBody), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
    }

    // ---------- UI 辅助 ----------
    private fun pill(bg: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(bg); cornerRadius = dp(999).toFloat()
        setStroke(dp(1), stroke)
    }
    private fun cardBg(bg: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(bg); cornerRadius = dp(12).toFloat()
        setStroke(dp(1), stroke)
    }
    private fun barBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#E5E7EB")); cornerRadius = dp(3).toFloat()
    }
    private fun thumb(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
    }
    private fun circleBtn(fill: Int, edge: Int) = StateListDrawable().apply {
        val n = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(fill, edge)).apply {
            shape = GradientDrawable.OVAL; setStroke(dp(2), Color.WHITE)
        }
        val p = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(edge); setStroke(dp(3), Color.parseColor("#FCA5A5")) }
        addState(intArrayOf(android.R.attr.state_pressed), p); addState(intArrayOf(), n)
    }
    private fun padKey() = StateListDrawable().apply {
        val n = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.WHITE, Color.parseColor("#ECEFF2"))).apply {
            cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE)
        }
        val p = GradientDrawable().apply { setColor(Color.parseColor("#DCE9FF")); cornerRadius = dp(12).toFloat(); setStroke(dp(2), BLUE) }
        addState(intArrayOf(android.R.attr.state_pressed), p); addState(intArrayOf(), n)
    }
    private fun padKeyEeg() = GradientDrawable().apply {
        setColor(Color.parseColor("#DBEAFE")); cornerRadius = dp(12).toFloat(); setStroke(dp(2), BLUE)
    }
    private fun smallButton(label: String, action: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(INK); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE) }
        setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener(action)
    }
    private fun smallButtonBlue(label: String, action: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(BLUE); cornerRadius = dp(10).toFloat() }
        setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener(action)
    }
    private fun smallButtonRed(label: String, action: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(RED); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), RED) }
        setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener(action)
    }
    private fun editText(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(); inputType = type
        textSize = 13f; setTextColor(INK); setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat(); setStroke(dp(1), LINE) }
    }
    private fun metric(row: LinearLayout, label: String, value: String): TextView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(MUTED) })
        val v = TextView(this).apply { text = value; textSize = 14f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; isSingleLine = true }
        box.addView(v)
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        return v
    }
    private fun section(title: String, body: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE) }
        }
        val head = TextView(this).apply {
            text = "$title   ⌄"; textSize = 14f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        box.addView(head); body.visibility = View.GONE; box.addView(body)
        return box
    }
    private fun TextView.show(value: String) { if (text.toString() != value) text = value }

    // ---------- 真实逻辑 ----------
    private fun copyLogs() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("EEG receiver",
            status.text.toString() + "\n" + recognition.text + "\n" + unity.text + "\n" + details.text + "\n" + logs.text +
                "\n控制事件\n" + (ReceiverService.controls?.authority?.events()?.takeLast(20)?.joinToString("\n") { "${it.atMs} ${it.kind} ${it.state.mode} ${it.state.command} ${it.state.reason}" } ?: "—")))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
    private fun addresses(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }.joinToString { it.hostAddress ?: "?" }.ifEmpty { "未获取，请连接 Wi-Fi" }
    } catch (_: Exception) { "无法读取" }

    private fun render() {
        val session = ReceiverService.session
        val s = session.snapshot()
        val now = SystemClock.elapsedRealtime()
        if (lastSession !== session) { lastSession = session; lastReceived = s.received; lastAt = now; pps = 0.0 }
        if (now - lastAt >= 1000) { pps = (s.received - lastReceived) * 1000.0 / (now - lastAt); lastReceived = s.received; lastAt = now }

        val r = ReceiverService.recognizer?.snapshot()
        val controls = ReceiverService.controls
        val controlNow = controls?.nowMs() ?: now
        val final = controls?.authority?.snapshot()
        val sent = controls?.dispatcher?.status()
        val link = controls?.unityLink?.snapshot()
        val feedback = link?.feedback

        // 状态胶囊
        val locked = final?.emergencyLocked == true
        val error = ReceiverService.error
        val stateText = when {
            error != null -> "故障"
            locked -> "急停锁定"
            ReceiverService.listening -> "监听中"
            else -> "未监听"
        }
        status.show(stateText)
        status.setTextColor(if (locked || error != null) RED else BLUE)
        status.background = pill(if (locked || error != null) Color.parseColor("#FEE2E2") else Color.parseColor("#EFF6FF"), if (locked || error != null) RED else BLUE)

        // 数值行
        speedMetric?.text = "${final?.speed ?: ReceiverService.unitySettings(this).second}"
        feedMetric?.text = if (link?.feedbackOnline == true) "在线" else "超时"
        ppsMetric?.text = "%.1f".format(pps)
        modeMetric?.text = when (final?.mode) { ControlMode.MANUAL -> "手动"; ControlMode.EEG -> "脑控"; else -> "—" }

        // 当前指令 + 置信度 + D-pad 高亮
        val cmd = final?.command
        val cmdLabel = when (cmd) {
            ControlCommand.FORWARD -> "前进"; ControlCommand.BACKWARD -> "后退"
            ControlCommand.LEFT -> "左转"; ControlCommand.RIGHT -> "右转"
            ControlCommand.STOP -> "停止"; else -> if (locked) "急停" else "待机"
        }
        commandText.show(cmdLabel)
        commandText.setTextColor(if (locked) RED else BLUE)

        val scores = r?.scores
        val conf = if (scores != null && scores.isNotEmpty()) scores.maxOrNull() ?: 0.0 else 0.0
        confText.show("EEG %.2f".format(conf))
        (confBar.background as? GradientDrawable)?.setColor(if (locked) Color.parseColor("#FEE2E2") else Color.parseColor("#DBEAFE"))
        confBar.layoutParams.width = (dp(120) * conf.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(1)

        padKeys.forEach { (c, v) -> v.background = if (c == cmd && !locked) padKeyEeg() else padKey() }
        unlockCard.visibility = if (locked) View.VISIBLE else View.GONE

        // 识别详情
        recognition.show(if (r == null) "服务未启动；安全状态需本次会话确认。存储 ${ReceiverService.safetyStorage ?: "尚未检查"}" else
            "CCA 前/左/右/后：" + scores!!.joinToString(" / ") { "%.3f".format(it) } + "\n" +
            "CCA候选 ${r.winner.label} / 门控 ${r.raw.label} / 稳定 ${r.stable.label}\n${r.reason}\n" +
            "最终控制 ${final?.command} / 急停锁定 ${final?.emergencyLocked}：${final?.reason}\n" +
            "运动许可 ${final?.feedbackHealthy} / 需重新授权 ${final?.feedbackRecoveryRequired}：${final?.feedbackFaultReason ?: final?.feedbackReason}\n" +
            "存储许可 ${final?.storageReady} / ${final?.storageReason} · 发送故障 ${final?.transportFault ?: "—"}\n" +
            "阈值 ${r.config.absolute} / ${r.config.difference} · ${r.config.votes}窗 / ${r.config.debounceMs}ms\n" +
            "计算 %.1fms / CPU %.1fms · 判决间隔 ${r.periodMs}ms · ${r.analysisCount}次\n".format(r.computeMs, r.cpuMs))

        unity.show("模式 ${final?.mode ?: "未启动"} · 速度 ${final?.speed ?: ReceiverService.unitySettings(this).second}\n" +
            "Unity ${link?.target ?: ReceiverService.unitySettings(this).first.ifEmpty { "未配置" }}:9001 · 9002 反馈${if (link?.feedbackOnline == true) "在线" else "超时/未收到"}\n" +
            "本机已发 ${link?.sentPackets ?: 0} 包 / 最近 ${sent?.sent?.command ?: "—"} · ${link?.lastSentHex ?: "—"}\n" +
            "对端动作 ${feedback?.action ?: "—"} / 实际速度 ${feedback?.linearMmS ?: "—"}mm/s / 转速 ${feedback?.angularMilliRadS ?: "—"}mrad/s\n" +
            "对端档 ${feedback?.speedPercent ?: "—"} / 急停 ${feedback?.emergency ?: "—"} / 故障 ${feedback?.fault ?: "—"}\n" +
            "本地急停 ${final?.emergencyLocked} / 06 发送 ${sent?.emergencies ?: 0}\n" +
            "安全保存 ${controls?.persistence?.snapshot()}\n" +
            "${sent?.error ?: link?.error ?: "反馈为遥测，非逐包 ACK"}")

        details.show("数据 UDP 9000 / 发现 UDP 9001\n来源 ${s.source ?: "—"}\n" +
            "收包 ${s.received} / 接受 ${s.accepted} / 拒收 ${s.rejected.values.sum()}\n" +
            "最近 %.1f 包/秒\n".format(pps) +
            "采样率 ${s.info?.rate ?: "—"} Hz / 通道 ${s.info?.channels ?: "—"}\n" +
            "重复 ${s.duplicates} / 乱序 ${s.outOfOrder} / 缺口 ${s.missingPackets}\n" +
            "本机 IPv4：${addresses()}")

        val lh = ReceiverService.logHealth?.snapshot()
        logs.show("日志健康 ${lh?.state ?: "未启动"} / 失败 ${lh?.failures ?: 0}\n" + s.logs.takeLast(12).joinToString("\n"))
    }

    // 供 render 内数值行引用的延迟初始化占位（在 onCreate 中重新指向真实 TextView）
    private var speedMetric: TextView? = null
    private var feedMetric: TextView? = null
    private var ppsMetric: TextView? = null
    private var modeMetric: TextView? = null

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
