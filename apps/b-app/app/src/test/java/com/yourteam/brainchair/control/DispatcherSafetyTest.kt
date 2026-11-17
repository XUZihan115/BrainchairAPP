package com.yourteam.brainchair.control

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class DispatcherSafetyTest {
    private class BlockingWire(private val ignoreInterrupts:Boolean=false):UnityTransport {
        override val mode="ISOLATED_BLOCKING_TRANSPORT"
        val entered=CountDownLatch(1);val gate=CountDownLatch(1)
        val sent=CopyOnWriteArrayList<UnityCommand>()
        override fun send(command:UnityCommand){sent.add(command)}
        override fun sendGuarded(command:UnityCommand,current:()->Boolean,priority:Boolean):Boolean {
            if(!priority){
                entered.countDown()
                if(ignoreInterrupts){val end=System.nanoTime()+4_000_000_000L
                    while(gate.count>0 && System.nanoTime()<end)try{gate.await(10,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){}
                }else gate.await(4,TimeUnit.SECONDS)
            }
            if(!current())return false
            sent.add(command);return true
        }
    }
    @Test fun blockedNormalSendDoesNotHoldAuthorityOrDelayEmergencyPriorityLane(){
        val wire=BlockingWire();val a=ControlAuthority();ControlDispatcher(a,wire).use{d->
            try{
                d.start();a.manual(ControlCommand.FORWARD);assertTrue(wire.entered.await(1,TimeUnit.SECONDS))
                val before=System.nanoTime();a.emergencyStop();val elapsed=(System.nanoTime()-before)/1e6
                assertTrue("emergency API latency $elapsed",elapsed<100)
                Stage3Peer.await("priority ESTOP without releasing normal IO",500){wire.sent.any{it.command==ControlCommand.ESTOP}}
                Stage3Peer.await("blocked send budget is visible",500){d.status().timeouts>0}
                assertEquals(ControlCommand.ESTOP,a.snapshot().command);assertNotNull(a.snapshot().transportFault)
                wire.gate.countDown();Thread.sleep(80)
                assertTrue(wire.sent.none{it.command.moving});assertFalse(a.manual(ControlCommand.LEFT))
                safetyEvidence("blocked-send.txt","estopApiMs=$elapsed\n${d.status()}\nNo canceled motion emitted after the blocked pre-IO stage was released.\n")
            }finally{wire.gate.countDown()}
        }
    }
    @Test fun fourSecondDeadlineRunsWhileOrdinarySendIsBlocked(){
        val now=AtomicLong();val a=ControlAuthority({now.get()});val wire=BlockingWire()
        ControlDispatcher(a,wire).use{d->
            try{
                d.start();a.manual(ControlCommand.LEFT);assertTrue(wire.entered.await(1,TimeUnit.SECONDS))
                now.set(4000)
                Stage3Peer.await("independent deadline STOP",500){a.snapshot().command==ControlCommand.STOP}
                Stage3Peer.await("priority STOP despite blocked normal worker",500){wire.sent.any{it.command==ControlCommand.STOP}}
                wire.gate.countDown();Thread.sleep(60);assertTrue(wire.sent.none{it.command.moving})
            }finally{wire.gate.countDown()}
        }
    }
    @Test fun arbitraryDeliveryCallbackAndConfigurationLockContentionCannotBlockEstop(){
        val a=ControlAuthority();val entered=CountDownLatch(1);val gate=CountDownLatch(1)
        val t=Thread{a.deliver{entered.countDown();gate.await(2,TimeUnit.SECONDS)}}.apply{start()}
        try{assertTrue(entered.await(1,TimeUnit.SECONDS));a.emergencyStop();assertEquals(ControlCommand.ESTOP,a.snapshot().command)}
        finally{gate.countDown();t.join(1000)}
        val b=ControlAuthority();val token=b.beginTransition();val applying=CountDownLatch(1);val finish=CountDownLatch(1)
        val config=Thread{b.runTransition(token){applying.countDown();finish.await(2,TimeUnit.SECONDS)}}.apply{start()}
        try{
            assertTrue(applying.await(1,TimeUnit.SECONDS));assertFalse(b.manual(ControlCommand.FORWARD))
            val begin=System.nanoTime();b.emergencyStop();assertTrue((System.nanoTime()-begin)/1e6<100)
            assertEquals(ControlCommand.ESTOP,b.snapshot().command)
        }finally{finish.countDown();config.join(1000)}
    }
    @Test fun sendExceptionsLatchFaultAndOrdinaryStopNeverClearsEstop(){
        val a=ControlAuthority();val sent=CopyOnWriteArrayList<UnityCommand>()
        val wire=object:UnityTransport{
            override val mode="ISOLATED_SEND_ERROR"
            override fun send(command:UnityCommand){if(command.command.moving)throw IOException("injected send failure");sent.add(command)}
        }
        ControlDispatcher(a,wire).use{d->
            d.start();a.manual(ControlCommand.FORWARD)
            Stage3Peer.await("send exception revoke"){a.snapshot().transportFault!=null}
            assertEquals(ControlCommand.STOP,a.snapshot().command);assertFalse(a.manual(ControlCommand.RIGHT))
            a.emergencyStop();a.ordinaryStop();a.manual(ControlCommand.FORWARD)
            Stage3Peer.await("estop survives mixed commands"){sent.any{it.command==ControlCommand.ESTOP}}
            assertTrue(a.snapshot().emergencyLocked);assertTrue(d.status().failures>0)
        }
    }
    @Test fun closeIsBoundedWhenAProviderIgnoresInterrupts(){
        val wire=BlockingWire(true);val a=ControlAuthority();val d=ControlDispatcher(a,wire)
        try{
            d.start();a.manual(ControlCommand.FORWARD);assertTrue(wire.entered.await(1,TimeUnit.SECONDS))
            val begin=System.nanoTime();d.close();val elapsed=(System.nanoTime()-begin)/1e6
            assertTrue("close caller wait $elapsed",elapsed<400)
        }finally{wire.gate.countDown();d.close()}
    }
    @Test fun aFullSafetyWakeupQueueDoesNotTurnAConfigurationBarrierIntoATransportFault(){
        // First STOP is running and the next STOP occupies the only queue slot.
        // The configuration barrier must wait within its budget, not treat rejection
        // by the bounded executor as an IO exception and permanently disable motion.
        val entered=CountDownLatch(1);val gate=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val a=ControlAuthority()
        val wire=object:UnityTransport {
            override val mode="ISOLATED_QUEUE_CONTENTION"
            override fun send(command:UnityCommand){
                if(command.command==ControlCommand.STOP && first.compareAndSet(true,false)){
                    entered.countDown();check(gate.await(2,TimeUnit.SECONDS))
                }
            }
        }
        ControlDispatcher(a,wire).use{d->
            try{
                d.start();assertTrue(entered.await(1,TimeUnit.SECONDS))
                a.ordinaryStop() // queue the coalesced safety wakeup behind the running STOP
                d.transition{a.switchMode(ControlMode.MANUAL)}
                Thread.sleep(20);gate.countDown()
                Stage3Peer.await("contended configuration completes"){!a.snapshot().transitioning}
                assertNull("${d.status()}",a.snapshot().transportFault)
                assertTrue("${a.snapshot()}",a.manual(ControlCommand.FORWARD))
            }finally{gate.countDown()}
        }
    }
}
