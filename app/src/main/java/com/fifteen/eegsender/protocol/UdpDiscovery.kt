package com.fifteen.eegsender.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * 发现子协议：免去手动查/填接收端 IP。
 *
 * 发送端在局域网内**广播**一条 DISCOVER，正在监听的接收端**单播**回一条
 * ANNOUNCE，发送端据此拿到接收端的 IP 与数据端口，再照常推流。
 *
 * 它和数据流协议（EEG v2）是**完全解耦**的两套东西：
 *
 * - 走**不同端口**（发现默认 9001，数据流默认 9000），互不干扰；
 * - 用**不同 magic**（`0x47EF` vs `0x47EE`），即使混在同一个 socket 上
 *   也能一眼区分，不会和数据报混淆；
 * - 有**独立版本号**，两套协议将来各自演进互不影响。
 *
 * 因此只实现了数据流的老接收端**完全不受影响**：它在 9000 上收流，
 * 9001 上的广播与它无关；反之发送端也不会把发现报文混进数据流。
 *
 * 报文一律小端，与数据流协议保持一致。
 *
 * ### DISCOVER（发送端 → 广播，固定 16 字节）
 *
 * ```
 * 偏移  长度  字段      说明
 *  0     2    magic     u16 = 0x47EF
 *  2     1    version   u8  = 1
 *  3     1    msgType   u8  = 0x01
 *  4     4    nonce     u32 随机数，接收端必须原样回显
 *  8     4    deviceId  u32 = 0x45454731（"EEG1"）
 * 12     4    crc32     u32 覆盖 [0,12)
 * ```
 *
 * ### ANNOUNCE（接收端 → 发送端单播，13 + nameLen + 4 字节）
 *
 * ```
 * 偏移  长度    字段      说明
 *  0     2      magic     u16 = 0x47EF
 *  2     1      version   u8  = 1
 *  3     1      msgType   u8  = 0x02
 *  4     4      nonce     u32 回显 DISCOVER 的 nonce
 *  8     2      dataPort  u16 接收端在哪个端口收数据流（通常 9000）
 * 10     2      reserved  u16 = 0，预留扩展位
 * 12     1      nameLen   u8  0..40
 * 13  nameLen   name      UTF-8 接收端自报名称，可为空
 * ...    4      crc32     u32 覆盖 name 之前的全部字节
 * ```
 */
object DiscoveryProtocol {

    const val MAGIC = 0x47EF
    const val VERSION = 1

    const val MSG_DISCOVER = 0x01
    const val MSG_ANNOUNCE = 0x02

    /** 发现报文的默认端口（数据流默认走 9000）。 */
    const val DEFAULT_PORT = 9001

    /** 与数据流共用的设备标识，便于接收端认出是谁在找它。 */
    const val DEVICE_ID = EegFrame.DEVICE_ID

    const val CRC_SIZE = 4

    /** DISCOVER 的固定长度。 */
    const val DISCOVER_SIZE = 16

    /** ANNOUNCE 除 name 与 crc32 之外的固定头长度。 */
    const val ANNOUNCE_HEADER_SIZE = 13

    /** 名称最长字节数，防止报文被撑大。 */
    const val MAX_NAME_BYTES = 40

    /** 解析后的 ANNOUNCE。 */
    data class Announce(val nonce: Int, val dataPort: Int, val name: String)

