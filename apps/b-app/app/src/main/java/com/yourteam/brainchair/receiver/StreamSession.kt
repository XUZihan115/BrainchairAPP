package com.yourteam.brainchair.receiver

data class ReceiverSnapshot(
    val status: String, val source: String?, val info: StreamInfo?, val received: Long,
    val accepted: Long, val rejected: Map<String, Long>, val duplicates: Long, val outOfOrder: Long,
    val missingPackets: Long, val missingSamples: Long, val buffered: Int, val nextIndex: Long,
    val lastTimeUs: Long?, val generation: Long, val rate: Double, val logs: List<String>,
    val infoPackets:Long=0, val blockPackets:Long=0, val stopPackets:Long=0,
    val stopAudits:List<StopAudit> = emptyList(), val streamId:Long=0, val streamStartedNs:Long?=null
)

/** B socket/accept timestamps through synchronous invalidation completion; not network or Unity latency. */
data class StopAudit(val seq:Long,val nextIndex:Long,val reason:Int,val source:String,
                     val acceptStartedNs:Long,val invalidationCompletedNs:Long,val listenersNotified:Int,
                     val socketReceivedNs:Long?=null) {
    val processingMs:Double get()=(invalidationCompletedNs-acceptStartedNs)/1e6
    val receiveToInvalidationMs:Double? get()=socketReceivedNs?.let{(invalidationCompletedNs-it)/1e6}
}
data class SampleWindow(val generation: Long, val info: StreamInfo, val firstIndex: Long,
                        val firstTimeUs: Long, val samples: FloatArray)
data class StreamHealth(val generation: Long, val info: StreamInfo?, val nextIndex: Long,
                        val lastDataMs: Long?, val status: String, val streamId:Long=0)
data class SampleIncrement(val health: StreamHealth, val firstIndex: Long, val reset: Boolean,
                           val samples: FloatArray)

