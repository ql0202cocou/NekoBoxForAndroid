package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// parseClash 是纯函数：节点与每个条目的结果都从返回值里取
class ClashParseTest {

    private fun parse(yaml: String) = parseClash(loadClashYaml(yaml))

    private fun failure(yaml: String): ClashNodeResult.Failed =
        parse(yaml).nodes.single() as ClashNodeResult.Failed

    @Test
    fun `各条目按顺序给出结果，导入的节点补过默认值`() {
        val result = parse(
            """
            proxies:
              - {name: a, type: ss, server: ss.example.com, port: 8388, cipher: aes-128-gcm, password: pw-placeholder-1}
              - {name: b, type: snell, server: snell.example.com, port: 443}
              - {name: c, type: trojan, server: trojan.example.com, port: 443, password: pw-placeholder-2}
            """.trimIndent()
        )
        assertEquals(2, result.beans.size)
        val ss = result.beans[0] as ShadowsocksBean
        assertEquals("ss.example.com", ss.serverAddress)
        assertEquals(8388, ss.serverPort)
        assertEquals("aes-128-gcm", ss.method)
        assertEquals("", ss.plugin)
        val trojan = result.beans[1] as TrojanBean
        assertEquals("tls", trojan.security)
        assertEquals("tcp", trojan.type)

        assertEquals(listOf(0, 1, 2), result.nodes.map { it.index })
        assertTrue(result.nodes[0] is ClashNodeResult.Imported)
        val unknown = result.nodes[1] as ClashNodeResult.UnknownType
        assertEquals("snell", unknown.type)
        assertEquals("b", unknown.name)
        assertTrue(result.nodes[2] is ClashNodeResult.Imported)
    }

    @Test
    fun `没有 proxies 列表时抛专用异常`() {
        assertThrows(ClashNoProxiesException::class.java) { parse("proxies: none") }
        assertThrows(ClashNoProxiesException::class.java) { parse("proxy-groups: []\nproxies:") }
    }

    @Test
    fun `全部条目被跳过时返回零个节点和全部结果`() {
        val result = parse(
            """
            proxies:
              - "ss://placeholder"
              - {name: x, type: vmess, port: 443, uuid: 00000000-0000-0000-0000-000000000001}
              - {name: y, type: ssr, server: ssr.example.com, port: 443}
            """.trimIndent()
        )
        assertTrue(result.beans.isEmpty())
        assertEquals(ClashNodeFailure.ENTRY_NOT_MAP, (result.nodes[0] as ClashNodeResult.Failed).failure)
        assertEquals(ClashNodeFailure.MISSING_SERVER, (result.nodes[1] as ClashNodeResult.Failed).failure)
        assertTrue(result.nodes[2] is ClashNodeResult.UnknownType)
    }

    @Test
    fun `已知类型的失败原因来自封闭集合`() {
        assertEquals(
            ClashNodeFailure.MISSING_TYPE,
            failure("proxies:\n  - {name: a, server: a.example.com, port: 1}").failure,
        )
        assertEquals(
            ClashNodeFailure.MISSING_PORT,
            failure("proxies:\n  - {name: a, type: vless, server: a.example.com}").failure,
        )
        assertEquals(
            ClashNodeFailure.INVALID_PORT,
            failure("proxies:\n  - {name: a, type: vless, server: a.example.com, port: p-placeholder}").failure,
        )
        assertEquals(
            ClashNodeFailure.INVALID_PORT,
            failure("proxies:\n  - {name: a, type: socks5, server: a.example.com, port: 70000}").failure,
        )
        assertEquals(
            ClashNodeFailure.MISSING_SERVER,
            failure("proxies:\n  - {name: a, type: anytls, port: 443, password: pw-placeholder}").failure,
        )
        assertEquals(
            ClashNodeFailure.MISSING_PORT,
            failure("proxies:\n  - {name: a, type: hysteria2, server: h.example.com}").failure,
        )
        assertEquals(
            ClashNodeFailure.INVALID_PORT,
            failure("proxies:\n  - {name: a, type: hysteria2, server: h.example.com, port: 443, ports: 9-1}").failure,
        )
    }

    @Test
    fun `原有拒绝照旧：传输方式、SS 插件、TUIC v4`() {
        val transport = failure("proxies:\n  - {name: a, type: vmess, server: v.example.com, port: 443, network: kcp}")
        assertEquals(ClashNodeFailure.UNSUPPORTED_TRANSPORT, transport.failure)
        assertEquals("network", transport.path)
        assertEquals("kcp", transport.shownValue)

        val plugin = failure(
            "proxies:\n  - {name: a, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, plugin: shadow-tls}"
        )
        assertEquals(ClashNodeFailure.UNSUPPORTED_SS_PLUGIN, plugin.failure)
        assertEquals("unsupported shadowsocks plugin at plugin = shadow-tls", plugin.description)

        val tuic = failure("proxies:\n  - {name: a, type: tuic, server: t.example.com, port: 443, token: tk-placeholder}")
        assertEquals(ClashNodeFailure.TUIC_V4, tuic.failure)
        assertFalse(tuic.description.contains("tk-placeholder"))
    }

    @Test
    fun `兜底原因只带异常类名，不带值`() {
        val failed = failure(
            "proxies:\n  - {name: a, type: socks5, server: s.example.com, port: secret-port-placeholder}"
        )
        assertFalse(failed.description.contains("secret-port-placeholder"))
    }

    @Test
    fun `封闭集合以外的异常记 OTHER，只带异常类名`() {
        // 正常输入到不了兜底：用一个读 server 时抛异常的 map，异常消息里带着用户的值
        val entry = object : LinkedHashMap<String, Any?>() {
            override fun get(key: String): Any? =
                if (key == "server") throw IllegalStateException("secret-zq41") else super.get(key)
        }.apply {
            put("name", "a")
            put("type", "socks5")
            put("server", "s.example.com")
            put("port", "1080")
        }
        val failed = parseClash(mapOf("proxies" to listOf(entry))).nodes.single() as ClashNodeResult.Failed
        assertEquals(ClashNodeFailure.OTHER, failed.failure)
        assertEquals("IllegalStateException", failed.detail)
        assertEquals("parse error (IllegalStateException)", failed.description)
        assertFalse(failed.description.contains("zq41"))
    }

    @Test
    fun `socks5 与 vmess 基本字段`() {
        val result = parse(
            """
            proxies:
              - {name: s, type: socks5, server: 192.0.2.1, port: 1080, username: user-placeholder, password: pw-placeholder}
              - {name: v, type: vmess, server: v.example.com, port: 443, uuid: 00000000-0000-0000-0000-000000000002, alterId: 0, cipher: auto, tls: true, servername: sni.example.com}
            """.trimIndent()
        )
        val socks = result.beans[0] as SOCKSBean
        assertEquals("192.0.2.1", socks.serverAddress)
        assertEquals("user-placeholder", socks.username)
        val vmess = result.beans[1] as VMessBean
        assertEquals("tls", vmess.security)
        assertEquals("sni.example.com", vmess.sni)
        assertEquals("auto", vmess.encryption)
    }
}