    /** 把字符串编码成不超过 [MAX_NAME_BYTES] 的 UTF-8 字节（按字符边界截断，不会截出半个字符）。 */
    fun nameBytes(name: String): ByteArray {
        var text = name
        var bytes = text.toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_NAME_BYTES && text.isNotEmpty()) {
            text = text.substring(0, text.length - 1)
            bytes = text.toByteArray(Charsets.UTF_8)
        }
        return bytes
    }

    /** ANNOUNCE 编码后的总长度。 */
    fun announceSize(name: String): Int =
        ANNOUNCE_HEADER_SIZE + nameBytes(name).size + CRC_SIZE

    /** 写入一条 DISCOVER，返回写入长度。 */
    fun encodeDiscover(nonce: Int, out: ByteArray = ByteArray(DISCOVER_SIZE)): Int {
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MAGIC.toShort())
        buf.put(VERSION.toByte())
        buf.put(MSG_DISCOVER.toByte())
        buf.putInt(nonce)
        buf.putInt(DEVICE_ID)
        buf.putInt(crc32(out, 0, DISCOVER_SIZE - CRC_SIZE))
        return DISCOVER_SIZE
    }

    /** 写入一条 ANNOUNCE，返回写入长度。 */
    fun encodeAnnounce(
        nonce: Int,
        dataPort: Int,
        name: String,
        out: ByteArray = ByteArray(announceSize(name))
    ): Int {
        val nameBytes = nameBytes(name)
        val bodyLength = ANNOUNCE_HEADER_SIZE + nameBytes.size

        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(MAGIC.toShort())
        buf.put(VERSION.toByte())
        buf.put(MSG_ANNOUNCE.toByte())
        buf.putInt(nonce)
        buf.putShort(dataPort.toShort())
        buf.putShort(0)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.putInt(crc32(out, 0, bodyLength))
        return bodyLength + CRC_SIZE
    }

    /**
     * 校验一条 DISCOVER，通过则返回其中的 nonce，否则返回 null。
     *
     * 接收端按此实现：命中就回一条 ANNOUNCE 到报文的源 IP:源端口。
     */
    fun parseDiscover(data: ByteArray, length: Int): Int? {
        if (length < DISCOVER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.short.toInt() and 0xFFFF) != MAGIC) return null
        if (buf.get().toInt() != VERSION) return null
        if (buf.get().toInt() != MSG_DISCOVER) return null
        val nonce = buf.getInt()
        buf.int // deviceId，仅作标识，不参与判定
        val expected = crc32(data, 0, DISCOVER_SIZE - CRC_SIZE)
        return if (buf.int == expected) nonce else null
    }

    /**
     * 校验并解析一条 ANNOUNCE；magic / version / msgType / 长度 / crc32 任一不符
     * 都返回 null（调用方据此静默丢弃，例如丢弃无关的其它广播流量）。
     */
    fun parseAnnounce(data: ByteArray, length: Int): Announce? {
        if (length < ANNOUNCE_HEADER_SIZE + CRC_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.short.toInt() and 0xFFFF) != MAGIC) return null
        if (buf.get().toInt() != VERSION) return null
        if (buf.get().toInt() != MSG_ANNOUNCE) return null
        val nonce = buf.getInt()
        val dataPort = buf.short.toInt() and 0xFFFF
        buf.short // reserved
        val nameLength = buf.get().toInt() and 0xFF
        if (length < ANNOUNCE_HEADER_SIZE + nameLength + CRC_SIZE) return null

        val raw = ByteArray(nameLength)
        buf.get(raw)
        val expected = crc32(data, 0, ANNOUNCE_HEADER_SIZE + nameLength)
        if (buf.int != expected) return null

        return Announce(nonce, dataPort, String(raw, Charsets.UTF_8))
    }

    internal fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }
}

/** 一次成功发现的结果。 */
data class DiscoveredReceiver(val address: String, val dataPort: Int, val name: String) {
    val endpoint: String get() = "$address:$dataPort"
}

/**
 * 一个广播出口：从 [bindIp] 这块网卡发出，目标地址是该网卡的定向广播 [broadcast]。
 *
 * 把「绑定地址」和「广播目标」配成对是必须的：手机自己当热点时，AP 网卡并不是
 * 应用的默认网络，套接字若绑到 `0.0.0.0`，报文会交给默认网络（常常是移动数据），
 * 于是热点下的接收端一个包也收不到。绑到具体网卡的 IP 上，才能借 Android 的
 * 源地址策略路由把报文逼到正确的出口。
 */
data class BroadcastTarget(
    val bindIp: InetAddress,
    val broadcast: InetAddress,
    val interfaceName: String
) {
    override fun toString(): String = "$interfaceName $bindIp -> $broadcast"
}

