package com.yourteam.brainchair.control

import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

data class UnityLinkSnapshot(val target:String?,val feedback:UnityState?,val receivedNs:Long?,val feedbackOnline:Boolean,
                             val sentPackets:Long,val validPackets:Long,val rejected:Map<String,Long>,
                             val lastSentHex:String?,val error:String?,val senderThread:String?,val epoch:Long=0)

/** Actual UDP, independent from A's discovery listener. Feedback is monitoring, not a command ACK.
 * Callbacks run OUTSIDE the link lock; neither the receive thread nor the sender may invert locks.
 */
class UnityUdpLink(ip:String="",private val controlPort:Int=UnitySum8Codec.CONTROL_PORT,
                   private val feedbackPort:Int=UnitySum8Codec.STATUS_PORT,
                   private val nanos:()->Long={System.nanoTime()}):UnityTransport {
    override val mode="UDP_SUM8_9001_STATUS_9002"
    override val ready:Boolean get()=destination!=null && tx.isOpen && !txDisabled
    override val priorityReady:Boolean get()=destination!=null && urgentTx.isOpen
    private val lock=Any()
    private val tx=DatagramChannel.open().apply{configureBlocking(false)}
    private val urgentTx=DatagramChannel.open().apply{configureBlocking(false)}
    private data class Target(val address:InetAddress,val epoch:Long)
    @Volatile private var destination=if(ip.isBlank())null else Target(UnitySum8Codec.ipv4(ip),0)
    @Volatile private var txDisabled=false
    private var rx:DatagramSocket?=null
    private var target:InetAddress?=if(ip.isBlank())null else UnitySum8Codec.ipv4(ip)
    private var state:UnityState?=null
    private var lastNs:Long?=null
    private var sent=0L;private var valid=0L;private var lastHex:String?=null
    private var error:String?=null
    private var senderThread:String?=null
    private val rejected=linkedMapOf<String,Long>()
    @Volatile private var running=false
    @Volatile var onState:(UnityState,Boolean)->Unit={_,_->}
    @Volatile var onFeedback:(UnityState,Boolean,Long,Long)->Unit={_,_,_,_->}
    private var epoch=0L
    var boundFeedbackPort:Int=0;private set
    fun start() {
        synchronized(lock){
            if(running)return
            val socket=DatagramSocket(null)
            try{socket.soTimeout=200;socket.bind(InetSocketAddress(feedbackPort))}catch(e:Exception){socket.close();throw e}
            rx=socket
            boundFeedbackPort=rx!!.localPort;running=true
        }
        Thread({receiveLoop()},"unity-status-udp").apply{isDaemon=true;start()}
    }
    fun configure(ip:String) {
        val address=if(ip.isBlank())null else UnitySum8Codec.ipv4(ip)
        synchronized(lock){target=address;epoch++;destination=address?.let{Target(it,epoch)};state=null;lastNs=null;error=null}
    }
    override fun send(command:UnityCommand) {check(sendGuarded(command,{true},!command.command.moving))}
    override fun sendGuarded(command:UnityCommand,current:()->Boolean,priority:Boolean):Boolean {
        val bytes=UnitySum8Codec.encode(command)
        val endpoint=destination ?: throw IllegalStateException("未配置Unity IP，未发送")
        if(!current() || destination!==endpoint)return false
        val channel=if(priority)urgentTx else tx
        check(priority || !txDisabled){"普通发送通道已故障锁定"}
        // No link/arbitration lock is held over native IO. Nonblocking send returns 0 if full.
        val count=channel.send(ByteBuffer.wrap(bytes),InetSocketAddress(endpoint.address,controlPort))
        check(count==bytes.size){"非阻塞UDP缓冲区不可写"}
        synchronized(lock){
            sent++;lastHex=UnitySum8Codec.hex(bytes);error=null;senderThread=Thread.currentThread().name
        }
        return true
    }
    override fun abortMotion(){txDisabled=true;tx.close()}
    private fun reject(reason:String)=synchronized(lock){rejected[reason]=(rejected[reason]?:0)+1}
    private fun receiveLoop() {
        val packet=DatagramPacket(ByteArray(65535),65535)
        while(running)try {
            packet.length=packet.data.size;rx?.receive(packet) ?: break
            val now=nanos();val bytes=packet.data.copyOf(packet.length)
            val parsed=try{UnitySum8Codec.state(bytes)}catch(e:UnityFrameError){reject(e.reason);continue}
            var restart=false;var acceptedEpoch=0L
            val accepted=synchronized(lock){
                if(target==null || packet.address!=target){rejected["foreign-source"]=(rejected["foreign-source"]?:0)+1;false}
                else {
                    val previous=state
                    val delta=previous?.let{(parsed.timestampMs-it.timestampMs) and 0xffffffffL}
                    val fresh=lastNs?.let{now-it in 0 until SafetyTiming.FEEDBACK_NS}==true
                    when {
                        delta==0L ->{rejected["duplicate"]=(rejected["duplicate"]?:0)+1;false}
                        delta!=null && delta>0x7fffffffL && fresh ->{rejected["late-status"]=(rejected["late-status"]?:0)+1;false}
                        else->{restart=delta!=null && delta>0x7fffffffL;state=parsed;lastNs=now;valid++;acceptedEpoch=epoch;error=null;true}
                    }
                }
            }
            if(accepted){onFeedback(parsed,restart,now,acceptedEpoch);onState(parsed,restart)}
        }catch(_:SocketTimeoutException){/* monitoring timeout computed when read; invalid frames never renew it */}
        catch(e:Exception){if(running)synchronized(lock){error="状态接收异常：${e.message}"}}
    }
    fun snapshot():UnityLinkSnapshot=synchronized(lock){UnityLinkSnapshot(target?.hostAddress,state,lastNs,
        lastNs?.let{nanos()-it in 0 until SafetyTiming.FEEDBACK_NS}==true,sent,valid,rejected.toMap(),lastHex,error,senderThread,epoch)}
    override fun close(){running=false;rx?.close();tx.close();urgentTx.close()}
}
