package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun safetyEvidence(name:String,text:String){
    val folder=File(requireNotNull(System.getProperty("brainchair.safety.evidence")));folder.mkdirs();File(folder,name).writeText(text)
}
class FeedbackIntegrationTest {
    @Test fun feedbackTimeoutInvalidatesBlockedCcaAndOldCompletionCannotRestoreMotion(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean(false)
        Stage3Rig{c,x->if(blocked.get()){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};c.scores(x)}.use{rig->
            rig.info();rig.feed(3750);assertEquals(1,rig.wire())
            rig.feed(250,tick=false);blocked.set(true)
            val calculation=Thread{rig.runtime.engine.tick()}.apply{start()}
            try{
                assertTrue(entered.await(1,TimeUnit.SECONDS));rig.runtime.dispatcher.start();rig.peer.feedbackEnabled=false
                Stage3Peer.await("feedback watchdog preempts running CCA",1700){rig.runtime.authority.snapshot().feedbackRecoveryRequired}
                Stage3Peer.await("old votes cleared independently"){rig.runtime.engine.eventSnapshot().scores.isEmpty()}
                val cut=rig.peer.records.size;release.countDown();calculation.join(2000)
                assertFalse(calculation.isAlive);Thread.sleep(250)
                assertTrue(rig.peer.records.drop(cut).all{it.code==5})
                assertEquals(ControlCommand.STOP,rig.runtime.authority.snapshot().command)
            }finally{release.countDown();calculation.join(2000)}
        }
    }
    @Test fun only9002LossStopsManualDespiteWorking9001AndRecoveryDoesNotResume(){
        Stage3Peer().use{peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link,isolatedTestStorage=true).use{r->
                r.start();peer.handshake(r);assertTrue(r.manual(ControlCommand.FORWARD))
                Stage3Peer.await("normal manual renewals"){peer.records.count{it.code==1}>=3}
                peer.feedbackEnabled=false
                val lastGood=link.snapshot().receivedNs!!;val cut=peer.records.size
                Stage3Peer.await("manual loses permit after only 9002 loss",1600){r.authority.snapshot().command==ControlCommand.STOP}
                Stage3Peer.await("active STOP delivered on still working 9001"){peer.records.drop(cut).any{it.code==5}}
                val stop=peer.records.drop(cut).first{it.code==5};val delay=(stop.ns-lastGood)/1e6
                assertTrue("feedback-to-STOP $delay",delay in 990.0..1250.0)
                peer.feedbackEnabled=true;Stage3Peer.await("feedback restored"){link.snapshot().feedbackOnline}
                Thread.sleep(300);assertEquals(5,peer.applied);assertTrue(r.authority.snapshot().feedbackRecoveryRequired)
                assertTrue(r.manual(ControlCommand.LEFT));Stage3Peer.await("explicit new authorization"){peer.applied==3}
                safetyEvidence("manual-9002-loss.txt","lastValidFeedbackNs=$lastGood\nstopReceiptNs=${stop.ns}\nfeedbackAgeAtStopMs=$delay\n9001 remained operational; fresh feedback did not replay the old direction.\n")
            }
        }
    }
    @Test fun continuousRealCcaCannotRenewUnityMotionWhenOnlyFeedbackIsLost(){
        Stage3Peer().use{peer->
            val session=StreamSession();val link=UnityUdpLink("127.0.0.1",peer.port,0)
            val r=ControlRuntime(session,unityLink=link,isolatedTestStorage=true);val udp=UdpReceiver(session,0,0)
            val running=AtomicBoolean(true);val sourceErrors=mutableListOf<Throwable>()
            var source:Thread?=null
            try{
                udp.start();r.start();peer.handshake(r)
                source=Thread{
                    try{DatagramSocket().use{socket->
                        fun send(b:ByteArray){socket.send(DatagramPacket(b,b.size,InetAddress.getByName("127.0.0.1"),udp.boundDataPort))}
                        send(Fixtures.info(names=Stage27IntegrationTest.names));val begin=System.nanoTime();var first=0L;var seq=2L
                        while(running.get() && first<14000){
                            val due=begin+first*1_000_000;val wait=due-System.nanoTime();if(wait>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait)
                            send(Stage3Signals.wave(seq++,first,1_800_000_100_000_000));first+=5
                        }
                        send(Fixtures.stop(seq.toInt(),first))
                    }}catch(e:Throwable){sourceErrors.add(e)}
                }.apply{name="isolated-synthetic-A";start()}
                Stage3Peer.await("actual CCA forward",6500){peer.records.any{it.code==1}}
                peer.feedbackEnabled=false;val lastGood=link.snapshot().receivedNs!!;val cut=peer.records.size
                Stage3Peer.await("9002 failure overrides EEG renewal",1600){r.authority.snapshot().command==ControlCommand.STOP}
                Stage3Peer.await("EEG active STOP on 9001"){peer.records.drop(cut).any{it.code==5}}
                val stop=peer.records.drop(cut).first{it.code==5};val before=session.snapshot().blockPackets
                Thread.sleep(300);assertTrue("A input continues",session.snapshot().blockPackets>before+30)
                assertTrue(peer.records.drop(cut).dropWhile{it.code!=5}.all{it.code==5})
                peer.feedbackEnabled=true;Stage3Peer.await("EEG feedback restored"){link.snapshot().feedbackOnline}
                Thread.sleep(300);assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                r.switchMode(ControlMode.EEG);val resumeCut=peer.records.size
                Stage3Peer.await("explicit EEG selection requires fresh stable votes",2000){peer.records.drop(resumeCut).any{it.code==1}}
                safetyEvidence("eeg-9002-loss.txt","lastValidFeedbackNs=$lastGood\nstopReceiptNs=${stop.ns}\nfeedbackAgeAtStopMs=${(stop.ns-lastGood)/1e6}\ncontinuousA=true\nexplicitEegResume=true\n")
            }finally{running.set(false);source?.join(2000);udp.close();r.close()}
            assertTrue(sourceErrors.toString(),sourceErrors.isEmpty())
        }
    }
    @Test fun duplicateRollbackMalformedAndRemoteFaultNeverBypassFeedbackGate(){
        Stage3Peer().use{peer->
            peer.feedbackEnabled=false
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link,isolatedTestStorage=true).use{r->
                r.start();peer.feedbackPort=link.boundFeedbackPort
                assertFalse(r.manual(ControlCommand.FORWARD));peer.send(Stage3Peer.status(time=100))
                Stage3Peer.await("first valid status"){r.authority.snapshot().feedbackHealthy}
                assertTrue(r.manual(ControlCommand.FORWARD))
                repeat(11){peer.send(Stage3Peer.status(time=100));peer.send(Stage3Peer.status(time=99));Thread.sleep(100)}
                Stage3Peer.await("duplicates do not renew online"){!link.snapshot().feedbackOnline}
                assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                assertTrue((link.snapshot().rejected["duplicate"]?:0)>=10)
                peer.send(Stage3Peer.status(time=1));Stage3Peer.await("restart accepted only as stopped state"){link.snapshot().feedback?.timestampMs==1L}
                assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                peer.send(Stage3Peer.status(time=2).apply{this[19]=0})
                Stage3Peer.await("bad checksum counted"){(link.snapshot().rejected["sum8"]?:0)>0}
                peer.send(Stage3Peer.status(flags=2,time=3));Stage3Peer.await("remote fault"){r.authority.snapshot().remoteFault}
                assertFalse(r.manual(ControlCommand.LEFT))
                peer.send(Stage3Peer.status(time=4));Stage3Peer.await("healthy stationary new state"){!r.authority.snapshot().remoteFault}
                assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                assertTrue(r.manual(ControlCommand.LEFT))
            }
        }
    }
}
