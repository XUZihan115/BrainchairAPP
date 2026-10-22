package com.fifteen.eegsender.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * 把真实脑电数据当作「采集设备的实时输出」，按固定采样节拍经 UDP 持续推流。
 *
 * 一次推流（按住方向键）的报文序列：
 *
 * ```
 * 1 × STREAM_INFO  →  n × SAMPLE_BLOCK（每 5 ms 一个）
 * ```
 *
 * 怎么结束决定要不要收尾报文（见 [release] / [interrupt]）：
 *
 * - **松手收尾** [release]：按住期间不设长度上限，松开时补一个 STREAM_STOP
 *   （reason = 0x01）。接收端拿到显式标签，就不必再靠「静默超时」猜这次想象
 *   什么时候结束。
 * - **被打断** [interrupt]：一个收尾报文都不发 —— 换方向时新一路流的 STREAM_INFO
 *   本身就是「上一次已结束」的硬边界，多补一个只会让接收端多做一次无意义收尾。
 * - **接收端失联**：发送连续发不出去（内核缓冲满，通常是接收端停止收包）累计
 *   超过 1 s，主动中止本次推流并补发 STREAM_STOP（reason = 0x02，尽力送达）。
 *   发送是**有界的**（单包最多重试 250 ms），因此推流线程任何时候都不会被
 *   网络阻塞卡死。
 *
 * 接收端三种情况都不至于出错：只认 STREAM_INFO 的实现在三条路径下都对。
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

        /** 本次推流结束（[release] 松手时会已补发 STREAM_STOP；被打断时不补）。 */
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

    /**
     * 退出时要不要补发 STREAM_STOP。
     *
     * 由 [release]（true）/ [interrupt]（false）写入，推流线程在收尾处读。
     * 两个字段都是 volatile，且写入方总是**先写本字段再写 [stopRequested]**，
     * 读方也总是先读 [stopRequested] 再读本字段 —— 看到停止标志就一定看到
     * 正确的收尾意图，不需要额外同步。
     */
    @Volatile
    private var sendStopOnExit = false

    @Volatile
    private var current = -1

    /**
     * 流代数。每次 [start] 都会 +1；推流线程的收尾回调与 finally 里对共享状态的
     * 复位都只在「自己还是最新一代」时执行。这样即便某一代线程因为极端情况被
     * [awaitWorker] 弃守（daemon 线程，随进程消亡），它晚到的收尾也不会去碰
     * 新一路流的 [running] / [current]。
     */
    @Volatile
    private var generation = 0L

    /** 当前这一路流正在使用的通道；[awaitWorker] 弃守时会关闭它来打断阻塞发送。 */
    @Volatile
    private var activeChannel: DatagramChannel? = null

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
     *   （例如 4000 点、每包 5 点 ⇒ 正好 800 个包）。传 **0 表示不限长度**，
     *   一直推到 [release] / [interrupt] 为止 —— 这是「按住持续发」用的模式。
     *   传非 0 时发满即结束（并补发 STREAM_STOP），只用于兜底安全上限。
     */
    fun start(command: Int, limitSamples: Long = 0L): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopRequested = false
        // 默认不发收尾报文；只有 [release] 会把它改成 true
        sendStopOnExit = false
        current = command
        val gen = ++generation
        worker = thread(name = "eeg-udp-stream", isDaemon = true) {
            runStream(command, limitSamples, gen)
        }
        return true
    }

    /**
     * 松手收尾：请求停止、**补发 STREAM_STOP**（reason = 0x01 正常结束），
     * 然后等推流线程真正退出。
     *
     * 这是「按住持续发」的收尾动作：按住期间不设长度上限，松手才结束这一次想象。
     * 等退出有个实际好处：调用方回到主线程后 [isStreaming] 已经是 false，可以立刻
     * 再 [start] 一路新流，不会撞上「上一路还在循环里」的竞态。等待是**有界**的
     * （[AWAIT_WORKER_MS]）：推流线程因发送卡住来不及退时，主线程不会被拖住，
     * 界面永远保持可响应。
     */
    fun release() {
        sendStopOnExit = true
        stopRequested = true
        awaitWorker()
    }

    /**
     * 打断：请求停止但**不发任何收尾报文**，然后等推流线程真正退出。
     *
     * 用于「换方向」与页面销毁：新一路流的 STREAM_INFO 就是硬边界。
     * 若调用前刚 [release] 过还没来得及发收尾，这里会把收尾取消掉 ——
     * 那是刻意的：既然已经有新边界，收尾报文就不再需要。
     */
    fun interrupt() {
        sendStopOnExit = false
        stopRequested = true
        awaitWorker()
    }

    /**
     * 停止并等待推流线程退出。等价于 [interrupt]，保留旧名字给页面销毁用。
     *
     * 退出后 [running] 已由推流线程复位，同一个对象可以立刻再 [start] 一路全新的流
     * —— 这也是打断后能复用同一发送器的原因（不必重建、不必改端口）。
     */
    fun shutdown() = interrupt()

    /**
     * 等推流线程退出；不改变收尾语义，只负责把 [worker] 收干净。
     *
     * 最多等 [AWAIT_WORKER_MS]。超时说明线程僵在不可中断的发送里：先关闭通道打断
     * 它（send 会立刻抛异常退出），再给最后 [AWAIT_GRACE_MS]。若仍然存活则弃守
     * （daemon 线程随进程消亡），并乐观复位状态 —— 后续 [start] 会推进 [generation]，
     * 旧线程迟到的 finally 不会再碰共享状态。
     */
    private fun awaitWorker() {
        val thread = worker
        worker = null
        if (thread == null) return

        val deadline = System.currentTimeMillis() + AWAIT_WORKER_MS
        while (thread.isAlive && System.currentTimeMillis() < deadline) {
            runCatching { thread.join(30) }
        }

        if (thread.isAlive) {
            // 线程卡在非阻塞发送的有界重试里通常不会走到这；若走到了，关通道是
            // 唯一能立刻打断 `channel.send(...)` 的途径。
            runCatching { activeChannel?.close() }
            runCatching { thread.join(AWAIT_GRACE_MS) }
        }

        if (thread.isAlive) {
            // 弃守：daemon 线程不会阻止进程退出。
            running.set(false)
            current = -1
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
     * 开一个**能真正把包发出去**的非阻塞 UDP 通道：依次尝试绑定到各局域网网卡的
     * 源地址，成功发出首包后返回该通道；全部失败返回 null。
     *
     * 为什么必须逐个试：`DatagramChannel` 绑 `0.0.0.0` 时，出口由系统按路由表挑。
     * 手机同时有移动数据和 WiFi 时，优先级判断常常偏向蜂窝网络，包被路由到移动数据
     * 出口，往 `10.x` 这类局域网地址发就报 EPERM —— 而显式绑定 WiFi 网卡地址就正常。
     *
     * 非阻塞模式配合 [boundedSend] 的有界重试：内核发送缓冲满（接收端不读了）时
     * `send` 返回 0 而不是无限阻塞，推流线程因此永远不可能被网络拖死。
     */
    private fun openStreamChannel(data: ByteArray, length: Int, target: SocketAddress): DatagramChannel? {
        val bindAddresses = runCatching { UdpDiscovery().localBindAddresses() }
            .getOrDefault(emptyList())
        triedBindAddresses = bindAddresses.map { it.hostAddress ?: "?" }

        val candidates = ArrayList<InetAddress?>(bindAddresses.size + 1)
        candidates.addAll(bindAddresses)
        candidates.add(null) // 兜底：不绑源地址，交给系统默认网络

        var lastFailure: Throwable? = null
        for (bindIp in candidates) {
            val candidate = runCatching {
                DatagramChannel.open().apply {
                    configureBlocking(false)
                    setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    setOption(StandardSocketOptions.SO_SNDBUF, 1 shl 20)
                    bind(InetSocketAddress(bindIp, 0))
                }
            }.getOrElse { error ->
                if (lastFailure == null) lastFailure = error
                null
            } ?: continue

            // 试发首包：通道刚建好、缓冲是空的，正常情况下立刻就能发出去。
            val probe = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            val sent = runCatching { candidate.send(probe, target) }.getOrElse { error ->
                lastFailure = error
                -1
            }
            if (sent > 0) {
                openFailure = null
                return candidate
            }
            runCatching { candidate.close() }
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

    /**
     * 有界发送：非阻塞通道一次 `send` 没发出去（内核发送缓冲满 / 对端停止收包）
     * 就等 [SEND_RETRY_MS] 重试，单包最多试 [capMs]。
     *
     * @return 0 = 一次成功；>0 = 重试后成功（耗费的毫秒数，供停滞累计用）；
     *   -1 = 超时没发出去。
     */
    private fun boundedSend(
        channel: DatagramChannel,
        buffer: ByteBuffer,
        target: SocketAddress,
        capMs: Long
    ): Long {
        val deadline = System.currentTimeMillis() + capMs
        var waited = 0L
        while (true) {
            val sent = runCatching { channel.send(buffer, target) }.getOrElse { -1 }
            if (sent > 0) return waited
            if (System.currentTimeMillis() >= deadline) return -1
            LockSupport.parkNanos(SEND_RETRY_MS * 1_000_000L)
            waited += SEND_RETRY_MS
        }
    }

    private fun runStream(command: Int, limitSamples: Long, gen: Long) {
        var channel: DatagramChannel? = null
        var packets = 0L
        var sampleIndex = 0L
        var bytesSent = 0L

        /** 本代线程是否还是最新一代：不是的话，所有回调和状态复位都不得再执行。 */
        fun live(): Boolean = gen == generation

        try {
            val region = source.regionOf(command)
            val samplesPerPacket = samplesPerPacket()
            val packetsPerSecond = source.sampleRate / samplesPerPacket
            val periodNs = samplesPerPacket * 1_000_000_000L / source.sampleRate

            val encoder = PacketEncoder()
            val staging = ByteArray(samplesPerPacket * source.bytesPerSample)
            val packetBuffer = encoder.array
            val target = InetAddress.getByName(host)
            val targetAddr = InetSocketAddress(target, port)
            // 复用同一块缓冲：每次发送前重设 position/limit 即可，不必新建对象。
            val out = ByteBuffer.wrap(packetBuffer).order(ByteOrder.LITTLE_ENDIAN)

            var seq = 1
            var length = encoder.encodeStreamInfo(
                seq++, source.sampleRate, source.channelCount, source.channelNames
            )

            // 不能只用裸 DatagramChannel()（等价于绑 0.0.0.0）。手机多半同时插着 SIM 卡，
            // 系统默认网络经常是移动数据：往局域网地址发包会被直接拒绝（sendto 报
            // EPERM），一个包都出不去。这里按「逐个局域网网卡的源地址」试发，谁成功
            // 就用谁，最后才退回不绑源地址交给系统挑出口。
            val streaming = openStreamChannel(packetBuffer, length, targetAddr)
            if (streaming == null) {
                if (live()) listener.onStreamFailed(command, describeOpenFailure())
                return
            }
            channel = streaming
            activeChannel = streaming
            bytesSent += length

            if (live()) listener.onStreamStarted(command, samplesPerPacket, packetsPerSecond)

            // 时间基：每个采样点都有确定的绝对时间戳，接收端可据此做时间对齐
            val epochBaseUs = System.currentTimeMillis() * 1000L
            val startedAtNs = System.nanoTime()
            var nextDueNs = startedAtNs
            var position = 0
            var lastProgressMs = -PROGRESS_INTERVAL_MS

            // 定点试次：上限向下对齐到整包，保证发出去的采样点数刚好是整数个包。
            // 0 表示不限长度 —— 一直推到 release() / interrupt() 为止（按住持续发）。
            val fixedSamples =
                if (limitSamples > 0) {
                    (limitSamples / samplesPerPacket).coerceAtLeast(1) * samplesPerPacket
                } else {
                    0L
                }

            // 发送停滞累计；超过 ABORT_STALL_MS 判定接收端失联，中止推流。
            var stallMs = 0L
            var aborted = false

            while (!stopRequested) {
                // 兜底上限：到点就停（不是正常收尾路径，正常收尾由松手触发）
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
                out.position(0)
                out.limit(length)

                // 有界发送：单包最多等 SEND_CAP_MS；发不出去计入停滞。
                val waitMs = boundedSend(streaming, out, targetAddr, SEND_CAP_MS)
                if (waitMs >= 0) {
                    bytesSent += length
                    stallMs = if (waitMs > 0) stallMs + waitMs else 0L
                } else {
                    stallMs += SEND_CAP_MS
                }
                if (stallMs > ABORT_STALL_MS) {
                    // 接收端长时间收不下包（大概率已经掉线）：不再硬发，主动收尾。
                    aborted = true
                    break
                }

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
                    if (live()) {
                        listener.onStreamProgress(
                            command, elapsedMs, packets, sampleIndex, bytesSent
                        )
                    }
                }
            }

            if (aborted) {
                // 接收端失联中止：尽力补一个 reason=0x02 的 STREAM_STOP。
                // 对端可能已经收不到了，发不出也接受 —— 关键是推流线程到此结束，
                // 界面留在前台，等用户修好接收端后再按一次即可。
                length = encoder.encodeStreamStop(seq, sampleIndex, EegFrame.STOP_REASON_ERROR)
                out.position(0)
                out.limit(length)
                runCatching { boundedSend(streaming, out, targetAddr, SEND_CAP_MS) }
                if (live()) listener.onStreamFailed(command, STALL_ABORT_MESSAGE)
            } else {
                // 收尾报文：两种「正常结束」都补一个 STREAM_STOP ——
                //   * 松手（[release] 把 [sendStopOnExit] 置为 true）；
                //   * 定点试次发满（固定长度的兜底上限）。
                // 只有「被打断」（[interrupt] / 换方向 / 页面销毁）才一个都不发：
                // 那种情况由新一路流的 STREAM_INFO 划硬边界。
                if (sendStopOnExit || fixedSamples > 0) {
                    length = encoder.encodeStreamStop(seq, sampleIndex, EegFrame.STOP_REASON_RELEASED)
                    out.position(0)
                    out.limit(length)
                    runCatching { boundedSend(streaming, out, targetAddr, SEND_CAP_MS) }
                }
                if (live()) {
                    listener.onStreamStopped(
                        command, EegFrame.STOP_REASON_RELEASED, sampleIndex, packets, bytesSent
                    )
                }
            }
        } catch (t: Throwable) {
            if (live()) listener.onStreamFailed(command, NetDiagnostics.explain(t.message))
        } finally {
            runCatching { channel?.close() }
            if (channel == activeChannel) activeChannel = null
            // 过期的旧线程（被弃守后又有新流启动）不再碰共享状态。
            if (live()) {
                current = -1
                running.set(false)
            }
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L

        /** 落后超过该值即视为缓冲溢出，需要跳采样点追赶。 */
        const val MAX_LAG_NS = 500_000_000L

        /** 单包有界发送的最长等待；超过即认为这一包发不出去。 */
        const val SEND_CAP_MS = 250L

        /** 发送停滞累计超过该时长即判定接收端失联，主动中止推流。 */
        const val ABORT_STALL_MS = 1000L

        /** 有界发送的重试间隔。 */
        const val SEND_RETRY_MS = 5L

        /** 等推流线程退出的主线程预算；超时则关通道打断并弃守。 */
        const val AWAIT_WORKER_MS = 300L

        /** 关通道打断后，再给推流线程的宽限。 */
        const val AWAIT_GRACE_MS = 50L

        /** 接收端失联中止时展示给用户的消息。 */
        const val STALL_ABORT_MESSAGE =
            "接收端长时间无响应（发送停滞超过 1 秒），已中止推流并补发 STREAM_STOP(reason=0x02)"
    }
}
