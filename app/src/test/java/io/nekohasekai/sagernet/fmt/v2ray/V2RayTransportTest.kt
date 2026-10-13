package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RayTransportTest {

    // 解析失败的回退链会写日志，JVM 里没有 libcore
    @org.junit.Before
    fun silenceLogs() {
        io.nekohasekai.sagernet.ktx.Logs.enabled = false
    }

    @org.junit.After
    fun restoreLogs() {
        io.nekohasekai.sagernet.ktx.Logs.enabled = true
    }

    @Test
    fun `已知传输方式原样返回`() {
        for (name in listOf("tcp", "http", "ws", "quic", "grpc", "httpupgrade")) {
            assertEquals(name, requireV2RayTransport(name))
        }
        assertEquals(V2RAY_TRANSPORTS, setOf("tcp", "http", "ws", "quic", "grpc", "httpupgrade"))
    }

    @Test
    fun `别名映射到规范名`() {
        assertEquals("tcp", requireV2RayTransport("raw"))
        assertEquals("tcp", requireV2RayTransport("none"))
        assertEquals("http", requireV2RayTransport("h2"))
        assertEquals("ws", requireV2RayTransport("websocket"))
    }

    @Test
    fun `空值按 TCP`() {
        assertEquals("tcp", requireV2RayTransport(null))
        assertEquals("tcp", requireV2RayTransport(""))
        assertEquals("tcp", requireV2RayTransport("  "))
    }

    @Test
    fun `不区分大小写`() {
        assertEquals("ws", requireV2RayTransport("WS"))
        assertEquals("tcp", requireV2RayTransport("Raw"))
        assertEquals("http", requireV2RayTransport("H2"))
        assertEquals("httpupgrade", requireV2RayTransport("HttpUpgrade"))
    }

    @Test
    fun `未知值拒绝且报错带原始值`() {
        for (name in listOf("xhttp", "splithttp", "kcp", "mkcp", "XHTTP", "ws+tls", "wss")) {
            assertNull(v2rayTransportOrNull(name))
            val e = assertThrows(UnsupportedTransportException::class.java) {
                requireV2RayTransport(name)
            }
            assertEquals(name, e.transport)
            assertTrue(e.message!!.contains("\"$name\""))
        }
    }

    @Test
    fun `形态不合格的值不回显`() {
        for (name in listOf("bad\nline-zq71", "has space-zq72", "x".repeat(65))) {
            val e = assertThrows(UnsupportedTransportException::class.java) {
                requireV2RayTransport(name)
            }
            assertEquals(name, e.transport)
            assertEquals("unsupported transport (value not shown)", e.message)
        }
    }

    @Test
    fun `Clash network 不写或 tcp 是 TCP`() {
        assertEquals("tcp", clashNetworkTransport(null))
        assertEquals("tcp", clashNetworkTransport(""))
        assertEquals("tcp", clashNetworkTransport("tcp"))
    }

    @Test
    fun `Clash network 的 h2 与 http 都落到 http，ws 与 grpc 原样`() {
        assertEquals("http", clashNetworkTransport("h2"))
        assertEquals("http", clashNetworkTransport("http"))
        assertEquals("ws", clashNetworkTransport("ws"))
        assertEquals("grpc", clashNetworkTransport("grpc"))
    }

    @Test
    fun `Clash network 只认 mihomo 的写法`() {
        for (name in listOf("xhttp", "splithttp", "kcp", "quic", "httpupgrade", "raw", "websocket", "WS")) {
            val e = assertThrows(UnsupportedTransportException::class.java) {
                clashNetworkTransport(name)
            }
            assertEquals(name, e.transport)
        }
    }

    // 以下经由真实的链接解析与构建入口，确认它们都走上面这张表

    private fun vless(query: String) = VMessBean().apply {
        alterId = -1
        parseDuckSoft("https://00000000-0000-0000-0000-000000000000@example.com:443?$query".toHttpUrl())
    }

    @Test
    fun `标准链接 type=raw 映射成 tcp`() {
        assertEquals("tcp", vless("type=raw").type)
        assertEquals("tcp", vless("security=tls").type)
        assertEquals("http", vless("type=h2").type)
        assertEquals("http", vless("type=raw&headerType=http").type)
        assertEquals("ws", vless("type=ws&path=%2Fws").type)
    }

    @Test
    fun `标准链接未知 type 拒绝`() {
        for (name in listOf("xhttp", "splithttp", "kcp")) {
            val e = assertThrows(UnsupportedTransportException::class.java) {
                vless("type=$name")
            }
            assertEquals(name, e.transport)
        }
        assertThrows(UnsupportedTransportException::class.java) {
            TrojanBean().parseDuckSoft("https://pw@example.com:443?type=xhttp".toHttpUrl())
        }
    }

    @Test
    fun `构建时存量节点的未知传输方式报错`() {
        val bean = VMessBean().apply { type = "xhttp" }
        val e = assertThrows(UnsupportedTransportException::class.java) {
            buildSingBoxOutboundStreamSettings(bean)
        }
        assertEquals("xhttp", e.transport)
    }

    @Test
    fun `构建时别名按规范名处理`() {
        assertNull(buildSingBoxOutboundStreamSettings(VMessBean().apply { type = "raw" }))
        assertEquals("grpc", buildSingBoxOutboundStreamSettings(VMessBean().apply {
            type = "grpc"
            path = "svc"
        })!!.type)
    }

    // 未知 security 拒绝导入，与未知传输方式一致

    private val uuid = "00000000-0000-0000-0000-000000000000"

    @Test
    fun `标准链接已知 security 照旧`() {
        assertEquals("tls", vless("security=tls").security)
        assertEquals("tls", vless("security=reality").security)
        assertEquals("none", vless("security=none").security)
        assertEquals("none", vless("").security)
        assertEquals("none", vless("security=").security)
        assertEquals("tls", TrojanBean().apply { parseDuckSoft("https://pw@example.com:443".toHttpUrl()) }.security)
    }

    @Test
    fun `标准链接未知 security 拒绝`() {
        for (name in listOf("xtls", "tsl", "ssl")) {
            val e = assertThrows(UnsupportedSecurityException::class.java) { vless("security=$name") }
            assertEquals(name, e.security)
            assertEquals("unsupported security \"$name\"", e.message)
        }
        assertThrows(UnsupportedTransportException::class.java) {
            TrojanBean().parseDuckSoft("https://pw@example.com:443?security=xtls".toHttpUrl())
        }
        val hidden = assertThrows(UnsupportedSecurityException::class.java) { vless("security=a%20b") }
        assertEquals("unsupported security (value not shown)", hidden.message)
    }

    private fun vmessJson(tls: String): String {
        return """{"v":"2","ps":"n","add":"example.com","port":"443","id":"$uuid","aid":"0","net":"tcp","tls":"$tls"}"""
    }

    @Test
    fun `VMess JSON 已知 tls 照旧，未知拒绝`() {
        assertEquals("tls", parseV2RayNJson(vmessJson("tls")).security)
        assertEquals("tls", parseV2RayNJson(vmessJson("reality")).security)
        assertTrue(parseV2RayNJson(vmessJson("")).security.isNullOrEmpty())
        assertTrue(parseV2RayNJson(vmessJson("none")).security.isNullOrEmpty())
        val e = assertThrows(UnsupportedSecurityException::class.java) { parseV2RayNJson(vmessJson("xtls")) }
        assertEquals("xtls", e.security)
    }

    @Test
    fun `批量导入里 security 未知的一条不影响其它条`() {
        val text = listOf(
            "vless://$uuid@a.example.com:443?security=tls#a",
            "vless://$uuid@b.example.com:443?security=xtls#b",
            "vless://$uuid@c.example.com:443?security=none#c",
        ).joinToString("\n")
        val result = kotlinx.coroutines.runBlocking { io.nekohasekai.sagernet.ktx.parseProxies(text) { false } }
        assertEquals(listOf("a.example.com", "c.example.com"), result.map { it.serverAddress })
        val onlyBad = assertThrows(UnsupportedTransportException::class.java) {
            kotlinx.coroutines.runBlocking {
                io.nekohasekai.sagernet.ktx.parseProxies("vless://$uuid@b.example.com:443?security=xtls") { false }
            }
        }
        assertTrue(onlyBad is UnsupportedSecurityException)
    }
}
