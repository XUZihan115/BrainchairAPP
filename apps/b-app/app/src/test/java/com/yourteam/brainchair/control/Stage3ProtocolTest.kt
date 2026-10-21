package com.yourteam.brainchair.control

import org.junit.Assert.*
import org.junit.Test
import java.net.*

class Stage3ProtocolTest {
    private fun bytes(hex:String)=hex.split(' ').map{it.toInt(16).toByte()}.toByteArray()
    @Test fun commandGoldenFramesMatchUnityDocument() {
        val vectors=listOf(
            Triple(ControlCommand.FORWARD,50,"AA 55 01 02 01 32 36 0D 0A"),
            Triple(ControlCommand.FORWARD,100,"AA 55 01 02 01 64 68 0D 0A"),
            Triple(ControlCommand.FORWARD,null,"AA 55 01 01 01 03 0D 0A"),
            Triple(ControlCommand.BACKWARD,30,"AA 55 01 02 02 1E 23 0D 0A"),
            Triple(ControlCommand.LEFT,100,"AA 55 01 02 03 64 6A 0D 0A"),
            Triple(ControlCommand.RIGHT,50,"AA 55 01 02 04 32 39 0D 0A"),
            Triple(ControlCommand.STOP,null,"AA 55 01 01 05 07 0D 0A"),
            Triple(ControlCommand.ESTOP,null,"AA 55 01 01 06 08 0D 0A"))
        for((command,speed,expected) in vectors)assertArrayEquals(expected,bytes(expected),UnitySum8Codec.command(command,speed))
        assertArrayEquals(bytes("AA 55 01 02 01 00 04 0D 0A"),UnitySum8Codec.command(ControlCommand.FORWARD,0))
        assertEquals(9001,UnitySum8Codec.CONTROL_PORT);assertEquals(9002,UnitySum8Codec.STATUS_PORT)
    }
    private fun rejected(b:ByteArray,reason:String?=null){try{UnitySum8Codec.state(b);fail("bad status accepted")}catch(e:UnityFrameError){if(reason!=null)assertEquals(reason,e.reason)}}
    @Test fun statusExampleChecksumIsCorrectedByFormulaNotCopied() {
        val example=bytes("AA 55 03 0F 08 01 F4 01 00 00 7B 00 C8 01 32 60 EA 00 00 CE 0D 0A")
        rejected(example,"sum8")
        example[19]=0xd0.toByte()
        val s=UnitySum8Codec.state(example)
        assertEquals(500,s.linearMmS);assertEquals(123,s.xCm);assertEquals(456,s.zCm)
        assertEquals(50,s.speedPercent);assertEquals(60000L,s.timestampMs);assertTrue(s.moving)
    }
    @Test fun signedEndianUnsignedUptimeAndBinaryCrLfAreParsed() {
        val p=Stage3Peer.status(flags=3,action=6,linear=-321,angular=-2345,x=-300,z=0x0a0d,speed=100,time=0xfedcba98)
        val s=UnitySum8Codec.state(p)
        assertEquals(-321,s.linearMmS);assertEquals(-2345,s.angularMilliRadS);assertEquals(-300,s.xCm)
        assertEquals(0x0a0d,s.zCm);assertEquals(0xfedcba98L,s.timestampMs);assertTrue(s.emergency);assertTrue(s.fault)
    }
    @Test fun malformedStatusAndConfigurationAreRejected() {
        val good=Stage3Peer.status()
        for(n in 0 until good.size)rejected(good.copyOf(n),"length")
        rejected(good+0,"length");rejected(good.copyOf().apply{this[7]=99},"sum8")
        rejected(good.copyOf().apply{this[2]=1},"type/length")
        rejected(good.copyOf().apply{this[3]=14},"type/length")
        rejected(good.copyOf().apply{this[21]=0},"delimiter")
        rejected(Stage3Peer.status(flags=16),"fields");rejected(Stage3Peer.status(action=7),"fields");rejected(Stage3Peer.status(speed=101),"fields")
        for(ip in listOf("", "abc", "1.2.3", "256.1.2.3", "0.0.0.0", "224.1.2.3", "192.168.1.255", "1.2.3.-1"))
            try{UnitySum8Codec.ipv4(ip);fail(ip)}catch(_:IllegalArgumentException){}
        assertEquals("192.168.1.4",UnitySum8Codec.ipv4("192.168.1.4").hostAddress)
    }
    @Test fun actualDefaultPortsFeedbackTimeoutDuplicatesAndWrap() {
        Stage3Peer(9001).use { peer->
            peer.feedbackEnabled=false
            UnityUdpLink("127.0.0.1").use { link->
                link.start();peer.feedbackPort=9002
                link.send(UnityCommand(ControlCommand.RIGHT,50,1,1,System.nanoTime()))
                Stage3Peer.await("9001 independent receiver"){peer.records.isNotEmpty()}
                assertEquals("AA 55 01 02 04 32 39 0D 0A",peer.records.last().hex)
                peer.send(Stage3Peer.status(time=0xfffffff0));Stage3Peer.await("9002 state"){link.snapshot().validPackets==1L}
                peer.send(Stage3Peer.status(time=20));Stage3Peer.await("uint32 wrap accepted"){link.snapshot().validPackets==2L}
                peer.send(Stage3Peer.status(time=20));peer.send(Stage3Peer.status(time=19))
                peer.send(Stage3Peer.status(time=21).apply{this[19]=0})
                Stage3Peer.await("bad/late/duplicate counted"){link.snapshot().rejected.values.sum()>=3}
                Thread.sleep(1050);assertFalse(link.snapshot().feedbackOnline)
                peer.send(Stage3Peer.status(time=2));Stage3Peer.await("restart after timeout"){link.snapshot().validPackets==3L}
                assertEquals(2L,link.snapshot().feedback!!.timestampMs)
            }
        }
    }
}
