package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test

class EegFeedbackRecoveryTest {
    @Test fun stopWithResidualLinearOrAngularMotionIsNormalDecelerationNotAFault(){
        for(strict in listOf(false,true))for((linear,angular) in listOf(120 to 0,0 to -90,60 to 20)){
            val a=ControlAuthority(feedbackRequired=strict)
            a.ordinaryStop()
            a.feedback(UnityState(8,5,linear,angular,0,0,30,100),System.nanoTime())
            assertTrue(a.snapshot().feedbackHealthy)
            assertFalse(a.snapshot().feedbackStationary)
            assertFalse("STOP deceleration is allowed by the existing Unity interface",a.snapshot().feedbackRecoveryRequired)
            assertEquals(ControlCommand.STOP,a.snapshot().command)
        }
    }
    @Test fun verifiedZReleaseClearsItsFeedbackLatchButNeverReplaysOldEegMotion(){
        var now=0L
        val a=ControlAuthority({now},{now*1_000_000},emergencyInitiallyLocked=true,feedbackRequired=true)
        a.boundary(1,1,"INFO")
        a.feedback(UnityState(1,6,0,0,0,0,0,1),0)
        assertTrue(a.snapshot().feedbackRecoveryRequired)
        val eeg=a.prepareEeg();a.activateEeg(eeg,10,2500,1) // EEG chosen before Z confirmation
        now=200;a.feedback(UnityState(0,5,0,0,0,0,30,2),now*1_000_000)
        assertTrue(a.releaseEmergency(receiverStationary=true,linkFresh=true))
        assertFalse(a.snapshot().emergencyLocked)
        assertFalse("the same verified acknowledgement must not leave a second ESTOP feedback lock",a.snapshot().feedbackRecoveryRequired)
        assertFalse(a.snapshot().feedbackSafetyFault)
        assertEquals(ControlCommand.STOP,a.snapshot().command)
        assertNull(a.snapshot().confirmed)
        val c=RecognitionControl();c.accept(Direction.FORWARD,Direction.FORWARD,now,now)
        a.recognition(RecognitionSnapshot(listOf(.9,.1,.1,.1),Direction.FORWARD,Direction.FORWARD,"old in-flight result",
            generation=1,windowEnd=2750,analysisCount=11,decidedMs=now,inputMs=now,control=c.state))
        assertEquals("unlock alone must not restore EEG movement",ControlCommand.STOP,a.snapshot().command)
    }
    @Test fun coastingDoesNotQualifyAsStationaryForFaultReauthorization(){
        val a=ControlAuthority()
        a.feedback(UnityState(2,5,0,0,0,0,30,1),System.nanoTime())
        a.feedback(UnityState(8,5,50,0,0,0,30,2),System.nanoTime())
        assertFalse(a.manual(ControlCommand.LEFT))
        assertTrue(a.snapshot().feedbackRecoveryRequired)
        a.feedback(UnityState(0,5,0,0,0,0,30,3),System.nanoTime())
        assertEquals(ControlCommand.STOP,a.snapshot().command)
        assertTrue(a.manual(ControlCommand.LEFT))
    }
    @Test fun realFaultEstopUnknownAndRestartRemainBlockingAndRecordTheTrigger(){
        for(state in listOf(UnityState(2,5,0,0,0,0,0,91),UnityState(1,6,0,0,0,0,0,92),UnityState(0,0,0,0,0,0,0,93))){
            val a=ControlAuthority()
            a.feedback(state,System.nanoTime())
            assertTrue(a.snapshot().feedbackRecoveryRequired)
            assertFalse(a.manual(ControlCommand.FORWARD))
            assertTrue("trigger fields must remain in copied diagnostics",a.snapshot().feedbackFaultReason!!.contains("flags="))
            a.configureFeedbackProtection(true);a.configureFeedbackProtection(false)
            assertFalse(a.manual(ControlCommand.RIGHT))
        }
        val b=ControlAuthority();b.feedback(UnityState(0,5,0,0,0,0,30,1),System.nanoTime(),restart=true)
        assertTrue(b.snapshot().feedbackRecoveryRequired)
    }
    @Test fun allFourActualCcaDirectionsCanRestartAfterStreamStopAndCoastingFeedbackOverUdp(){
        Stage3Rig().use{r->
            r.peer.feedbackEnabled=false
            Thread.sleep(60) // drain any already-started automatic status before manual status injection
            fun status(moving:Boolean=false){
                val stamp=(r.link.snapshot().feedback?.timestampMs?:0)+1
                r.peer.send(Stage3Peer.status(flags=if(moving)8 else 0,action=5,
                    linear=if(moving)60 else 0,time=stamp))
                Stage3Peer.await("actual status datagram accepted"){r.link.snapshot().feedback?.timestampMs==stamp}
            }
            for((frequency,command) in listOf(12.0 to 1,8.57 to 3,6.67 to 4,5.45 to 2)){
                status();r.info();r.feed(3750,frequency)
                assertEquals("CCA frequency $frequency to actual UDP direction",command,r.wire())
                r.stop();assertEquals(5,r.wire())
                status(moving=true)
                assertFalse("ordinary STOP must not latch a fault and block the next direction",r.runtime.authority.snapshot().feedbackRecoveryRequired)
                assertEquals(ControlCommand.STOP,r.runtime.authority.snapshot().command)
                status()
            }
        }
    }
}
