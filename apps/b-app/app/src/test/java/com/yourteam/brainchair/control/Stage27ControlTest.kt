package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class Stage27ControlTest {
    private var now=0L
    private fun vote(d:Direction,g:Long=1)=RecognitionSnapshot(raw=d,stable=d,generation=g,
        control=ControlSnapshot(output=d,reason="test reliable vote",expiresMs=now+1000))
    @Test fun sixCommandsMapToLatestDraftWithoutLegacyEstopCollision() {
        assertEquals(listOf(1,2,3,4,5,6),ControlCommand.values().map{it.code})
        assertEquals(ControlCommand.LEFT,ControlCommand.from(Direction.LEFT))
        assertEquals(ControlCommand.BACKWARD,ControlCommand.from(Direction.BACKWARD))
        val a=ControlAuthority({now});val r=SimulatedUnityReceiver();val t=ControlDispatcher(a,SimulatedUnityTransport(r))
        try {
            a.boundary(1,1,"new")
            listOf(Direction.FORWARD,Direction.BACKWARD,Direction.LEFT,Direction.RIGHT).forEach { d->
                a.recognition(vote(d));t.flush();assertEquals(ControlCommand.from(d),r.current())
                assertEquals(30,r.receipts().last().message.speed)
            }
            a.ordinaryStop();t.flush();assertEquals(ControlCommand.STOP,r.current());assertEquals(0,r.receipts().last().message.speed)
            a.emergencyStop();t.flush();assertEquals(ControlCommand.ESTOP,r.current());assertEquals(6,r.receipts().last().message.command.code)
        }finally{t.close()}
    }
    @Test fun queuedWakeupsReadCurrentStateAndCannotOverwriteStop() {
        val a=ControlAuthority({now});val r=SimulatedUnityReceiver();val t=ControlDispatcher(a,SimulatedUnityTransport(r))
        try {
            a.boundary(1,1,"start");a.recognition(vote(Direction.FORWARD)) // pending motion, not delivered
            a.boundary(2,1,"STREAM_STOP");t.flush()
            a.recognition(vote(Direction.FORWARD,1));t.flush() // delayed old calculation
            assertTrue(r.receipts().all{it.message.command==ControlCommand.STOP})
            assertEquals(ControlCommand.STOP,a.snapshot().command)
        }finally{t.close()}
    }
    @Test fun ordinaryStopAndExpiryRequireFreshMotionAndEmergencyRequiresExplicitLiveUnlock() {
        val a=ControlAuthority({now},{now*1_000_000});val r=SimulatedUnityReceiver{now*1_000_000}
        val t=ControlDispatcher(a,SimulatedUnityTransport(r),{now*1_000_000})
        try {
            a.boundary(1,1,"start");a.recognition(vote(Direction.RIGHT));t.flush()
            now=1001;t.flush();assertEquals(ControlCommand.STOP,r.current())
            a.ordinaryStop();a.recognition(vote(Direction.FORWARD));t.flush();assertEquals(ControlCommand.STOP,r.current())
            a.boundary(2,2,"new flow");a.recognition(vote(Direction.FORWARD,2));t.flush();assertEquals(ControlCommand.FORWARD,r.current())
            a.emergencyStop();t.flush()
            a.boundary(3,3,"new flow");a.ordinaryStop();a.recognition(vote(Direction.LEFT,3));t.flush()
            assertEquals(ControlCommand.ESTOP,r.current());assertTrue(a.snapshot().emergencyLocked)
            assertFalse(a.releaseEmergency(true,false));assertFalse(a.releaseEmergency(false,true))
            assertTrue(a.releaseEmergency(true,true,r::releaseEmergency));t.flush()
            a.recognition(vote(Direction.LEFT,3));t.flush();assertEquals(ControlCommand.STOP,r.current())
            a.boundary(4,4,"next flow");a.recognition(vote(Direction.LEFT,4));t.flush();assertEquals(ControlCommand.LEFT,r.current())
        }finally{t.close()}
    }
    @Test fun simulatedReceiverWatchdogAndLatchedRestartRemainDistinctFromActualUnity() {
        var n=0L;val receiver=SimulatedUnityReceiver{n}
        receiver.receive(UnityCommand(ControlCommand.FORWARD,30,1,1,n));assertEquals(ControlCommand.FORWARD,receiver.current())
        n=1_000_000_000;assertEquals(ControlCommand.STOP,receiver.current())
        val a=ControlAuthority(emergencyInitiallyLocked=true)
        a.boundary(1,1,"new stream");a.recognition(vote(Direction.FORWARD));a.ordinaryStop()
        assertEquals(ControlCommand.ESTOP,a.snapshot().command)
    }
    @Test fun unconfirmedCodecRefusesAnyUdpAndNeverFallsBackToPrototypeFrame() {
        val c=UnityCommand(ControlCommand.ESTOP,0,1,1,0)
        assertFalse(PendingUnityCodec.confirmed)
        assertThrows(IllegalStateException::class.java){PendingUnityCodec.encode(c)}
        assertThrows(IllegalArgumentException::class.java){UdpUnityTransport(InetSocketAddress("127.0.0.1",9001),PendingUnityCodec)}
        assertEquals("SIMULATED_IN_PROCESS_NO_UDP",SimulatedUnityTransport(SimulatedUnityReceiver()).mode)
    }
    @Test fun emergencyReleasePreservesHalfSecondFreshLinkRequirement() {
        var now=0L;val receiver=SimulatedUnityReceiver{now}
        receiver.receive(UnityCommand(ControlCommand.ESTOP,0,1,1,now))
        now=600_000_000L
        assertTrue(receiver.fresh()) // 1s command watchdog has not expired
        assertFalse(receiver.releaseEmergency()) // unlock requires the stricter existing 500ms rule
        assertEquals(ControlCommand.ESTOP,receiver.current())
        receiver.receive(UnityCommand(ControlCommand.ESTOP,0,1,2,now))
        assertTrue(receiver.releaseEmergency());assertEquals(ControlCommand.STOP,receiver.current())
    }
}
