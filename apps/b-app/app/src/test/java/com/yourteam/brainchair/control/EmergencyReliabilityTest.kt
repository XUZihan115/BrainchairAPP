package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.StreamSession
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.*

internal class FaultStore(initial:StoredEmergency?=StoredEmergency(false)):EmergencyStore {
    @Volatile var disk=initial
    @Volatile var fail=false
    @Volatile var failUnlock=false
    @Volatile var readFailure=false
    @Volatile var writeFailure=false
    @Volatile var ignoreWrite=false
    @Volatile var waitFor:CountDownLatch?=null
    @Volatile var entered=CountDownLatch(1)
    val writes=CopyOnWriteArrayList<Boolean>()
    override fun read():StoredEmergency? {if(readFailure)throw IOException("injected read failure");return disk}
    override fun write(locked:Boolean):Boolean {
        entered.countDown();waitFor?.await(5,TimeUnit.SECONDS)
        if(writeFailure)throw IOException("injected write failure")
        writes.add(locked)
        if(fail || (!locked && failUnlock))return false
        if(!ignoreWrite)disk=StoredEmergency(locked)
        return true
    }
}

class EmergencyReliabilityTest {
    @Test fun aRealUdpRuntimeCannotAccidentallyUseTheEphemeralFixtureStore(){
        UnityUdpLink("127.0.0.1",9001,0).use{link->
            assertThrows(IllegalArgumentException::class.java){ControlRuntime(StreamSession(),unityLink=link)}
        }
    }
    @Test fun missingCorruptUnreadableAndPreviouslyUnlockedRecordsAllStartLocked(){
        for(record in listOf(null,StoredEmergency(false),StoredEmergency(true),StoredEmergency(false,99))){
            val store=FaultStore(record)
            EmergencyPersistence(store).use{p->
                ControlRuntime(StreamSession(),durableEmergency=p).use{r->
                    assertTrue(r.authority.snapshot().emergencyLocked)
                    assertFalse(r.manual(ControlCommand.FORWARD))
                    Stage3Peer.await("boot stores confirmed lock"){p.snapshot().ready}
                    assertEquals(StoredEmergency(true),store.disk);assertTrue(r.authority.snapshot().emergencyLocked)
                }
            }
        }
        val store=FaultStore().apply{readFailure=true}
        EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p).use{r->
            Stage3Peer.await("read failure visible"){p.snapshot().failures>0}
            assertFalse(r.authority.snapshot().storageReady);assertTrue(r.authority.snapshot().emergencyLocked)
        }}
    }
    @Test fun commitFalseExceptionAndReadbackMismatchStayLockedAcrossRecreation(){
        for(fault in listOf("commit-false","exception","readback-mismatch")){
            val store=FaultStore(StoredEmergency(false)).apply{
                fail=fault=="commit-false";writeFailure=fault=="exception";ignoreWrite=fault=="readback-mismatch"
            }
            EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p).use{r->
                Stage3Peer.await("failed lock save"){p.snapshot().failures>0}
                assertFalse(r.authority.snapshot().storageReady);assertTrue(r.authority.snapshot().emergencyLocked)
                r.ordinaryStop();assertTrue(r.authority.snapshot().emergencyLocked)
                assertEquals(StoredEmergency(false),store.disk)
            }}
            store.fail=false;store.writeFailure=false;store.ignoreWrite=false
            EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p).use{r->
                assertTrue("a recreated process/service never defaults unlocked",r.authority.snapshot().emergencyLocked)
                Stage3Peer.await("recreated session lock durable"){p.snapshot().ready}
                assertFalse(r.manual(ControlCommand.LEFT))
            }}
        }
    }
    @Test fun blockedStoreCannotDelayEmergencyOrPrioritySendAndMailboxIsBounded(){
        val gate=CountDownLatch(1);val store=FaultStore().apply{waitFor=gate}
        EmergencyPersistence(store).use{p->
            ControlRuntime(StreamSession(),durableEmergency=p).use{r->
                try{
                    assertTrue(store.entered.await(1,TimeUnit.SECONDS));r.start()
                    val begin=System.nanoTime();r.emergencyStop();val elapsed=(System.nanoTime()-begin)/1e6
                    assertTrue("estop API $elapsed",elapsed<200)
                    Stage3Peer.await("local simulated ESTOP while store is blocked"){r.simulatedUnity.current()==ControlCommand.ESTOP}
                    assertFalse(r.authority.snapshot().storageReady);assertFalse(r.releaseEmergency())
                    repeat(100){r.emergencyStop()}
                    assertTrue(store.writes.isEmpty());gate.countDown()
                    Stage3Peer.await("only latest requested lock eventually saved"){p.snapshot().ready}
                    assertTrue(store.writes.size<=2);assertEquals(StoredEmergency(true),store.disk)
                    safetyEvidence("blocked-store.txt","estopApiMs=$elapsed\ncompletedWrites=${store.writes.size}\nprioritySentWhileBlocked=true\n")
                }finally{gate.countDown()}
            }
        }
    }
    @Test fun failedUnlockWriteNeverClearsLocalLockAndRetryRequiresRemoteHandshake(){
        Stage3Peer().use{peer->
            val store=FaultStore();val p=EmergencyPersistence(store)
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link,durableEmergency=p).use{r->
                try{
                    r.start();peer.feedbackPort=link.boundFeedbackPort
                    Stage3Peer.await("startup local save and remote latch report"){p.snapshot().ready && link.snapshot().feedback?.emergency==true && r.dispatcher.emergencyBurstComplete(r.authority.snapshot().emergencySequence)}
                    assertTrue(r.authority.snapshot().emergencyLocked);assertFalse(r.manual(ControlCommand.FORWARD))
                    peer.z();Stage3Peer.await("fresh remote Z state"){link.snapshot().let{it.feedback?.let{s->!s.emergency && s.stationary}==true && (it.receivedNs?:0)>r.dispatcher.lastEmergencySentNs}}
                    store.failUnlock=true;assertTrue(r.releaseEmergency())
                    Stage3Peer.await("unlock commit failure"){p.snapshot().failures>0 && !p.snapshot().pending}
                    assertTrue(r.authority.snapshot().emergencyLocked);assertFalse(r.authority.snapshot().storageReady)
                    assertEquals(StoredEmergency(true),store.disk)
                    store.failUnlock=false;r.retrySafetyStorage()
                    Stage3Peer.await("retry safely re-latches and persists"){p.snapshot().ready && link.snapshot().feedback?.emergency==true && r.dispatcher.emergencyBurstComplete(r.authority.snapshot().emergencySequence)}
                    peer.z();Stage3Peer.await("new post-burst Z feedback"){link.snapshot().let{it.feedback?.let{s->!s.emergency && s.stationary}==true && (it.receivedNs?:0)>r.dispatcher.lastEmergencySentNs}}
                    assertTrue(r.releaseEmergency());Stage3Peer.await("verified async unlock"){!r.authority.snapshot().emergencyLocked}
                    assertEquals(StoredEmergency(false),store.disk);assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                    assertTrue(r.manual(ControlCommand.FORWARD));Stage3Peer.await("new action only after unlock"){peer.applied==1}
                }finally{p.close()}
            }
        }
    }
    @Test fun allThreeInitialEstopsCanBeLostAndLaterRetriesStillObtainRemoteLatchReport(){
        val authority=ControlAuthority();val received=CopyOnWriteArrayList<ControlCommand>();val attempts=AtomicInteger()
        lateinit var d:ControlDispatcher
        val wire=object:UnityTransport{
            override val mode="ISOLATED_LOSS_INJECTION"
            override fun send(command:UnityCommand){
                if(command.command==ControlCommand.ESTOP){
                    if(attempts.incrementAndGet()<=3)return
                    received.add(command.command);d.emergencyObserved(authority.snapshot().emergencySequence)
                }else received.add(command.command)
            }
        }
        d=ControlDispatcher(authority,wire)
        d.use{
            d.start();authority.emergencyStop()
            Stage3Peer.await("retry after all initial 06 loss"){received.contains(ControlCommand.ESTOP)}
            assertTrue(attempts.get()>=4);assertTrue(authority.snapshot().emergencyLocked)
            val sent=attempts.get();Thread.sleep(450)
            assertEquals("retry stops once remote lock is observed",sent,attempts.get())
            assertEquals(ControlCommand.STOP,received.last())
        }
    }
    @Test fun newerEmergencySupersedesBlockedOldUnlockPersistence(){
        val store=FaultStore();EmergencyPersistence(store).use{p->
            val first=CountDownLatch(1);p.request(true){first.countDown()};assertTrue(first.await(1,TimeUnit.SECONDS))
            val gate=CountDownLatch(1);store.waitFor=gate;store.entered=CountDownLatch(1)
            var obsoleteCallback=false
            p.request(false){obsoleteCallback=true};assertTrue(store.entered.await(1,TimeUnit.SECONDS))
            p.request(true);gate.countDown()
            Stage3Peer.await("newest emergency survives old unlock write"){p.snapshot().ready && store.disk?.locked==true}
            assertFalse(obsoleteCallback)
        }
    }
}
