package com.yourteam.brainchair.control

import com.yourteam.brainchair.receiver.*
import com.yourteam.brainchair.signal.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

class Stage3ControlTest {
    @Test fun manualLatchRenewsEvery200msWithoutAAndModeSwitchStops() {
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link).use { r->
                r.start();peer.feedbackPort=link.boundFeedbackPort
                r.switchMode(ControlMode.MANUAL);Stage3Peer.await("manual mode ready"){r.authority.snapshot().let{it.mode==ControlMode.MANUAL && !it.transitioning}}
                assertTrue(r.manual(ControlCommand.LEFT))
                Stage3Peer.await("manual motion datagrams"){peer.records.count{it.code==3}>=7}
                val motion=peer.records.filter{it.code==3};val gaps=motion.zipWithNext{a,b->(b.ns-a.ns)/1e6}.drop(1)
                assertTrue("200ms repeat $gaps",gaps.all{it in 150.0..350.0});assertEquals(3,peer.applied)
                assertTrue(motion.all{it.speed==30})
                r.switchMode(ControlMode.EEG)
                Stage3Peer.await("mode change wire STOP"){peer.records.last().code==5}
                assertFalse(r.manual(ControlCommand.RIGHT));Thread.sleep(260);assertEquals(5,peer.applied)
                Stage3Peer.evidence("manual-heartbeat.csv","index,command,receivedNs,hex\n"+peer.records.mapIndexed{i,a->"$i,${a.code},${a.ns},${a.hex}"}.joinToString("\n")+"\n")
            }
        }
    }
    @Test fun allManualCommandsStopSpeedZeroAndTargetChangeAreActualUdp() {
        Stage3Rig().use { r->
            r.runtime.switchMode(ControlMode.MANUAL)
            for(c in ControlCommand.values().filter{it.moving}){assertTrue(r.runtime.manual(c));r.flush();assertEquals(c.code,r.wire())}
            r.runtime.ordinaryStop();r.flush();assertEquals(5,r.wire())
            r.runtime.authority.setSpeed(100);r.runtime.manual(ControlCommand.RIGHT);r.flush();assertEquals(100,r.peer.records.last().speed)
            r.runtime.authority.setSpeed(0);r.flush();assertEquals(5,r.wire())
            assertFalse(r.runtime.manual(ControlCommand.FORWARD));r.flush();assertEquals(5,r.wire())
            r.runtime.authority.setSpeed(30);r.runtime.manual(ControlCommand.FORWARD);r.flush();assertEquals(1,r.wire())
            r.runtime.configureUnity("",30)
            Stage3Peer.await("old target STOP before detach"){r.peer.records.last().code==5}
            val count=r.peer.records.size;r.runtime.dispatcher.flush();Thread.sleep(30);assertEquals(count,r.peer.records.size)
            r.runtime.configureUnity("127.0.0.1",40);r.flush();assertEquals(5,r.wire())
            assertEquals(40,r.runtime.authority.snapshot().speed)
        }
    }
    @Test fun manualHasExclusiveDirectionAuthorityAndEegNeedsFreshFlowAfterModeChange() {
        Stage3Rig().use { r->
            r.runtime.switchMode(ControlMode.MANUAL);r.runtime.manual(ControlCommand.RIGHT);r.flush()
            r.info();r.feed(3750,12.0);assertEquals(Direction.FORWARD,r.runtime.engine.eventSnapshot().stable)
            assertEquals(4,r.wire());r.stop();assertEquals(4,r.wire()) // A cannot override an explicit MANUAL direction.
            r.runtime.switchMode(ControlMode.EEG);r.flush();assertEquals(5,r.wire())
            r.info();r.feed(3750);assertEquals(1,r.wire())
            r.runtime.ordinaryStop();r.flush();r.feed(1000);assertEquals(5,r.wire())
            r.info(32000);r.feed(3750,8.57);assertEquals("${r.runtime.engine.eventSnapshot()} / ${r.runtime.authority.snapshot()} / ${r.session.snapshot()}",3,r.wire())
        }
    }
    @Test fun estopBurstIsThreeFramesThenStopAndZConfirmationNeverAutoResumes() {
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            val persisted=mutableListOf<Boolean>()
            ControlRuntime(StreamSession(),unityLink=link,persistEmergency={persisted.add(it)}).use { r->
                r.start();peer.feedbackPort=link.boundFeedbackPort;r.switchMode(ControlMode.MANUAL)
                Stage3Peer.await("manual ready"){!r.authority.snapshot().transitioning};r.manual(ControlCommand.FORWARD)
                Stage3Peer.await("moving"){peer.applied==1}
                r.emergencyStop();assertFalse(r.releaseEmergency())
                Stage3Peer.await("three ESTOP frames"){peer.records.count{it.code==6}==3}
                val burst=peer.records.filter{it.code==6};val gaps=burst.zipWithNext{a,b->(b.ns-a.ns)/1e6}
                assertTrue("30ms ESTOP burst $gaps",gaps.all{it in 20.0..180.0})
                Thread.sleep(450);assertEquals(3,peer.records.count{it.code==6});assertEquals(5,peer.records.last().code)
                assertEquals(6,peer.applied);assertTrue(r.authority.snapshot().emergencyLocked)
                r.ordinaryStop();r.switchMode(ControlMode.EEG);assertTrue(r.authority.snapshot().emergencyLocked)
                Stage3Peer.await("Unity reports emergency"){link.snapshot().feedback?.emergency==true}
                assertFalse(r.releaseEmergency());peer.z()
                Stage3Peer.await("Unity Z feedback"){link.snapshot().feedback?.let{!it.emergency && it.stationary}==true}
                assertTrue(r.releaseEmergency());assertFalse(r.authority.snapshot().emergencyLocked)
                Thread.sleep(260);assertEquals(5,peer.applied);assertTrue(peer.records.none{it.code==7})
                assertEquals(listOf(true,false),persisted)
                Stage3Peer.evidence("emergency-burst.csv","command,receivedNs,hex\n"+peer.records.joinToString("\n"){"${it.code},${it.ns},${it.hex}"}+"\n")
            }
        }
    }
    @Test fun remoteFaultBlocksNewMovementAndClearingNeedsNewAction() {
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            ControlRuntime(StreamSession(),unityLink=link).use { r->
                r.start();peer.feedbackPort=link.boundFeedbackPort;r.switchMode(ControlMode.MANUAL)
                Stage3Peer.await("manual ready"){!r.authority.snapshot().transitioning};r.manual(ControlCommand.BACKWARD)
                Stage3Peer.await("manual back"){peer.applied==2};peer.fault=true
                Stage3Peer.await("remote fault STOP"){r.authority.snapshot().remoteFault && peer.records.last().code==5}
                assertFalse(r.manual(ControlCommand.FORWARD));peer.fault=false
                Stage3Peer.await("fault clear"){!r.authority.snapshot().remoteFault}
                assertEquals(ControlCommand.STOP,r.authority.snapshot().command)
                assertTrue(r.manual(ControlCommand.RIGHT));Stage3Peer.await("new manual input"){peer.applied==4}
            }
        }
    }
    @Test fun stopAndNewInfoPreemptBlockedActualCcaAndNeverSendOldMotion() {
        for(newInfo in listOf(false,true)) {
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean(false)
            Stage3Rig { c,x->if(blocked.get()){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};c.scores(x) }.use { r->
                r.info();r.feed(3750);assertEquals(1,r.wire());r.feed(250,tick=false);blocked.set(true)
                val t=Thread{r.runtime.engine.tick()};t.start()
                try {
                    assertTrue(entered.await(2,TimeUnit.SECONDS))
                    if(newInfo)r.info(34000) else r.stop()
                    val cut=r.peer.records.size;assertEquals(5,r.wire())
                    release.countDown();t.join(3000);assertFalse(t.isAlive);repeat(3){r.flush()}
                    assertTrue(r.peer.records.drop(cut).all{it.code==5})
                }finally{release.countDown();t.join(3000)}
            }
        }
    }
    @Test fun crcLossLatePacketInterruptAndRestartAlwaysRevokeEegWireMotion() {
        Stage3Rig().use { r->
            r.info();r.feed(3750);assertEquals(1,r.wire())
            val corrupt=Stage3Signals.wave(r.seq,r.first,r.origin).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
            r.session.accept(corrupt,r.source);r.flush();assertEquals(5,r.wire())
            r.info();r.feed(3750);assertEquals(1,r.wire())
            r.session.accept(Stage3Signals.wave(r.seq+1,r.first+5,r.origin),r.source);r.flush();assertEquals(5,r.wire())
            val size=r.session.snapshot().buffered
            r.session.accept(Stage3Signals.wave(r.seq-1,r.first-5,r.origin),r.source);r.flush();assertEquals(size,r.session.snapshot().buffered);assertEquals(5,r.wire())
            r.info();r.feed(3750);assertEquals(1,r.wire());r.now+=1001;r.runtime.engine.guard();r.flush();assertEquals(5,r.wire())
            r.info();r.feed(3750);assertEquals(1,r.wire());val oldOrigin=r.origin;val oldSource=r.source
            r.stop();r.info(35000)
            r.session.accept(Stage3Signals.wave(752,3750,oldOrigin),oldSource);r.flush()
            assertEquals(0,r.session.snapshot().buffered);assertEquals(5,r.wire())
            r.feed(3750,8.57);assertEquals(3,r.wire());r.stop();r.stop();assertEquals(5,r.wire())
        }
    }
    @Test fun panicIsPersistentOnStartupAndNewEegCannotUnlock() {
        Stage3Rig().use { r->
            r.runtime.emergencyStop();r.flush();r.info();r.feed(3750);r.stop();r.info(36000);r.feed(3750,8.57)
            assertTrue(r.runtime.authority.snapshot().emergencyLocked);assertTrue(r.peer.records.none{it.code in 1..4})
        }
        val a=ControlAuthority(emergencyInitiallyLocked=true);a.boundary(20,10,"new INFO");a.ordinaryStop();assertTrue(a.snapshot().emergencyLocked)
    }
    @Test fun invalidSignalGateAndMissingChannelsStopWithDataStillArriving() {
        Stage3Rig().use { r->
            r.info();r.feed(3750);assertEquals(1,r.wire())
            repeat(650){r.session.accept(Stage3Signals.noise(r.seq++,r.first,r.origin),r.source);r.first+=5;r.now+=5
                if(r.first%250==0L)r.runtime.engine.tick()}
            r.flush();assertNotNull(r.session.health().info) // not a network timeout or STREAM_STOP
            assertEquals(Direction.STOP,r.runtime.engine.eventSnapshot().raw);assertEquals(5,r.wire())
            r.info();r.feed(3750);assertEquals(1,r.wire())
            r.session.accept(Fixtures.info(names=listOf("Oz","O1")),r.source);r.runtime.engine.tick();r.flush()
            assertEquals(5,r.wire());assertTrue(r.runtime.engine.eventSnapshot().reason.contains("缺少"))
        }
    }
    @Test fun independentPeerOneSecondLeaseStopsIfAllUdpControlIsLost() {
        Stage3Peer().use { peer->
            val link=UnityUdpLink("127.0.0.1",peer.port,0)
            val r=ControlRuntime(StreamSession(),unityLink=link)
            try{
                r.start();r.switchMode(ControlMode.MANUAL);Stage3Peer.await("manual ready"){!r.authority.snapshot().transitioning};r.manual(ControlCommand.FORWARD)
                Stage3Peer.await("motion before loss"){peer.applied==1};link.close() // deliberately loses STOP as well
                val last=peer.records.last{it.code==1}.ns
                Stage3Peer.await("peer lease stops",2000){peer.applied==5}
                val elapsed=(System.nanoTime()-last)/1e6;assertTrue("peer lease $elapsed",elapsed in 990.0..1500.0)
                Stage3Peer.evidence("peer-lease.txt","Independent UDP peer only, not actual Unity: $elapsed ms after last motion datagram.\n")
            }finally{r.close()}
        }
    }
    @Test fun modeConfigurationBarriersAndShutdownNeverSendFromUiCaller() {
        val sent=CopyOnWriteArrayList<Pair<String,ControlCommand>>()
        val a=ControlAuthority()
        val transport=object:UnityTransport {
            override val mode="THREAD_ASSERTING_TEST"
            override fun send(command:UnityCommand){check(Thread.currentThread().name=="control-delivery");sent.add(Thread.currentThread().name to command.command)}
        }
        val dispatcher=ControlDispatcher(a,transport);dispatcher.start()
        try {
            dispatcher.transition{a.switchMode(ControlMode.MANUAL)}
            Stage3Peer.await("mode barrier done"){!a.snapshot().transitioning}
            assertTrue(a.manual(ControlCommand.FORWARD));Stage3Peer.await("worker movement"){sent.any{it.second==ControlCommand.FORWARD}}
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            dispatcher.reconfigure{entered.countDown();check(release.await(2,TimeUnit.SECONDS))}
            try{
                assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(a.manual(ControlCommand.LEFT))
                assertEquals(ControlCommand.STOP,sent.last().second)
            }finally{release.countDown()}
            Stage3Peer.await("configuration barrier done"){!a.snapshot().transitioning}
            assertEquals(ControlCommand.STOP,a.snapshot().command)
        }finally{dispatcher.close()}
        assertTrue(sent.isNotEmpty());assertTrue(sent.all{it.first=="control-delivery"});assertEquals(ControlCommand.STOP,sent.last().second)
    }
}
