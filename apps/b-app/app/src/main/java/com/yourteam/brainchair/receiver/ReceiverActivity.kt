package com.yourteam.brainchair.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import java.net.NetworkInterface
import com.yourteam.brainchair.signal.RecognitionConfig
import com.yourteam.brainchair.signal.SignalTiming
import com.yourteam.brainchair.control.*
import android.text.InputType

@SuppressLint("SetTextI18n") // Chinese-only diagnostic screen; UI team will replace presentation.
class ReceiverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var logs: TextView
    private lateinit var recognition: TextView
    private lateinit var unity:TextView
    private var lastReceived = 0L; private var lastAt = 0L
    private var lastSession: StreamSession? = null
    private var pps = 0.0
    private var frozen = false
    private val refresh = object : Runnable {
        override fun run() { if (!frozen) render(); handler.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(24 + insets.systemWindowInsetLeft, 24 + insets.systemWindowInsetTop, 24 + insets.systemWindowInsetRight, 24 + insets.systemWindowInsetBottom)
            insets
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 8); root.addView(this) }
        text("BrainChair B · Stage 3", 22f)
        text("${SignalTiming.WINDOW_SECONDS}秒CCA · 手动/EEG · Unity UDP。真实脑控安全验收未通过。", 14f)
        text("本机 IPv4：" + addresses(), 14f)
        val row = LinearLayout(this)
        root.addView(row)
        fun button(label: String, action: () -> Unit) { row.addView(Button(this).apply { text = label; contentDescription=label;setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f)) }
        button("开始监听") {
            val intent = Intent(this, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
        button("停止监听") { stopListening() }
        button("复制日志") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("EEG receiver", status.text.toString() + "\n" + recognition.text + "\n" + details.text + "\n" + logs.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        val settings=ReceiverService.unitySettings(this)
        val networkRow=LinearLayout(this);root.addView(networkRow)
        val ip=EditText(this).apply{hint="Unity电脑IPv4";setText(settings.first);contentDescription="unity-ip";setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI}
        val speed=EditText(this).apply{hint="速度0–100";setText(settings.second.toString());contentDescription="unity-speed";setSingleLine();inputType=InputType.TYPE_CLASS_NUMBER}
        networkRow.addView(ip,LinearLayout.LayoutParams(0,-2,2f));networkRow.addView(speed,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(Button(this).apply{text="保存Unity地址与速度";contentDescription="unity-save";setOnClickListener{
            try{ReceiverService.configureUnity(this@ReceiverActivity,ip.text.toString(),speed.text.toString().toInt())
                Toast.makeText(this@ReceiverActivity,"已保存；切换配置先停止，需新的控制输入",Toast.LENGTH_SHORT).show()
            }catch(e:Exception){Toast.makeText(this@ReceiverActivity,e.message?:"配置无效",Toast.LENGTH_LONG).show()}
        }})
        val modeRow=LinearLayout(this);root.addView(modeRow)
        fun mode(label:String,m:ControlMode){modeRow.addView(Button(this).apply{text=label;contentDescription="mode-${m.name}";setOnClickListener{
            val c=ReceiverService.controls
            if(c==null)Toast.makeText(this@ReceiverActivity,"请先开始监听以启动控制服务",Toast.LENGTH_SHORT).show() else c.switchMode(m)
        }},LinearLayout.LayoutParams(0,-2,1f))}
        mode("手动模式",ControlMode.MANUAL);mode("EEG模式",ControlMode.EEG)
        val safetyRow=LinearLayout(this);root.addView(safetyRow)
        fun safety(label:String,action:()->Unit){safetyRow.addView(Button(this).apply{text=label;contentDescription=label;setOnClickListener{action()}},LinearLayout.LayoutParams(0,-2,1f))}
        safety("普通停止"){ReceiverService.controls?.ordinaryStop()}
        safety("急停锁定"){ReceiverService.emergencyStop(this)}
        safety("确认Unity Z解除"){
            val ok=ReceiverService.controls?.releaseEmergency()==true
            Toast.makeText(this,if(ok)"B锁已解除，需新流或新的手动操作" else "未解除：需先收到Unity急停状态，再按Z解除，并收到新鲜、静止、无故障状态",Toast.LENGTH_LONG).show()
        }
        val moveRow=LinearLayout(this);root.addView(moveRow)
        for((label,command) in listOf("前进" to ControlCommand.FORWARD,"后退" to ControlCommand.BACKWARD,"左转" to ControlCommand.LEFT,"右转" to ControlCommand.RIGHT)){
            moveRow.addView(Button(this).apply{text=label;contentDescription="manual-${command.name}";setOnClickListener{
                if(ReceiverService.controls?.manual(command)!=true)Toast.makeText(this@ReceiverActivity,"需启动服务、选择手动模式且解除安全锁；速度须大于0",Toast.LENGTH_SHORT).show()
            }},LinearLayout.LayoutParams(0,-2,1f))
        }
        text("手动为持续控制，点击普通停止结束；切换模式先停止。急停先发06短连发，此后STOP保活；Unity按Z后仍需在B确认。",12f)
        unity=text("Unity尚未配置",14f)
        status = text("未监听", 18f); recognition=text("等待识别",14f)
        val configRow=LinearLayout(this);root.addView(configRow)
        fun field(hint:String,value:String)=EditText(this).apply { this.hint=hint;setText(value);textSize=14f;setSingleLine();configRow.addView(this,LinearLayout.LayoutParams(0,-2,1f)) }
        val c=ReceiverService.config
        val absolute=field("绝对门",c.absolute.toString());val difference=field("差值门",c.difference.toString())
        val votes=field("投票窗",c.votes.toString());val debounce=field("去抖ms",c.debounceMs.toString())
        root.addView(Button(this).apply{text="应用调试阈值（未校准）";setOnClickListener{
            try{ReceiverService.configure(RecognitionConfig(absolute.text.toString().toDouble(),difference.text.toString().toDouble(),votes.text.toString().toInt(),debounce.text.toString().toLong()))
                Toast.makeText(this@ReceiverActivity,"参数生效，判决已重置",Toast.LENGTH_SHORT).show()
            }catch(_:Exception){Toast.makeText(this@ReceiverActivity,"门限0–1，投票1–10，去抖0–2000ms",Toast.LENGTH_LONG).show()}
        }})
        root.addView(Button(this).apply {text="暂停显示（接收继续）";setOnClickListener{frozen=!frozen;text=if(frozen)"恢复显示" else "暂停显示（接收继续）"}})
        details = text("",14f); logs = text("", 12f)
        logs.setTextIsSelectable(true)
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
    }
    private fun stopListening() {
        val stopIntent = Intent(this, ReceiverService::class.java)
        stopService(stopIntent)
    }
    private fun TextView.show(value: String) { if(text.toString() != value) text = value }
    private fun addresses(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }.joinToString { it.hostAddress ?: "?" }.ifEmpty { "未获取，请连接 Wi-Fi" }
    } catch (_: Exception) { "无法读取" }
    private fun render() {
        val session = ReceiverService.session; val s = session.snapshot(); val now = SystemClock.elapsedRealtime()
        if (lastSession !== session) { lastSession = session; lastReceived = s.received; lastAt = now; pps = 0.0 }
        if (now - lastAt >= 1000) { pps = (s.received - lastReceived) * 1000.0 / (now - lastAt); lastReceived = s.received; lastAt = now }
        status.show(ReceiverService.error ?: "${if(ReceiverService.listening) "监听中" else "未监听"} · ${s.status}")
        val r=ReceiverService.recognizer?.snapshot()
        val controls=ReceiverService.controls
        val final=controls?.authority?.snapshot()
        val sent=controls?.dispatcher?.status()
        val link=controls?.unityLink?.snapshot();val feedback=link?.feedback
        recognition.show(if(r==null) "识别未启动；急停锁定 ${getSharedPreferences("control-safety",MODE_PRIVATE).getBoolean("emergencyLocked",false)}；参数仅保存在本次App进程" else
            "CCA 前/左/右/后："+r.scores.joinToString(" / "){"%.3f".format(it)}+"\nCCA候选 ${r.winner.label} / 门控 ${r.raw.label} / 稳定 ${r.stable.label}\n${r.reason}\n"+
                "最终控制 ${final?.command} / 急停锁定 ${final?.emergencyLocked}：${final?.reason}\n"+
            "阈值 ${r.config.absolute} / ${r.config.difference}（候选，未通过真实EEG验收）· ${r.config.votes}窗 / ${r.config.debounceMs}ms\n"+
            "计算 %.1fms / CPU %.1fms · 判决间隔 ${r.periodMs}ms · ${r.analysisCount}次".format(r.computeMs,r.cpuMs))
        unity.show("模式 ${final?.mode ?: "未启动"} · 配置速度 ${final?.speed ?: ReceiverService.unitySettings(this).second}\n"+
            "Unity ${link?.target ?: ReceiverService.unitySettings(this).first.ifEmpty{"未配置"}}:9001 · 9002反馈${if(link?.feedbackOnline==true)"在线" else "超时/未收到"}\n"+
            "本机已发 ${link?.sentPackets?:0} 包 / 最近 ${sent?.sent?.command ?: "—"} · ${link?.lastSentHex ?: "—"}\n"+
            "对端报告动作 ${feedback?.action ?: "—"} / 实际速度 ${feedback?.linearMmS ?: "—"}mm/s / 转速 ${feedback?.angularMilliRadS ?: "—"}mrad/s\n"+
            "对端速度档 ${feedback?.speedPercent ?: "—"} / 急停 ${feedback?.emergency ?: "—"} / 故障 ${feedback?.fault ?: "—"}\n"+
            "反馈有效 ${link?.validPackets?:0} / 异常 ${link?.rejected ?: emptyMap<String,Long>()}\n"+
            "${sent?.error ?: link?.error ?: "反馈为遥测，非逐包ACK；停车效果须看真实Unity"}")
        details.show("数据 UDP 9000 / 发现 UDP 9001\n来源 ${s.source ?: "—"}\n" +
            "收包 ${s.received} / 接受 ${s.accepted} / 拒收 ${s.rejected.values.sum()}\n" +
            "最近速率 %.1f 包/秒\n".format(pps) +
            "采样率 ${s.info?.rate ?: "—"} Hz / 通道 ${s.info?.channels ?: "—"}\n" +
            "重复 ${s.duplicates} / 迟到乱序 ${s.outOfOrder}\n" +
            "序号缺口 ${s.missingPackets} / 样本缺口 ${s.missingSamples}\n" +
            "连续缓冲 ${s.buffered} 点 / 下一样本 ${s.nextIndex}\n" +
            "块首时间戳 ${s.lastTimeUs ?: "—"} µs\n" +
            "异常 ${s.rejected}\n通道 ${s.info?.names?.joinToString() ?: "—"}")
        logs.show(s.logs.takeLast(12).joinToString("\n"))
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
