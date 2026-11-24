package com.fifteen.eegsender

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
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
 * 发送端主界面。
 *
 * 交互约定：**按住方向键 = 一直输出该方向的想象脑电，松手才结束**。
 * 按下瞬间起流，以 1000 Hz 采样率、按真实采集设备的报文格式持续推送，松手
 * （ACTION_UP / ACTION_CANCEL）时补发一个 STREAM_STOP 作为「本次想象到此为止」
 * 的显式标签。按住期间长度不设限，只有 [MAX_HOLD_SECONDS] 这个兜底上限。
 *
 * 按住期间可以随时拖动「信号置信度」滑块（0~100 = 置信度，100 = 原样发送）：
 * 陷波强度是**每个报文重新读一次**的 volatile 字段，改一下立刻出现在码流里
 * （延迟 ≤ 一个包 = 5 ms），不需要重开流。
 * 注意配套的是接收端要 3 秒窗才出一次判决 —— 信号是瞬时的，**判决**是滞后的。
 *
 * 「停」键推的是真实静息段（无 SSVEP 刺激），对应接收端的 stop 指令。
 *
 * 目标地址有两种给法：
 * - **IP 留空** → 先在局域网内广播 DISCOVER 自动找到接收端（见 [UdpDiscovery]）；
 * - **填了 IP** → 定向直发，不做任何发现。
 */
class MainActivity : AppCompatActivity(), UdpEegSender.Listener {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvStream: TextView
    private lateinit var tvLog: TextView
    private lateinit var sbConfidence: SeekBar
    private lateinit var tvConfidenceValue: TextView
    private lateinit var tvDegradeState: TextView

    /** 五个指令按键，数据未载入完成前整体禁用。 */
    private val commandButtons = ArrayList<Button>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = ArrayDeque<String>()

    private var source: EegSignalSource? = null
    private var sender: UdpEegSender? = null
    private var senderKey: String? = null

    /** 当前正在推流的方向；-1 表示空闲。 */
    private var activeCommand = -1

