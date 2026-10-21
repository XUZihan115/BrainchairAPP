package com.yourteam.brainchair.signal

import com.yourteam.brainchair.receiver.*
import org.junit.Test
import org.junit.Assert.*

class AbIntegrationTest {
    private class Rig {
        var now=0L;var nanos=0L
        val session=StreamSession({now},nanoClock={nanos})
        val engine=SignalEngine(session,RecognitionConfig(votes=1,debounceMs=0),clock={now},scoreFunction={_,_->doubleArrayOf(.9,.1,.1,.1)})
        fun active(){session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"A");engine.tick()
            repeat(1000){k->session.accept(Fixtures.block(k+2,k*5L,channels=9),"A");now+=5;if(now%200==0L)engine.tick()}
            assertEquals(Direction.FORWARD,engine.controlSnapshot().output)
        }
    }
    @Test fun stopAuditCoversCallbacksAndClearedFinalOutput() {
        val r=Rig();r.active();r.nanos=1000
        val observer:(String)->Unit={assertEquals(Direction.STOP,r.engine.eventSnapshot().control.output);r.nanos+=20000}
        r.session.addInvalidationListener(observer)
        r.session.accept(Fixtures.stop(1002,5000),"A",500)
        val audit=r.session.snapshot().stopAudits.single()
        assertEquals(1000L,audit.acceptStartedNs);assertEquals(21000L,audit.invalidationCompletedNs)
        assertEquals(.02,audit.processingMs,1e-12);assertEquals(2,audit.listenersNotified)
        assertEquals(.0205,audit.receiveToInvalidationMs!!,1e-12)
        assertEquals(1002L,audit.seq);assertEquals(5000L,audit.nextIndex);assertEquals(1,audit.reason)
        assertEquals(Direction.STOP,r.engine.eventSnapshot().control.output)
        assertEquals(1L,r.session.snapshot().infoPackets);assertEquals(1000L,r.session.snapshot().blockPackets);assertEquals(1L,r.session.snapshot().stopPackets)
        r.session.removeInvalidationListener(observer);r.engine.close()
    }
    @Test fun outputBoundaryChecksFreshnessAndNewStreamStartsStopped() {
        val r=Rig();r.active();val source:ControlOutputSource=r.engine
        assertEquals(Direction.FORWARD,source.currentControl().output)
        r.now+=1001;assertEquals(Direction.STOP,source.currentControl().output)
        r.session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"A");r.engine.tick()
        r.session.accept(Fixtures.block(channels=9),"A");r.engine.tick()
        assertEquals(Direction.STOP,source.currentControl().output);assertEquals(2L,r.session.snapshot().infoPackets)
        r.engine.close()
    }
    @Test fun auditIsBoundedAndCountersSurviveNewSessions() {
        val s=StreamSession()
        repeat(40){s.accept(Fixtures.info(),"A");s.accept(Fixtures.block(),"A");s.accept(Fixtures.stop(3,5),"A")}
        val snap=s.snapshot();assertEquals(32,snap.stopAudits.size);assertEquals(40L,snap.stopPackets)
        assertEquals(40L,snap.infoPackets);assertEquals(40L,snap.blockPackets)
        assertTrue(snap.stopAudits.all{it.invalidationCompletedNs>=it.acceptStartedNs && it.listenersNotified==0})
    }
    @Test fun invalidStopDoesNotBecomeValidStopEvidence() {
        val s=StreamSession();s.accept(Fixtures.info(),"A");s.accept(Fixtures.block(),"A")
        s.accept(Fixtures.stop(3,5).apply{this[20]=(this[20].toInt() xor 1).toByte()},"A")
        assertEquals(0L,s.snapshot().stopPackets);assertTrue(s.snapshot().stopAudits.isEmpty());assertEquals(1L,s.snapshot().rejected["crc"])
    }
    @Test fun receiverRecordsSocketReturnToSynchronousStop() {
        val s=StreamSession();val engine=SignalEngine(s);val udp=UdpReceiver(s,0,0);udp.start()
        java.net.DatagramSocket().use { socket->
            fun send(bytes:ByteArray){socket.send(java.net.DatagramPacket(bytes,bytes.size,java.net.InetAddress.getLoopbackAddress(),udp.boundDataPort))}
            try {
                send(Fixtures.info(names=SignalEngine.CHANNELS));send(Fixtures.block(channels=9));send(Fixtures.stop(3,5))
                val deadline=System.nanoTime()+2_000_000_000L
                while(s.snapshot().stopPackets==0L && System.nanoTime()<deadline)Thread.sleep(5)
                val a=s.snapshot().stopAudits.single();assertNotNull(a.socketReceivedNs)
                assertTrue(a.socketReceivedNs!!<=a.acceptStartedNs);assertTrue(a.receiveToInvalidationMs!!>=a.processingMs)
                assertEquals(1,a.listenersNotified);assertEquals(Direction.STOP,engine.eventSnapshot().control.output)
            }finally{udp.close();engine.close()}
        }
    }
    @Test fun validInfoFromSameIpNewPortResetsOwnershipAndAllOldState() {
        val r=Rig();r.session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"10.0.2.15:10001")
        r.engine.tick()
        repeat(1000){k->r.session.accept(Fixtures.block(k+2,k*5L,channels=9),"10.0.2.15:10001");r.now+=5;if(r.now%200==0L)r.engine.tick()}
        assertEquals(Direction.FORWARD,r.engine.controlSnapshot().output)
        val generation=r.session.snapshot().generation
        r.session.accept(Fixtures.info(names=SignalEngine.CHANNELS),"10.0.2.15:10002")
        assertEquals(Direction.STOP,r.engine.eventSnapshot().control.output)
        assertEquals(0,r.session.snapshot().buffered);assertTrue(r.session.snapshot().generation>generation)
        r.session.accept(Fixtures.block(channels=9),"10.0.2.15:10002");r.engine.tick()
        assertEquals(5L,r.session.snapshot().nextIndex);assertEquals("10.0.2.15:10002",r.session.snapshot().source)
        r.session.accept(Fixtures.stop(1002,5000),"10.0.2.15:10001") // delayed old STOP cannot clear the new stream
        assertEquals("10.0.2.15:10002",r.session.snapshot().source);assertEquals(0L,r.session.snapshot().stopPackets)
        assertEquals(Direction.STOP,r.engine.controlSnapshot().output);r.engine.close()
    }
    @Test fun blockWithoutInfoAndDifferentPeerCannotChangeOwner() {
        val s=StreamSession();s.accept(Fixtures.info(),"10.0.2.15:10001");s.accept(Fixtures.block(),"10.0.2.15:10001")
        s.accept(Fixtures.block(),"10.0.2.15:10002");s.accept(Fixtures.info(),"10.0.2.16:10002")
        val bad=Fixtures.info().apply{this[20]=(this[20].toInt() xor 1).toByte()};s.accept(bad,"10.0.2.15:10002")
        assertEquals("10.0.2.15:10001",s.snapshot().source);assertEquals(1L,s.snapshot().infoPackets)
        assertEquals(2L,s.snapshot().rejected["foreign-source"]);assertEquals(1L,s.snapshot().rejected["crc"])
    }
}
