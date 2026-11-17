package com.yourteam.brainchair.comm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yourteam.brainchair.model.ChairEngine
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Snapshot(
    val connected: Boolean, val simulated: Boolean, val message: String,
    val battery: Int, val speed: Int, val state: Int, val fault: Int,
    val latched: Boolean, val ageMs: Long, val logs: List<String>
)

/** A single worker owns protocol state. UI requests replace intentions, never queue movement. */
class CommManager(private val update: (Snapshot) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val connector = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var generation = 0
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile var connected = false; private set
    @Volatile var simulated = false; private set
    @Volatile private var desired = 0
    @Volatile var limit = 30; private set
    @Volatile private var emergencyRequested = false
    @Volatile private var resetRequested = false
    @Volatile var latched = false; private set
    @Volatile var heartbeatEnabled = true
    private var engine: ChairEngine? = null
    private var incoming = ProtocolParser(::receive)
    private var lowerParser: ProtocolParser? = null
    private var message = "未连接 · 先连接模拟轮椅"
    private var battery = -1
    private var speed = -1
    private var state = -1
    private var fault = 0
    private var lastRx = 0L
    private var lastSend = 0L
    private var lastDirection = -1
    private var lastLimit = -1
    private var lastPublish = 0L
    private val logs = ArrayDeque<String>()
    private fun now() = SystemClock.elapsedRealtime()
    init { worker.scheduleWithFixedDelay({ if (!closed) try { tick() } catch (e: Exception) { disconnectWorker("通信异常：${e.message}") } }, 0, 50, TimeUnit.MILLISECONDS) }
    private fun log(s: String) {
        if (logs.size >= 160) logs.removeFirst()
        logs.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}  $s")
    }
    private fun publish() {
        val snapshot = Snapshot(connected, simulated, message, battery, speed, state, fault, latched, if (lastRx == 0L) -1 else now() - lastRx, logs.toList())
        main.post { if (!closed) update(snapshot) }
    }
    private fun receive(f: Frame) {
        val valid = when (f.cmd) {
            Protocol.BATTERY -> f.arg in 0..100
            Protocol.SPEED -> f.arg in 0..100
            Protocol.STATE -> f.arg in 0..2
            Protocol.FAULT -> true
            Protocol.ACK -> f.arg in 1..9
            else -> false
        }
        if (!valid) return
        lastRx = now()
        when (f.cmd) {
            Protocol.BATTERY -> battery = f.arg
            Protocol.SPEED -> speed = f.arg
            Protocol.STATE -> { state = f.arg; if (state == 2) { latched = true; desired = 0 } }
            Protocol.FAULT -> {
                if (fault != f.arg) log("故障码 ${f.arg}")
                fault = f.arg
                if (fault != 0) { latched = true; desired = 0 }
            }
            Protocol.ACK -> {
                if (f.arg == Protocol.RESET) { latched = false; log("设备确认解除锁定，保持停止") }
                if (f.arg in 1..7 && f.arg != lastDirection) log("收到 ACK：${f.arg}")
            }
        }
    }
    private fun write(cmd: Int, arg: Int = 0) {
        val data = Protocol.build(cmd, arg)
        if (simulated) lowerParser?.feed(data) else socket?.outputStream?.write(data)
    }
    private fun tick() {
        if (!connected) return
        val t = now()
        synchronized(lock) {
            if (emergencyRequested) {
                desired = 0; latched = true; emergencyRequested = false
                write(Protocol.ESTOP); lastDirection = 0; log("发送急停，锁定控制")
            }
            if (resetRequested) {
                resetRequested = false
                if (desired == 0 && speed == 0 && heartbeatEnabled) { write(Protocol.RESET); log("请求解除锁定") }
            }
            if (lastLimit != limit) { write(Protocol.LIMIT, limit); lastLimit = limit }
            if (t - lastSend >= 200) {
                if (heartbeatEnabled) write(Protocol.HEARTBEAT)
                if (desired != 0 && !latched) write(desired, limit)
                lastSend = t
            }
            val d = if (latched) 0 else desired
            if (d != lastDirection) {
                write(if (d == 0) Protocol.STOP else d, if (d == 0) 0 else limit)
                log(if (d == 0) "发送停止" else "发送方向 $d，速度上限 $limit%")
                lastDirection = d
            }
        }
        engine?.tick(t)
        if (t - lastRx >= 1000) { disconnectWorker("反馈超时，已断开；请重新连接"); return }
        if (t - lastPublish >= 100) { lastPublish = t; publish() }
    }
    fun move(direction: Int) = synchronized(lock) {
        if (direction in 1..4 && connected && !latched && heartbeatEnabled) desired = direction
    }
    fun stop() = synchronized(lock) { desired = 0 }
    fun emergency() = synchronized(lock) { desired = 0; latched = true; emergencyRequested = true }
    fun setLimit(value: Int) = synchronized(lock) { desired = 0; limit = value.coerceIn(0, 30) }
    fun reset() = synchronized(lock) { desired = 0; resetRequested = true }
    fun injectFault() { worker.execute { engine?.injectFault(); publish() } }
    fun connectSimulation() {
        cancelConnection()
        val g = generation
        worker.execute {
            if (g != generation || closed) return@execute
            disconnectWorker("切换设备")
            simulated = true; connected = true; freshState()
            engine = ChairEngine { bytes ->
                // Deliberate fragmentation exercises the real upstream parser.
                incoming.feed(bytes.copyOfRange(0, 3)); incoming.feed(bytes.copyOfRange(3, bytes.size))
            }
            lowerParser = ProtocolParser { frame -> engine?.receive(frame, now()) }
            message = "已连接 · 模拟轮椅（非真实设备）"; log(message); publish()
        }
    }
    private fun freshState() {
        desired = 0; latched = false; emergencyRequested = false; resetRequested = false
        heartbeatEnabled = true; limit = 30; lastLimit = -1; lastDirection = -1
        battery = -1; speed = -1; state = -1; fault = 0; lastRx = now(); lastSend = 0
        incoming = ProtocolParser(::receive)
    }
    @SuppressLint("MissingPermission")
    fun connectBluetooth(device: BluetoothDevice) {
        cancelConnection()
        val g = generation
        worker.execute { disconnectWorker("正在连接蓝牙…"); publish() }
        connector.execute {
            var s: BluetoothSocket? = null
            try {
                s = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                pendingSocket = s
                val candidate = s
                main.postDelayed({ if (pendingSocket === candidate) try { candidate.close() } catch (_: Exception) {} }, 10000)
                s.connect()
                if (closed || g != generation) { s.close(); return@execute }
                socket = s; pendingSocket = null
                worker.execute {
                    if (closed || g != generation) return@execute
                    simulated = false; engine = null; lowerParser = null; freshState(); connected = true
                    message = "已连接 · ${device.name ?: device.address}"; log(message); publish()
                }
                val buf = ByteArray(1024)
                while (!closed && g == generation) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) throw java.io.IOException("设备关闭连接")
                    val copy = buf.copyOf(n)
                    worker.execute { if (g == generation && connected) incoming.feed(copy) }
                }
            } catch (e: Exception) {
                if (!closed && g == generation) worker.execute { disconnectWorker("蓝牙断开：${e.message}；可手动重连"); publish() }
            } finally {
                try { s?.close() } catch (_: Exception) {}
                if (pendingSocket === s) pendingSocket = null
            }
        }
    }
    private fun cancelConnection() {
        generation++
        try { pendingSocket?.close() } catch (_: Exception) {}
        pendingSocket = null
    }
    private fun disconnectWorker(reason: String) {
        desired = 0
        if (connected) try { write(Protocol.STOP) } catch (_: Exception) {}
        connected = false; engine?.stop(); engine = null; lowerParser = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null; speed = -1; state = -1; battery = -1; message = reason
        log(reason); publish()
    }
    fun disconnect() {
        stop(); cancelConnection()
        // Close from a separate thread if a device has blocked the writer.
        val oldSocket = socket
        main.postDelayed({ if (oldSocket != null && socket === oldSocket) try { oldSocket.close() } catch (_: Exception) {} }, 150)
        worker.execute { disconnectWorker("已断开 · 重新连接后保持停止") }
    }
    fun shutdown() {
        disconnect()
        worker.execute { closed = true; worker.shutdown(); connector.shutdownNow() }
    }
}
