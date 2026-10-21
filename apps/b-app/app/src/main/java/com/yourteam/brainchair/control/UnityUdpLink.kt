package com.yourteam.brainchair.control

import java.net.*

data class UnityLinkSnapshot(val target:String?,val feedback:UnityState?,val receivedNs:Long?,val feedbackOnline:Boolean,
                             val sentPackets:Long,val validPackets:Long,val rejected:Map<String,Long>,
                             val lastSentHex:String?,val error:String?,val senderThread:String?)

/** Actual UDP, independent from A's discovery listener. Feedback is monitoring, not a command ACK.
 * Callbacks run OUTSIDE the link lock; neither the receive thread nor the sender may invert locks.
 */
class UnityUdpLink(ip:String="",private val controlPort:Int=UnitySum8Codec.CONTROL_PORT,
                   private val feedbackPort:Int=UnitySum8Codec.STATUS_PORT,
                   private val nanos:()->Long={System.nanoTime()}):UnityTransport {
    override val mode="UDP_SUM8_9001_STATUS_9002"
    override val ready:Boolean get()=synchronized(lock){target!=null && !tx.isClosed}
    private val lock=Any()
    private val tx=DatagramSocket()
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
        synchronized(lock){target=address;state=null;lastNs=null;error=null}
    }
    override fun send(command:UnityCommand) {
        val bytes=UnitySum8Codec.encode(command)
        synchronized(lock){
            val address=target ?: throw IllegalStateException("未配置Unity IP，未发送")
            tx.send(DatagramPacket(bytes,bytes.size,address,controlPort));sent++;lastHex=UnitySum8Codec.hex(bytes);error=null;senderThread=Thread.currentThread().name
        }
    }
    private fun reject(reason:String)=synchronized(lock){rejected[reason]=(rejected[reason]?:0)+1}
    private fun receiveLoop() {
        val packet=DatagramPacket(ByteArray(65535),65535)
        while(running)try {
            packet.length=packet.data.size;rx?.receive(packet) ?: break
            val now=nanos();val bytes=packet.data.copyOf(packet.length)
            val parsed=try{UnitySum8Codec.state(bytes)}catch(e:UnityFrameError){reject(e.reason);continue}
            var restart=false
            val accepted=synchronized(lock){
                if(target==null || packet.address!=target){rejected["foreign-source"]=(rejected["foreign-source"]?:0)+1;false}
                else {
                    val previous=state
                    val delta=previous?.let{(parsed.timestampMs-it.timestampMs) and 0xffffffffL}
                    val fresh=lastNs?.let{now-it<1_000_000_000L}==true
                    when {
                        delta==0L ->{rejected["duplicate"]=(rejected["duplicate"]?:0)+1;false}
                        delta!=null && delta>0x7fffffffL && fresh ->{rejected["late-status"]=(rejected["late-status"]?:0)+1;false}
                        else->{restart=delta!=null && delta>0x7fffffffL;state=parsed;lastNs=now;valid++;error=null;true}
                    }
                }
            }
            if(accepted)onState(parsed,restart)
        }catch(_:SocketTimeoutException){/* monitoring timeout computed when read; invalid frames never renew it */}
        catch(e:Exception){if(running)synchronized(lock){error="状态接收异常：${e.message}"}}
    }
    fun snapshot():UnityLinkSnapshot=synchronized(lock){UnityLinkSnapshot(target?.hostAddress,state,lastNs,
        lastNs?.let{nanos()-it in 0 until 1_000_000_000L}==true,sent,valid,rejected.toMap(),lastHex,error,senderThread)}
    override fun close(){running=false;rx?.close();tx.close()}
}
