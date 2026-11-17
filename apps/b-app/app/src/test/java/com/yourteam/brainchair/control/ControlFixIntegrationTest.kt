package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ControlFixIntegrationTest {
    private fun evidence(name:String,value:String){
        val dir=File(requireNotNull(System.getProperty("brainchair.controlFix.evidence")));dir.mkdirs();File(dir,name).writeText(value)
    }
    @Test fun manualOwnershipIsRetainedAcrossControlServiceRecreation() {
        var savedMode=ControlMode.EEG
        ControlRuntime(StreamSession(),persistMode={savedMode=it}).use { r->
            r.manual(ControlCommand.LEFT);assertEquals(ControlMode.MANUAL,savedMode)
        }
        ControlRuntime(StreamSession(),initialMode=savedMode,persistMode={savedMode=it}).use { r->
            assertEquals(ControlMode.MANUAL,r.authority.snapshot().mode)
            assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
            r.switchMode(ControlMode.EEG);assertEquals(ControlMode.EEG,savedMode)
        }
    }
    @Test fun actualUdpManualTakeoverRenewsAndStopsAtReplacementFourSecondDeadline() {
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link,isolatedTestStorage=true).use { r->
                r.start();peer.handshake(r)
                assertEquals(ControlMode.EEG,r.authority.snapshot().mode)
                val started=System.nanoTime();assertTrue(r.manual(ControlCommand.FORWARD))
                Stage3Peer.await("manual automatic takeover UDP"){peer.applied==1}
                Thread.sleep(1000)
                val replaced=System.nanoTime();assertTrue(r.manual(ControlCommand.LEFT))
                Stage3Peer.await("replacement LEFT UDP"){peer.applied==3}
                val leftIndex=peer.records.indexOfFirst{it.code==3}
                Stage3Peer.await("old deadline passed",3500){System.nanoTime()-started>4_100_000_000L}
                assertEquals("canceled old timer cannot stop LEFT",3,peer.applied)
                Stage3Peer.await("active STOP at new four-second deadline",2000){peer.records.drop(leftIndex).any{it.code==5}}
                val stopped=peer.records.drop(leftIndex).first{it.code==5}
                val delay=(stopped.ns-replaced)/1e6
                assertTrue("actual STOP $delay ms after LEFT click",delay in 3950.0..4200.0)
                assertEquals(ControlMode.MANUAL,r.authority.snapshot().mode)
                assertTrue(peer.records.drop(leftIndex).none{it.code==1})
                val left=peer.records.drop(leftIndex).takeWhile{it.code!=5}.filter{it.code==3}
                assertTrue("manual renewals",left.size>=18)
                assertTrue("keep 1s lease",left.zipWithNext{a,b->(b.ns-a.ns)/1e6}.all{it<500})
                Thread.sleep(250);assertEquals(5,peer.applied)
                evidence("manual-four-seconds.csv","command,receivedNs,hex\n"+peer.records.joinToString("\n"){"${it.code},${it.ns},${it.hex}"}+"\n")
                evidence("manual-stop-latency.txt","replacementClickNs=$replaced\nstopReceivedNs=${stopped.ns}\nclickToStopMs=$delay\nleftRenewalPackets=${left.size}\n")
            }
        }
    }
    @Test fun runningEegCannotOverrideManualAndExplicitEegResumeNeedsFreshStableVotes() {
        Stage3Rig().use { r->
            r.info();r.feed(3750);assertEquals(1,r.wire())
            assertTrue(r.runtime.manual(ControlCommand.RIGHT));r.flush();assertEquals(4,r.wire())
            r.feed(1000);assertEquals(Direction.FORWARD,r.runtime.engine.eventSnapshot().stable)
            assertEquals(4,r.wire());assertEquals(ControlMode.MANUAL,r.runtime.authority.snapshot().mode)
            r.stop();r.info();r.feed(500);assertEquals(4,r.wire())
            r.runtime.switchMode(ControlMode.EEG);r.flush();assertEquals(5,r.wire())
            r.feed(3250);assertEquals(1,r.wire())
            r.runtime.manual(ControlCommand.LEFT);r.flush();assertEquals(3,r.wire())
            r.runtime.switchMode(ControlMode.EEG);r.flush();val count=r.runtime.engine.eventSnapshot().analysisCount
            repeat(5){r.runtime.engine.tick();r.runtime.engine.guard();r.flush();assertEquals(5,r.wire())}
            assertEquals(count,r.runtime.engine.eventSnapshot().analysisCount)
            r.feed(250);assertEquals(5,r.wire());r.feed(250);assertEquals(5,r.wire())
            r.feed(250);assertEquals(1,r.wire())
            assertEquals(RecognitionConfig(),r.runtime.engine.eventSnapshot().config)
            r.runtime.ordinaryStop();r.flush();assertEquals(ControlMode.MANUAL,r.runtime.authority.snapshot().mode)
            r.info();r.feed(1000);assertEquals(5,r.wire())
            evidence("eeg-resume-events.txt",r.runtime.authority.events().joinToString("\n"))
        }
    }
    @Test fun temporaryScoreRejectionSurvivesButSustainedRejectionStopsActualUdp() {
        var reject=false
        Stage3Rig {c,x->if(reject)doubleArrayOf(.3,.2,.1,.1) else c.scores(x)}.use { r->
            // Only this policy fault-injection overrides scores. Acquisition uses real filter/CCA.
            r.info();r.feed(3750);assertEquals(1,r.wire())
            reject=true;r.feed(250);assertEquals(Direction.STOP,r.runtime.engine.eventSnapshot().raw)
            assertEquals(1,r.wire());val until=r.runtime.authority.snapshot().graceUntilMs
            reject=false;r.feed(250);assertEquals(1,r.wire());assertEquals(until,r.runtime.authority.snapshot().graceUntilMs)
            r.feed(500);assertEquals(1,r.wire());assertNull(r.runtime.authority.snapshot().graceUntilMs)
            reject=true;r.feed(250);val since=r.runtime.authority.snapshot().rejectedSinceMs!!
            r.feed(500);assertEquals(since,r.runtime.authority.snapshot().rejectedSinceMs);assertEquals(1,r.wire())
            r.now=since+750;r.runtime.authority.enforceDeadlines();r.flush();assertEquals(5,r.wire())
            assertTrue(r.session.health().info!=null);assertTrue(r.runtime.authority.snapshot().reason.contains("拒识"))
            evidence("rejection-grace.txt","singleRejectDeadlineMs=$until\nsustainedRejectSinceMs=$since\nstopAtMs=${r.now}\n"+
                r.runtime.authority.events().joinToString("\n"))
        }
    }
    @Test fun stopInfoCrcGapTimeoutAndInvalidScoresNeverWaitForGrace() {
        for(event in listOf("STOP","INFO","CRC","GAP","TIMEOUT","INVALID_SCORE")){
            var invalid=false
            Stage3Rig {c,x->if(invalid)doubleArrayOf(Double.NaN,.2,.1,.1) else c.scores(x)}.use { r->
                r.info();r.feed(3750);assertEquals(1,r.wire())
                when(event){
                    "STOP"->r.stop()
                    "INFO"->r.info(34001)
                    "CRC"->r.session.accept(Stage3Signals.wave(r.seq,r.first,r.origin).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()},r.source)
                    "GAP"->r.session.accept(Stage3Signals.wave(r.seq+1,r.first+5,r.origin),r.source)
                    "TIMEOUT"->{r.now+=1000;r.runtime.engine.guard()}
                    else->{invalid=true;r.feed(250)}
                }
                r.flush();assertEquals("$event has immediate wire STOP",5,r.wire());assertNull(r.runtime.authority.snapshot().confirmed)
                repeat(3){r.runtime.engine.guard();r.flush()};assertEquals(5,r.wire())
            }
        }
    }
    @Test fun blockedCcaCannotUndoManualStopEmergencyOrAnExplicitEegReset() {
        for(action in listOf("MANUAL","STOP","ESTOP","EEG_RESET")){
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean(false)
            Stage3Rig {c,x->if(blocked.get()){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};c.scores(x)}.use { r->
                r.info();r.feed(3750);r.feed(250,tick=false);blocked.set(true)
                val worker=Thread{r.runtime.engine.tick()};worker.start()
                try{
                    assertTrue(entered.await(2,TimeUnit.SECONDS))
                    when(action){"MANUAL"->r.runtime.manual(ControlCommand.LEFT);"STOP"->r.runtime.ordinaryStop();"ESTOP"->r.runtime.emergencyStop();else->r.runtime.switchMode(ControlMode.EEG)}
                    r.flush();val count=r.peer.records.size;val expected=when(action){"MANUAL"->3;"ESTOP"->6;else->5}
                    assertEquals(expected,r.wire());release.countDown();worker.join(3000);assertFalse(worker.isAlive)
                    repeat(2){r.flush()};assertTrue(r.peer.records.drop(count).none{it.code==1})
                    assertEquals(expected,r.wire())
                }finally{release.countDown();worker.join(3000)}
            }
        }
    }
    @Test fun queuedConfigurationCannotUndoNewerManualDirection() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
        val receiver=SimulatedUnityReceiver()
        val transport=object:UnityTransport{
            override val mode="BLOCKED_SEND_FOR_QUEUE_TEST"
            override val ready:Boolean get(){
                if(first.compareAndSet(true,false)){entered.countDown();check(release.await(2,TimeUnit.SECONDS))}
                return true
            }
            override fun send(command:UnityCommand){receiver.receive(command)}
        }
        val a=ControlAuthority();ControlDispatcher(a,transport).use{d->
            d.start();assertTrue(entered.await(1,TimeUnit.SECONDS))
            // Block the delivery worker before taking the authority lock; queue a real config.
            var oldRan=false
            d.reconfigure{oldRan=true;a.setSpeed(100)}
            a.manual(ControlCommand.RIGHT);release.countDown()
            Stage3Peer.await("manual direction delivered"){receiver.current()==ControlCommand.RIGHT}
            Thread.sleep(60)
            assertFalse(oldRan);assertEquals(ControlMode.MANUAL,a.snapshot().mode);assertEquals(30,a.snapshot().speed)
        }
    }
}
