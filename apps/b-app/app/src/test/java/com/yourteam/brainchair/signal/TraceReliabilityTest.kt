package com.yourteam.brainchair.signal

import com.yourteam.brainchair.control.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

class TraceReliabilityTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun unwritableDirectoryIsReportedAndWarningsAreRateLimited(){
        val path=folder.newFile("not-a-directory");val now=AtomicLong();val warnings=mutableListOf<String>()
        val health=TraceHealth({now.get()}){warnings.add(it)}
        val sink=FileTraceStorage(path,{})
        repeat(50){health.protect{health.phase("latest");sink.latest("{}")}}
        assertEquals(50L,health.snapshot().failures);assertEquals("FAULT",health.snapshot().state)
        assertEquals(1,warnings.size);assertTrue(health.snapshot().lastError!!.contains("不可写"))
        now.set(5000);health.protect{sink.latest("{}")};assertEquals(2,warnings.size)
        assertEquals(5000L,health.snapshot().lastFailureMs)
    }
    @Test fun rotationFailureDoesNotAppendWithoutBoundAndRecoveryWorks(){
        val dir=folder.newFolder("rotation");val health=TraceHealth();val current=File(dir,"control-diagnostics.jsonl")
        val sink=FileTraceStorage(dir,{},limit=128,rotate={_,_->throw IOException("injected rotation failure")})
        repeat(3){assertTrue(health.protect{sink.diagnostic("x".repeat(50))})}
        val length=current.length();assertTrue(length>=128)
        repeat(20){assertFalse(health.protect{health.phase("rotation");sink.diagnostic("y".repeat(50))})}
        assertEquals(length,current.length());assertEquals(20L,health.snapshot().failures)
        val recovered=FileTraceStorage(dir,{},limit=128)
        assertTrue(health.protect{recovered.diagnostic("recovered")})
        assertEquals("OK",health.snapshot().state);assertEquals(0L,health.snapshot().consecutiveFailures)
        assertEquals(length,File(dir,"control-diagnostics.previous.jsonl").length());assertTrue(current.length()<128)
    }
    @Test fun latestAndCsvFailuresStayVisibleWithoutGrowingCsvForever(){
        val dir=folder.newFolder("csv");val health=TraceHealth()
        val sink=FileTraceStorage(dir,{throw IOException("injected atomic latest failure")},limit=128,rowLimit=3)
        assertFalse(health.protect{health.phase("latest");sink.latest("{}")})
        repeat(100){sink.csv("header\n","row")}
        assertEquals(4,File(dir,"stage2-trace.csv").readLines().size)
        assertEquals(1L,health.snapshot().failures)
        val csv=File(dir,"stage2-trace.csv");assertTrue(csv.delete());assertTrue(csv.mkdir())
        val failing=FileTraceStorage(dir,{})
        assertFalse(health.protect{health.phase("csv");failing.csv("header\n","row")})
        assertEquals(2L,health.snapshot().failures);assertTrue(health.snapshot().lastError!!.contains("csv"))
    }
    @Test fun blockedWriterIsObservableAndCannotDelayDeadlineOrEstop(){
        val now=AtomicLong();val health=TraceHealth({now.get()})
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val logger=Thread{health.protect{health.phase("blocked writer");entered.countDown();release.await(3,TimeUnit.SECONDS)}}.apply{start()}
        val a=ControlAuthority({now.get()});val receiver=SimulatedUnityReceiver()
        ControlDispatcher(a,SimulatedUnityTransport(receiver)).use{d->
            try{
                assertTrue(entered.await(1,TimeUnit.SECONDS));d.start();a.manual(ControlCommand.FORWARD)
                Stage3Peer.await("motion before deadline"){receiver.current()==ControlCommand.FORWARD}
                now.set(4000);a.enforceDeadlines()
                Stage3Peer.await("STOP independent of logger"){receiver.current()==ControlCommand.STOP}
                assertEquals("STALLED",health.snapshot().state)
                val begin=System.nanoTime();a.emergencyStop();assertTrue((System.nanoTime()-begin)/1e6<100)
                Stage3Peer.await("ESTOP independent of logger"){receiver.current()==ControlCommand.ESTOP}
                assertEquals(ControlCommand.ESTOP,a.snapshot().command)
            }finally{release.countDown();logger.join(1000)}
        }
    }
    @Test fun warningFailureIsAlsoCountedAndDoesNotEscapeIntoControl(){
        val health=TraceHealth(warn={throw IOException("warning output failed")})
        assertFalse(health.protect{throw IOException("trace write failed")})
        assertEquals(1L,health.snapshot().failures);assertEquals(1L,health.snapshot().warningFailures)
        assertNotNull(health.snapshot().lastError)
    }
}