/** Single bounded time-major ring. All public access synchronized; UI never owns mutable samples. */
class StreamSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val timeoutMs: Long = 1000,
                    private val nanoClock:()->Long={System.nanoTime()}) {
    private var info: StreamInfo? = null
    private var owner: String? = null
    private var status = "等待 STREAM_INFO"
    private var ring = FloatArray(0)
    private var capacity = 0; private var size = 0; private var write = 0
    private var next = 0L; private var seq = 0L; private var timeUs: Long? = null
    private var originUs: Long? = null
    private var streamId=0L
    private var streamStartedNs:Long?=null
    // v2 has no session ID. Remember retired endpoints/origins, without changing its wire format.
    // An indistinguishable delayed INFO can only be treated as another safe reset (see Stage 2.7 notes).
    private val retiredEndpoints=LinkedHashSet<String>()
    private val retiredOrigins=LinkedHashSet<Pair<String,Long>>()
    private var lastEndpoint:String?=null
    private var lastValid = 0L; private var start = clock()
    private var lastDataMs: Long? = null
    private var received = 0L; private var accepted = 0L
    private var infoPackets=0L; private var blockPackets=0L;private var stopPackets=0L
    private val stopAudits=ArrayDeque<StopAudit>()
    private var duplicates = 0L; private var order = 0L; private var missing = 0L; private var missingSamples = 0L
    private var generation = 0L
    private val errors = linkedMapOf<String, Long>()
    private val logs = ArrayDeque<String>()
    private val seen = LinkedHashSet<Long>()
    // Subscribers MUST be short, non-blocking state invalidations; never filter or perform IO here.
    private val invalidationListeners = LinkedHashSet<(String)->Unit>()
    @Synchronized fun addInvalidationListener(listener:(String)->Unit) { invalidationListeners.add(listener) }
    @Synchronized fun removeInvalidationListener(listener:(String)->Unit) { invalidationListeners.remove(listener) }
    private fun invalidateWindow(reason:String) {
        size=0; write=0; generation++
        invalidationListeners.forEach { it(reason) }
    }
    private fun log(s: String) { if (logs.size >= 60) logs.removeFirst(); logs.addLast(s) }
    private fun reject(s: String) { errors[s] = (errors[s] ?: 0) + 1; if (errors[s] == 1L || errors[s]!! % 100 == 0L) log("拒收 $s (${errors[s]})") }
    @Synchronized fun event(s: String) { log(s) }
    private fun clear(label: String) {
        val endpoint=owner;val origin=originUs
        if(endpoint!=null && origin!=null) {
            retiredOrigins.add(endpoint to origin)
            if(retiredOrigins.size>256)retiredOrigins.remove(retiredOrigins.first())
        }
        info = null; owner = null; ring = FloatArray(0); capacity = 0; size = 0; write = 0
        next = 0; seq = 0; timeUs = null; originUs = null; lastDataMs = null; seen.clear(); status = label
        streamStartedNs=null
        invalidateWindow(label)
    }
    @Synchronized fun stop(label: String = "监听已停止") { clear(label); log(label) }
    @Synchronized fun checkTimeout() {
        if (info != null && clock() - lastValid >= timeoutMs) { clear("断流超时，等待新 STREAM_INFO"); log(status) }
    }
    @Synchronized fun accept(raw: ByteArray, source: String, socketReceivedNs:Long?=null) {
        val acceptStartedNs=nanoClock()
        received++
        val msg = try { EegProtocol.parse(raw) } catch(e: PacketError) {
            reject(e.reason)
            if(owner!=null && owner==source) invalidateWindow("当前数据源错误：${e.reason}")
            return
        }
        if(source in retiredEndpoints){reject("retired-source");return}
        if (owner != null && owner != source) {
            // A may open a new ephemeral socket for the next stream. A validated INFO is the
            // explicit boundary; do not permit a BLOCK/STOP or another IP to take ownership.
            val previous=owner!!
            val samePeerInfo=msg is EegMessage.Info && ':' in previous && ':' in source &&
                previous.substringBeforeLast(':')==source.substringBeforeLast(':')
            if(!samePeerInfo){reject("foreign-source");return}
            log("同一A的新INFO更换源端口：$previous → $source；重置旧流")
        }
        if (msg is EegMessage.Info) {
            // No session identifier exists on wire: every valid INFO is a conservative reset.
            if(lastEndpoint!=null && lastEndpoint!=source) {
                retiredEndpoints.add(lastEndpoint!!)
                if(retiredEndpoints.size>128)retiredEndpoints.remove(retiredEndpoints.first())
            }
            lastEndpoint=source;streamId++
            clear("流已声明，等待样本")
            info = msg.info; owner = source; capacity = msg.info.rate * 4
            streamStartedNs=acceptStartedNs
            ring = FloatArray(capacity * msg.info.channels); seq = 1; seen.add(1)
            lastValid = clock(); accepted++;infoPackets++;log("INFO $source ${msg.info.rate}Hz ${msg.info.channels}ch；清空旧窗口")
            return
        }
        val i = info ?: run { reject("missing-info"); return }
        if (msg.seq <= seq) {
            if (seen.contains(msg.seq)) duplicates++ else order++
            return
        }
        when(msg) {
            is EegMessage.Block -> {
                if (msg.channels != i.channels) { reject("channel-mismatch"); invalidateWindow("通道布局错误"); return }
                if (msg.first < next) { order++; return }
                val offset = msg.first * 1_000_000 / i.rate
                if (msg.timeUs < offset) { reject("timestamp"); invalidateWindow("采样时间戳错误"); return }
                val origin = msg.timeUs - offset
                if(source to origin in retiredOrigins){reject("retired-origin");return}
                if(originUs==null && (msg.first!=0L || msg.seq!=2L)) {
                    reject("missing-stream-start");invalidateWindow("缺少新流首包，等待新 STREAM_INFO");return
                }
                if (originUs != null && kotlin.math.abs(origin - originUs!!) > 1) { reject("timestamp"); invalidateWindow("采样时间戳错误"); return }
                if (originUs == null) originUs = origin
                val gap = msg.first - next
                if (gap > 0 || msg.seq != seq + 1) {
                    missingSamples += gap; invalidateWindow("样本连续性中断，重新积累")
                    log("连续性中断：样本缺口=$gap；重建窗口")
                }
                for (row in 0 until msg.count) {
                    System.arraycopy(msg.samples, row * i.channels, ring, write * i.channels, i.channels)
                    write = (write + 1) % capacity; size = minOf(capacity, size + 1)
                }
                next = msg.first + msg.count; timeUs = msg.timeUs
                status = "接收中"; lastDataMs = clock()
                blockPackets++
            }
            is EegMessage.Stop -> {
                if (msg.nextIndex < next) { reject("stop-index"); invalidateWindow("停止报文序号错误"); return }
                missing += msg.seq - seq - 1; missingSamples += msg.nextIndex - next
                accepted++;stopPackets++;clear("流已结束 reason=${msg.reason}")
                if(stopAudits.size>=32)stopAudits.removeFirst()
                stopAudits.addLast(StopAudit(msg.seq,msg.nextIndex,msg.reason,source,acceptStartedNs,nanoClock(),invalidationListeners.size,socketReceivedNs))
                log(status);return
            }
            else -> return
        }
        missing += msg.seq - seq - 1; seq = msg.seq; seen.add(seq)
        if (seen.size > 1024) seen.remove(seen.first())
        lastValid = clock(); accepted++
    }
    @Synchronized fun window(): SampleWindow? {
        val i = info ?: return null
        if (size != capacity) return null
        val values = FloatArray(size * i.channels)
        for (row in 0 until size) System.arraycopy(ring, ((write + row) % capacity) * i.channels, values, row * i.channels, i.channels)
        val first = next - size
        return SampleWindow(generation, i, first, originUs!! + first * 1_000_000 / i.rate, values)
    }
    @Synchronized fun snapshot(): ReceiverSnapshot = ReceiverSnapshot(status, owner, info, received, accepted,
        errors.toMap(), duplicates, order, missing, missingSamples, size, next, timeUs, generation,
        received * 1000.0 / maxOf(1, clock() - start), logs.toList(),infoPackets,blockPackets,stopPackets,stopAudits.toList(),streamId,streamStartedNs)
    @Synchronized fun health() = StreamHealth(generation, info, next, lastDataMs, status,streamId)
    /** Copy only new selected channels. Filtering/CCA must happen AFTER this short lock is released. */
    @Synchronized fun increment(previousGeneration: Long, cursor: Long, channels: List<String>): SampleIncrement {
        val h = health(); val i = info ?: return SampleIncrement(h, 0, true, FloatArray(0))
        val picks = channels.map { i.names.indexOf(it) }
        require(picks.all { it >= 0 }) { "缺少规定通道" }
        val earliest = next - size
        val reset = previousGeneration != generation || cursor < earliest || cursor > next
        val first = if (reset) earliest else cursor
        val count = (next - first).toInt()
        val result = FloatArray(count * picks.size)
        val oldest = (write - size + capacity) % capacity
        for (r in 0 until count) {
            val row = (oldest + (first - earliest).toInt() + r) % capacity
            for (c in picks.indices) result[r * picks.size + c] = ring[row * i.channels + picks[c]]
        }
        return SampleIncrement(h, first, reset, result)
    }
}
