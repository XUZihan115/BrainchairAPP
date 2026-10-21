package com.yourteam.brainchair

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.*
import android.widget.*
import com.yourteam.brainchair.comm.*
import com.yourteam.brainchair.model.IntentGate

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private lateinit var comm: CommManager
    private lateinit var content: LinearLayout
    private lateinit var connection: TextView
    private lateinit var tabs: LinearLayout
    private var stateText: TextView? = null
    private var logText: TextView? = null
    private var motionText: TextView? = null
    private var devicesBox: LinearLayout? = null
    private var snapshot: Snapshot? = null
    private var page = 0
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var lastDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private val intentGate = IntentGate()
    private var sequence = 0L
    private var eegArmed = false
    private var activeTouch = 0
    private val motionButtons = mutableListOf<Button>()
    private val adapter: BluetoothAdapter? get() = getSystemService(BluetoothManager::class.java)?.adapter
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) { devices[device.address] = device; renderDevices() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> toast("搜索结束；点击设备配对或连接")
                BluetoothAdapter.ACTION_STATE_CHANGED -> if (adapter?.isEnabled != true) { comm.disconnect(); devices.clear(); renderDevices() }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                dp(20) + insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            @Suppress("DEPRECATION")
            insets.consumeSystemWindowInsets()
        }
        content = findViewById(R.id.content); connection = findViewById(R.id.connection); tabs = findViewById(R.id.tabs)
        comm = CommManager { s -> snapshot = s; refresh(s) }
        findViewById<Button>(R.id.emergency).setOnClickListener {
            activeTouch = 0; eegArmed = false; comm.emergency(); toast("已请求急停，控制已锁定")
        }
        listOf("连接", "控制", "状态").forEachIndexed { index, name ->
            val b = button(name) { showPage(index) }
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(receiver, filter) }
        showPage(0)
    }
    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(32, 52, 59)); setPadding(0, dp(7), 0, dp(7))
    }
    private fun heading(value: String) { content.addView(text(value, 23f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private fun note(value: String) { content.addView(text(value, 14f)) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.rgb(22, 106, 117))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(228, 238, 240))
        setOnClickListener { action() }
    }
    private fun addButton(label: String, action: () -> Unit): Button {
        val b = button(label, action); content.addView(b, LinearLayout.LayoutParams(-1, dp(54))); return b
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun showPage(index: Int) {
        comm.stop(); activeTouch = 0; eegArmed = false; handler.removeCallbacksAndMessages(null)
        page = index; content.removeAllViews(); stateText = null; logText = null; motionText = null; devicesBox = null; motionButtons.clear()
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).alpha = if (i == index) 1f else 0.55f
        when (index) { 0 -> connectionPage(); 1 -> controlPage(); else -> statusPage() }
        snapshot?.let(::refresh)
    }
    private fun connectionPage() {
        heading("先从模拟轮椅开始")
        note("没有硬件也能练习连接、方向控制、状态反馈和急停。模拟数据不代表真实轮椅表现。")
        addButton("连接模拟轮椅") { comm.connectSimulation() }
        addButton("断开连接") { comm.disconnect() }
        heading("蓝牙设备")
        note("仅支持经典蓝牙 SPP。连接真实设备前，请先确认通信协议；BLE 设备不能直接使用。")
        addButton("搜索 / 刷新蓝牙设备") { discover() }
        addButton("打开系统蓝牙设置") { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        addButton("重新连接上次蓝牙设备") {
            val d = lastDevice
            if (d == null) toast("尚未连接过蓝牙设备") else if (ensureBluetooth()) { adapter?.cancelDiscovery(); comm.connectBluetooth(d) }
        }
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(devicesBox); renderDevices()
    }
    private fun controlPage() {
        heading("按住方向，松手停止")
        motionText = text("连接后即可控制", 17f); content.addView(motionText)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf(listOf(0 to "", 1 to "↑ 前进", 0 to ""), listOf(3 to "← 左转", 5 to "停止", 4 to "右转 →"), listOf(0 to "", 2 to "↓ 后退", 0 to ""))
        for (row in labels) {
            val line = LinearLayout(this)
            for ((cmd, label) in row) {
                val v: View = if (cmd == 0) Space(this) else button(label) { if (cmd == 5) { activeTouch = 0; eegArmed = false; comm.stop() } }.apply {
                    textSize = 18f
                    if (cmd in 1..4) {
                        motionButtons.add(this)
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    handler.removeCallbacksAndMessages(null)
                                    eegArmed = false
                                    if (activeTouch == 0) { activeTouch = cmd; comm.move(cmd); view.parent.requestDisallowInterceptTouchEvent(true) }
                                    else { activeTouch = 0; comm.stop() }
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    activeTouch = 0; comm.stop(); view.parent.requestDisallowInterceptTouchEvent(false)
                                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                                }
                            }
                            true
                        }
                    }
                }
                line.addView(v, LinearLayout.LayoutParams(0, dp(78), 1f))
            }
            grid.addView(line)
        }
        content.addView(grid)
        val speedLabel = text("速度上限 ${comm.limit}%（演示最大 30%）")
        content.addView(speedLabel)
        content.addView(SeekBar(this).apply {
            max = 30; progress = comm.limit
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { comm.setLimit(p); speedLabel.text = "速度上限 $p%（演示最大 30%）" } }
                override fun onStartTrackingTouch(bar: SeekBar) { comm.stop() }
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        addButton("解除急停 / 故障锁定") {
            AlertDialog.Builder(this).setTitle("确认设备已停止")
                .setMessage("解除后仍保持停止，需要再次按方向键才会移动。真实设备还必须按厂家要求排除故障。")
                .setPositiveButton("解除锁定") { _, _ -> comm.reset() }.setNegativeButton("取消", null).show()
        }
        heading("脑控接口演示")
        note("这里只使用模拟意图，不采集或识别脑电；演示仅对模拟轮椅开放。")
        addButton("模拟“前进”意图 · 300 毫秒") {
            if (!comm.simulated || !comm.connected || comm.latched) { toast("请先连接模拟轮椅并解除锁定"); return@addButton }
            activeTouch = 0; eegArmed = true; sequence++
            if (intentGate.accept(1, 0.95, 0, sequence, eegArmed)) {
                comm.move(1)
                handler.postDelayed({ comm.stop(); eegArmed = false }, 300)
            }
        }
    }
    private fun statusPage() {
        heading("轮椅反馈")
        stateText = text("等待连接", 19f); content.addView(stateText)
        note("速度以控制百分比显示，不冒充真实 km/h。断开后数值显示为未知。")
        heading("模拟故障测试")
        addButton("暂停心跳 / 恢复心跳") {
            if (!comm.simulated || !comm.connected) { toast("仅模拟模式可测试"); return@addButton }
            comm.heartbeatEnabled = !comm.heartbeatEnabled
            toast(if (comm.heartbeatEnabled) "心跳已恢复" else "心跳已暂停")
        }
        addButton("注入模拟故障") { if (comm.simulated && comm.connected) comm.injectFault() else toast("请先连接模拟轮椅") }
        addButton("复制测试日志") {
            val clip = ClipData.newPlainText("BrainChair logs", snapshot?.logs?.joinToString("\n") ?: "")
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); toast("日志已复制")
        }
        heading("最近事件")
        logText = text("暂无事件", 12f).apply { setTextIsSelectable(true) }; content.addView(logText)
    }
    private fun refresh(s: Snapshot) {
        connection.setIfChanged(s.message)
        val status = if (!s.connected) "未连接" else if (s.latched) "已锁定 · 请检查后解除" else when(s.state) { 0 -> "已停止"; 1 -> "移动中"; 2 -> "设备锁定"; else -> "等待反馈" }
        motionText?.setIfChanged("$status\n${if (s.simulated) "模拟轮椅" else "蓝牙设备"} · 当前速度 ${if (s.speed < 0) "—" else "${s.speed}%"}")
        for (b in motionButtons) b.isEnabled = s.connected && !s.latched && comm.heartbeatEnabled
        val freshness = if (!s.connected || s.ageMs < 0) "—" else if (s.ageMs < 500) "正常（500ms 内）" else "反馈延迟"
        stateText?.setIfChanged("电量  ${if (s.battery < 0) "—" else "${s.battery}%"}\n\n速度  ${if (s.speed < 0) "—" else "${s.speed}%"}\n\n状态  $status\n\n故障码  ${s.fault}\n\n最近反馈  $freshness\n\n心跳  ${if (comm.heartbeatEnabled) "开启" else "已暂停（测试）"}")
        logText?.setIfChanged(s.logs.takeLast(18).reversed().joinToString("\n"))
    }
    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }
    private fun ensureBluetooth(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(permissions, 101); return false }
        val a = adapter
        if (a == null) { toast("此设备没有蓝牙，请使用模拟模式"); return false }
        if (!a.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return false }
        return true
    }
    private fun discover() {
        if (!ensureBluetooth()) return
        comm.disconnect()
        devices.clear(); adapter?.bondedDevices?.forEach { devices[it.address] = it }; renderDevices()
        adapter?.cancelDiscovery()
        if (adapter?.startDiscovery() != true) toast("无法开始搜索，请检查蓝牙和定位开关") else toast("正在搜索附近设备…")
    }
    private fun renderDevices() {
        val box = devicesBox ?: return
        box.removeAllViews()
        if (devices.isEmpty()) box.addView(text("暂无设备。模拟模式不需要蓝牙。", 14f))
        for (device in devices.values) {
            try {
                val paired = device.bondState == BluetoothDevice.BOND_BONDED
                box.addView(button("${device.name ?: "未命名设备"}\n${device.address} · ${if (paired) "连接" else "配对"}") {
                    if (ensureBluetooth()) {
                        adapter?.cancelDiscovery()
                        if (!paired) { if (!device.createBond()) toast("配对未启动，请在系统蓝牙设置中配对") }
                        else AlertDialog.Builder(this).setTitle("连接实验设备")
                            .setMessage("请确认设备使用本项目教学协议。尚未核对协议的真实轮椅不能直接连接控制。")
                            .setPositiveButton("协议已确认，连接") { _, _ -> lastDevice = device; comm.connectBluetooth(device) }
                            .setNegativeButton("取消", null).show()
                    }
                })
            } catch (_: SecurityException) { box.addView(text("需要蓝牙权限")); break }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        toast(if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) "权限已允许，请再次搜索" else "未获授权，仍可使用模拟模式")
    }
    override fun onPause() { comm.disconnect(); activeTouch = 0; eegArmed = false; handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { unregisterReceiver(receiver); comm.shutdown(); super.onDestroy() }
}
