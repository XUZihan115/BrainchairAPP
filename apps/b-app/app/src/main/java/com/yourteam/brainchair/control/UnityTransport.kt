package com.yourteam.brainchair.control

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.RejectedExecutionException

data class UnityCommand(val command:ControlCommand,val speed:Int,val revision:Long,val serial:Long,val sentNs:Long) {
    init {require(speed in 0..100);require(command.moving || speed==0)}
}
data class UnityReceipt(val message:UnityCommand,val receivedNs:Long)
interface UnityTransport:AutoCloseable {
    val mode:String
    val ready:Boolean get()=true
    /** Must finish promptly; never queue a stale cached motion in this method. */
    fun send(command:UnityCommand)
    override fun close() {}
}

/** Behavioral test double only. These typed objects are NOT a Unity wire protocol. */
class SimulatedUnityReceiver(private val nanos:()->Long={System.nanoTime()}) {
    private val records=ArrayDeque<UnityReceipt>()
    private var latched=false
    private var applied=ControlCommand.STOP
    @Synchronized fun receive(message:UnityCommand) {
        if(records.lastOrNull()?.message?.serial?.let{message.serial<=it}==true)return
        if(message.command==ControlCommand.ESTOP)latched=true
        applied=if(latched)ControlCommand.ESTOP else message.command
        if(records.size>=256)records.removeFirst()
        records.addLast(UnityReceipt(message,nanos()))
    }
    @Synchronized fun receipts():List<UnityReceipt> = records.toList()
    @Synchronized fun current():ControlCommand {
        if(applied.moving && records.lastOrNull()?.let{nanos()-it.receivedNs>=1_000_000_000L}!=false)applied=ControlCommand.STOP
        return applied
    }
    @Synchronized fun fresh(maxAgeNs:Long=1_000_000_000L)=records.lastOrNull()?.let{nanos()-it.receivedNs in 0 until maxAgeNs}==true
    @Synchronized fun releaseEmergency():Boolean {
        if(!fresh(500_000_000L) || current().moving)return false
        latched=false;applied=ControlCommand.STOP;return true
    }
}
class SimulatedUnityTransport(val receiver:SimulatedUnityReceiver):UnityTransport {
    override val mode="SIMULATED_IN_PROCESS_NO_UDP"
    override fun send(command:UnityCommand)=receiver.receive(command)
}

interface UnityCodec {
    val confirmed:Boolean
    fun encode(command:UnityCommand):ByteArray
}
/** Intentional fail-closed adapter: the draft does not define enough bytes to interoperate. */
object PendingUnityCodec:UnityCodec {
    override val confirmed=false
    val missing=listOf("类型字段取值/宽度","长度字段宽度及计算范围","数据中指令和速度的顺序/编码",
        "校验算法及覆盖字节","急停解除与接收回执的正式语义")
    override fun encode(command:UnityCommand):ByteArray=throw IllegalStateException("Unity协议待确认：${missing.joinToString()}")
}
/** Isolated future adapter. Never enabled with PendingUnityCodec, never uses legacy Protocol. */
class UdpUnityTransport(private val target:InetSocketAddress,private val codec:UnityCodec):UnityTransport {
    private val socket:DatagramSocket
    init {require(codec.confirmed){"禁止发送未确认的Unity帧"};socket=DatagramSocket()}
    override val mode="UDP_CONFIRMED_CODEC"
    override fun send(command:UnityCommand) {
        val bytes=codec.encode(command)
        socket.send(DatagramPacket(bytes,bytes.size,target))
    }
    override fun close()=socket.close()
}
data class TransportStatus(val mode:String,val sent:UnityCommand?,val error:String?)

/** Change-triggered sends plus 200ms Latch renewal. Queue contains wakeups, never commands.
 * ESTOP is a three-packet 30ms burst, then STOP heartbeats while B remains latched. Repeating
 * 06 forever would immediately re-latch Unity after its operator presses Z.
 */
class ControlDispatcher(private val authority:ControlAuthority,private val transport:UnityTransport,
                        private val nanos:()->Long={System.nanoTime()}):AutoCloseable {
    private val worker=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"control-delivery").apply{isDaemon=true} }
    private val queued=AtomicBoolean(false)
    private var serial=0L
    @Volatile private var started=false
    @Volatile private var closed=false
    @Volatile private var closing=false
    @Volatile private var last:UnityCommand?=null
    @Volatile private var error:String?=null
    @Volatile private var burstSequence=-1L
    @Volatile private var burstCount=0
    @Volatile var lastEmergencySentNs:Long=0;private set
    init {authority.onChanged={request()}}
    private fun request() {
        if(!started || closed || closing || !queued.compareAndSet(false,true))return
        try{worker.execute {queued.set(false);flush()}}
        catch(_:RejectedExecutionException){queued.set(false)} // shutdown raced with a final invalidation
    }
    fun start() {
        if(started || closed)return
        started=true;worker.scheduleWithFixedDelay({flush()},0,200,TimeUnit.MILLISECONDS)
    }
    /** Public deterministic test hook; production sends only from the single delivery worker. */
    @Synchronized fun flush() {
        if(closed)return
        if(!transport.ready){error="未配置Unity IP，未发送";return}
        try {
            authority.deliver { s->
                val now=nanos()
                var wire=s.command
                if(s.emergencyLocked) {
                    if(burstSequence!=s.emergencySequence){burstSequence=s.emergencySequence;burstCount=0}
                    if(burstCount in 1..2 && now-lastEmergencySentNs<30_000_000L)return@deliver
                    wire=if(burstCount<3)ControlCommand.ESTOP else ControlCommand.STOP
                }
                val command=UnityCommand(wire,if(wire.moving)s.speed else 0,s.revision,++serial,now)
                transport.send(command);last=command;error=null
                if(wire==ControlCommand.ESTOP) {
                    lastEmergencySentNs=now;burstCount++
                    if(started && burstCount<3)worker.schedule({flush()},30,TimeUnit.MILLISECONDS)
                }
            }
        }catch(e:Exception){error="${e.javaClass.simpleName}: ${e.message}";if(authority.snapshot().command.moving)authority.ordinaryStop()}
    }
    fun emergencyBurstComplete(sequence:Long)=burstSequence==sequence && burstCount>=3
    /** UI calls only revoke state and enqueue this barrier. All actual UDP remains on the
     * delivery thread, including old-target STOP; Android forbids network IO on its UI thread.
     * New motion is rejected until all pending changes finish, never cached for later replay.
     */
    fun transition(change:()->Unit) {
        if(closed || closing)return
        authority.beginTransition()
        val task={synchronized(this){
            try{flush();change();flush()}
            catch(e:Exception){error="配置切换失败：${e.message}"}
            finally{authority.endTransition()}
        }}
        if(started)try{worker.execute{task()}}catch(_:RejectedExecutionException){authority.endTransition()}
        else task() // deterministic JVM hook; production starts before exposing controls to UI.
    }
    fun reconfigure(change:()->Unit)=transition(change)
    fun status()=TransportStatus(transport.mode,last,error)
    override fun close() {
        synchronized(this){if(closed || closing)return;closing=true}
        authority.ordinaryStop();authority.onChanged={}
        if(started) {
            // Bounded drain, no dispatcher monitor held while waiting for its worker.
            try{worker.submit{flush()}.get(500,TimeUnit.MILLISECONDS)}catch(e:Exception){error="关闭时STOP发送未确认：${e.javaClass.simpleName}"}
        }else flush()
        synchronized(this){closed=true;worker.shutdownNow();transport.close()}
    }
}
