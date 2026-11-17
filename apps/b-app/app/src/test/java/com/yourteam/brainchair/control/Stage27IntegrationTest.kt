package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Packet construction is independent of the production decoder. No class/label is on wire.
 * Real B filter/CCA/gates are used throughout, including the blocked-computation race test.
 */
class Stage27IntegrationTest {
    companion object {
        val names=listOf("O2","P2","PO4","Pz","O1","Oz","P1","POz","PO3")
        fun wave(seq:Long,first:Long,origin:Long,f:Double=12.0):ByteArray {
            val b=ByteBuffer.allocate(20+5*names.size*4).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(first).putLong(origin+first*1000).putShort(5).putShort(names.size.toShort())
            repeat(5){row->val t=(first+row)/1000.0
                repeat(names.size){c->val y=5*sin(2*PI*f*t+c*.37)+2*cos(4*PI*f*t+c*.17)+sin(6*PI*f*t+c*.23)+.25*sin(2*PI*(17.19+c*1.71)*t+c)
                    b.putFloat(y.toFloat())}}
            return Fixtures.packet(2,seq.toInt(),b.array())
        }
        fun evidence(name:String,text:String) {
            val dir=File(System.getProperty("brainchair.stage27.evidence")?:"build/stage27-evidence");dir.mkdirs()
            File(dir,name).writeText(text)
        }
    }
    private class Rig(score:(Cca,Array<DoubleArray>)->DoubleArray={c,x->c.scores(x)}):AutoCloseable {
        var now=0L;var origin=10_000_000L;var first=0L;var seq=2L;var source="127.0.0.1:31000"
        val s=StreamSession({now},nanoClock={now*1_000_000})
        val runtime=ControlRuntime(s,clock={now},nanos={now*1_000_000},scoreFunction=score)
        val e get()=runtime.engine
        fun info(port:Int?=null) {
            if(port!=null)source="127.0.0.1:$port"
            origin+=10_000_000;first=0;seq=2
            s.accept(Fixtures.info(names=names),source);e.tick();runtime.dispatcher.flush()
        }
        fun feed(ms:Int,f:Double=12.0,tick:Boolean=true) {
            repeat(ms/5){s.accept(wave(seq++,first,origin,f),source);first+=5;now+=5
                if(tick && now%200==0L)e.tick()}
            runtime.dispatcher.flush()
        }
        fun stop(){s.accept(Fixtures.stop(seq.toInt(),first),source);runtime.dispatcher.flush()}
        fun command()=runtime.authority.snapshot().command
        override fun close()=runtime.close()
    }
    @Test fun fourSecondClickHasNoCompleteAnalysisOrMotionAndStopClearsEveryLayer() {
        Rig().use { r->
            r.info();r.feed(4000);assertEquals(4000L,r.s.snapshot().nextIndex)
            assertEquals(0L,r.e.eventSnapshot().analysisCount);assertEquals(ControlCommand.STOP,r.command())
            r.stop();assertNull(r.s.health().info);assertNull(r.s.window());assertEquals(0,r.s.snapshot().buffered)
            assertEquals(Direction.STOP,r.e.eventSnapshot().raw);assertEquals(Direction.STOP,r.e.eventSnapshot().stable)
            assertEquals(0L,r.e.eventSnapshot().windowEnd);assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
            assertTrue(r.runtime.simulatedUnity.receipts().all{!it.message.command.moving})
        }
    }
    @Test fun rapidClicksNeverSpliceDifferentFlowWindowsAndRestartBeginsEmpty() {
        Rig().use { r->
            r.info(31001);r.feed(1500,12.0)
            r.info(31002);assertEquals(0,r.s.snapshot().buffered);r.feed(1000,8.57)
            r.info(31003);r.feed(700,6.67)
            r.info(31004);r.feed(4000,5.45);r.stop()
            r.s.accept(Fixtures.stop(r.seq.toInt(),r.first),r.source) // duplicate STOP
            r.info(31005);r.feed(4000,8.57);r.stop()
            assertEquals(0L,r.e.eventSnapshot().analysisCount)
            assertTrue(r.runtime.simulatedUnity.receipts().all{it.message.command==ControlCommand.STOP})
            assertEquals(5L,r.s.snapshot().streamId);assertEquals(2L,r.s.snapshot().stopPackets)
        }
    }
    @Test fun retiredEndpointAndTimestampOriginCannotRefillNewFlow() {
        Rig().use { r->
            r.info(32001);val oldOrigin=r.origin;r.feed(2000)
            r.info(32002)
            r.s.accept(Fixtures.info(names=names),"127.0.0.1:32001")
            r.s.accept(wave(402,2000,oldOrigin),"127.0.0.1:32001")
            r.s.accept(Fixtures.stop(403,2005),"127.0.0.1:32001")
            assertEquals(0L,r.s.snapshot().nextIndex);assertEquals("127.0.0.1:32002",r.s.snapshot().source)
            r.feed(1000);val oldSameEndpointOrigin=r.origin;r.stop();r.info()
            r.s.accept(wave(2,0,oldSameEndpointOrigin),r.source) // old first block arrives before current first block
            assertEquals(0,r.s.snapshot().buffered);assertEquals(1L,r.s.snapshot().rejected["retired-origin"])
            r.feed(4000,8.57);assertEquals(0L,r.e.eventSnapshot().analysisCount)
            assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
        }
    }
    @Test fun firstBlockMustProveStreamStartAndLateIdenticalInfoSafelyResets() {
        Rig().use { r->
            r.info();r.s.accept(wave(10,40,r.origin),r.source);r.e.tick();r.runtime.dispatcher.flush()
            assertEquals(0,r.s.snapshot().buffered);assertEquals(ControlCommand.STOP,r.command())
            r.info();r.feed(1200)
            r.s.accept(Fixtures.info(names=names),r.source) // wire cannot identify which INFO this is
            r.s.accept(wave(2,0,r.origin),r.source)
            assertEquals(0,r.s.snapshot().buffered);assertEquals(ControlCommand.STOP,r.command())
        }
    }
    @Test fun crcGapLatePacketAndTimeoutRevokeActualDeliveredMotion() {
        Rig().use { r->
            r.info();r.feed(5400);assertEquals(ControlCommand.FORWARD,r.runtime.simulatedUnity.current())
            val bad=wave(r.seq,r.first,r.origin).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
            r.s.accept(bad,r.source);r.runtime.dispatcher.flush();assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
            assertEquals(Direction.STOP,r.e.eventSnapshot().stable)
            r.info();r.feed(5400);assertEquals(ControlCommand.FORWARD,r.command())
            r.s.accept(wave(r.seq+1,r.first+5,r.origin),r.source);r.runtime.dispatcher.flush()
            assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current());assertEquals(1L,r.s.snapshot().missingPackets)
            val buffered=r.s.snapshot().buffered
            r.s.accept(wave(r.seq-1,r.first-5,r.origin),r.source)
            assertEquals(buffered,r.s.snapshot().buffered);assertTrue(r.s.snapshot().outOfOrder+r.s.snapshot().duplicates>0)
            r.info();r.feed(5400);assertEquals(ControlCommand.FORWARD,r.command())
            r.now+=1001;r.e.guard();r.runtime.dispatcher.flush()
            assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current());assertNull(r.s.health().info)
            r.info();r.feed(4000);r.stop();assertEquals(ControlCommand.STOP,r.command())
        }
    }
    @Test fun stopPreemptsInFlightActualCcaAndItsLaterResultCannotPublishMotion() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val block=AtomicBoolean(false)
        Rig { c,x->if(block.get()){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};c.scores(x) }.use { r->
            r.info();r.feed(5400);assertEquals(ControlCommand.FORWARD,r.command())
            r.feed(200,tick=false);block.set(true)
            val thread=Thread{r.e.tick()};thread.start()
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));r.stop()
                val stopSerial=r.runtime.simulatedUnity.receipts().last().message.serial
                assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
                release.countDown();thread.join(3000);assertFalse(thread.isAlive)
                repeat(3){r.runtime.dispatcher.flush()}
                assertTrue(r.runtime.simulatedUnity.receipts().filter{it.message.serial>=stopSerial}.all{it.message.command==ControlCommand.STOP})
                assertEquals(Direction.STOP,r.e.eventSnapshot().stable)
            }finally{release.countDown();thread.join(3000)}
        }
    }
    @Test fun newInfoPreemptsInFlightCcaAndNoOldVoteReappears() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val block=AtomicBoolean(false)
        Rig { c,x->if(block.get()){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};c.scores(x) }.use { r->
            r.info();r.feed(5400);r.feed(200,tick=false);block.set(true)
            val thread=Thread{r.e.tick()};thread.start()
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                r.s.accept(Fixtures.info(names=names),"127.0.0.1:33001");r.runtime.dispatcher.flush()
                release.countDown();thread.join(3000);r.runtime.dispatcher.flush()
                assertFalse(thread.isAlive);assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
                assertEquals(0,r.s.snapshot().buffered);assertEquals(Direction.STOP,r.e.eventSnapshot().stable)
            }finally{release.countDown();thread.join(3000)}
        }
    }
    @Test fun emergencyPersistsAcrossStopRapidNewFlowsAndManualReleaseNeedsNewStream() {
        Rig().use { r->
            r.info();r.feed(5400);assertEquals(ControlCommand.FORWARD,r.command())
            r.runtime.emergencyStop();r.runtime.dispatcher.flush();r.stop();r.info(34001);r.feed(4000,8.57)
            r.runtime.ordinaryStop();r.runtime.dispatcher.flush()
            assertEquals(ControlCommand.ESTOP,r.runtime.simulatedUnity.current());assertTrue(r.runtime.authority.snapshot().emergencyLocked)
            assertTrue(r.runtime.releaseEmergency());r.runtime.dispatcher.flush()
            r.feed(1400,8.57);assertEquals(ControlCommand.STOP,r.runtime.simulatedUnity.current())
            r.info(34002);r.feed(5400,8.57);assertEquals(ControlCommand.LEFT,r.runtime.simulatedUnity.current())
        }
    }
    @Test fun actualFourSecondUdpTimelineAndUnmodifiedLongDiagnostic() {
        val session=StreamSession();val runtime=ControlRuntime(session);val udp=UdpReceiver(session,0,0)
        val timeline=StringBuilder("scenario,event,offsetMs,command,detail\n")
        fun waitFor(label:String,predicate:()->Boolean) {
            val deadline=System.nanoTime()+2_000_000_000
            while(!predicate() && System.nanoTime()<deadline)Thread.sleep(2)
            assertTrue(label,predicate())
        }
        udp.start();runtime.start()
        try {
            DatagramSocket().use { sender->
                fun send(p:ByteArray){sender.send(DatagramPacket(p,p.size,InetAddress.getLoopbackAddress(),udp.boundDataPort))}
                // 4s real wall-clock, 200 packets/s. All three endpoints share System.nanoTime.
                val origin=1_800_000_000_000_000L
                val packets=List(800){wave(it+2L,it*5L,origin)}
                val start=System.nanoTime();send(Fixtures.info(names=names))
                timeline.append("fourSeconds,INFO,0,STOP,4s window plus unchanged 0.5s warmup\n")
                for((k,p) in packets.withIndex()) {
                    val due=start+k*5_000_000L
                    while(System.nanoTime()<due)TimeUnit.NANOSECONDS.sleep(minOf(1_000_000L,due-System.nanoTime()).coerceAtLeast(1))
                    send(p)
                }
                while(System.nanoTime()<start+4_000_000_000)Thread.sleep(0,500000)
                val stopSent=System.nanoTime();val beforeSerial=runtime.simulatedUnity.receipts().lastOrNull()?.message?.serial?:0
                send(Fixtures.stop(802,4000))
                waitFor("B accepts STOP"){session.snapshot().stopPackets==1L}
                val audit=session.snapshot().stopAudits.single()
                val stopRevision=runtime.authority.snapshot().revision
                waitFor("mock receives STOP after invalidation"){runtime.simulatedUnity.receipts().any{it.message.serial>beforeSerial && it.message.revision>=stopRevision && it.message.sentNs>=stopSent}}
                val receipt=runtime.simulatedUnity.receipts().first{it.message.serial>beforeSerial && it.message.revision>=stopRevision && it.message.sentNs>=stopSent}
                val stopToSend=(receipt.message.sentNs-stopSent)/1e6;val sendToReceive=(receipt.receivedNs-receipt.message.sentNs)/1e6
                timeline.append("fourSeconds,source STOP sent,${(stopSent-start)/1e6},STOP,800 SAMPLE_BLOCK\n")
                timeline.append("fourSeconds,B stop revoked,${(audit.invalidationCompletedNs-start)/1e6},STOP,${audit.receiveToInvalidationMs} ms socket to revoke\n")
                timeline.append("fourSeconds,B mock send,${(receipt.message.sentNs-start)/1e6},STOP,$stopToSend ms source STOP to B send\n")
                timeline.append("fourSeconds,mock receive,${(receipt.receivedNs-start)/1e6},STOP,$sendToReceive ms mock send to receive; NO B-C UDP\n")
                assertEquals(800L,session.snapshot().blockPackets);assertEquals(0L,runtime.engine.eventSnapshot().analysisCount)
                assertTrue(runtime.simulatedUnity.receipts().all{!it.message.command.moving})
                assertTrue(stopToSend<1000)
            }
            // Separate, explicitly longer diagnostic. It does not change the 4s click rule.
            DatagramSocket().use { sender->
                fun send(p:ByteArray){sender.send(DatagramPacket(p,p.size,InetAddress.getLoopbackAddress(),udp.boundDataPort))}
                val start=System.nanoTime();val origin=1_800_000_010_000_000L;var rawNs:Long?=null;var stableNs:Long?=null
                send(Fixtures.info(names=names))
                repeat(1200){k->
                    val due=start+k*5_000_000L
                    while(System.nanoTime()<due)TimeUnit.NANOSECONDS.sleep(minOf(1_000_000L,due-System.nanoTime()).coerceAtLeast(1))
                    send(wave(k+2L,k*5L,origin))
                    val snapshot=runtime.engine.eventSnapshot()
                    if(snapshot.raw==Direction.FORWARD && rawNs==null)rawNs=System.nanoTime()
                    if(runtime.simulatedUnity.current()==ControlCommand.FORWARD && stableNs==null)stableNs=System.nanoTime()
                }
                assertNotNull("real CCA finds forward in separate 6s diagnostic",rawNs)
                assertNotNull("default votes/debounce produce forwarded motion after >4s",stableNs)
                timeline.append("sixSecondDiagnostic,first gated raw,${(rawNs!!-start)/1e6},FORWARD,actual causal filter + CCA\n")
                timeline.append("sixSecondDiagnostic,first mock received stable motion,${(stableNs!!-start)/1e6},FORWARD,not a 4s click\n")
                assertTrue(rawNs!!-start>=4_000_000_000);assertTrue(stableNs!!>=rawNs!!)
                send(Fixtures.stop(1202,6000));waitFor("diagnostic final STOP"){session.snapshot().stopPackets==2L}
            }
        }finally{evidence("udp-timeline.csv",timeline.toString());udp.close();runtime.close()}
    }
}
