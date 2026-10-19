package com.fifteen.eegsender.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 对「线上报文格式」做字节级校验。
 *
 * 除了在这里逐字段断言，还会把一段完整推流（STREAM_INFO → 若干 SAMPLE_BLOCK →
 * STREAM_STOP）的原始字节导出为 hex 文本，交给独立的 Python 解析器交叉验证，
 * 确保接收端按协议文档实现就能正确解出数据。
 */
class ProtocolWireTest {

    private val littleEndian: ByteBuffer =
        ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

    private fun floats(vararg values: Float): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun hex(bytes: ByteArray, from: Int, until: Int): String =
        (from until until).joinToString(" ") { "%02X".format(bytes[it]) }

    private fun crc32Of(bytes: ByteArray, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, 0, length)
        return crc.value
    }

    /** 按协议把 u64 表示为小端 hex，便于独立于被测代码构造期望值。 */
    private fun u64Le(value: Long): String = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value)
        .array()
        .joinToString(" ") { "%02X".format(it) }

    // ------------------------------------------------------------------ 包头

    @Test
    fun streamInfoLayoutIsStable() {
        val encoder = PacketEncoder()
        val length = encoder.encodeStreamInfo(1, 1000, 2, listOf("Fp1", "Oz"))
        val bytes = encoder.array

        // 通用包头 16 字节
        assertEquals("magic 小端应写出 EE 47", "EE 47", hex(bytes, 0, 2))
        assertEquals("version", "02", hex(bytes, 2, 3))
        assertEquals("msgType = STREAM_INFO", "01", hex(bytes, 3, 4))
        assertEquals("deviceId = ASCII \"EEG1\"", "31 47 45 45", hex(bytes, 4, 8))
        assertEquals("seq = 1", "01 00 00 00", hex(bytes, 8, 12))
        assertEquals("payloadLen = 10 + 6", "10 00 00 00", hex(bytes, 12, 16))

        // 负载
        assertEquals("sampleRate = 1000", "E8 03 00 00", hex(bytes, 16, 20))
        assertEquals("channelCount = 2", "02 00", hex(bytes, 20, 22))
        assertEquals("dataType = float32", "01", hex(bytes, 22, 23))
        assertEquals("reserved = 0", "00", hex(bytes, 23, 24))
        assertEquals("namesLen = 6", "06 00", hex(bytes, 24, 26))
        assertEquals("\"Fp1,Oz\" 的 UTF-8", "46 70 31 2C 4F 7A", hex(bytes, 26, 32))

        // 报文总长 = 16 头 + 16 负载 + 4 CRC
        assertEquals(36, length)
        assertEquals(crc32Of(bytes, 32), readU32Le(bytes, 32))
    }

    @Test
    fun sampleBlockLayoutIsStable() {
        val encoder = PacketEncoder()
        val samples = floats(1.0f, -2.5f, 3.25f, 0.0f)
        val length = encoder.encodeSampleBlock(
            seq = 2,
            firstSampleIndex = 0L,
            deviceTimeUs = 1_700_000_000_000_000L,
            sampleCount = 2,
            channelCount = 2,
            samples = samples,
            samplesOffset = 0,
            samplesLength = samples.size
        )
        val bytes = encoder.array

        assertEquals("msgType = SAMPLE_BLOCK", "02", hex(bytes, 3, 4))
        assertEquals("seq = 2", "02 00 00 00", hex(bytes, 8, 12))
        assertEquals("payloadLen = 20 + 16", "24 00 00 00", hex(bytes, 12, 16))
        assertEquals("firstSampleIndex = 0", u64Le(0L), hex(bytes, 16, 24))
        assertEquals("deviceTimeUs = 1.7e15", u64Le(1_700_000_000_000_000L), hex(bytes, 24, 32))
        assertEquals("sampleCount = 2", "02 00", hex(bytes, 32, 34))
        assertEquals("channelCount = 2", "02 00", hex(bytes, 34, 36))
        assertEquals("float32 小端、时间主序", "00 00 80 3F 00 00 20 C0 00 00 50 40 00 00 00 00", hex(bytes, 36, 52))

        assertEquals(56, length)
        assertEquals(crc32Of(bytes, 52), readU32Le(bytes, 52))
    }

    @Test
    fun streamStopLayoutIsStable() {
        val encoder = PacketEncoder()
        val length = encoder.encodeStreamStop(7, 12345L, EegFrame.STOP_REASON_RELEASED)
        val bytes = encoder.array

        assertEquals("msgType = STREAM_STOP", "03", hex(bytes, 3, 4))
        assertEquals("seq = 7", "07 00 00 00", hex(bytes, 8, 12))
        assertEquals("payloadLen = 9", "09 00 00 00", hex(bytes, 12, 16))
        assertEquals("lastSampleIndex = 12345", "39 30 00 00 00 00 00 00", hex(bytes, 16, 24))
        assertEquals("reason = RELEASED", "01", hex(bytes, 24, 25))

        assertEquals(29, length)
        assertEquals(crc32Of(bytes, 25), readU32Le(bytes, 25))
    }

    // ------------------------------------------------------- 数据回绕与端到端

    @Test
    fun copyLoopWrapsAroundRegion() {
        // 4 个采样点 × 2 通道，数值即 采样序号.通道序号，便于定位
        val source = EegSignalSource(
            sampleRate = 1000,
            channelCount = 2,
            channelNames = listOf("Fp1", "Oz"),
            data = floats(0f, 1f, 10f, 11f, 20f, 21f, 30f, 31f),
            regions = mapOf(EegCommands.FORWARD to EegSignalSource.Region(EegCommands.FORWARD, 0, 4))
        )
        val region = source.regionOf(EegCommands.FORWARD)
        val dest = ByteArray(6 * source.bytesPerSample)

        // 从第 3 个采样点开始取 6 个：应得到 3,0,1,2,3,0
        val written = source.copyLoop(region, 3, 6, dest, 0)
        assertEquals(6 * 8, written)

        val values = ByteBuffer.wrap(dest).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val actual = FloatArray(12) { values.get(it) }
        assertTrue(
            "期望 3,0,1,2,3,0（各带 2 通道）实际 ${actual.toList()}",
            actual.contentEquals(floatArrayOf(30f, 31f, 0f, 1f, 10f, 11f, 20f, 21f, 30f, 31f, 0f, 1f))
        )
    }

    @Test
    fun writeWireDumpForCrossCheck() {
        // 用 2 通道、4 采样点的小区段跑一遍完整报文序列，方便 Python 侧独立解码
        val source = EegSignalSource(
            sampleRate = 1000,
            channelCount = 2,
            channelNames = listOf("Fp1", "Oz"),
            data = floats(0f, 1f, 10f, 11f, 20f, 21f, 30f, 31f),
            regions = mapOf(EegCommands.FORWARD to EegSignalSource.Region(EegCommands.FORWARD, 0, 4))
        )
        val region = source.regionOf(EegCommands.FORWARD)

        val encoder = PacketEncoder()
        val out = ArrayList<Byte>()
        fun append(length: Int) {
            for (i in 0 until length) out.add(encoder.array[i])
        }

        append(encoder.encodeStreamInfo(1, source.sampleRate, source.channelCount, source.channelNames))

        val samplesPerPacket = 3
        val staging = ByteArray(samplesPerPacket * source.bytesPerSample)
        var position = 0
        var sampleIndex = 0L
        for (seq in 2..5) {
            val copied = source.copyLoop(region, position, samplesPerPacket, staging, 0)
            append(
                encoder.encodeSampleBlock(
                    seq, sampleIndex, sampleIndex * 1000L, samplesPerPacket, source.channelCount,
                    staging, 0, copied
                )
            )
            sampleIndex += samplesPerPacket
            position = (position + samplesPerPacket) % region.sampleCount
        }

        append(encoder.encodeStreamStop(6, sampleIndex, EegFrame.STOP_REASON_RELEASED))

        val dump = File("build/wire-dump.hex")
        dump.parentFile?.mkdirs()
        dump.writeText(out.joinToString(" ") { "%02X".format(it) })

        assertTrue("应导出报文样本", dump.length() > 0)
        println("wire dump written to ${dump.absolutePath} (${out.size} bytes)")
    }

    @Test
    fun datagramFitsInMtu() {
        val source = EegSignalSource(
            sampleRate = 1000,
            channelCount = 62,
            channelNames = (1..62).map { "ch$it" },
            data = ByteArray(16000 * 62 * 4),
            regions = mapOf(EegCommands.FORWARD to EegSignalSource.Region(EegCommands.FORWARD, 0, 16000))
        )
        val samplesPerPacket = (EegFrame.MAX_DATAGRAM - EegFrame.HEADER_SIZE - EegFrame.CRC_SIZE -
            EegFrame.SAMPLE_BLOCK_OVERHEAD) / source.bytesPerSample
        assertEquals("62 通道 float32 时每包 5 个采样点", 5, samplesPerPacket)

        val encoder = PacketEncoder()
        val payload = ByteArray(samplesPerPacket * source.bytesPerSample)
        val length = encoder.encodeSampleBlock(1, 0L, 0L, samplesPerPacket, 62, payload, 0, payload.size)
        assertEquals(16 + 20 + 1240 + 4, length)
        assertTrue("报文不能超过 MTU", length <= EegFrame.MAX_DATAGRAM)
    }

    // ---------------------------------------------------------------- 发现子协议

    @Test
    fun discoverLayoutIsStable() {
        val out = ByteArray(DiscoveryProtocol.DISCOVER_SIZE)
        val length = DiscoveryProtocol.encodeDiscover(0x11223344, out)

        assertEquals("DISCOVER 固定 16 字节", 16, length)
        assertEquals("magic 小端应写出 EF 47", "EF 47", hex(out, 0, 2))
        assertEquals("version", "01", hex(out, 2, 3))
        assertEquals("msgType = DISCOVER", "01", hex(out, 3, 4))
        assertEquals("nonce 小端", "44 33 22 11", hex(out, 4, 8))
        assertEquals("deviceId = ASCII \"EEG1\"", "31 47 45 45", hex(out, 8, 12))
        assertEquals(crc32Of(out, 12), readU32Le(out, 12))
    }

    @Test
    fun announceLayoutIsStable() {
        val out = ByteArray(DiscoveryProtocol.announceSize("PC"))
        val length = DiscoveryProtocol.encodeAnnounce(0x11223344, 9000, "PC", out)

        // 13 字节固定头 + 2 字节名称 + 4 字节 CRC
        assertEquals(19, length)
        assertEquals("magic 小端应写出 EF 47", "EF 47", hex(out, 0, 2))
        assertEquals("version", "01", hex(out, 2, 3))
        assertEquals("msgType = ANNOUNCE", "02", hex(out, 3, 4))
        assertEquals("nonce 回显", "44 33 22 11", hex(out, 4, 8))
        assertEquals("dataPort = 9000", "28 23", hex(out, 8, 10))
        assertEquals("reserved = 0", "00 00", hex(out, 10, 12))
        assertEquals("nameLen = 2", "02", hex(out, 12, 13))
        assertEquals("\"PC\" 的 UTF-8", "50 43", hex(out, 13, 15))
        assertEquals(crc32Of(out, 15), readU32Le(out, 15))
    }

    @Test
    fun announceRoundTrips() {
        val name = "客厅接收端 A"
        val out = ByteArray(DiscoveryProtocol.announceSize(name))
        val length = DiscoveryProtocol.encodeAnnounce(123456, 9000, name, out)

        val parsed = DiscoveryProtocol.parseAnnounce(out, length)
        assertTrue("应能解析自身写出的 ANNOUNCE", parsed != null)
        assertEquals(123456, parsed!!.nonce)
        assertEquals(9000, parsed.dataPort)
        assertEquals(name, parsed.name)
    }

    @Test
    fun announceRejectsCorruptedOrForeignPackets() {
        val out = ByteArray(DiscoveryProtocol.announceSize("PC"))
        val length = DiscoveryProtocol.encodeAnnounce(1, 9000, "PC", out)

        // CRC 被改坏：必须拒绝，否则会把噪声/其它流量当成接收端
        val corrupted = out.copyOf()
        corrupted[6] = (corrupted[6].toInt() xor 0xFF).toByte()
        assertTrue("CRC 不符应拒绝", DiscoveryProtocol.parseAnnounce(corrupted, length) == null)

        // 长度不足
        assertTrue("长度不足应拒绝", DiscoveryProtocol.parseAnnounce(out, 8) == null)

        // 数据流协议的报文（magic 0x47EE）不能被当成 ANNOUNCE
        val encoder = PacketEncoder()
        val streamLength = encoder.encodeStreamInfo(1, 1000, 2, listOf("Fp1", "Oz"))
        assertTrue(
            "数据流报文不应被解析为 ANNOUNCE",
            DiscoveryProtocol.parseAnnounce(encoder.array, streamLength) == null
        )
    }

    @Test
    fun discoverParserRejectsDataStreamPackets() {
        val encoder = PacketEncoder()
        val streamLength = encoder.encodeStreamInfo(1, 1000, 2, listOf("Fp1", "Oz"))

        assertTrue(
            "数据流报文不应被解析为 DISCOVER",
            DiscoveryProtocol.parseDiscover(encoder.array, streamLength) == null
        )

        val out = ByteArray(DiscoveryProtocol.DISCOVER_SIZE)
        val length = DiscoveryProtocol.encodeDiscover(777, out)
        assertEquals(777, DiscoveryProtocol.parseDiscover(out, length))

        val corrupted = out.copyOf()
        corrupted[0] = 0x00
        assertTrue("magic 不符应拒绝", DiscoveryProtocol.parseDiscover(corrupted, length) == null)
    }

    @Test
    fun announceNameIsTruncatedOnCharacterBoundary() {
        // 每个汉字 3 字节，40 字节上限 => 最多 13 个汉字（39 字节）
        val long = "接收端".repeat(20)
        val bytes = DiscoveryProtocol.nameBytes(long)
        assertTrue("名称不得超过上限字节数", bytes.size <= DiscoveryProtocol.MAX_NAME_BYTES)
        assertEquals("应按整字符截断，不能截出半个汉字", 0, bytes.size % 3)

        val out = ByteArray(DiscoveryProtocol.announceSize(long))
        val length = DiscoveryProtocol.encodeAnnounce(1, 9000, long, out)
        val parsed = DiscoveryProtocol.parseAnnounce(out, length)
        assertTrue("截断后的名称仍应可解析", parsed != null)
        assertEquals(String(bytes, Charsets.UTF_8), parsed!!.name)
    }

    @Test
    fun discoveryIsIsolatedFromDataStream() {
        // 两套协议必须能共存于同一网络：端口不同、magic 不同
        assertTrue(
            "发现端口必须与数据流端口区分开",
            DiscoveryProtocol.DEFAULT_PORT != 9000
        )
        assertTrue(
            "发现 magic 必须与数据流 magic 区分开",
            DiscoveryProtocol.MAGIC != EegFrame.MAGIC
        )
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        littleEndian.clear()
        littleEndian.put(bytes, offset, 4)
        littleEndian.flip()
        return littleEndian.int.toLong() and 0xFFFFFFFFL
    }

    @Test
    fun broadcastAddressIsComputedFromPrefix() {
        // 热点网卡（AP）常常不给 getBroadcast()，只能自己按前缀算。
        // 算错的表现就是「自动发现永远找不到接收端」。
        val discovery = UdpDiscovery()
        assertEquals(
            "10.14.166.234/24 应算出 10.14.166.255",
            "10.14.166.255",
            discovery.computeBroadcast(ip("10.14.166.234"), 24)!!.hostAddress
        )
        assertEquals(
            "192.168.56.1/24 应算出 192.168.56.255",
            "192.168.56.255",
            discovery.computeBroadcast(ip("192.168.56.1"), 24)!!.hostAddress
        )
        assertEquals(
            "172.20.10.1/28 应算出 172.20.10.15",
            "172.20.10.15",
            discovery.computeBroadcast(ip("172.20.10.1"), 28)!!.hostAddress
        )
        assertEquals(
            "172.20.10.20/28 应算出 172.20.10.31",
            "172.20.10.31",
            discovery.computeBroadcast(ip("172.20.10.20"), 28)!!.hostAddress
        )
        assertEquals(
            "10.0.0.5/32 的广播就是它自己",
            "10.0.0.5",
            discovery.computeBroadcast(ip("10.0.0.5"), 32)!!.hostAddress
        )
        assertEquals(
            "前缀 0 视为无效，必须返回 null（避免算出 255.255.255.255 乱发）",
            null,
            discovery.computeBroadcast(ip("10.0.0.5"), 0)
        )
        assertEquals(
            "前缀 33 视为无效，必须返回 null",
            null,
            discovery.computeBroadcast(ip("10.0.0.5"), 33)
        )
    }

    @Test
    fun broadcastTargetsNeverThrowAndStayWellFormed() {
        // describeTargets() 在真机上就是 discovery 用来出网的出口清单。
        // 这里只守「不抛异常」与「格式正确」；出口是否为空取决于本机网卡状态，
        // 但不能因为某块网卡异常读取而整体崩掉。
        val lines = UdpDiscovery().describeTargets()
        for (line in lines) {
            assertTrue("出口描述应形如 '网卡 绑定IP -> 广播IP'，实际是：$line", line.contains(" -> "))
            assertTrue("出口必须带上网卡名，实际是：$line", line.substringBefore(" ").isNotEmpty())
            assertTrue("出口不得是回环地址，实际是：$line", !line.contains("/127."))
        }
    }

    private fun ip(literal: String) = InetAddress.getByName(literal) as Inet4Address
}
