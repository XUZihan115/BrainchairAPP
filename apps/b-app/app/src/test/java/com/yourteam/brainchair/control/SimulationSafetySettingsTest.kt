package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.StreamSession
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimulationSafetySettingsTest {
    @Test fun defaultsAreOffAndAllFourSettingsCombinationsRoundTripIndependently(){
        val saved=mutableMapOf<String,Boolean>()
        assertEquals(SimulationSafetySettings(false,false),SimulationSafetySettings.read{k,d->saved[k]?:d})
        for(a in listOf(false,true))for(b in listOf(false,true)){
            val expected=SimulationSafetySettings(a,b)
            expected.write{k,v->saved[k]=v}
            assertEquals(expected,SimulationSafetySettings.read{k,d->saved[k]?:d})
            assertEquals(2,saved.size)
        }
    }
    @Test fun startupAndFeedbackPoliciesAreIndependentAndStrictStartupStillLocks(){
        for(a in listOf(false,true))for(b in listOf(false,true)){
            Stage3Peer().use{peer->EmergencyPersistence(FaultStore()).use{p->
                val link=UnityUdpLink("127.0.0.1",peer.port,0)
                ControlRuntime(StreamSession(),unityLink=link,durableEmergency=p,
                    startupEmergencyLock=a,feedbackTimeoutStop=b).use{r->
                    Stage3Peer.await("startup record verified"){r.authority.snapshot().storageReady}
                    assertEquals(a,r.authority.snapshot().emergencyLocked)
                    assertEquals(b,r.authority.snapshot().feedbackRequired)
                    assertEquals(!a && !b,r.manual(ControlCommand.FORWARD))
                }
            }}
        }
    }
    @Test fun relaxedStartupInitializesAMissingRecordWithoutManufacturingEstop(){
        val store=FaultStore(null)
        EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p,
            startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
            assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
            Stage3Peer.await("new record verified"){r.authority.snapshot().storageReady}
            assertFalse(r.authority.snapshot().emergencyLocked);assertEquals(StoredEmergency(false),store.disk)
            assertTrue(r.manual(ControlCommand.LEFT))
        }}
    }
    @Test fun savedManualOrRemoteEstopSurvivesRelaxedServiceAndProcessRecreation(){
        val store=FaultStore()
        EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p,
            startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
            Stage3Peer.await("unlocked startup verified"){r.authority.snapshot().storageReady}
            r.emergencyStop();Stage3Peer.await("real estop saved"){p.snapshot().ready && store.disk?.locked==true}
        }}
        repeat(2){EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p,
            startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
            Stage3Peer.await("saved lock restored"){r.authority.snapshot().storageReady}
            assertTrue(r.authority.snapshot().emergencyLocked)
            r.configureFeedbackProtection(false);r.ordinaryStop()
            assertFalse(r.manual(ControlCommand.RIGHT));assertEquals(StoredEmergency(true),store.disk)
        }}}
    }
    @Test fun corruptUnreadableAndFailedStorageCannotBecomeAnUnlockedRelaxedStartup(){
        for(fault in listOf("schema","read","commit")){
            val store=FaultStore(if(fault=="schema")StoredEmergency(false,99) else StoredEmergency(false)).apply{
                readFailure=fault=="read";fail=fault=="commit"
            }
            EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p,
                startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
                Stage3Peer.await("unknown state fails closed"){p.snapshot().failures>0 && r.authority.snapshot().emergencyLocked}
                assertFalse(r.authority.snapshot().storageReady);assertFalse(r.manual(ControlCommand.FORWARD))
            }}
        }
    }
    @Test fun anEstopDuringRestoreSupersedesThePendingUnlockedStartup(){
        val gate=CountDownLatch(1);val store=FaultStore().apply{waitFor=gate}
        EmergencyPersistence(store).use{p->ControlRuntime(StreamSession(),durableEmergency=p,
            startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
            try{
                assertTrue(store.entered.await(1,TimeUnit.SECONDS));r.emergencyStop();gate.countDown()
                Stage3Peer.await("newer estop persists"){r.authority.snapshot().storageReady && store.disk?.locked==true}
                assertTrue(r.authority.snapshot().emergencyLocked);assertFalse(r.manual(ControlCommand.FORWARD))
            }finally{gate.countDown()}
        }}
    }
    private class EegRig(strict:Boolean=false){
        var now=0L;private var count=0L
        val a=ControlAuthority({now},{now*1_000_000},feedbackRequired=strict)
        private val gate=DecisionGate(RecognitionConfig())
        init{a.boundary(1,1,"INFO")}
        fun feedback(flags:Int=0,action:Int=5){a.feedback(UnityState(flags,action,0,0,0,0,30,now+1),now*1_000_000)}
        fun vote(){
            val scores=doubleArrayOf(.9,.1,.1,.1);val d=gate.evaluate(scores,now)
            val c=RecognitionControl();c.accept(d.raw,d.stable,now,now)
            a.recognition(RecognitionSnapshot(scores.toList(),d.raw,d.stable,d.reason,generation=1,
                windowEnd=3000+count*250,analysisCount=++count,decidedMs=now,inputMs=now,control=c.state))
        }
        fun acquire(){vote();now=250;vote();now=500;vote();assertEquals(ControlCommand.FORWARD,a.snapshot().command)}
    }
    @Test fun disabledFeedbackTimeoutDoesNotBlockManualButFourSecondDeadlineStillStops(){
        val r=EegRig();assertTrue(r.a.manual(ControlCommand.LEFT)) // no feedback has ever arrived
        r.feedback();r.now=1500;r.a.enforceDeadlines()
        assertEquals(ControlCommand.LEFT,r.a.snapshot().command);assertFalse(r.a.snapshot().feedbackRecoveryRequired)
        r.now=4000;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.a.manual(ControlCommand.RIGHT);r.a.ordinaryStop();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.a.emergencyStop();r.a.configureFeedbackProtection(true);r.a.configureFeedbackProtection(false)
        assertEquals(ControlCommand.ESTOP,r.a.snapshot().command)
    }
    @Test fun disabledFeedbackTimeoutAllowsFreshEegButNotEegDataExpiryOrStreamStop(){
        val r=EegRig();r.acquire()
        repeat(6){r.now+=250;r.vote();assertEquals(ControlCommand.FORWARD,r.a.snapshot().command)}
        assertFalse(r.a.snapshot().feedbackRecoveryRequired)
        r.now+=1000;r.a.enforceDeadlines();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        val s=EegRig();s.acquire();s.a.boundary(2,1,"STREAM_STOP")
        assertEquals(ControlCommand.STOP,s.a.snapshot().command);s.vote()
        assertEquals(ControlCommand.STOP,s.a.snapshot().command) // old generation is invalid
    }
    @Test fun enablingProtectionStopsFirstAndRestoredFeedbackCannotReplayOldMotion(){
        val r=EegRig();r.a.manual(ControlCommand.FORWARD)
        r.a.configureFeedbackProtection(true);assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        r.feedback();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.manual(ControlCommand.LEFT));r.now=1000;r.a.enforceDeadlines()
        assertEquals(ControlCommand.STOP,r.a.snapshot().command);assertTrue(r.a.snapshot().feedbackRecoveryRequired)
        r.a.configureFeedbackProtection(false);assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.manual(ControlCommand.RIGHT)) // expiry alone no longer locks this simulation
    }
    @Test fun disablingTimeoutCannotClearARemoteFaultOrTransportFault(){
        val r=EegRig(true);r.feedback();r.a.manual(ControlCommand.FORWARD)
        r.now=1000;r.a.enforceDeadlines();r.feedback(flags=2) // upgrade timeout to real remote fault
        r.a.configureFeedbackProtection(false)
        assertTrue(r.a.snapshot().feedbackSafetyFault);assertFalse(r.a.manual(ControlCommand.LEFT))
        r.now+=2000;assertFalse(r.a.manual(ControlCommand.LEFT))
        r.feedback();assertEquals(ControlCommand.STOP,r.a.snapshot().command)
        assertTrue(r.a.manual(ControlCommand.LEFT)) // fresh healthy stationary + explicit new authorization
        r.a.transportFailed("injected");r.a.configureFeedbackProtection(true);r.a.configureFeedbackProtection(false)
        assertNotNull(r.a.snapshot().transportFault);assertFalse(r.a.manual(ControlCommand.FORWARD))
    }
    @Test fun relaxedRuntimeStillSendsStopAndHonorsRemoteFaultAndRemoteEstopOverUdp(){
        Stage3Peer().use{peer->EmergencyPersistence(FaultStore()).use{p->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link,durableEmergency=p,
                startupEmergencyLock=false,feedbackTimeoutStop=false).use{r->
                r.start();peer.feedbackPort=link.boundFeedbackPort
                Stage3Peer.await("normal startup verified"){r.authority.snapshot().storageReady}
                assertTrue(r.manual(ControlCommand.FORWARD));Stage3Peer.await("actual UDP forward"){peer.applied==1}
                peer.feedbackEnabled=false;Thread.sleep(1150)
                assertEquals(ControlCommand.FORWARD,r.authority.snapshot().command)
                assertEquals(1,peer.applied);assertFalse(link.snapshot().feedbackOnline)
                r.ordinaryStop();Stage3Peer.await("actual UDP ordinary STOP"){peer.applied==5}
                assertTrue(r.manual(ControlCommand.LEFT));Stage3Peer.await("actual UDP left"){peer.applied==3}
                peer.fault=true;peer.feedbackEnabled=true
                Stage3Peer.await("remote fault overrides relaxed timeout"){r.authority.snapshot().feedbackSafetyFault && peer.applied==5}
                assertFalse(r.manual(ControlCommand.FORWARD))
                peer.send(Stage3Peer.status(flags=1,action=6,time=0x70000000L))
                Stage3Peer.await("remote emergency still locks and sends 06"){r.authority.snapshot().emergencyLocked && peer.records.any{it.code==6}}
                Stage3Peer.await("remote lock saved"){p.snapshot().ready && p.snapshot().locked}
                r.ordinaryStop();assertTrue(r.authority.snapshot().emergencyLocked)
            }
        }}
    }
}
