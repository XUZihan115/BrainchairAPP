package com.yourteam.brainchair

import com.yourteam.brainchair.comm.*
import com.yourteam.brainchair.model.*
import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test fun parserRecoversFromNoiseFragmentationAndBadChecksum() {
        val actual = mutableListOf<Frame>(); val parser = ProtocolParser(actual::add)
        val bad = Protocol.build(1, 30).apply { this[4] = 0 }
        val stream = byteArrayOf(9, 0xAA.toByte(), 1) + bad + Protocol.build(1, 30) + Protocol.build(5, 0)
        stream.forEach { parser.feed(byteArrayOf(it)) }
        assertEquals(listOf(Frame(1, 30), Frame(5, 0)), actual); assertEquals(1, parser.invalidFrames)
    }
    @Test fun heartbeatTimeoutLatchesAndCannotResumeByHeartbeatAlone() {
        val e = ChairEngine {}; e.receive(Frame(8, 0), 0); e.receive(Frame(1, 100), 0)
        assertEquals(30, e.speed); e.tick(499); assertEquals(30, e.speed)
        e.tick(500); assertEquals(0, e.speed); assertTrue(e.latched)
        e.receive(Frame(8, 0), 501); e.receive(Frame(1, 30), 501); assertEquals(0, e.speed)
        e.receive(Frame(9, 0), 502); assertFalse(e.latched); assertEquals(0, e.speed)
    }
    @Test fun heartbeatCannotKeepOldMotionAlive() {
        val e = ChairEngine {}; e.receive(Frame(8, 0), 0); e.receive(Frame(1, 30), 0)
        e.receive(Frame(8, 0), 400); e.tick(500); assertEquals(0, e.speed); assertTrue(e.latched)
    }
    @Test fun emergencyRejectsMotionUntilExplicitReset() {
        val e = ChairEngine {}; e.receive(Frame(8, 0), 0); e.receive(Frame(7, 0), 10)
        (1..4).forEach { e.receive(Frame(it, 30), 20) }; assertEquals(0, e.speed)
        e.receive(Frame(5, 0), 30); assertTrue(e.latched)
        e.receive(Frame(9, 0), 40); assertFalse(e.latched); assertEquals(0, e.speed)
    }
    @Test fun stopAndLimitAreApplied() {
        val e = ChairEngine {}; e.receive(Frame(8, 0), 0); e.receive(Frame(1, 30), 1)
        e.receive(Frame(6, 12), 2); assertEquals(12, e.speed)
        e.receive(Frame(5, 0), 3); assertEquals(0, e.speed)
    }
    @Test fun invalidFramesCannotMoveDevice() {
        val e = ChairEngine {}; e.receive(Frame(1, 30), 0); assertEquals(0, e.speed)
        e.receive(Frame(8, 0), 0); e.receive(Frame(1, 255), 1); assertEquals(0, e.speed)
        val parser = ProtocolParser { e.receive(it, 2) }
        parser.feed(Protocol.build(1, 30).apply { this[4] = 1 }); assertEquals(0, e.speed)
    }
    @Test fun eegRejectsStaleLowConfidenceAndReplay() {
        val g = IntentGate()
        assertFalse(g.accept(1, 0.9, 0, 1, false)); assertFalse(g.accept(1, 0.7, 0, 1, true))
        assertFalse(g.accept(1, 0.9, 301, 1, true)); assertFalse(g.accept(1, Double.NaN, 0, 1, true))
        assertTrue(g.accept(1, 0.9, 0, 1, true)); assertFalse(g.accept(1, 0.9, 0, 1, true))
    }
}
