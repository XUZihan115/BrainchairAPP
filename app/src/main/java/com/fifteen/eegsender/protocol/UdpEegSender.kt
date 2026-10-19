package com.fifteen.eegsender.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * 把真实脑电数据当作「采集设备的实时输出」，按固定采样节拍经 UDP 持续推流。
 *
 * 一次推流（点一下方向键）的报文序列：
 *
 * ```
 * 1 × STREAM_INFO  →  n × SAMPLE_BLOCK（每 5 ms 一个）
 * ```
 *
 * 发满预定长度后**静默结束，不补发 STREAM_STOP**（见 [start] 的 `limitSamples`
 * 与 [SEND_STREAM_STOP]）：真实采集设备不会「礼貌告别」，流断了就是断了，
 * 接收端按「多久没收到包」自行判定本次想象结束。
 *
 * 发送节拍以 `System.nanoTime()` 对齐到「第 k 个报文应发的绝对时刻」，而不是每次
 * 固定 sleep 一个间隔，因此长时间推流也不会累积漂移。若因网络拥塞落后过多，
 * 会像真实设备缓冲溢出那样**跳过一段采样点**追上实时，而不是突发补包。
 */
class UdpEegSender(
    private val host: String,
    private val port: Int,
    private val source: EegSignalSource,
    private val listener: Listener
) {

    /** 推流回调，均在推流线程上触发，调用方需自行切回主线程刷新 UI。 */
    interface Listener {
        /** 已发出 STREAM_INFO，开始推采样块。 */
        fun onStreamStarted(command: Int, samplesPerPacket: Int, packetsPerSecond: Int)

        /** 周期性进度（默认每 200 ms 一次）。 */
        fun onStreamProgress(
            command: Int,
            elapsedMs: Long,
            packets: Long,
            sampleIndex: Long,
            bytesSent: Long
        )

        /** 已发出 STREAM_STOP，本次推流正常结束。 */
        fun onStreamStopped(
            command: Int,
            reason: Int,
            lastSampleIndex: Long,
            packets: Long,
            bytesSent: Long
        )

        /** 推流异常中止。 */
        fun onStreamFailed(command: Int, message: String)
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var current = -1

    private var worker: Thread? = null

    /** 是否正在推流。 */
    fun isStreaming(): Boolean = running.get()

    /** 当前正在推流的指令；未推流时为 -1。 */
    fun currentCommand(): Int = current

    /**
     * 开始一路推流，返回是否成功启动。若已有推流在进行中则返回 false
     * （任意时刻只允许一路流，符合「一个指令对应一次想象」的语义）。
     *
     * @param limitSamples 本次最多推送的采样点数，会向下对齐到整包
     *   （例如 4000 点、每包 5 点 ⇒ 正好 800 个包）。传 0 表示不限长度，
     *   一直推到调用 [stop] 为止。
     */
    fun start(command: Int, limitSamples: Long = 0L): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopRequested = false
        current = command
        worker = thread(name = "eeg-udp-stream", isDaemon = true) {
            runStream(command, limitSamples)
        }
        return true
    }

    /** 请求停止推流（异步；推流循环最多 5 ms 后退出收尾）。 */
    fun stop() {
        stopRequested = true
    }

    /**
     * 停止并等待推流线程退出。用于 Activity 销毁，也用于「换方向」时**打断**当前试次。
     *
     * 推流循环每 5 ms 检查一次 [stopRequested]，所以实际等待通常不到 6 ms。退出后
     * [running] 已由推流线程复位，同一个对象可以立刻再 [start] 一路全新的流
     * —— 这也是打断后能复用同一发送器的原因（不必重建、不必改端口）。
     */
    fun shutdown() {
        stopRequested = true
        val thread = worker
        worker = null
        if (thread != null) {
            runCatching { thread.join(2000) }
        }
    }

    /** 单个报文携带的采样点数：在不超过 MTU 的前提下尽量填满。 */
    fun samplesPerPacket(): Int {
        val overhead = EegFrame.HEADER_SIZE + EegFrame.CRC_SIZE + EegFrame.SAMPLE_BLOCK_OVERHEAD
        return maxOf(1, (EegFrame.MAX_DATAGRAM - overhead) / source.bytesPerSample)
    }

    /** 最后一个候选出口的失败原因，用于把 EPERM 翻译成能照做的提示。 */
    private var openFailure: Throwable? = null

    /** 本次尝试过的源地址，失败时一并告诉用户，便于判断是否压根没连上局域网。 */
    private var triedBindAddresses: List<String> = emptyList()

    /**
     * 开一个**能真正把包发出去**的套接字：依次尝试绑定到各局域网网卡的源地址，
     * 成功发出首包后返回该套接字；全部失败返回 null。
     *
     * 为什么必须逐个试：`DatagramSocket()` 绑 `0.0.0.0` 时，出口由系统按路由表挑。
     * 手机同时有移动数据和 WiFi 时，优先级判断常常偏向蜂窝网络，包被路由到移动数据
     * 出口，往 `10.x` 这类局域网地址发就报 EPERM —— 而显式绑定 WiFi 网卡地址就正常。
     */
    private fun openStreamSocket(firstPacket: DatagramPacket): DatagramSocket? {
        val bindAddresses = runCatching { UdpDiscovery().localBindAddresses() }
            .getOrDefault(emptyList())
        triedBindAddresses = bindAddresses.map { it.hostAddress ?: "?" }

        val candidates = ArrayList<InetAddress?>(bindAddresses.size + 1)
        candidates.addAll(bindAddresses)
        candidates.add(null) // 兜底：不绑源地址，交给系统默认网络

        var lastFailure: Throwable? = null
        for (bindIp in candidates) {
            val candidate = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindIp, 0))
                }
            }.getOrElse { error ->
                if (lastFailure == null) lastFailure = error
                null
            } ?: continue

            runCatching { candidate.sendBufferSize = 1 shl 20 }
            try {
                candidate.send(firstPacket)
                openFailure = null
                return candidate
            } catch (error: Throwable) {
                lastFailure = error
                runCatching { candidate.close() }
            }
        }

        openFailure = lastFailure
        return null
    }

    /** 把「开不出套接字」的原因整理成用户能照着做的一句话。 */
    private fun describeOpenFailure(): String {
        val raw = openFailure?.message ?: openFailure?.javaClass?.simpleName
        val tried =
            if (triedBindAddresses.isEmpty()) "（没找到任何局域网网卡）"
            else triedBindAddresses.joinToString("、")
        return "${NetDiagnostics.explain(raw)}｜已尝试的源地址：$tried、0.0.0.0"
    }

    private fun runStream(command: Int, limitSamples: Long) {
        var socket: DatagramSocket? = null
        var packets = 0L
        var sampleIndex = 0L
        var bytesSent = 0L

        try {
            val region = source.regionOf(command)
            val samplesPerPacket = samplesPerPacket()
            val packetsPerSecond = source.sampleRate / samplesPerPacket
            val periodNs = samplesPerPacket * 1_000_000_000L / source.sampleRate

            val encoder = PacketEncoder()
            val staging = ByteArray(samplesPerPacket * source.bytesPerSample)
            val packetBuffer = encoder.array
            val target = InetAddress.getByName(host)
            val datagram = DatagramPacket(packetBuffer, 0, target, port)

            var seq = 1
            var length = encoder.encodeStreamInfo(
                seq++, source.sampleRate, source.channelCount, source.channelNames
            )
            datagram.setData(packetBuffer, 0, length)

            // 不能只用裸 DatagramSocket()（等价于绑 0.0.0.0）。手机多半同时插着 SIM 卡，
            // 系统默认网络经常是移动数据：往局域网地址发包会被直接拒绝（sendto 报
            // EPERM），一个包都出不去。这里按「逐个局域网网卡的源地址」试发，谁成功
            // 就用谁，最后才退回不绑源地址交给系统挑出口。
            val activeSocket = openStreamSocket(datagram)
            if (activeSocket == null) {
                listener.onStreamFailed(command, describeOpenFailure())
                return
            }
            socket = activeSocket
            bytesSent += length

            listener.onStreamStarted(command, samplesPerPacket, packetsPerSecond)

            // 时间基：每个采样点都有确定的绝对时间戳，接收端可据此做时间对齐
            val epochBaseUs = System.currentTimeMillis() * 1000L
            val startedAtNs = System.nanoTime()
            var nextDueNs = startedAtNs
            var position = 0
            var lastProgressMs = -PROGRESS_INTERVAL_MS

            // 定点试次：上限向下对齐到整包，保证发出去的采样点数刚好是整数个包。
            // 0 表示不限长度（一直推到 stop() 为止）。
            val fixedSamples =
                if (limitSamples > 0) {
                    (limitSamples / samplesPerPacket).coerceAtLeast(1) * samplesPerPacket
                } else {
                    0L
                }

            while (!stopRequested) {
                // 发满即停：不再往下发，也不补发 STREAM_STOP
                if (fixedSamples > 0 && sampleIndex >= fixedSamples) break
                var now = System.nanoTime()
                while (nextDueNs > now && !stopRequested) {
                    LockSupport.parkNanos(nextDueNs - now)
                    now = System.nanoTime()
                }
                if (stopRequested) break

                val copied = source.copyLoop(region, position, samplesPerPacket, staging, 0)
                val deviceTimeUs = epochBaseUs + sampleIndex * 1_000_000L / source.sampleRate
                length = encoder.encodeSampleBlock(
                    seq++, sampleIndex, deviceTimeUs, samplesPerPacket, source.channelCount,
                    staging, 0, copied
                )
                datagram.setData(packetBuffer, 0, length)
                activeSocket.send(datagram)

                bytesSent += length
                sampleIndex += samplesPerPacket
                packets++
                position += samplesPerPacket
                if (position >= region.sampleCount) position -= region.sampleCount

                nextDueNs += periodNs

                // 落后过多：按真实设备缓冲溢出的行为跳过一段采样点追上实时
                val lagNs = System.nanoTime() - nextDueNs
                if (lagNs > MAX_LAG_NS) {
                    val skipPackets = (lagNs / periodNs).toInt()
                    if (skipPackets > 0) {
                        val skipSamples = skipPackets * samplesPerPacket
                        position = (position + skipSamples) % region.sampleCount
                        sampleIndex += skipSamples.toLong()
                        nextDueNs += skipPackets.toLong() * periodNs
                    }
                }

                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                if (elapsedMs - lastProgressMs >= PROGRESS_INTERVAL_MS) {
                    lastProgressMs = elapsedMs
                    listener.onStreamProgress(command, elapsedMs, packets, sampleIndex, bytesSent)
                }
            }

            // 试次发满即**静默结束**：当前不补发 STREAM_STOP。
            // 接收端按「多久没收到包」自行判定本次想象结束 —— 与真实采集设备一致。
            // 想恢复「礼貌告别」把 [SEND_STREAM_STOP] 改成 true 即可，协议本身没变。
            if (SEND_STREAM_STOP) {
                length = encoder.encodeStreamStop(seq, sampleIndex, EegFrame.STOP_REASON_RELEASED)
                datagram.setData(packetBuffer, 0, length)
                activeSocket.send(datagram)
                bytesSent += length
            }

            listener.onStreamStopped(
                command, EegFrame.STOP_REASON_RELEASED, sampleIndex, packets, bytesSent
            )
        } catch (t: Throwable) {
            listener.onStreamFailed(command, NetDiagnostics.explain(t.message))
        } finally {
            runCatching { socket?.close() }
            current = -1
            running.set(false)
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L

        /** 落后超过该值即视为缓冲溢出，需要跳采样点追赶。 */
        const val MAX_LAG_NS = 500_000_000L

        /**
         * 试次结束时是否补发 STREAM_STOP。
         *
         * 当前为 false：发满即静默闲置，接收端靠「静默超时」判定一次想象结束。
         * 用 `val` 而非 `const val`，避免编译器把下面的分支折叠成「恒假」告警。
         */
        private val SEND_STREAM_STOP = false
    }
}
