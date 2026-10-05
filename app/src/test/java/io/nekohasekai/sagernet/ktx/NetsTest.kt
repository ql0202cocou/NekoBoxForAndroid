package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

// 只覆盖不依赖 Android 框架的纯函数；mkPort、lookupViaNameserver 等依赖 SystemClock / native 核心，
// 在本地 JVM 单测里不可用，不在此测试。isLocalNameserverAddress / usableNameservers 的默认解析走
// android.system.Os，这里只测注入解析函数的版本
class NetsTest {

    // 只认这几个字面量的假解析，其余都当作不是数字地址；不查 DNS
    private val parsed = ArrayList<String>()
    private val fakeParse: (String) -> InetAddress? = { text ->
        parsed += text
        when (text) {
            "127.0.0.1" -> InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            "0.0.0.0" -> InetAddress.getByAddress(ByteArray(4))
            "::1" -> InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
            "192.0.2.53" -> InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53))
            else -> null
        }
    }

    @Test
    fun `isLocalNameserverAddress 用注入的解析函数判断回环与未指定地址`() {
        assertTrue("udp://127.0.0.1".isLocalNameserverAddress(fakeParse))
        assertTrue("[::1]:53".isLocalNameserverAddress(fakeParse))
        assertTrue("tls://0.0.0.0/".isLocalNameserverAddress(fakeParse))
        assertTrue("localhost:53".isLocalNameserverAddress(fakeParse))
        assertFalse("192.0.2.53".isLocalNameserverAddress(fakeParse))
        assertFalse("https://dns.example.com/dns-query".isLocalNameserverAddress(fakeParse))
        // 端口、方括号、scheme、路径都去掉之后才交给解析函数
        assertEquals(listOf("127.0.0.1", "::1", "0.0.0.0", "localhost", "192.0.2.53", "dns.example.com"), parsed)
    }

    @Test
    fun `usableNameservers 按注入的解析函数过滤本机地址`() {
        val text = listOf(
            "https://dns.example.com/dns-query#h3",
            "system",
            "local",
            "udp://127.0.0.1",
            "[::1]:53",
            "192.0.2.53",
            "https://dns.example.com/dns-query",
            "",
        ).joinToString("\n")
        assertEquals(listOf("https://dns.example.com/dns-query", "192.0.2.53"), text.usableNameservers(fakeParse))
    }

    @Test
    fun `withHttpScheme 替换链接 scheme`() {
        assertEquals("https://example.com/a", "vmess://example.com/a".withHttpScheme())
    }

    @Test
    fun `withHttpScheme 不碰查询串里的 scheme 分隔符`() {
        assertEquals(
            "https://example.com/?u=ss://x",
            "vmess://example.com/?u=ss://x".withHttpScheme()
        )
    }

    @Test
    fun `isServerAddress 普通域名和 IP 通过`() {
        assertTrue(isServerAddress("example.com"))
        assertTrue(isServerAddress("1.1.1.1"))
        assertTrue(isServerAddress("[2001:db8::1]"))
    }

    @Test
    fun `isServerAddress 空白值被拒绝`() {
        assertFalse(isServerAddress(""))
        assertFalse(isServerAddress("   "))
    }

    @Test
    fun `isServerAddress 含空格或斜杠被拒绝`() {
        assertFalse(isServerAddress("exa mple.com"))
        assertFalse(isServerAddress("example.com/path"))
    }

    @Test
    fun `unwrapIPV6Host 去掉一层方括号`() {
        assertEquals("2001:db8::1", "[2001:db8::1]".unwrapIPV6Host())
    }

    @Test
    fun `unwrapIPV6Host 无括号原样返回`() {
        assertEquals("example.com", "example.com".unwrapIPV6Host())
    }

    @Test
    fun `unwrapIPV6Host 多层括号递归去掉`() {
        assertEquals("::1", "[[::1]]".unwrapIPV6Host())
    }

    @Test
    fun `wrapIPV6Host 裸 IPv6 加上方括号`() {
        assertEquals("[2001:db8::1]", "2001:db8::1".wrapIPV6Host())
    }

    @Test
    fun `wrapIPV6Host 已带括号不重复添加`() {
        assertEquals("[2001:db8::1]", "[2001:db8::1]".wrapIPV6Host())
    }

    @Test
    fun `wrapIPV6Host 域名原样返回`() {
        assertEquals("example.com", "example.com".wrapIPV6Host())
    }

    @Test
    fun `isIpAddress 识别 IPv4`() {
        assertTrue("192.168.1.1".isIpAddress())
        assertFalse("256.1.1.1".isIpAddress())
    }

    @Test
    fun `isIpAddress 识别 IPv6`() {
        assertTrue("::1".isIpAddress())
        assertFalse("example.com".isIpAddress())
    }

    @Test
    fun `splitHostPort 域名带端口`() {
        assertEquals("example.com" to "443", "example.com:443".splitHostPort())
    }

    @Test
    fun `splitHostPort 域名不带端口`() {
        assertEquals("example.com" to null, "example.com".splitHostPort())
    }

    @Test
    fun `splitHostPort 方括号 IPv6 带端口`() {
        assertEquals("2001:db8::1" to "443", "[2001:db8::1]:443".splitHostPort())
    }

    @Test
    fun `splitHostPort 方括号 IPv6 不带端口`() {
        assertEquals("2001:db8::1" to null, "[2001:db8::1]".splitHostPort())
    }

    @Test
    fun `splitHostPort 裸 IPv6 视为无端口`() {
        assertEquals("2001:db8::1" to null, "2001:db8::1".splitHostPort())
    }

    @Test
    fun `splitHostPort 方括号未闭合返回 null`() {
        assertNull("[2001:db8::1".splitHostPort())
    }

    @Test
    fun `splitHostPort 空方括号返回 null`() {
        assertNull("[]:443".splitHostPort())
    }

    @Test
    fun `splitHostPort 方括号后接非端口内容返回 null`() {
        assertNull("[2001:db8::1]junk".splitHostPort())
    }
}
