package com.yourteam.brainchair.receiver

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.locks.LockSupport

/** Independent little-endian fixture writer and bitwise CRC, no production encoder reused. */
object Fixtures {
    fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun crc(a: ByteArray, n: Int = a.size): Int {
        var c = -1
        for(i in 0 until n) {
            c = c xor (a[i].toInt() and 255)
            repeat(8) { c = (c ushr 1) xor (if ((c and 1) != 0) 0xedb88320.toInt() else 0) }
        }
        return c.inv()
    }
    fun seal(a: ByteArray): ByteArray = a + le(4).putInt(crc(a)).array()
    fun packet(type: Int, seq: Int, payload: ByteArray): ByteArray = seal(le(16).putShort(0x47ee.toShort()).put(2).put(type.toByte())
        .putInt(0x45454731).putInt(seq).putInt(payload.size).array() + payload)
    fun info(rate: Int = 1000, names: List<String> = listOf("O1", "Oz"), dtype: Int = 1): ByteArray {
        val n = names.joinToString(",").toByteArray()
        return packet(1, 1, le(10).putInt(rate).putShort(names.size.toShort()).put(dtype.toByte()).put(0).putShort(n.size.toShort()).array() + n)
    }
    fun block(seq: Int = 2, first: Long = 0, count: Int = 5, channels: Int = 2, time: Long = 1_000_000 + first * 1000): ByteArray {
        val p = le(20 + count * channels * 4).putLong(first).putLong(time).putShort(count.toShort()).putShort(channels.toShort())
        repeat(count) { t -> repeat(channels) { c -> p.putFloat((t * 100 + c).toFloat()) } }
        return packet(2, seq, p.array())
    }
    fun stop(seq: Int, next: Long, reason: Int = 1) = packet(3, seq, le(9).putLong(next).put(reason.toByte()).array())
    fun discover(nonce: Int = 0x87654321.toInt()) = seal(le(12).putShort(0x47ef.toShort()).put(1).put(1).putInt(nonce).putInt(0x45454731).array())
}

