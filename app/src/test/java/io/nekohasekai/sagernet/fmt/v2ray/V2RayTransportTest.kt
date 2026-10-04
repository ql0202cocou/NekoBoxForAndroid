package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RayTransportTest {

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
}