/**
 * 广播 DISCOVER 并等待 ANNOUNCE。
 *
 * 阻塞式调用，必须放到后台线程；通过 [cancelled] 让调用方（例如「用户已经松手」）
 * 能中途作废这次发现。
 *
 * 每个候选网卡都**单独开一个绑到该网卡 IP 的套接字**，各自发往自己的定向广播
 * 地址，最后再补一个 `255.255.255.255` 兜底。这一点很关键：手机自己当热点时，
 * 仅发 `255.255.255.255` 会被系统路由到移动数据出口，接收端一个包都收不到。
 */
class UdpDiscovery(
    private val discoveryPort: Int = DiscoveryProtocol.DEFAULT_PORT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val cancelled: () -> Boolean = { false }
) {

    /** 供界面展示：本次发现会用到哪些出口（排查「连不上」时非常有用）。 */
    fun describeTargets(): List<String> = broadcastTargets().map { it.toString() }

    /**
     * 上一次 [discover] 失败的原因；成功时为 null。
     *
     * 发现本质是「广播出去、等应答」，发送被系统拒绝时如果只返回 null，界面就只剩
     * 「未发现接收端」这种误导性结论 —— 真实原因（例如 EPERM：系统不允许应用访问
     * 局域网）必须留出来给界面展示。
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 本机可用于**绑定源地址**的局域网 IPv4 列表。
     *
     * 推流时按这些地址逐个尝试发送，是绕开「系统默认网络是移动数据 → 往局域网发包
     * 被拒」的关键手段。复用 [broadcastTargets] 的网卡筛选逻辑，保证发现与推流两条
     * 路径认定的是同一批网卡。
     */
    fun localBindAddresses(): List<InetAddress> = broadcastTargets().map { it.bindIp }

    fun discover(): DiscoveredReceiver? {
        val nonce = Random.nextInt()
        val outbound = ByteArray(DiscoveryProtocol.DISCOVER_SIZE)
        val length = DiscoveryProtocol.encodeDiscover(nonce, outbound)

        lastError = null
        val exits = mutableListOf<Exit>()
        try {
            // 每个候选网卡单独一个出口，绑定到该网卡自己的 IP
            for (target in broadcastTargets()) {
                val bound = runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = RECEIVE_SLICE_MS
                        bind(InetSocketAddress(target.bindIp, 0))
                    }
                }.getOrNull() ?: continue
                exits.add(Exit(bound, target))
            }
            // 兜底出口：绑 0.0.0.0，交给系统默认网络
            runCatching {
                val fallback = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = RECEIVE_SLICE_MS
                    bind(InetSocketAddress(0))
                }
                exits.add(Exit(fallback, null))
            }
            if (exits.isEmpty()) {
                lastError = "没有可用的网络出口（是否没连 WiFi / 热点？）"
                return null
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val receiveBuffer = ByteArray(256)
            var attempt = 0

            while (attempt < attempts && System.currentTimeMillis() < deadline) {
                if (cancelled()) return null

                for (exit in exits) {
                    if (cancelled()) return null
                    val target = exit.target?.broadcast ?: ANY_ADDRESS
                    val packet = DatagramPacket(outbound, 0, length, target, discoveryPort)
                    // 单块网卡发送失败（例如系统不允许在该网卡广播）不应中断整体发现，
                    // 但原因必须留下来 —— 系统拒绝时界面只会显示「未发现接收端」。
                    runCatching { exit.socket.send(packet) }
                        .onFailure { failure ->
                            if (lastError == null) {
                                lastError = failure.message ?: failure.javaClass.simpleName
                            }
                        }
                }
                attempt++

                // 轮流在每块出口上收应答；每片超时就换下一个出口
                val slice = (RECEIVE_SLICE_MS / exits.size).coerceAtLeast(40)
                for (exit in exits) {
                    if (cancelled()) return null
                    exit.socket.soTimeout = slice
                    while (System.currentTimeMillis() < deadline) {
                        if (cancelled()) return null
                        val reply = DatagramPacket(receiveBuffer, receiveBuffer.size)
                        try {
                            exit.socket.receive(reply)
                        } catch (timeout: SocketTimeoutException) {
                            break
                        } catch (broken: java.io.IOException) {
                            break
                        }
                        val announce = DiscoveryProtocol.parseAnnounce(reply.data, reply.length)
                            ?: continue
                        if (announce.nonce != nonce) continue
                        if (announce.dataPort !in 1..65535) continue
                        val from = reply.address?.hostAddress ?: continue
                        return DiscoveredReceiver(from, announce.dataPort, announce.name)
                    }
                }

                if (System.currentTimeMillis() >= deadline) break
                runCatching { Thread.sleep(RETRY_GAP_MS) }
            }
            return null
        } catch (t: Throwable) {
            if (lastError == null) lastError = t.message ?: t.javaClass.simpleName
            return null
        } finally {
            for (exit in exits) runCatching { exit.socket.close() }
        }
    }

    /**
     * 本机所有可用网卡的「绑定地址 + 定向广播地址」。
     *
     * 会跳过蜂窝网卡（`rmnet`/`ccmni`/`pdp`/`clat`）与虚拟网卡（`dummy`/`p2p`），
     * 免得把发现报文发到移动数据上。
     *
     * 有些网卡（尤其是热点 AP 网卡）在 `InterfaceAddress.getBroadcast()` 上返回
     * null，此时**按前缀长度自己算**，否则会把最有希望的那块网卡整个跳过。
     */
    private fun broadcastTargets(): List<BroadcastTarget> {
        val targets = LinkedHashSet<BroadcastTarget>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()

        for (nif in interfaces) {
            // 单块网卡读取失败（权限或状态异常）不应影响其它网卡
            runCatching {
                if (!nif.isUp || nif.isLoopback) return@runCatching
                val name = (nif.name ?: "").lowercase()
                if (SKIP_PREFIXES.any { name.startsWith(it) }) return@runCatching
                for (address in nif.interfaceAddresses) {
                    val ip = address.address ?: continue
                    if (ip !is Inet4Address || ip.isLoopbackAddress || ip.isAnyLocalAddress) continue
                    val broadcast = address.broadcast
                        ?: computeBroadcast(ip, address.networkPrefixLength.toInt())
                        ?: continue
                    targets.add(BroadcastTarget(ip, broadcast, name))
                }
            }
        }
        return targets.toList()
    }

    /**
     * 由 IP 与前缀长度算出定向广播地址（`getBroadcast()` 返回 null 时的兜底）。
     *
     * `internal` 是为了让 JVM 单测能直接验证这段位运算——热点网卡上它算错，
     * 表现就是「自动发现永远找不到接收端」。
     */
    internal fun computeBroadcast(ip: Inet4Address, prefix: Int): InetAddress? {
        if (prefix <= 0 || prefix > 32) return null
        var value = 0L
        for (b in ip.address) value = (value shl 8) or (b.toLong() and 0xFF)
        val hostMask = if (prefix == 32) 0L else (1L shl (32 - prefix)) - 1
        val broadcast = value or hostMask
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    ((broadcast shr 24) and 0xFF).toByte(),
                    ((broadcast shr 16) and 0xFF).toByte(),
                    ((broadcast shr 8) and 0xFF).toByte(),
                    (broadcast and 0xFF).toByte()
                )
            )
        }.getOrNull()
    }

    /** 一块网卡对应的发送出口。 */
    private class Exit(val socket: DatagramSocket, val target: BroadcastTarget?)

    companion object {
        /** 总等待时间，超过即判定「未发现接收端」。 */
        const val DEFAULT_TIMEOUT_MS = 2000L

        /** 广播重发次数，抵御首包丢失。 */
        const val DEFAULT_ATTEMPTS = 3

        /** 单次 receive 的等待片长。 */
        const val RECEIVE_SLICE_MS = 250

        /** 两次广播之间的间隔。 */
        const val RETRY_GAP_MS = 120L

        /**
         * 这些前缀的网卡一律不发发现报文（蜂窝 / 虚拟）。
         *
         * 与 [LocalNetwork] 共用同一份清单：定向直发的网段校验必须和发现路径认定
         * 同一批网卡，否则会出现「发现能用、直发却说不在网段内」这种自相矛盾。
         */
        private val SKIP_PREFIXES = LocalNetwork.SKIP_PREFIXES

        /** 兜底出口的目标地址。 */
        private val ANY_ADDRESS: InetAddress = InetAddress.getByName("255.255.255.255")
    }
}