class ReceiverTest {
    private fun rejects(bytes: ByteArray, reason: String? = null) {
        try { EegProtocol.parse(bytes); fail("accepted malformed packet") }
        catch(e: PacketError) { if(reason != null) assertEquals(reason, e.reason) }
    }
    @Test fun crcGoldenCheckAndNormalInfo() {
        assertEquals(0xcbf43926.toInt(), Fixtures.crc("123456789".toByteArray()))
        val p = EegProtocol.parse(Fixtures.info(500, listOf("Oz", "O1"))) as EegMessage.Info
        assertEquals(500, p.info.rate); assertEquals(listOf("Oz", "O1"), p.info.names)
    }
    @Test fun timeMajorSamplesAndUnsignedSequence() {
        val p = EegProtocol.parse(Fixtures.block(-1)) as EegMessage.Block
        assertEquals(0xffffffffL, p.seq); assertEquals(1_000_000L, p.timeUs)
        assertArrayEquals(floatArrayOf(0f,1f,100f,101f,200f,201f,300f,301f,400f,401f), p.samples, 0f)
    }
    @Test fun malformedLengthsAndCrc() {
        val good = Fixtures.block()
        rejects(good.copyOf(good.size - 1), "length"); rejects(good + byteArrayOf(0), "length")
        rejects(good.copyOf().apply { this[40] = 88 }, "crc")
        rejects(good.copyOf().apply { for(i in 12..15) this[i] = -1 }, "length")
        for(n in 0 until 20) rejects(good.copyOf(n))
        rejects(ByteArray(1473), "length")
        rejects(Fixtures.packet(2, 2, ByteArray(20)), "block")
        rejects(Fixtures.packet(3, 2, ByteArray(8)), "stop")
    }
    @Test fun invalidInfoAndSamplesRejected() {
        rejects(Fixtures.info(0), "resource-limit"); rejects(Fixtures.info(dtype = 2), "info")
        rejects(Fixtures.info(names = listOf("Oz", "Oz")), "channels")
        rejects(Fixtures.info(names = listOf("", "O1")), "channels")
        val payload = Fixtures.le(12).putInt(1000).putShort(1).put(1).put(0).putShort(2).put(0xc3.toByte()).put(0x28).array()
        rejects(Fixtures.packet(1,1,payload), "utf8")
        val p = Fixtures.le(24).putLong(0).putLong(1000).putShort(1).putShort(1).putFloat(Float.NaN).array()
        rejects(Fixtures.packet(2,2,p), "nonfinite")
        rejects(Fixtures.packet(2,2,Fixtures.le(24).putLong(-1).putLong(1000).putShort(1).putShort(1).putFloat(0f).array()), "block")
        rejects(Fixtures.packet(99,2,byteArrayOf()), "unknown-type")
    }
    @Test fun duplicatesLatePacketsGapsAndWindowInvalidation() {
        val s = StreamSession(); s.accept(Fixtures.info(), "A"); s.accept(Fixtures.block(), "A")
        s.accept(Fixtures.block(), "A")
        s.accept(Fixtures.block(4,10), "A")
        s.accept(Fixtures.block(3,5), "A")
        val v = s.snapshot()
        assertEquals(1L,v.duplicates); assertEquals(1L,v.outOfOrder)
        assertEquals(1L,v.missingPackets); assertEquals(5L,v.missingSamples)
        assertEquals(5,v.buffered); assertEquals(15L,v.nextIndex); assertNull(s.window())
    }
    @Test fun stopClearsStateAndNewStreamResetsSequence() {
        val s = StreamSession(); s.accept(Fixtures.info(), "A"); s.accept(Fixtures.block(), "A")
        s.accept(Fixtures.stop(3,5), "A")
        assertNull(s.snapshot().info); assertNull(s.snapshot().source); assertEquals(0,s.snapshot().buffered)
        s.accept(Fixtures.block(4,5), "A"); assertEquals(1L,s.snapshot().rejected["missing-info"])
        s.accept(Fixtures.info(), "A"); s.accept(Fixtures.block(), "A")
        assertEquals(5L,s.snapshot().nextIndex); assertEquals(0L,s.snapshot().duplicates)
    }
    @Test fun sourceChannelTimestampAndStopIndexValidation() {
        val s = StreamSession(); s.accept(Fixtures.info(), "A"); s.accept(Fixtures.block(), "B")
        s.accept(Fixtures.block(channels=1), "A"); s.accept(Fixtures.block(), "A")
        s.accept(Fixtures.block(3,5,time=10), "A"); s.accept(Fixtures.stop(3,4), "A")
        assertEquals(setOf("foreign-source","channel-mismatch","timestamp","stop-index"),s.snapshot().rejected.keys)
        assertEquals(5L,s.snapshot().nextIndex)
    }
    @Test fun timeoutOnlyRefreshesForAcceptedData() {
        var now = 0L; val s = StreamSession({now})
        s.accept(Fixtures.info(), "A"); s.accept(Fixtures.block(), "A")
        now=999; s.accept(Fixtures.block(), "A"); s.checkTimeout(); assertNotNull(s.snapshot().info)
        now=1000; s.checkTimeout(); assertNull(s.snapshot().info); assertEquals(0,s.snapshot().buffered)
    }
    @Test fun ringRemainsBoundedAndReturnsIndependentChronologicalWindow() {
        val s = StreamSession(); s.accept(Fixtures.info(), "A")
        repeat(1000){s.accept(Fixtures.block(it+2,it*5L),"A")}
        val w = s.window()!!
        assertEquals(1000L,w.firstIndex); assertEquals(2_000_000L,w.firstTimeUs)
        assertEquals(8000,w.samples.size); assertEquals(4000,s.snapshot().buffered)
        w.samples[0]=9999f; assertEquals(0f,s.window()!!.samples[0],0f)
    }
    @Test fun discoveryEchoCrcAndUtf8Boundary() {
        val a=EegProtocol.announce(Fixtures.discover(),9000,"脑".repeat(20))
        val b=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x47ef,b.short.toInt()); assertEquals(1,b.get().toInt()); assertEquals(2,b.get().toInt())
        assertEquals(0x87654321.toInt(),b.int); assertEquals(9000,b.short.toInt()); assertEquals(0,b.short.toInt())
        val n=b.get().toInt(); assertEquals(39,n)
        assertEquals("脑".repeat(13),a.copyOfRange(13,13+n).toString(Charsets.UTF_8))
        assertEquals(Fixtures.crc(a,a.size-4),b.getInt(a.size-4))
        try { EegProtocol.announce(Fixtures.discover().apply{this[4]=0},9000,"B"); fail() } catch(_:PacketError) {}
    }
    @Test fun udpLoopbackAt200PacketsPerSecondFor30Seconds() {
        val s=StreamSession(); val receiver=UdpReceiver(s,0,0); receiver.start()
        val sender=DatagramSocket(); sender.soTimeout=2000
        fun send(bytes:ByteArray,port:Int=receiver.boundDataPort){sender.send(DatagramPacket(bytes,bytes.size,InetAddress.getLoopbackAddress(),port))}
        try {
            send(Fixtures.discover(),receiver.boundDiscoveryPort)
            val reply=DatagramPacket(ByteArray(100),100); sender.receive(reply)
            assertEquals(receiver.boundDataPort,ByteBuffer.wrap(reply.data).order(ByteOrder.LITTLE_ENDIAN).getShort(8).toInt() and 65535)
            send(Fixtures.info(names=(1..62).map{"C$it"}))
            val begin=System.nanoTime()
            repeat(6000){ index ->
                val deadline=begin+index*5_000_000L
                while(System.nanoTime()<deadline) LockSupport.parkNanos(deadline-System.nanoTime())
                send(Fixtures.block(index+2,index*5L,channels=62))
            }
            val deadline=System.nanoTime()+800_000_000L
            while(s.snapshot().accepted<6001 && System.nanoTime()<deadline) Thread.sleep(5)
            val v=s.snapshot()
            println("UDP_PERF sent=6000 acceptedBlocks=${v.accepted-1} elapsedSeconds=${(System.nanoTime()-begin)/1e9} gaps=${v.missingPackets} errors=${v.rejected} buffered=${v.buffered} channels=${v.info?.channels}")
            assertEquals(6001L,v.accepted); assertEquals(0L,v.missingPackets); assertTrue(v.rejected.isEmpty()); assertEquals(4000,v.buffered)
            send(Fixtures.stop(6002,30000)); Thread.sleep(100); assertNull(s.snapshot().info)
        } finally {sender.close();receiver.close()}
    }
}
