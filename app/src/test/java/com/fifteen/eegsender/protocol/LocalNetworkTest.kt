package com.fifteen.eegsender.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

/**
 * 定向直发的地址校验。
 *
 * 用例里的 `10.14.16.166.234` 与 `10.14.16.234` 都不是编的：它们是真机联调时
 * 把 `10.14.166.234` 打错后实际填进去的值。前者被系统当主机名做 DNS 解析，
 * 后者更隐蔽 —— 格式合法、`sendto` 成功、界面显示「推流中」，但包全被网关丢了。
 */
class LocalNetworkTest {

    private fun ip(text: String): Inet4Address {
        val parsed = LocalNetwork.parseIpv4(text)
        assertNotNull("应能解析：$text", parsed)
        return parsed!!
    }

    @Test
    fun parseIpv4AcceptsValidLiterals() {
        assertEquals("10.14.166.234", ip("10.14.166.234").hostAddress)
        assertEquals("192.168.1.1", ip("192.168.1.1").hostAddress)
        assertEquals("0.0.0.0", ip("0.0.0.0").hostAddress)
        assertEquals("255.255.255.255", ip("255.255.255.255").hostAddress)
        // 前导零是允许的写法，不应被拒
        assertEquals("10.1.2.3", ip("010.001.002.003").hostAddress)
    }

    @Test
    fun parseIpv4RejectsMalformedInput() {
        // 真机实测：多打一段 → 会被 InetAddress 当主机名去做 DNS 查询
        assertNull(LocalNetwork.parseIpv4("10.14.16.166.234"))
        // 段数不足
        assertNull(LocalNetwork.parseIpv4("10.14.166"))
        assertNull(LocalNetwork.parseIpv4("10.14.166."))
        assertNull(LocalNetwork.parseIpv4(""))
        // 越界与非数字
        assertNull(LocalNetwork.parseIpv4("10.14.166.256"))
        assertNull(LocalNetwork.parseIpv4("10.14.166.-1"))
        assertNull(LocalNetwork.parseIpv4("10.14.166.a"))
        assertNull(LocalNetwork.parseIpv4("10.14.166.1a"))
        // 空段
        assertNull(LocalNetwork.parseIpv4("10..166.234"))
        // 调用方会 trim，但函数本身不该悄悄接受空白或主机名
        assertNull(LocalNetwork.parseIpv4(" 10.14.166.234"))
        assertNull(LocalNetwork.parseIpv4("10.14.166.234 "))
        assertNull(LocalNetwork.parseIpv4("localhost"))
        assertNull(LocalNetwork.parseIpv4("192.168.1.1:9000"))
    }

    @Test
    fun subnetMatchesItsOwnAddress() {
        val subnet = LocalNetwork.Subnet(ip("10.14.166.234"), 24)
        assertTrue(subnet.contains(ip("10.14.166.234")))
        assertTrue(subnet.contains(ip("10.14.166.1")))
        assertTrue(subnet.contains(ip("10.14.166.255")))
    }

    @Test
    fun subnetRejectsAddressFromAnotherNetwork() {
        // 真机实测：漏打一个 6，10.14.166.234 变成 10.14.16.234，跨了网段
        val subnet = LocalNetwork.Subnet(ip("10.14.166.234"), 24)
        assertFalse(subnet.contains(ip("10.14.16.234")))
        assertFalse(subnet.contains(ip("10.14.167.1")))
        assertFalse(subnet.contains(ip("10.14.165.255")))
        assertFalse(subnet.contains(ip("192.168.56.1")))
        // 网络地址（.0）与广播地址（.255）本身都落在网段内，不算「另一个网段」
        assertTrue(subnet.contains(ip("10.14.166.0")))
    }

    @Test
    fun subnetHonoursPrefixLength() {
        val slash16 = LocalNetwork.Subnet(ip("10.14.166.234"), 16)
        assertTrue(slash16.contains(ip("10.14.0.1")))
        assertTrue(slash16.contains(ip("10.14.255.254")))
        assertFalse(slash16.contains(ip("10.15.0.1")))

        val slash28 = LocalNetwork.Subnet(ip("172.20.10.1"), 28)
        assertTrue(slash28.contains(ip("172.20.10.15")))
        assertFalse(slash28.contains(ip("172.20.10.16")))

        // /32 只匹配自己
        val slash32 = LocalNetwork.Subnet(ip("10.0.0.5"), 32)
        assertTrue(slash32.contains(ip("10.0.0.5")))
        assertFalse(slash32.contains(ip("10.0.0.6")))
    }

    @Test
    fun subnetRejectsNonsensicalPrefix() {
        // 前缀越界不该被当成「匹配一切」，否则校验形同虚设
        assertFalse(LocalNetwork.Subnet(ip("10.14.166.234"), 0).contains(ip("10.14.166.234")))
        assertFalse(LocalNetwork.Subnet(ip("10.14.166.234"), 33).contains(ip("10.14.166.234")))
        assertFalse(LocalNetwork.Subnet(ip("10.14.166.234"), -1).contains(ip("10.14.166.234")))
    }

    @Test
    fun subnetsNeverThrowAndStayWellFormed() {
        // 只是保证在任何机器上调用都不炸；拿不到网卡时返回空列表也算合格
        val subnets = LocalNetwork.subnets()
        for (subnet in subnets) {
            assertTrue("前缀应在 1..32：" + subnet.prefixLength, subnet.prefixLength in 1..32)
            assertTrue("不得包含回环地址：" + subnet, !subnet.address.isLoopbackAddress)
            assertTrue(subnet.contains(subnet.address))
        }
        // 描述文案在空列表时也要能打印，否则失败日志会炸
        assertTrue(LocalNetwork.describe(subnets).isNotEmpty())
        assertTrue(LocalNetwork.describe(emptyList()).isNotEmpty())
    }
}