    /**
     * 降质参数预热线程。
     *
     * 每段的 `band` / `rms` 要跑 62 次 16384 点 FFT（约 0.5 秒），只能在后台算：
     * 在 UI 线程算会卡住界面，在推流线程算会卡出成片丢包。单线程串行即可。
     */
    private val degradeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eeg-degrade-prep").apply { isDaemon = true }
    }

    /** 降质参数是否正在后台预热（只在主线程读写）。 */
    private var degradePreparing = false

    /**
     * 推流/发现令牌：每次「点按」自增，页面销毁时再自增。
     *
     * 自动发现在后台线程里跑，可能比一次试次还慢。页面销毁时把令牌推进一格，
     * 发现线程回来对不上号就丢弃结果 —— 避免它回来时触碰已销毁的界面。
     * 后台线程会读它，故 volatile。
     */
    @Volatile
    private var streamToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        tvStatus = findViewById(R.id.tvStatus)
        tvStream = findViewById(R.id.tvStream)
        tvLog = findViewById(R.id.tvLog)
        // 日志可长按选择复制：取证时直接把文本粘出来，比截图可靠（避免手机截图转文字失真）
        tvLog.setTextIsSelectable(true)

        bindTap(R.id.btnForward, EegCommands.FORWARD)
        bindTap(R.id.btnLeft, EegCommands.LEFT)
        bindTap(R.id.btnStop, EegCommands.STOP)
        bindTap(R.id.btnRight, EegCommands.RIGHT)
        bindTap(R.id.btnBackward, EegCommands.BACKWARD)

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logLines.clear()
            tvLog.text = ""
        }

        bindDegradeControls()

        tvStream.text = getString(R.string.stream_idle)
        setStatus(getString(R.string.status_loading), R.color.warn)
        showLastBreath()
        logEnvironment()
        loadDataset()
    }

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
     * 两个降质滑块：**拖动只改一个 float，立刻生效**，没有 FFT 参与。
     *
     * 真正花时间的是「每段的 band / rms 预计算」。数据集载入完成后会立刻
     * **无条件**把 5 个方向都预热一遍（见 [loadDataset]），所以第一次拖滑块时
     * 参数一般已经就绪、改动一个包（5 ms）之内就出现在码流里。
     * 万一某方向还没预热完，那一包原样输出 ——
     * 宁可这一包不降质，也不能为了降质把 5 ms 的推流节拍打乱。
     */
    private fun bindDegradeControls() {
        sbConfidence = findViewById(R.id.sbConfidence)
        tvConfidenceValue = findViewById(R.id.tvConfidenceValue)
        tvDegradeState = findViewById(R.id.tvDegradeState)

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

        // 100% 时强度恰好为 0 —— 走的是「原样拷贝」快路径，一个字节都不动。
        // 置信度滑块只驱动陷波路径（确定性、单调）；加噪路径非确定且抬静息基线，
        // 不作为「置信度」的可复现刻度（SignalDegrade 仍保留该能力供标定用）。
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
        // 实测曲线是「3 秒窗」下的，界面上只作粗略刻度用
        SignalDegrade.expectedRho(confidence)
    )

    /**
     * 后台把 5 个方向的降质参数都算好，这样按哪个键都不会漏。
     *
     * 只跑一次：算过的方向会被缓存（约 4 MB/方向），之后拖滑块、切方向都不再重算。
     */
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
        // 滑块在 100% 时其实**没有**降质，只是参数已经备好了。文案必须把这点
        // 说清楚：否则用户看到「5/5 个方向已预热」会以为信号已经被改过了。
        if (::sbConfidence.isInitialized && sbConfidence.progress >= MAX_CONFIDENCE) {
            tvDegradeState.text = getString(R.string.degrade_off_ready)
            return
        }
        val ready = (0 until EegCommands.COUNT).count { data.isDegradeReady(it) }
        tvDegradeState.text = getString(R.string.degrade_ready, ready, EegCommands.COUNT)
    }

    /**
     * 把当前降质状态压成一行，写进发送日志。
     *
     * 事后拿抓包记录逐段比对时，「这一段到底有没有降质、强度是多少」必须在日志里
     * 能查到 —— 否则一旦某个方向还没预热完，看到的现象会像是降质根本不生效。
     */
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
     * 把某个按键绑定成「按住一直发、松手收尾」。
     *
     * 用 OnTouchListener 而非 OnClickListener，是为了在**按下瞬间**就起流，
     * 而不是等抬手；同时按下时阻断父容器的滚动拦截，避免手指轻微移动就被
     * ScrollView 抢走事件。抬手（含 ACTION_CANCEL）才收尾。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindTap(viewId: Int, command: Int) {
        val button = findViewById<Button>(viewId)
        commandButtons.add(button)
        button.isEnabled = false
        // 不参与焦点（XML 样式已设，代码再兜底一次）：按下瞬间不让 ScrollView
        // 因为子控件请求焦点而自动滚动，屏幕保持原位不跳动。
        button.isFocusable = false
        button.isFocusableInTouchMode = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.isPressed = true
                    beginStream(command)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.isPressed = false
                    endStream(command)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 松手收尾：让当前这路流补发 STREAM_STOP 并退出。
     *
     * 两道守卫都是针对**多点触控**的：两根手指先后按下不同方向时，前一路会被
     * 打断，此时先按的那根手指抬起就不该再动当前这条流 ——
     * 所以「还在推流」与「推的就是我这一路」两个条件都要满足。
     */
    private fun endStream(command: Int) {
        val active = sender ?: return
        if (!active.isStreaming()) return
        if (active.currentCommand() != command) return

        appendLog("▲ 松手 → 收尾「${EegCommands.name(command)}」，补发 STREAM_STOP")
        // release() 会等推流线程退出（正常不到 6 ms），回来时 isStreaming 已是 false，
        // 所以紧接着再按同一个键不会撞上「上一路还在跑」的竞态。
        val t0 = System.currentTimeMillis()
        active.release()
        appendLog("  release() 已返回（耗时 ${System.currentTimeMillis() - t0} ms）")
    }

    // ------------------------------------------------------------ 环境自检

    /**
     * 启动时把「设备 / 系统 / 网卡 / 局域网权限」打进日志。
     *
     * 真机联调时这几行几乎能定位所有「连不上」的问题：手机到底有没有连上局域网、
     * 系统版本会不会触发局域网访问保护、权限有没有被系统拒掉。
     */
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

    /**
     * 局域网访问权限的状态；未授予时**尽力申请一次**。
     *
     * 只影响展示，绝不阻塞推流：在多数系统上它是普通权限（装机即授予），
     * 申请只是为了让个别需要用户确认的 ROM 有机会弹框。
     */
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

        // 任意时刻只允许一路流。已在推流（或正在发现接收端）时：
        //   * 点到**同一个方向** → 视作重复按下（同一次按住里事件重发），忽略；
        //   * 点到**别的方向** → 立刻打断，重开一路新流。新流的 STREAM_INFO
        //     就是「上一次行为已结束」的标签，接收端收到即清窗、重新计时。
        //     打断走 interrupt()：**不补 STREAM_STOP**，省掉一次无意义的收尾。
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
            // 同步等推流线程退出（循环每 5 ms 查一次停止标志）。退出后 sender 可立刻
            // 复用：下面的 startStreaming 会在同一个对象上 start 一路全新的流。
            activeNow.interrupt()
            activeCommand = -1
        } else if (activeCommand != -1) {
            // 还停在「发现中」（广播最长约 2 秒），规则同上。
            if (activeCommand == command) {
                appendLog("「${EegCommands.name(command)}」正在发现接收端，重复点按忽略")
                return
            }
            appendLog(
                "⇄ 打断发现中的「${EegCommands.name(activeCommand)}」" +
                    " → 切换到「${EegCommands.name(command)}」"
            )
            streamToken++ // 作废正在进行的那次发现
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

        // 定向直发必须先校验地址。UDP 是无连接的：往一个打错但格式合法的地址发包，
        // sendto 照样成功、界面照样显示「推流中 250 kB/s」，而包全被丢给默认网关，
        // 接收端一个都收不到。这种「假成功」比直接报错危险得多，必须提前拦掉。
        val target = LocalNetwork.parseIpv4(host)
        if (target == null) {
            setStatus(getString(R.string.status_bad_ip), R.color.error)
            appendLog("  IP 格式不对：「$host」不是 IPv4 地址")
            appendLog("  应为 4 段 0-255 的数字（例如 10.14.166.234）；或留空改用自动发现。")
            return
        }

        // 同网段校验。手机没暴露任何网卡时不做判断，免得误杀能用的情况。
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

    /**
     * IP 留空时的路径：先在局域网内广播 DISCOVER，拿到接收端的 IP 与数据端口再推流。
     *
     * 发现是阻塞式的（最长等约 2 秒），所以放在后台线程；结果回到主线程才使用。
     * 数据端口以**接收端应答里声明的为准**，而不是本机端口输入框 —— 免去两边对端口。
     */
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
                // 页面销毁会推进令牌，对不上就说明这次发现已作废
                if (token != streamToken) return@post

                if (found == null) {
                    activeCommand = -1
                    setStatus(getString(R.string.status_discover_failed), R.color.error)
                    // 发送被系统拒绝时只会「收不到应答」，把真实原因摊开，免得误判成网络不通
                    val why = discovery.lastError
                    if (why != null) appendLog("  广播发送失败：${NetDiagnostics.explain(why)}")
                    appendLog("  未发现接收端：请确认接收端已启动，并在 UDP $DISCOVERY_PORT 上应答；")
                    appendLog("  或在 IP 框里直接填接收端地址，改用定向直发。")
                    return@post
                }

                val label = if (found.name.isEmpty()) "（未命名）" else found.name
                appendLog("  已发现接收端 $label｜${found.endpoint}")
                // 把刚验证过的地址回填进 IP 框：之后想走定向直发直接按就行，
                // 不用手输 —— 手输正是最容易把 10.14.166.234 打成 10.14.16.234 的地方。
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
        // 长度上限传 0 = 不限长：一直发到松手（endStream → release()）为止。
        // 兜底上限 MAX_HOLD_SECONDS 只是防「抬手事件被系统吞掉」，正常用不到。
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

                    // 数据集是后来才载入的，此刻才可能真正开始预处理。**无条件预热**：
                    // 否则第一次把滑块拖离 100% 时才开始算，5 个方向串行约 2.5 秒，
                    // 而早期版本一次试次只有 4 秒 —— 表现为「拖了滑块但信号没变」。
                    // 现在按住持续发，预热慢一点尚可容忍，但把头一次的 2.5 秒空窗
                    // 消掉仍然是值得的：用户一拖就能立刻看到效果。
                    // 代价是常驻内存约 18 MB（4 个方向各 16000×62 float + 静息 8000×62）。
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
            // text 里要用到 source 的采样率，所以放到主线程里再算（source 由主线程赋值）。
            val text = String.format(
                Locale.US,
                "「%s」推流结束｜共 %.1f s｜%d 采样点｜%d 包｜%.2f MB",
                EegCommands.name(command),
                lastSampleIndex / sourceHz().toDouble(),
                lastSampleIndex,
                packets,
                bytesSent / 1048576.0
            )
            // 两条「别乱改状态」的理由，都得拦：
            //   * 换方向 / 多点触控换手打断时，新一路流已经接管 —— 这条只是旧流的讣告；
            //   * 同一方向松手后立刻又按下时，新流已在跑（isStreaming 为真），
            //     而 activeCommand 恰好还等于同一个值，光比命令号拦不住。
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
            // 同上：被打断的旧流即使以异常收尾，也不该把新流的状态清掉。
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
        // 倒序渲染：最新一条在最上方（聊天式），但不强制滚动位置——
        // 按下按键时页面停在用户当前滚动位置，不跳回顶部。
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

    /**
     * 启动时把「上次进程结束前」的最后几行捞回界面。
     *
     * 判断松手即退的真相全靠它：
     * - 日志停在「▲ 松手」或「release() 已返回」处且没有 LIFE onPause/onStop/onDestroy
     *   → 进程被系统直接杀掉，没走生命周期；
     * - 有 onPause/onStop/onDestroy → 系统按正常流程把它移走了；
     * - 有 UNCAUGHT 行 → App 是自己崩的，不是被杀。
     */
    private fun showLastBreath() {
        val lines = runCatching {
            File(filesDir, LAST_BREATH_FILE).readLines().takeLast(LAST_BREATH_SHOWN)
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) return
        // 配合倒序渲染：先入「本次会话」标题、再逆序入上次内容、最后入「上次会话」标题，
        // 倒序显示后恢复成 上次标题→内容（时间序）→本次标题，段内不乱。
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

        /**
         * 接收端出一次判决所需的窗口长度（秒）。
         *
         * 交互上**不再**用它限制推流长度（按住持续发，松手才停），只用来写提示文案：
         * 「建议至少按住 4 秒」。取 4 秒是因为它等于 OpenBMI 素材的单试次长度、也是
         * SSVEP 的经典取值：4 秒窗下 CCA 相关值最高（约 0.74）；压到 2 秒虽然指令
         * 更快，但相关值会明显下降、更容易被判成「无指令」（实测 2 秒窗判决会倒挂）。
         */
        const val TRIAL_SECONDS = 3

        /**
         * 单次按住的**兜底**长度上限（秒）。
         *
         * 正常收尾靠 ACTION_UP。这个上限只防意外：抬手事件被系统吞掉（手势被别的
         * 窗口抢走、界面被切到后台）时流不会永远发下去。素材单区段只有 16 秒，
         * 超过后硬回绕重播，所以长按本身无害 —— 上限只是保险丝。
         */
        const val MAX_HOLD_SECONDS = 60

        /** 自动发现走的端口，与数据流分开，见 [UdpDiscovery]。 */
        const val DISCOVERY_PORT = 9001

        const val MAX_LOG_LINES = 120

        /**
         * 本次构建标记。日志第一行会打印它：截图里能看到这行，就证明跑的是
         * 这份带 last-breath 日志的新代码（而不是旧 APK 没装成功）。
         */
        const val BUILD_TAG = "NIO-NOSCROLL-20261021"

        /** last-breath 日志落盘文件名（filesDir 下），进程被杀后下次启动仍可读回。 */
        const val LAST_BREATH_FILE = "last_breath.log"
        const val LAST_BREATH_MAX_BYTES = 64 * 1024
        const val LAST_BREATH_KEEP_LINES = 300
        const val LAST_BREATH_SHOWN = 30

        /** 滑块上限：100% 表示不做任何降质。 */
        const val MAX_CONFIDENCE = 100

        /**
         * Android 16+ 引入「局域网访问保护」，应用访问局域网设备需要该权限。
         * 只在 API >= [LOCAL_NETWORK_MIN_API] 时才去申请，老系统上系统不认这个权限名。
         */
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_MIN_API = 36
        const val REQUEST_LOCAL_NETWORK = 0x4C4E
    }
}
