package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxErrorProfileNameTest {

    private val result = ConfigBuildResult(
        "", listOf(), 0L, TrafficBindings(mapOf(), mapOf(), setOf()), mapOf(), -1L,
        mapOf("outbound[2]" to "美国", "endpoint[0]" to "WG"),
        mapOf("g-23" to "日本", "c-1-7" to "中转", "香港" to "香港", "[IPLC" to "短", "[IPLC] 新加坡" to "[IPLC] 新加坡x"),
    )

    @Test
    fun `出站序号换成节点名`() {
        val e = Exception("create service: initialize outbound[2]: unknown method: rc4-md5")
        val named = result.withBoxErrorProfileName(e)
        assertTrue(named is ProfileBuildException)
        assertEquals("美国: create service: initialize outbound[2]: unknown method: rc4-md5", named.message)
        assertSame(e, named.cause)
    }

    @Test
    fun `端点序号换成节点名`() {
        val named = result.withBoxErrorProfileName(Exception("create service: initialize endpoint[0]: bad key"))
        assertEquals("WG: create service: initialize endpoint[0]: bad key", named.message)
    }

    @Test
    fun `没记录的序号原样返回`() {
        val e = Exception("create service: initialize outbound[5]: something")
        assertSame(e, result.withBoxErrorProfileName(e))
    }

    @Test
    fun `入站等其它序号原样返回`() {
        val e = Exception("create service: initialize inbound[2]: listen failed")
        assertSame(e, result.withBoxErrorProfileName(e))
    }

    @Test
    fun `启动阶段的出站 tag 换成节点名`() {
        val named = result.withBoxErrorProfileName(Exception("start outbound/vless[g-23]: dial failed"))
        assertEquals("日本: start outbound/vless[g-23]: dial failed", named.message)
    }

    @Test
    fun `其它阶段的端点 tag 换成节点名`() {
        val named = result.withBoxErrorProfileName(Exception("post-start endpoint/wireguard[c-1-7]: bad peer"))
        assertEquals("中转: post-start endpoint/wireguard[c-1-7]: bad peer", named.message)
    }

    @Test
    fun `tag 本身就是节点名时原样返回`() {
        val e = Exception("start outbound/trojan[香港]: tls failed")
        assertSame(e, result.withBoxErrorProfileName(e))
    }

    @Test
    fun `节点名含方括号时取最长匹配的 tag`() {
        val named = result.withBoxErrorProfileName(Exception("start outbound/vless[[IPLC] 新加坡]: x"))
        assertEquals("[IPLC] 新加坡x: start outbound/vless[[IPLC] 新加坡]: x", named.message)
    }

    @Test
    fun `没记录的 tag 原样返回`() {
        val e = Exception("start outbound/direct[direct]: x")
        assertSame(e, result.withBoxErrorProfileName(e))
    }

    @Test
    fun `没有消息原样返回`() {
        val e = Exception()
        assertSame(e, result.withBoxErrorProfileName(e))
    }
}
