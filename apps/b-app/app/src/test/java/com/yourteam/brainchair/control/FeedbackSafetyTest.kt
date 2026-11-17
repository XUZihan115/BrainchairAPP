package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test

class FeedbackSafetyTest {
    private class Rig {
        var now=0L;var count=0L
        val a=ControlAuthority({now},{now*1_000_000},feedbackRequired=true)
        val gate=DecisionGate(RecognitionConfig())
        init{a.boundary(1,1,"INFO")}
        fun feedback(action:Int=5,flags:Int=0,epoch:Long=0){
            a.feedback(UnityState(flags,action,0,0,0,0,30,now+1),now*1_000_000,epoch=epoch)
        }
        fun vote(good:Boolean=true){
            val scores=if(good)doubleArrayOf(.9,.1,.1,.1) else doubleArrayOf(.3,.2,.1,.1)
            val d=gate.evaluate(scores,now);val c=RecognitionControl();c.accept(d.raw,d.stable,now,now)
            a.recognition(RecognitionSnapshot(scores.toList(),d.raw,d.stable,d.reason,generation=1,
                windowEnd=3000+count*250,analysisCount=++count,decidedMs=now,inputMs=now,control=c.state))
        }
        fun acquire(){feedback();vote();now=250;vote();now=500;vote();assertEquals(ControlCommand.FORWARD,a.snapshot().command)}
    }
    @Test fun firstMotionRequiresHealthyFeedbackAndRejectedRequestIsNeverReplayed(){
        val r=Rig();assertFalse(r.a.manual(ControlCommand.FORWARD));assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.feedback();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.manual(ControlCommand.LEFT));assertEquals(ControlCommand.LEFT,r.a.snapshot().command)
    }
    @Test fun manualAndContinuousEegStopAtTheSingleFeedbackBoundary(){
        for(eeg in listOf(false,true)){
            val r=Rig()
            if(eeg)r.acquire() else {r.feedback();r.a.manual(ControlCommand.FORWARD)}
            r.now=999;r.a.enforceDeadlines();assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
            r.now=1000;if(eeg)r.vote() else r.a.enforceDeadlines()
            assertEquals(ControlCommand.STOP,r.a.snapshot().command)
            assertTrue(r.a.snapshot().feedbackRecoveryRequired);assertNull(r.a.snapshot().confirmed)
            r.feedback();repeat(5){r.now+=50;r.vote()};assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        }
    }
    @Test fun healthyRecoveryRequiresStationaryStatusAndAnExplicitNewManualAction(){
        val r=Rig();r.feedback();r.a.manual(ControlCommand.FORWARD);r.now=1000;r.a.enforceDeadlines()
        r.feedback(action=1,flags=8);assertFalse(r.a.manual(ControlCommand.LEFT))
        r.feedback();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.manual(ControlCommand.LEFT));assertFalse(r.a.snapshot().feedbackRecoveryRequired)
    }
    @Test fun eegRecoveryCannotUseOldVotesOrResumeFromRestoredFeedbackAlone(){
        val r=Rig();r.acquire();r.now=1000;r.vote();r.feedback();r.vote()
        assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        val token=r.a.prepareEeg();r.gate.reset();r.a.activateEeg(token,r.count,0,1)
        r.now+=250;r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.now+=250;r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.now+=250;r.vote();assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
    }
    @Test fun remoteFaultUnknownAndEmergencyStatesRevokePermitImmediately(){
        for((action,flags) in listOf(5 to 2,0 to 0,6 to 8)){
            val r=Rig();r.acquire();r.feedback(action,flags)
            assertEquals(ControlCommand.STOP,r.a.snapshot().command)
            assertTrue(r.a.snapshot().feedbackRecoveryRequired)
            r.feedback();r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        }
    }
    @Test fun oldTargetFeedbackCannotRenewOrAuthorizeANewTarget(){
        val r=Rig();r.acquire();r.a.expectFeedback(1)
        r.feedback(epoch=0);assertNull(r.a.snapshot().feedbackReceivedNs)
        assertFalse(r.a.manual(ControlCommand.FORWARD))
        r.feedback(epoch=1);assertTrue(r.a.manual(ControlCommand.FORWARD))
    }
    @Test fun feedbackLossBypassesCcaGraceAndEstopStillHasHighestPriority(){
        val r=Rig();r.acquire();r.now=750;r.vote(false)
        assertEquals(1500L,r.a.snapshot().graceUntilMs)
        r.now=1000;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.a.emergencyStop();r.feedback();r.a.ordinaryStop();r.a.manual(ControlCommand.RIGHT)
        assertEquals(ControlCommand.ESTOP,r.a.snapshot().command)
        assertTrue(r.a.snapshot().emergencyLocked)
    }
    @Test fun storageAndTransportFaultsCannotBeBypassedByFreshFeedbackOrManualInput(){
        val r=Rig();r.acquire();r.a.storageState(false,"commit false")
        assertEquals(ControlCommand.STOP,r.a.snapshot().command);r.feedback();assertFalse(r.a.manual(ControlCommand.LEFT))
        r.a.storageState(true);r.a.transportFailed("blocked send")
        r.feedback();assertFalse(r.a.manual(ControlCommand.LEFT));assertNotNull(r.a.snapshot().transportFault)
    }
}
