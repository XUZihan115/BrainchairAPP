package com.yourteam.brainchair.control

import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test

/** Control policy fixtures, not EEG classification/accuracy evidence. The real unchanged gate
 * produces every raw/stable vote; labels are not inputs to a signal decoder. */
class ControlFixTest {
    private class Rig(val grace:Long=750) {
        var now=0L;var analysis=0L
        val gate=DecisionGate(RecognitionConfig())
        val a=ControlAuthority({now},{now*1_000_000},policy=ControlPolicy(grace))
        init{a.boundary(1,1,"INFO")}
        fun result(scores:DoubleArray=doubleArrayOf(.9,.1,.1,.1)):RecognitionSnapshot {
            val g=gate.evaluate(scores,now);val c=RecognitionControl();c.accept(g.raw,g.stable,now,now)
            return RecognitionSnapshot(scores.toList(),g.raw,g.stable,g.reason,generation=1,
                windowEnd=3000+analysis*250,decidedMs=now,analysisCount=++analysis,inputMs=now,control=c.state,voteCount=g.votes)
        }
        fun vote(scores:DoubleArray=doubleArrayOf(.9,.1,.1,.1)) {a.recognition(result(scores))}
        fun acquire(){vote();now=250;vote();now=500;vote();assertEquals(ControlCommand.FORWARD,a.snapshot().command)}
    }
    @Test fun allUserButtonsImmediatelyTakeManualAndEegCannotTakeBackOwnership() {
        for(command in ControlCommand.values()){
            val r=Rig();r.acquire();assertEquals(ControlMode.EEG,r.a.snapshot().mode)
            r.a.manual(command)
            assertEquals(ControlMode.MANUAL,r.a.snapshot().mode);assertEquals(command,r.a.snapshot().command)
            r.now+=250;r.vote();r.a.boundary(2,2,"new INFO");r.a.recognition(r.result().copy(generation=2))
            assertEquals(ControlMode.MANUAL,r.a.snapshot().mode);assertEquals(command,r.a.snapshot().command)
        }
    }
    @Test fun manualDeadlineIsFourSecondsAndReplacementCancelsTheOldDeadline() {
        val r=Rig();r.a.manual(ControlCommand.FORWARD);assertEquals(4000L,r.a.snapshot().expiresMs)
        r.now=1000;r.a.manual(ControlCommand.LEFT);assertEquals(5000L,r.a.snapshot().expiresMs)
        r.now=4000;r.a.enforceDeadlines();assertEquals(ControlCommand.LEFT,r.a.snapshot().command)
        r.now=4999;assertEquals(ControlCommand.LEFT,r.a.snapshot().command)
        r.now=5000;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.snapshot().reason.contains("4秒"));assertEquals(ControlMode.MANUAL,r.a.snapshot().mode)
        r.now=7000;r.a.recognition(r.result());assertEquals(ControlCommand.STOP,r.a.snapshot().command)
    }
    @Test fun stopAndEmergencyCancelMotionAndRemainManualWithoutLocalOnlyUnlock() {
        val r=Rig();r.a.manual(ControlCommand.FORWARD);r.now=1000;r.a.ordinaryStop()
        r.now=4100;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.a.manual(ControlCommand.LEFT);r.a.emergencyStop();r.now=9000;r.a.enforceDeadlines()
        r.a.boundary(2,2,"new INFO");r.a.ordinaryStop();r.a.prepareEeg()
        assertFalse(r.a.manual(ControlCommand.RIGHT));assertTrue(r.a.snapshot().emergencyLocked)
        assertEquals(ControlCommand.ESTOP,r.a.snapshot().command)
        assertFalse(r.a.releaseEmergency(true,false));assertFalse(r.a.releaseEmergency(false,true))
        assertFalse(r.a.releaseEmergency(true,true){false})
    }
    @Test fun singleRejectedWindowKeepsConfirmedDirectionWhileOriginalVotesRebuild() {
        val r=Rig();r.acquire();r.now=750;r.vote(doubleArrayOf(.3,.2,.1,.1))
        assertEquals(ControlCommand.FORWARD,r.a.snapshot().command);assertEquals(1500L,r.a.snapshot().graceUntilMs)
        r.now=1000;r.vote() // Unchanged gate starts its original three-vote sequence again.
        assertEquals(ControlCommand.FORWARD,r.a.snapshot().command);assertEquals(1500L,r.a.snapshot().graceUntilMs)
        r.now=1250;r.vote();r.now=1500;r.vote();assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
        assertNull(r.a.snapshot().graceUntilMs)
    }
    @Test fun repeatedRejectionAndDuplicateWatchdogCallbacksCannotExtendGrace() {
        val r=Rig();r.acquire();r.now=750
        var last=r.result(doubleArrayOf(.3,.2,.1,.1));r.a.recognition(last)
        for(t in listOf(800L,850L,900L,999L)){r.now=t;r.a.recognition(last)}
        r.now=1000;last=r.result(doubleArrayOf(.3,.2,.1,.1));r.a.recognition(last)
        r.now=1250;r.vote(doubleArrayOf(.3,.2,.1,.1));assertEquals(1500L,r.a.snapshot().graceUntilMs)
        r.now=1499;assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
        r.now=1500;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertNull(r.a.snapshot().confirmed)
        r.now=1510;r.a.recognition(last);assertEquals(ControlCommand.STOP,r.a.snapshot().command)
    }
    @Test fun differentDirectionCannotSwitchBeforeOriginalVotingAndDebounceRulesPass() {
        val r=Rig();r.acquire();val left=doubleArrayOf(.1,.9,.1,.1)
        r.now=750;r.vote(left);assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
        r.now=1000;r.vote(left);assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
        r.now=1250;r.vote(left);assertEquals(ControlCommand.LEFT,r.a.snapshot().command)
    }
    @Test fun alternatingGatePassesAndRejectionsCannotRenewAnOldDirectionForever() {
        val r=Rig();r.acquire()
        r.now=750;r.vote(doubleArrayOf(.3,.2,.1,.1))
        r.now=1000;r.vote();assertEquals(1500L,r.a.snapshot().graceUntilMs)
        r.now=1250;r.vote(doubleArrayOf(.3,.2,.1,.1))
        r.now=1499;r.a.enforceDeadlines();assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)
        r.now=1500;r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
    }
    @Test fun firstUnstableVotesNeverStartMotionAndHardInvalidationNeverGetsGrace() {
        val r=Rig();r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.now=250;r.vote();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.now=500;r.vote();r.now=750;r.vote(doubleArrayOf(.3,.2,.1,.1))
        r.a.recognition(r.result().copy(scores=emptyList(),raw=Direction.STOP,stable=Direction.STOP,reason="失效"))
        assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        for(reason in listOf("STREAM_STOP","new INFO","CRC","gap","timeout")){
            val h=Rig();h.acquire();h.a.boundary(2,2,reason)
            assertEquals(reason,ControlCommand.STOP,h.a.snapshot().command)
            h.a.recognition(h.result());assertEquals(ControlCommand.STOP,h.a.snapshot().command)
        }
    }
    @Test fun staleInputAndExpiredJudgementStopBeforeGraceCouldMaskThem() {
        for(staleInput in listOf(true,false)){
            val r=Rig();r.acquire();r.now=750;r.vote(doubleArrayOf(.3,.2,.1,.1))
            r.now=800;val fresh=r.result()
            r.a.recognition(if(staleInput)fresh.copy(inputMs=-201) else fresh.copy(decidedMs=-201))
            assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        }
        val r=Rig();r.acquire();r.now=1500;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
    }
    @Test fun resumeNeedsNewAnalysisAndNewSamplesAndCannotApplyAnOldQueuedSelection() {
        val r=Rig();r.acquire();val old=r.result();r.a.manual(ControlCommand.RIGHT)
        val token=r.a.prepareEeg();r.a.activateEeg(token,r.analysis,old.windowEnd,1)
        r.a.recognition(old);assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.now+=1;r.a.recognition(r.result().copy(windowEnd=old.windowEnd));assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.a.manual(ControlCommand.LEFT);r.a.activateEeg(token,0,0,1);r.vote()
        assertEquals(ControlMode.MANUAL,r.a.snapshot().mode);assertEquals(ControlCommand.LEFT,r.a.snapshot().command)
    }
    @Test fun laterManualActionCancelsQueuedConfigurationAndOldDeadline() {
        val r=Rig();val token=r.a.beginTransition();r.a.manual(ControlCommand.RIGHT)
        var ran=false;assertFalse(r.a.runTransition(token){ran=true});r.a.endTransition(token)
        assertFalse(ran);assertEquals(ControlCommand.RIGHT,r.a.snapshot().command);assertFalse(r.a.snapshot().transitioning)
    }
    @Test fun policyIsConfigurableButCannotOverrideIndependentFreshnessSafety() {
        val r=Rig(0);r.acquire();r.now=750;r.vote(doubleArrayOf(.3,.2,.1,.1));assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertThrows(IllegalArgumentException::class.java){ControlPolicy(-1)}
        assertThrows(IllegalArgumentException::class.java){ControlPolicy(1001)}
        assertEquals(RecognitionConfig(.65,.15,3,300),RecognitionConfig())
        assertEquals(2.5,SignalTiming.WINDOW_SECONDS,0.0);assertEquals(250,SignalTiming.UPDATE_MS)
    }
    @Test fun diagnosticEventHistoryIsBoundedAndDoesNotLogEveryGuardCallback() {
        val r=Rig();r.acquire();val snapshot=r.result();r.a.recognition(snapshot)
        val count=r.a.events().size;repeat(10000){r.a.recognition(snapshot)};assertEquals(count,r.a.events().size)
        repeat(500){r.a.manual(ControlCommand.STOP)};assertEquals(128,r.a.events().size)
    }
}
