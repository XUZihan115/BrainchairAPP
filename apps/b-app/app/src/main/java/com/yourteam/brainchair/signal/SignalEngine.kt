package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.StreamSession
import com.yourteam.brainchair.receiver.StreamInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RecognitionSnapshot(
    val scores: List<Double> = emptyList(), val raw: Direction=Direction.STOP,
    val stable: Direction=Direction.STOP, val reason: String="等待有效数据", val config: RecognitionConfig=RecognitionConfig(),
    val generation: Long=-1, val windowEnd: Long=0, val decidedMs: Long?=null,
    val analysisCount: Long=0, val computeMs: Double=0.0, val cpuMs: Double=0.0,
    val periodMs: Long=0, val cumulativeCpuMs: Double=0.0,
    val winner:Direction=Direction.STOP, val inputMs:Long?=null,
    val control:ControlSnapshot=ControlSnapshot()
)

/** Pull new data on a worker: no FFT/CCA/filter call can execute inside UDP accept(). */
class SignalEngine(private val session: StreamSession, config: RecognitionConfig=RecognitionConfig(),
                   private val clock:()->Long={System.nanoTime()/1_000_000},
                   private val cpuClock:()->Long={0L},
                   private val scoreFunction:(Cca,Array<DoubleArray>)->DoubleArray={c,x->c.scores(x)},
                   private val onDecision:(RecognitionSnapshot)->Unit={},
                   val windowSeconds:Double=SignalTiming.WINDOW_SECONDS,
                   val updateMs:Long=SignalTiming.UPDATE_MS):ControlOutputSource {
    companion object { val CHANNELS=listOf("Pz","O1","Oz","O2","P1","P2","POz","PO3","PO4") }
    private val decisionLock=Any()
    @Volatile private var closed=false
    @Volatile private var result=RecognitionSnapshot(config=config)
    private var revision=0L
    private var gate=DecisionGate(config)
    private val control=RecognitionControl()
    private var generation=-1L; private var cursor=0L; private var lastAnalyzed=-1L
    private var filter:Bandpass?=null; private var cca:Cca?=null
    private var ring=emptyArray<DoubleArray>(); private var write=0; private var size=0; private var warmup=0
    private val worker=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"eeg-analysis").apply { isDaemon=true } }
    private val watchdog=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"eeg-watchdog").apply { isDaemon=true } }
    private var started=false
    private val onSessionInvalidated:(String)->Unit = { reason->invalidate(reason) }
    init { session.addInvalidationListener(onSessionInvalidated) }
    @Synchronized fun start() {
        if(started || closed)return; started=true
        worker.scheduleWithFixedDelay({try{tick()}catch(e:Exception){invalidate("算法异常：${e.message}");generation=-1}},0,updateMs,TimeUnit.MILLISECONDS)
        watchdog.scheduleWithFixedDelay({guard()},0,50,TimeUnit.MILLISECONDS)
    }
    fun configure(config: RecognitionConfig) = synchronized(decisionLock) {
        revision++; gate=DecisionGate(config)
        control.stop("参数已变更，等待新判决",clock())
        result=result.copy(config=config,scores=emptyList(),winner=Direction.STOP,raw=Direction.STOP,stable=Direction.STOP,reason="参数已变更，等待新判决",decidedMs=null,inputMs=null,control=control.state)
        onDecision(result)
    }
    private fun invalidate(reason:String) = synchronized(decisionLock) {
        if(result.reason!=reason || result.stable!=Direction.STOP || result.raw!=Direction.STOP) revision++
        gate.reset();control.stop(reason,clock())
        result=result.copy(scores=emptyList(),winner=Direction.STOP,raw=Direction.STOP,stable=Direction.STOP,reason=reason,decidedMs=null,inputMs=null,windowEnd=0,control=control.state)
        onDecision(result)
    }
    /** Invalidates in-flight computation tokens as well as votes; does not relax any gate. */
    fun resetDecision(reason:String) { synchronized(decisionLock){revision++;invalidate(reason)} }
    /** Separate watchdog; also checked synchronously by every result consumer. */
    fun guard() {
        session.checkTimeout()
        val h=session.health(); val now=clock(); val r=result
        when {
            closed -> invalidate("识别已停止")
            h.info==null -> invalidate(h.status)
            !h.info.names.containsAll(CHANNELS) -> invalidate("缺少规定的9个枕顶通道")
            h.info.rate !in 100..4000 -> invalidate("不支持的识别采样率")
            h.generation!=r.generation -> invalidate("新流/窗口失效，重新积累")
            h.lastDataMs==null -> invalidate("等待样本")
            now-h.lastDataMs>=1000 -> invalidate("数据流中断超过1秒")
            r.decidedMs!=null && now-r.decidedMs>=1000 -> invalidate("超过1秒无新有效判决")
            r.inputMs!=null && now-r.inputMs>=1000 -> invalidate("分析输入超过1秒未更新")
        }
        synchronized(decisionLock){control.expire(now);result=result.copy(control=control.state);onDecision(result)}
    }
    fun snapshot():RecognitionSnapshot { guard(); return result }
    /** Read-only immediate event state: no polling side effect. Useful to audit STOP notification. */
    fun eventSnapshot():RecognitionSnapshot = result
    fun controlSnapshot():ControlSnapshot { guard(); return result.control }
    override fun currentControl():ControlSnapshot = controlSnapshot()
    private fun initialize(i:StreamInfo,g:Long) {
        filter=Bandpass(i.rate); cca=Cca(i.rate,windowSeconds); ring=Array(9){DoubleArray(cca!!.count)}
        generation=g; write=0;size=0;warmup=i.rate/2;lastAnalyzed=-1
        invalidate("滤波预热0.5秒后积累${windowSeconds}秒窗口")
        synchronized(decisionLock){result=result.copy(generation=g)}
    }
    /** Public for deterministic tests; production calls only on eeg-analysis thread. */
    fun tick() {
        if(closed)return
        guard()
        val h=session.health(); val i=h.info ?: return
        if(!i.names.containsAll(CHANNELS)){invalidate("缺少规定的9个枕顶通道");return}
        if(i.rate !in 100..4000){invalidate("不支持的识别采样率");return}
        val begin=System.nanoTime(); val cpuBegin=cpuClock()
        val chunk=session.increment(generation,cursor,CHANNELS)
        // INFO can change between health and increment; never interpret using a stale layout.
        if(chunk.health.info!=i || chunk.health.generation!=h.generation)return
        if(chunk.reset || filter==null)initialize(i,chunk.health.generation)
        if(chunk.samples.isEmpty())return
        val f=filter!!
        for(row in 0 until chunk.samples.size/9) {
            for(c in 0..8){val y=f.sample(chunk.samples[row*9+c].toDouble(),c);if(warmup==0)ring[c][write]=y}
            if(warmup>0)warmup-- else {write=(write+1)%ring[0].size;size=minOf(size+1,ring[0].size)}
        }
        cursor=chunk.health.nextIndex
        if(size<ring[0].size){invalidate("滤波预热/窗口不足${windowSeconds}秒");return}
        if(lastAnalyzed>=0 && cursor-lastAnalyzed < i.rate*updateMs/1000)return
        val token=synchronized(decisionLock){revision}
        val x=Array(9){c->DoubleArray(size){r->ring[c][(write+r)%size]}}
        val scores=scoreFunction(cca!!,x)
        val now=clock(); val current=session.health()
        if(closed || current.generation!=generation || current.info==null || current.lastDataMs==null || now-current.lastDataMs>=1000){guard();return}
        val inputMs=chunk.health.lastDataMs ?: return
        if(now-inputMs>=1000){invalidate("分析输入已过期，丢弃迟到结果");return}
        lastAnalyzed=cursor
        synchronized(decisionLock) {
            if(token!=revision)return
            val decision=gate.evaluate(scores,now)
            control.accept(decision.raw,decision.stable,inputMs,now)
            val cpu=(cpuClock()-cpuBegin)/1e6
            result=RecognitionSnapshot(if(scores.all{it.isFinite()})scores.toList() else emptyList(),decision.raw,decision.stable,decision.reason,result.config,generation,cursor,now,
                result.analysisCount+1,(System.nanoTime()-begin)/1e6,cpu,result.decidedMs?.let{now-it}?:0,result.cumulativeCpuMs+cpu,
                if(scores.size==4 && scores.all{it.isFinite()})Direction.values()[scores.indices.maxByOrNull{scores[it]}!!] else Direction.STOP,inputMs,control.state)
            onDecision(result)
        }
    }
    fun close() {closed=true;worker.shutdownNow();watchdog.shutdownNow();session.removeInvalidationListener(onSessionInvalidated);invalidate("识别已停止")}
}
