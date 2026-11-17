package com.yourteam.brainchair.control

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class UnityCommand(val command:ControlCommand,val speed:Int,val revision:Long,val serial:Long,val sentNs:Long) {
    init {require(speed in 0..100);require(command.moving || speed==0)}
}
data class UnityReceipt(val message:UnityCommand,val receivedNs:Long)
interface UnityTransport:AutoCloseable {
    val mode:String
    val ready:Boolean get()=true
    val priorityReady:Boolean get()=ready
    /** Must finish promptly; never queue a stale cached motion in this method. */
    fun send(command:UnityCommand)
    /** Blocking test transports must recheck current after their injected wait and before emit.
     * Production uses a nonblocking IO implementation. An already emitted UDP packet cannot be
     * recalled; the unchanged wire protocol cannot prevent network reordering/replay. */
    fun sendGuarded(command:UnityCommand,current:()->Boolean,priority:Boolean):Boolean {
        if(!current())return false;send(command);return true
    }
    fun abortMotion() {}
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
data class TransportStatus(val mode:String,val sent:UnityCommand?,val error:String?,
    val total:Long=0,val motion:Long=0,val stops:Long=0,val emergencies:Long=0,
    val lastMotionNs:Long?=null,val maxMotionGapMs:Double=0.0,
    val failures:Long=0,val timeouts:Long=0,val canceled:Long=0,val lateCompletions:Long=0,
    val normalInFlightNs:Long?=null,val priorityInFlightNs:Long?=null)
