package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/** Known-frequency synthetic fixture for transport/safety tests only. Broadband channel noise
 * avoids the short-window ill-conditioning of the older few-nuisance-tone fixture. */
internal object Stage3Signals {
    fun noise(seq:Long,first:Long,origin:Long):ByteArray {
        val b=Fixtures.le(20+5*9*4).putLong(first).putLong(origin+first*1000).putShort(5).putShort(9)
        repeat(5){row->repeat(9){c->
            var h=((first+row)*1597334677L+c*3812015801L+71).toInt();h=(h xor (h ushr 13))*1274126177;h=h xor (h ushr 16)
            b.putFloat((((h.toLong() and 0xffffffffL)/4294967296.0-.5)*200).toFloat())
        }}
        return Fixtures.packet(2,seq.toInt(),b.array())
    }
    fun wave(seq:Long,first:Long,origin:Long,f:Double=12.0):ByteArray {
        val names=Stage27IntegrationTest.names
        val b=Fixtures.le(20+5*names.size*4).putLong(first).putLong(origin+first*1000).putShort(5).putShort(names.size.toShort())
        repeat(5){row->val n=first+row;val t=n/1000.0
            repeat(names.size){c->
                var h=(n*374761393L+c*668265263L+17).toInt();h=(h xor (h ushr 13))*1274126177;h=h xor (h ushr 16)
                val noise=(h.toLong() and 0xffffffffL)/4294967296.0-.5
                val y=5*sin(2*PI*f*t+c*.37)+2*cos(4*PI*f*t+c*.17)+sin(6*PI*f*t+c*.23)+noise
                b.putFloat(y.toFloat())
            }}
        return Fixtures.packet(2,seq.toInt(),b.array())
    }
}

/** Independent Unity wire peer. Does not call the production command encoder/status decoder. */
internal class Stage3Peer(port:Int=0):AutoCloseable {
    data class Receipt(val code:Int,val speed:Int?,val ns:Long,val hex:String)
    val socket=DatagramSocket(port,InetAddress.getByName("127.0.0.1")).apply{soTimeout=50}
    val port get()=socket.localPort
    val records=CopyOnWriteArrayList<Receipt>()
    @Volatile var feedbackPort=0
    @Volatile var feedbackEnabled=true
    @Volatile var fault=false
    @Volatile private var running=true
    @Volatile private var latched=false
    @Volatile private var action=5
    @Volatile private var speed=30
    @Volatile private var lastMotion=0L
    private val start=System.nanoTime()
    private val thread=Thread({loop()},"independent-unity-test-peer").apply{isDaemon=true;start()}
    val applied:Int get(){if(action in 1..4 && System.nanoTime()-lastMotion>=1_000_000_000L)action=5;return if(latched)6 else action}
    fun z(){latched=false;action=5}
    fun send(bytes:ByteArray){socket.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),feedbackPort))}
    private fun loop() {
        var statusDue=0L
        val p=DatagramPacket(ByteArray(2048),2048)
        while(running) {
            try {
                p.length=p.data.size;socket.receive(p)
                val b=p.data.copyOf(p.length);fun u(i:Int)=b[i].toInt() and 255
                check(b.size>=8 && u(0)==170 && u(1)==85 && u(2)==1 && u(3) in 1..2 && b.size==u(3)+7)
                check(u(b.size-2)==13 && u(b.size-1)==10)
                var sum=0;for(i in 2 until b.size-3)sum+=u(i)
                check((sum and 255)==u(b.size-3));val code=u(4);check(code in 1..6)
                val level=if(u(3)==2)u(5) else null;check(level==null || level in 0..100)
                records.add(Receipt(code,level,System.nanoTime(),b.joinToString(" "){"%02X".format(it.toInt() and 255)}))
                if(code==6){latched=true;action=6}
                else if(!latched){action=code;if(level!=null && level>0)speed=level;if(code in 1..4)lastMotion=System.nanoTime()}
            }catch(_:SocketTimeoutException){}catch(e:Exception){if(running)throw AssertionError("invalid B wire frame",e)}
            if(feedbackEnabled && feedbackPort>0 && System.nanoTime()>=statusDue) {
                val a=applied
                send(status(flags=(if(latched)1 else 0) or (if(fault)2 else 0) or (if(a in 1..4)8 else 0),
                    action=a,linear=if(a in 1..2)100 else 0,angular=if(a in 3..4)100 else 0,
                    speed=speed,time=(System.nanoTime()-start)/1_000_000))
                statusDue=System.nanoTime()+200_000_000L
            }
        }
    }
    override fun close(){running=false;socket.close();thread.join(1000)}
    companion object {
        fun status(flags:Int=0,action:Int=5,linear:Int=0,angular:Int=0,x:Int=0,z:Int=0,speed:Int=30,time:Long=1):ByteArray {
            val b=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            b.put(170.toByte()).put(85).put(3).put(15).put(flags.toByte()).put(action.toByte())
                .putShort(linear.toShort()).putShort(angular.toShort()).putShort(x.toShort()).putShort(z.toShort()).put(speed.toByte()).putInt(time.toInt())
            var checksum=0;for(i in 2..18)checksum+=b.array()[i].toInt() and 255
            b.put((checksum and 255).toByte()).put(13).put(10)
            return b.array()
        }
        fun await(label:String,timeoutMs:Long=2500,condition:()->Boolean) {
            val end=System.nanoTime()+timeoutMs*1_000_000
            while(!condition() && System.nanoTime()<end)Thread.sleep(2)
            assertTrue(label,condition())
        }
        fun evidence(name:String,text:String) {
            val dir=File(requireNotNull(System.getProperty("brainchair.stage3.evidence")));dir.mkdirs();File(dir,name).writeText(text)
        }
    }
}

/** Virtual EEG time, actual filter/CCA and actual outbound UDP. Labels never enter the runtime. */
internal class Stage3Rig(score:(Cca,Array<DoubleArray>)->DoubleArray={c,x->c.scores(x)}):AutoCloseable {
    var now=0L;var origin=10_000_000L;var first=0L;var seq=2L;var source="127.0.0.1:31000"
    val peer=Stage3Peer()
    val link=UnityUdpLink("127.0.0.1",peer.port,0)
    val session=StreamSession({now})
    val runtime=ControlRuntime(session,clock={now},scoreFunction=score,unityLink=link)
    init{link.start();peer.feedbackPort=link.boundFeedbackPort}
    fun info(port:Int?=null){if(port!=null)source="127.0.0.1:$port";origin+=10_000_000;first=0;seq=2
        session.accept(Fixtures.info(names=Stage27IntegrationTest.names),source);runtime.engine.tick();flush()}
    fun feed(ms:Int,f:Double=12.0,tick:Boolean=true) {
        repeat(ms/5){session.accept(Stage3Signals.wave(seq++,first,origin,f),source);first+=5;now+=5
            if(tick && first%250==0L)runtime.engine.tick()}
        flush()
    }
    fun stop(){session.accept(Fixtures.stop(seq.toInt(),first),source);flush()}
    fun flush() {
        val before=link.snapshot().sentPackets;runtime.dispatcher.flush()
        if(link.snapshot().sentPackets>before)Stage3Peer.await("peer receives real UDP"){peer.records.size.toLong()>=link.snapshot().sentPackets}
    }
    fun wire()=peer.records.last().code
    override fun close(){runtime.close();peer.close()}
}
