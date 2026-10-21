package com.yourteam.brainchair.receiver

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

class PacketError(val reason: String) : Exception(reason)
data class StreamInfo(val rate: Int, val names: List<String>) { val channels get() = names.size }
sealed class EegMessage(val seq: Long) {
    class Info(seq: Long, val info: StreamInfo) : EegMessage(seq)
    class Block(seq: Long, val first: Long, val timeUs: Long, val count: Int,
                val channels: Int, val samples: FloatArray) : EegMessage(seq)
    class Stop(seq: Long, val nextIndex: Long, val reason: Int) : EegMessage(seq)
}

/** Wire v2 only. Resource limits are receiver limits, not changes to the wire format. */
object EegProtocol {
    const val MAX_PACKET = 1472
    const val DEVICE = 0x45454731L
    private fun fail(reason: String): Nothing = throw PacketError(reason)
    private fun ByteBuffer.u8() = get().toInt() and 255
    private fun ByteBuffer.u16() = short.toInt() and 65535
    private fun ByteBuffer.u32() = int.toLong() and 0xffffffffL
    private fun crc(bytes: ByteArray, end: Int): Long = CRC32().apply { update(bytes, 0, end) }.value
    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { fail("utf8") }
    fun parse(raw: ByteArray): EegMessage {
        if (raw.size !in 20..MAX_PACKET) fail("length")
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.u16() != 0x47ee || b.u8() != 2) fail("magic/version")
        val type = b.u8()
        if (b.u32() != DEVICE) fail("device")
        val seq = b.u32(); val length = b.u32()
        if (length != (raw.size - 20).toLong()) fail("length")
        if (crc(raw, raw.size - 4) != (b.getInt(raw.size - 4).toLong() and 0xffffffffL)) fail("crc")
        if (seq == 0L) fail("sequence")
        return when (type) {
            1 -> {
                if (length < 10 || seq != 1L) fail("info")
                val rate = b.u32(); val channels = b.u16(); val dtype = b.u8(); val reserved = b.u8(); val namesLen = b.u16()
                if (rate !in 1..4000 || channels !in 1..128 || rate * channels * 4 > 2_000_000) fail("resource-limit")
                if (dtype != 1 || reserved != 0 || length != 10L + namesLen) fail("info")
                val names = utf8(ByteArray(namesLen).also { b.get(it) }).split(',')
                if (names.size != channels || names.any { it.isBlank() } || names.distinct().size != channels) fail("channels")
                EegMessage.Info(seq, StreamInfo(rate.toInt(), names))
            }
            2 -> {
                if (length < 20 || seq < 2) fail("block")
                val first = b.long; val time = b.long; val count = b.u16(); val channels = b.u16()
                // JVM uses signed Long. Refuse unrepresentable u64 values rather than wrap.
                if (first < 0 || first > Long.MAX_VALUE / 1_000_000 || time < 0 || count == 0 || channels !in 1..128 ||
                    length != 20L + count.toLong() * channels * 4) fail("block")
                val samples = FloatArray(count * channels) { b.float }
                if (samples.any { !it.isFinite() }) fail("nonfinite")
                EegMessage.Block(seq, first, time, count, channels, samples)
            }
            3 -> {
                if (length != 9L || seq < 2) fail("stop")
                val next = b.long; val reason = b.u8()
                if (next < 0 || reason !in 1..3) fail("stop")
                EegMessage.Stop(seq, next, reason)
            }
            else -> fail("unknown-type")
        }
    }

    fun announce(request: ByteArray, port: Int, name: String): ByteArray {
        if (request.size != 16 || port !in 1..65535) fail("discovery-length")
        val b = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        if (b.u16() != 0x47ef || b.u8() != 1 || b.u8() != 1) fail("discovery-header")
        val nonce = b.int
        if (b.u32() != DEVICE || b.u32() != crc(request, 12)) fail("discovery-crc/device")
        var safeName = name
        while (safeName.toByteArray(Charsets.UTF_8).size > 40) {
            safeName = safeName.substring(0, safeName.offsetByCodePoints(safeName.length, -1))
        }
        val nb = safeName.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(17 + nb.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x47ef.toShort()).put(1).put(2).putInt(nonce).putShort(port.toShort()).putShort(0).put(nb.size.toByte()).put(nb)
        out.putInt(crc(out.array(), out.position()).toInt())
        return out.array()
    }
}
