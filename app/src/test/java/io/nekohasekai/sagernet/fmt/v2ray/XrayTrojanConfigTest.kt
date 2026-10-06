package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.externalCore
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

// Trojan 的 Xray 出站（D10 的生成器部分）：出站的完整形状、与 VMess / VLESS 共用的传输与 TLS 生成、
// Trojan 自己的 mux 规则与报错。预期值按 Xray 接受的写法（settings.servers 一项，v26.3.27 与 v26.9.30 相同，
// K1b X2 T2-19…24）手写
class XrayTrojanConfigTest {

    private val settings = ExternalCoreSettings(logLevel = 0, ipv6Mode = 0, globalAllowInsecure = false)

    private fun trojan(block: TrojanBean.() -> Unit = {}) = TrojanBean().apply {
        serverAddress = "trojan.example.com"
        serverPort = 443
        password = "fake-trojan-pass-1"
        initializeDefaultValues()
        block()
    }

    // 映射后外核拨的是本地地址，与 serverAddress 不同
    private fun build(bean: TrojanBean, settings: ExternalCoreSettings = this.settings): JsonObject =
        JsonParser.parseString(gson.toJson(buildXrayOutbound(bean, "127.0.0.1", 20000, settings))).asJsonObject

    private fun JsonObject.stream() = getAsJsonObject("streamSettings")

    // 按文本比较，键的顺序也要一致
    private fun assertJson(expected: String, actual: JsonElement) =
        assertEquals(JsonParser.parseString(expected).toString(), actual.toString())

