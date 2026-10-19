package com.fifteen.eegsender.protocol

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 本机局域网网段的识别，以及「定向直发」目标地址的校验。
 *
 * 存在的意义：UDP 是无连接的，往一个**打错的但格式合法**的地址发数据（例如把
 * `10.14.166.234` 写成 `10.14.16.234`），`sendto` 一样会成功返回，界面上照样显示
 * 「推流中 250 kB/s」—— 但包其实被交给了默认网关，接收端一个都收不到。
 *
 * 这种「假成功」比直接报错危险得多：用户会以为定向直发没问题，实际问题被藏起来，
 * 直到演示现场才发现收不到数据。所以发定向流量之前，先确认目标地址确实落在本机
 * 某块网卡的网段里。
 */
internal object LocalNetwork {

    /** 蜂窝网卡与虚拟网卡的名称前缀：它们的地址对局域网直发没有意义。 */
    val SKIP_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "clat", "dummy", "p2p", "tun", "sit"
    )

    /** 本机一块网卡的网段：地址 + 前缀长度。 */
    data class Subnet(val address: Inet4Address, val prefixLength: Int) {

        /** 目标地址是否与本网段同网段。 */
        fun contains(target: Inet4Address): Boolean {
            if (prefixLength !in 1..32) return false
            val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
            return (toLong(address) and mask) == (toLong(target) and mask)
        }

        /** 形如 `10.14.166.234/24`，用于提示文案。 */
        override fun toString(): String = "${address.hostAddress}/$prefixLength"
    }

    /** 本机全部可用的局域网网段；取不到时返回空列表。 */
    fun subnets(): List<Subnet> {
        val result = LinkedHashSet<Subnet>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()

        for (nic in interfaces) {
            if (nic.isLoopback) continue
            val name = nic.name?.lowercase(LocaleRoot) ?: ""
            if (SKIP_PREFIXES.any { name.startsWith(it) }) continue

            val addresses = runCatching { nic.interfaceAddresses }.getOrNull() ?: continue
            for (address in addresses) {
                val ip = address.address
                if (ip !is Inet4Address || ip.isLoopbackAddress || ip.isAnyLocalAddress) continue
                val prefix = address.networkPrefixLength.toInt()
                if (prefix !in 1..32) continue
                result.add(Subnet(ip, prefix))
            }
        }
        return result.toList()
    }

    /**
     * 严格解析 IPv4 字面量：不是「刚好 4 段、每段 0-255」就返回 null。
     *
     * 不能直接丢给 `InetAddress.getByName`：不符合格式的串会被它拿去做 DNS 查询，
     * 于是 `10.14.16.166.234` 这种打错的地址会变成一个**阻塞数百毫秒甚至数秒**的
     * 解析过程，最后抛出的还是英文的 `Unable to resolve host`。
     */
    fun parseIpv4(text: String): Inet4Address? {
        val parts = text.split('.')
        if (parts.size != 4) return null

        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (part.any { it !in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) as Inet4Address }.getOrNull()
    }

    /** 逐个数字段转 Long，便于做掩码比较。 */
    private fun toLong(ip: Inet4Address): Long {
        var value = 0L
        for (b in ip.address) value = (value shl 8) or (b.toLong() and 0xFFL)
        return value
    }

    /** 已获取到的网段列表 → 提示文案，如 `10.14.166.234/24、192.168.56.1/24`。 */
    fun describe(subnets: List<Subnet>): String =
        if (subnets.isEmpty()) "（本机没有可用网卡）" else subnets.joinToString("、") { it.toString() }

    /** 明确指定语言，避免土耳其语等区域设置下 `I`/`i` 大小写转换出意外。 */
    private val LocaleRoot = java.util.Locale.ROOT
}