    private val servers = """{"servers":[{"address":"127.0.0.1","port":20000,"password":"fake-trojan-pass-1"}]}"""
    private val realityKey = "BwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSY"
    private val mldsa65 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(1952) { (it * 7).toByte() })
    private val pin = "d2fd6f6b04f88cb7cf03ce84064d413aeefc44b6dad87d0900cb90aeca364325"

    @Test
    fun `tcp 加 tls：servers 一项、不写 flow，TLS 与 VMess 同一套`() {
        val outbound = build(trojan {
            sni = "sni.example.com"
            alpn = "h2,http/1.1"
            utlsFingerprint = "firefox"
        })
        assertJson(
            """{"protocol":"trojan","settings":$servers,"streamSettings":{"network":"tcp","security":"tls",
            "tlsSettings":{"serverName":"sni.example.com","alpn":["h2","http/1.1"],"fingerprint":"firefox"}}}""",
            outbound,
        )
    }

    @Test
    fun `tcp 加 REALITY`() {
        val outbound = build(trojan {
            sni = "www.example.org"
            realityPubKey = realityKey
            realityShortId = "0123abcd"
        })
        assertJson(
            """{"protocol":"trojan","settings":$servers,"streamSettings":{"network":"tcp","security":"reality",
            "realitySettings":{"serverName":"www.example.org","publicKey":"$realityKey","shortId":"0123abcd",
            "fingerprint":"chrome"}}}""",
            outbound,
        )
    }

    @Test
    fun `ws 加 tls：路径里的 ed 保留，Host 写独立的 host 键`() {
        val stream = build(trojan {
            type = "ws"
            host = "cdn.example.net"
            path = "/ws?ed=2048"
            sni = "sni.example.com"
        }).stream()
        assertJson(
            """{"network":"ws","wsSettings":{"path":"/ws?ed=2048","host":"cdn.example.net"},
            "security":"tls","tlsSettings":{"serverName":"sni.example.com"}}""",
            stream,
        )
    }

    @Test
    fun `grpc 加 tls`() {
        val stream = build(trojan {
            type = "grpc"
            path = "example-grpc"
            sni = "sni.example.com"
        }).stream()
        assertJson(
            """{"network":"grpc","grpcSettings":{"serviceName":"example-grpc"},
            "security":"tls","tlsSettings":{"serverName":"sni.example.com"}}""",
            stream,
        )
    }

    @Test
    fun `grpc 加 REALITY`() {
        val stream = build(trojan {
            type = "grpc"
            path = "example-grpc"
            sni = "www.example.org"
            realityPubKey = realityKey
            utlsFingerprint = "safari"
        }).stream()
        assertJson(
            """{"network":"grpc","grpcSettings":{"serviceName":"example-grpc"},"security":"reality",
            "realitySettings":{"serverName":"www.example.org","publicKey":"$realityKey","fingerprint":"safari"}}""",
            stream,
        )
    }

    @Test
    fun `httpupgrade 加 tls`() {
        val stream = build(trojan {
            type = "httpupgrade"
            host = "up.example.net"
            path = "/up"
            sni = "sni.example.com"
        }).stream()
        assertJson(
            """{"network":"httpupgrade","httpupgradeSettings":{"host":"up.example.net","path":"/up"},
            "security":"tls","tlsSettings":{"serverName":"sni.example.com"}}""",
            stream,
        )
    }

    @Test
    fun `tcp 的 http 头伪装，不带 TLS`() {
        val outbound = build(trojan {
            type = "http"
            security = "none"
            host = "a.example.com,b.example.com"
            path = "/fake"
        })
        assertJson(
            """{"protocol":"trojan","settings":$servers,"streamSettings":{"network":"tcp","tcpSettings":
            {"header":{"type":"http","request":{"path":["/fake"],"headers":{"Host":["a.example.com","b.example.com"]}}}}}}""",
            outbound,
        )
    }

    @Test
    fun `不带 TLS 时 streamSettings 只有 network，残留的 TLS 字段不读`() {
        val outbound = build(trojan {
            security = "none"
            sni = "sni.example.com"
            realityPubKey = realityKey
            allowInsecure = true
        })
        assertJson("""{"protocol":"trojan","settings":$servers,"streamSettings":{"network":"tcp"}}""", outbound)
    }

    @Test
    fun `证书指纹写成 pinnedPeerCertSha256，优先于 allowInsecure`() {
        val tls = build(
            trojan {
                sni = "sni.example.com"
                certificateFingerprint = pin
                allowInsecure = true
            },
            settings.copy(globalAllowInsecure = true),
        ).stream().getAsJsonObject("tlsSettings")
        assertJson("""{"serverName":"sni.example.com","pinnedPeerCertSha256":"$pin"}""", tls)
    }

    @Test
    fun `REALITY 带 mldsa65Verify`() {
        val reality = build(trojan {
            sni = "www.example.org"
            realityPubKey = realityKey
            realityShortId = "0123abcd"
            realityMldsa65Verify = mldsa65
        }).stream().getAsJsonObject("realitySettings")
        assertJson(
            """{"serverName":"www.example.org","publicKey":"$realityKey","shortId":"0123abcd",
            "mldsa65Verify":"$mldsa65","fingerprint":"chrome"}""",
            reality,
        )
    }

    @Test
    fun `ECH 内联配置写成 echConfigList`() {
        val tls = build(trojan {
            sni = "sni.example.com"
            enableECH = true
            echConfig = "AEX+DQBBpQAgACB\nexampleEchConfig=="
        }).stream().getAsJsonObject("tlsSettings")
        assertJson("""{"serverName":"sni.example.com","echConfigList":"AEX+DQBBpQAgACBexampleEchConfig=="}""", tls)
    }

    @Test
    fun `开 mux 写 Mux_Cool 与 xudpProxyUDP443 allow，packetEncoding 不读`() {
        val muxed = build(trojan {
            enableMux = true
            muxConcurrency = 4
            packetEncoding = 2
        })
        assertJson("""{"enabled":true,"concurrency":4,"xudpProxyUDP443":"allow"}""", muxed.getAsJsonObject("mux"))
        // 没填并发数时取 8，与 VMess / VLESS 相同
        val fallback = build(trojan {
            enableMux = true
            muxConcurrency = 0
        })
        assertJson("""{"enabled":true,"concurrency":8,"xudpProxyUDP443":"allow"}""", fallback.getAsJsonObject("mux"))
        // trojan:// 链接带进来的 xudp 不开 mux
        val xudpOnly = build(trojan { packetEncoding = 2 })
        assertEquals(listOf("protocol", "settings", "streamSettings"), xudpOnly.keySet().toList())
    }

    private fun buildError(bean: TrojanBean, settings: ExternalCoreSettings = this.settings): String? =
        assertThrows(IllegalStateException::class.java) {
            buildXrayOutbound(bean, "127.0.0.1", 20000, settings)
        }.message

    @Test
    fun `h2、quic 与生效的 allowInsecure 照现有规则报错`() {
        assertEquals(
            "xray-core no longer supports the http transport, use the sing-box core for this profile",
            buildError(trojan { type = "http" }),
        )
        assertEquals(
            "xray-core no longer supports the quic transport, use the sing-box core for this profile",
            buildError(trojan { type = "quic" }),
        )
        val insecure =
            "xray-core no longer supports allowInsecure, use a certificate fingerprint or the sing-box core for this profile"
        assertEquals(insecure, buildError(trojan { allowInsecure = true }))
        assertEquals(insecure, buildError(trojan(), settings.copy(globalAllowInsecure = true)))
    }

    @Test
    fun `密码为空时报错`() {
        assertEquals("Trojan password is empty", buildError(trojan { password = "" }))
    }

    // ---- 合并配置

    private val auth = LocalSocksAuth("u0123456789abcdef", "p0123456789abcdef0123456789abcdef")

    private fun vmess(vless: Boolean, block: VMessBean.() -> Unit) = VMessBean().apply {
        serverAddress = "vmess.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-00000000000${if (vless) 2 else 1}"
        alterId = if (vless) -1 else 0
        initializeDefaultValues()
        block()
    }

    @Test
    fun `VMess、VLESS、Trojan 同在一份合并配置里`() {
        val vmessWs = vmess(vless = false) {
            type = "ws"
            path = "/vmess"
            security = "tls"
            sni = "sni.example.com"
        }
        val vlessReality = vmess(vless = true) {
            security = "tls"
            sni = "www.example.org"
            realityPubKey = realityKey
        }
        val trojanGrpc = trojan {
            type = "grpc"
            path = "example-grpc"
            sni = "sni.example.com"
        }
        val trojanMux = trojan {
            password = "fake-trojan-pass-2"
            enableMux = true
        }
        val beans = listOf(vmessWs, vlessReality, trojanGrpc, trojanMux)
        // 跳实例的外核按 bean 取（externalCore）：Trojan 与 VMess / VLESS 都是 Xray（D10），合成一组
        val hops = beans.mapIndexed { i, bean ->
            ExternalHop(i, i, i + 1L, bean, 21000 + i, ExternalDialTarget.Mapped(30000 + i), localAuth = auth)
        }
        assertEquals(listOf("xray-plugin"), beans.map { externalCore(it)!!.pluginId }.distinct())
        assertEquals(1, ExternalRunPlan(hops).groups.size)
        val outbounds = beans.mapIndexed { i, bean ->
            when (bean) {
                is VMessBean -> buildXrayOutbound(bean, "127.0.0.1", 30000 + i, settings)
                is TrojanBean -> buildXrayOutbound(bean, "127.0.0.1", 30000 + i, settings)
                else -> error("unexpected bean")
            }
        }
        val config = JsonParser.parseString(buildXrayConfig(hops, outbounds, settings)).asJsonObject

        val written = config.getAsJsonArray("outbounds")
        assertEquals(
            listOf("block", "out-0", "out-1", "out-2", "out-3"),
            written.map { it.asJsonObject.get("tag").asString },
        )
        assertEquals(
            listOf("blackhole", "vmess", "vless", "trojan", "trojan"),
            written.map { it.asJsonObject.get("protocol").asString },
        )
        assertJson(
            """{"tag":"out-2","protocol":"trojan","settings":{"servers":[{"address":"127.0.0.1","port":30002,
            "password":"fake-trojan-pass-1"}]},"streamSettings":{"network":"grpc","grpcSettings":
            {"serviceName":"example-grpc"},"security":"tls","tlsSettings":{"serverName":"sni.example.com"}}}""",
            written[3],
        )
        assertJson(
            """{"tag":"out-3","protocol":"trojan","settings":{"servers":[{"address":"127.0.0.1","port":30003,
            "password":"fake-trojan-pass-2"}]},"streamSettings":{"network":"tcp","security":"tls",
            "tlsSettings":{"serverName":"trojan.example.com"}},"mux":{"enabled":true,"concurrency":1,"xudpProxyUDP443":"allow"}}""",
            written[4],
        )
        // 每个入站按 tag 路由到自己的出站，Trojan 与 VMess / VLESS 没有区别
        assertEquals(
            (0..3).map { "in-$it -> out-$it" },
            config.getAsJsonObject("routing").getAsJsonArray("rules").map {
                val rule = it.asJsonObject
                "${rule.getAsJsonArray("inboundTag").single().asString} -> ${rule.get("outboundTag").asString}"
            },
        )
        assertEquals(4, config.getAsJsonArray("inbounds").size())
    }
}
