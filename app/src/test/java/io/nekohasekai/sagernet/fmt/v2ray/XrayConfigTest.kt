package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// 黄金基线没有走到的 buildXrayOutbound 分支；预期值按改写前（org.json）的代码逻辑推出
class XrayConfigTest {

    private val settings = ExternalCoreSettings(logLevel = 0, ipv6Mode = 0, globalAllowInsecure = false)

    private fun bean(vless: Boolean, block: VMessBean.() -> Unit = {}) = VMessBean().apply {
        serverAddress = "server.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = if (vless) -1 else 0
        initializeDefaultValues()
        block()
    }

    // 一个跳实例的出站；映射后外核拨的是本地地址，与 serverAddress 不同
    private fun build(bean: VMessBean, settings: ExternalCoreSettings = this.settings): JsonObject =
        JsonParser.parseString(gson.toJson(buildXrayOutbound(bean, "127.0.0.1", 20000, settings))).asJsonObject

    private fun JsonObject.user() = getAsJsonObject("settings").getAsJsonArray("vnext")[0]
        .asJsonObject.getAsJsonArray("users")[0].asJsonObject

    private fun JsonObject.stream() = getAsJsonObject("streamSettings")

    // 按文本比较，键的顺序也要一致
    private fun assertJson(expected: String, actual: JsonElement) =
        assertEquals(JsonParser.parseString(expected).toString(), actual.toString())

    private val realityKey = "A".repeat(43)

    // 与 gen-xhttp.py 的 EXTRA_CANON 逐字一致（回环验证用的 extra）
    private val XHTTP_LOOPBACK_EXTRA = """{"headers":{"X-K2-Probe":"k2-probe-value"},"xPaddingBytes":"200-400",""" +
        """"xmux":{"maxConcurrency":"8-16","cMaxReuseTimes":64,"hMaxRequestTimes":"700-800","hMaxReusableSecs":"1900-2900","hKeepAlivePeriod":30}}"""

    @Test
    fun `VLESS 的 encryption 为 auto 时不写 flow`() {
        val user = build(bean(vless = true) { encryption = "auto" }).user()
        assertJson("""{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}""", user)
    }

    @Test
    fun `VMess 的 encryption 为空时 security 取 auto`() {
        val user = build(bean(vless = false) { encryption = "" }).user()
        assertJson("""{"id":"00000000-0000-0000-0000-000000000001","alterId":0,"security":"auto"}""", user)
    }

    // K1b D16：有 mux 块就写 xudpProxyUDP443 = allow（Xray 缺省 reject 会丢掉 UDP/443）；只开 xudp 时 TCP 不复用
    @Test
    fun `mux 块总写 xudpProxyUDP443 allow`() {
        assertJson(
            """{"enabled":true,"concurrency":4,"xudpProxyUDP443":"allow"}""",
            build(bean(vless = true) { enableMux = true; muxConcurrency = 4 }).getAsJsonObject("mux"),
        )
        assertJson(
            """{"enabled":true,"concurrency":8,"xudpProxyUDP443":"allow"}""",
            build(bean(vless = false) { enableMux = true; muxConcurrency = 0 }).getAsJsonObject("mux"),
        )
        assertJson(
            """{"enabled":true,"concurrency":-1,"xudpConcurrency":16,"xudpProxyUDP443":"allow"}""",
            build(bean(vless = true) { packetEncoding = 2 }).getAsJsonObject("mux"),
        )
        assertJson(
            """{"enabled":true,"concurrency":2,"xudpConcurrency":16,"xudpProxyUDP443":"allow"}""",
            build(bean(vless = false) { enableMux = true; muxConcurrency = 2; packetEncoding = 2 }).getAsJsonObject("mux"),
        )
        // 不开 mux 也不开 xudp、或 vision 流控：不写 mux 块
        assertFalse(build(bean(vless = true)).has("mux"))
        assertFalse(build(bean(vless = true) { encryption = StandardV2RayBean.FLOW_VISION; enableMux = true }).has("mux"))
    }

    @Test
    fun `tcp 伪 HTTP 头没有 host 时不写 headers`() {
        val stream = build(bean(vless = true) { type = "http" }).stream()
        assertJson(
            """{"network":"tcp","tcpSettings":{"header":{"type":"http","request":{"path":["/"]}}}}""",
            stream,
        )
    }

    @Test
    fun `httpupgrade 没有 host 时只写 path`() {
        val stream = build(bean(vless = true) { type = "httpupgrade"; path = "/up" }).stream()
        assertJson("""{"network":"httpupgrade","httpupgradeSettings":{"path":"/up"}}""", stream)
    }

    @Test
    fun `REALITY 的 sni 为空时兜底为 serverAddress，shortId 为空时不写`() {
        val stream = build(bean(vless = true) {
            security = "tls"
            realityPubKey = realityKey
        }).stream()
        assertJson(
            """{"network":"tcp","security":"reality","realitySettings":
            {"serverName":"server.example.com","publicKey":"$realityKey","fingerprint":"chrome"}}""",
            stream,
        )
    }

    @Test
    fun `sni 与 serverAddress 都为空时不写 serverName`() {
        val reality = build(bean(vless = true) {
            security = "tls"
            realityPubKey = realityKey
            serverAddress = ""
        }).stream()
        assertJson(
            """{"publicKey":"$realityKey","fingerprint":"chrome"}""",
            reality.getAsJsonObject("realitySettings"),
        )
        val tls = build(bean(vless = true) {
            security = "tls"
            serverAddress = ""
        }).stream()
        assertJson("""{"network":"tcp","security":"tls","tlsSettings":{}}""", tls)
    }

    @Test
    fun `security 不是 tls 时忽略残留的 REALITY 字段`() {
        val stream = build(bean(vless = true) {
            security = "none"
            realityPubKey = realityKey
            realityShortId = "abcd"
        }).stream()
        assertJson("""{"network":"tcp"}""", stream)
    }

    @Test
    fun `echConfig 有值但未启用 ECH 时不写 echConfigList`() {
        val tls = build(bean(vless = true) {
            security = "tls"
            sni = "sni.example.com"
            enableECH = false
            echConfig = "AEX+DQBBpQAgACB"
        }).stream().getAsJsonObject("tlsSettings")
        assertFalse(tls.has("echConfigList"))
        assertJson("""{"serverName":"sni.example.com"}""", tls)
    }

    @Test
    fun `全局允许不安全时证书固定优先，不写 allowInsecure`() {
        val pin = "ab".repeat(32)
        val tls = build(
            bean(vless = true) {
                security = "tls"
                sni = "sni.example.com"
                certificateFingerprint = pin
            },
            settings.copy(globalAllowInsecure = true),
        ).stream().getAsJsonObject("tlsSettings")
        assertJson("""{"serverName":"sni.example.com","pinnedPeerCertSha256":"$pin"}""", tls)
    }

    @Test
    fun `全局允许不安全且没有证书固定时报错`() {
        val e = assertThrows(IllegalStateException::class.java) {
            buildXrayOutbound(
                bean(vless = true) { security = "tls" },
                "127.0.0.1",
                20000,
                settings.copy(globalAllowInsecure = true),
            )
        }
        assertEquals(
            "xray-core no longer supports allowInsecure, use a certificate fingerprint or the sing-box core for this profile",
            e.message,
        )
    }

    // REALITY 分支本来就不写 allowInsecure：全局与节点的「允许不安全」都不该让它报错（K1 之前这里把 REALITY 节点误判成
    // 不支持 allowInsecure）
    @Test
    fun `REALITY 节点在全局允许不安全时照常生成`() {
        for (nodeInsecure in listOf(false, true)) {
            val stream = build(
                bean(vless = true) {
                    security = "tls"
                    sni = "sni.example.com"
                    realityPubKey = realityKey
                    allowInsecure = nodeInsecure
                },
                settings.copy(globalAllowInsecure = true),
            ).stream()
            assertJson(
                """{"network":"tcp","security":"reality","realitySettings":""" +
                    """{"serverName":"sni.example.com","publicKey":"$realityKey","fingerprint":"chrome"}}""",
                stream,
            )
        }
        assertFalse(bean(vless = true) { security = "tls"; realityPubKey = realityKey }.xrayLacksAllowInsecure(true))
    }

    // ---- XHTTP（K2）。预期值来自 K2 在模拟器上对 Xray v26.10.17 的回环验证（doc/agent/k1b-wip/probe-plan/xray/xhttp/）

    private val xhttpPin = "ab".repeat(32)

    @Test
    fun `xhttp 无 TLS 且 host 为空：host 用 serverAddress 补上，mode 默认 auto`() {
        // 经映射外核拨的是 127.0.0.1，host 为空时 Xray 的 Host 头是 127.0.0.1（XL-01），服务端设了 host 校验就会被拒（XL-08）
        val stream = build(bean(vless = true) { type = "xhttp"; path = "/xh" }).stream()
        assertJson(
            """{"network":"xhttp","xhttpSettings":{"host":"server.example.com","path":"/xh","mode":"auto"}}""",
            stream,
        )
    }

    @Test
    fun `xhttp 无 TLS：用户写了 host 就原样用，带端口也不改`() {
        for (value in listOf("cdn.example.net", "cdn.example.net:8443")) {
            val xhttp = build(bean(vless = true) { type = "xhttp"; path = "/xh"; host = value }).stream()
                .getAsJsonObject("xhttpSettings")
            assertEquals(value, xhttp.get("host").asString)
        }
    }

    @Test
    fun `xhttp 无 TLS 且服务器是 IPv6 字面量：补的 host 带方括号`() {
        // 裸写 Xray 会报 invalid port，一个请求都发不出去（XL-03）；带方括号与直连 IPv6 时 Xray 自己发的 Host 一致（XL-05）
        val xhttp = build(bean(vless = true) { type = "xhttp"; path = "/xh"; serverAddress = "2001:db8::1" }).stream()
            .getAsJsonObject("xhttpSettings")
        assertEquals("[2001:db8::1]", xhttp.get("host").asString)
        // 服务器地址本来就带方括号（initializeDefaultValues 会去掉，直接赋值时仍可能带）
        val bracketed = build(bean(vless = true) { type = "xhttp"; path = "/xh"; serverAddress = "[2001:db8::1]" }).stream()
            .getAsJsonObject("xhttpSettings")
        assertEquals("[2001:db8::1]", bracketed.get("host").asString)
        // IPv4 与域名不加方括号
        val v4 = build(bean(vless = true) { type = "xhttp"; path = "/xh"; serverAddress = "192.0.2.7" }).stream()
            .getAsJsonObject("xhttpSettings")
        assertEquals("192.0.2.7", v4.get("host").asString)
    }

    @Test
    fun `xhttp 加 TLS：host 为空不写，Xray 取 serverName`() {
        val stream = build(bean(vless = true) {
            type = "xhttp"; path = "/xh"; security = "tls"; sni = "sni.example.com"; certificateFingerprint = xhttpPin
        }).stream()
        assertJson(
            """{"network":"xhttp","xhttpSettings":{"path":"/xh","mode":"auto"},"security":"tls",
            "tlsSettings":{"serverName":"sni.example.com","pinnedPeerCertSha256":"$xhttpPin"}}""",
            stream,
        )
        // 显式 host 压过 SNI
        val explicit = build(bean(vless = true) {
            type = "xhttp"; path = "/xh"; security = "tls"; sni = "sni.example.com"; host = "cdn.example.net"
        }).stream().getAsJsonObject("xhttpSettings")
        assertEquals("cdn.example.net", explicit.get("host").asString)
    }

    @Test
    fun `xhttp 加 REALITY`() {
        val stream = build(bean(vless = true) {
            type = "xhttp"; path = "/xh"; security = "tls"; sni = "www.example.org"; realityPubKey = realityKey
            xhttpMode = "stream-one"
        }).stream()
        assertJson(
            """{"network":"xhttp","xhttpSettings":{"path":"/xh","mode":"stream-one"},"security":"reality",
            "realitySettings":{"serverName":"www.example.org","publicKey":"$realityKey","fingerprint":"chrome"}}""",
            stream,
        )
    }

    @Test
    fun `xhttp 的 mode 规范成小写，空值是 auto，不认识的值报错且不带值`() {
        for ((input, written) in listOf(
            "" to "auto", "auto" to "auto", "packet-up" to "packet-up", "stream-up" to "stream-up",
            "stream-one" to "stream-one", "Stream-One" to "stream-one", " PACKET-UP " to "packet-up",
        )) {
            val xhttp = build(bean(vless = true) { type = "xhttp"; path = "/xh"; xhttpMode = input }).stream()
                .getAsJsonObject("xhttpSettings")
            assertEquals("mode=$input", written, xhttp.get("mode").asString)
        }
        // Xray 区分大小写，原样写 Stream-One 会让整份配置起不来（XT-10），所以一定要在这里规范
        val e = assertThrows(InvalidXhttpExtraException::class.java) {
            build(bean(vless = true) { type = "xhttp"; path = "/xh"; xhttpMode = "secret-mode-value" })
        }
        assertFalse(e.message!!.contains("secret-mode-value"))
    }

    @Test
    fun `xhttp 的 extra 写成 JSON 对象，空 extra 不写`() {
        val input = """{"XPaddingBytes":"400-200","Headers":{"X-K2-Probe":"k2-probe-value"},
            "xmux":{"hKeepAlivePeriod":30,"cMaxReuseTimes":"64","maxConcurrency":"16-8","hMaxRequestTimes":"700-800",
            "hMaxReusableSecs":"1900-2900"}}"""
        val xhttp = build(bean(vless = true) { type = "xhttp"; path = "/xh"; xhttpExtra = input }).stream()
            .getAsJsonObject("xhttpSettings")
        // 写的是对象，不是字符串（字符串写法 Xray 报错，XT-02）；规范键名、固定顺序、范围从小到大
        assertTrue(xhttp.get("extra").isJsonObject)
        assertEquals(XHTTP_LOOPBACK_EXTRA, xhttp.get("extra").toString())
        assertJson(
            """{"host":"server.example.com","path":"/xh","mode":"auto","extra":$XHTTP_LOOPBACK_EXTRA}""",
            xhttp,
        )
        for (empty in listOf("", "  ", "{}", null)) {
            val without = build(bean(vless = true) { type = "xhttp"; path = "/xh"; xhttpExtra = empty }).stream()
                .getAsJsonObject("xhttpSettings")
            assertFalse(without.has("extra"))
        }
    }

    @Test
    fun `xhttp 的 extra 在生成时再校验一遍：绕过编辑器的值也进不来，错误信息不带值`() {
        val secret = "super-secret-token-value"
        for (extra in listOf(
            """{"downloadSettings":{"address":"dl.example.org"},"headers":{"Authorization":"$secret"}}""",
            """{"headers":{"Authorization":"$secret"},"unknownKey":1}""",
            """{"headers":{"Host":"$secret"}}""",
            """{"extra":{},"headers":{"X-Token":"$secret"}}""",
            """{"path":"/other","headers":{"X-Token":"$secret"}}""",
            """{"headers":{"X-Token":"$secret"}""",
        )) {
            val e = assertThrows(InvalidXhttpExtraException::class.java) {
                build(bean(vless = true) { type = "xhttp"; path = "/xh"; xhttpExtra = extra })
            }
            assertFalse("消息不该带值：${e.message}", e.message!!.contains(secret))
        }
    }

    @Test
    fun `xhttp 的 extra 规范形态与回环验证用的是同一份`() {
        // doc/agent/k1b-wip/probe-plan/xray/xhttp/gen-xhttp.py 的 EXTRA_CANON：回环验证的就是生成器会写的内容
        assertEquals(
            XHTTP_LOOPBACK_EXTRA,
            canonicalXhttpExtra(
                """{"xmux":{"maxConcurrency":"8-16","cMaxReuseTimes":64,"hMaxRequestTimes":"700-800","hMaxReusableSecs":"1900-2900","hKeepAlivePeriod":30},
                "xPaddingBytes":"200-400","headers":{"X-K2-Probe":"k2-probe-value"}}""",
            ),
        )
    }

    @Test
    fun `参数残缺的 xhttp 节点（path 为空）生成时报错`() {
        for (empty in listOf("", "  ", null)) {
            assertThrows(XhttpParametersLostException::class.java) {
                build(bean(vless = true) { type = "xhttp"; path = empty })
            }
        }
    }

    @Test
    fun `旧名 splithttp 与大小写写法生成 xhttp`() {
        for (name in listOf("splithttp", "SplitHTTP", "XHTTP")) {
            val stream = build(bean(vless = true) { type = name; path = "/xh" }).stream()
            assertEquals("xhttp", stream.get("network").asString)
            assertTrue(stream.has("xhttpSettings"))
        }
    }

    // 这两种组合能通过 Xray 的 run -test，运行时才每次连接失败（K2 回环 XL-45 / XL-49）；选核已排除，生成器再拦一道
    @Test
    fun `xhttp 配 Vision 流控或 Mux Cool 时生成报错，只开 XUDP 包编码可以`() {
        for (flow in listOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
            assertThrows(IllegalStateException::class.java) {
                build(bean(vless = true) { type = "xhttp"; path = "/xh"; security = "tls"; encryption = flow })
            }
        }
        for (isVless in listOf(true, false)) {
            assertThrows(IllegalStateException::class.java) {
                build(bean(vless = isVless) { type = "xhttp"; path = "/xh"; enableMux = true; muxConcurrency = 4 })
            }
        }
        val xudp = build(bean(vless = true) { type = "xhttp"; path = "/xh"; packetEncoding = 2 })
        assertJson(
            """{"enabled":true,"concurrency":-1,"xudpConcurrency":16,"xudpProxyUDP443":"allow"}""",
            xudp.getAsJsonObject("mux"),
        )
        // 其它传输上 Vision 与 Mux.Cool 的行为不变
        assertTrue(build(bean(vless = true) { enableMux = true }).has("mux"))
    }

    // header 的值可能是凭据：配置里必须带（Xray 才发得出去），写日志与存进报错文本的路径上都要遮掉
    @Test
    fun `xhttp extra headers 的值不进日志与报错文本`() {
        val secret = "tok-8f3a91c2d4e65b70"
        val node = bean(vless = true) {
            type = "xhttp"; path = "/xh"; host = "cdn.example.net"
            xhttpExtra = """{"headers":{"X-Api-Token":"$secret"},"xPaddingBytes":"100-200"}"""
        }
        val hop = ExternalHop(0, 0, 1L, node, 21000, ExternalDialTarget.Mapped(30000), localAuth = LocalSocksAuth.generate(Random(5)))
        val config = buildXrayConfig(listOf(hop), listOf(buildXrayOutbound(node, "127.0.0.1", 30000, settings)), settings)
        assertTrue("配置要带上 header 的值", config.contains(secret))

        val logged = Util.redactSecrets(config)
        assertFalse(logged, logged.contains(secret))
        for (kept in listOf("X-Api-Token", "cdn.example.net", "/xh", "100-200")) assertTrue(kept, logged.contains(kept))

        val error = Util.redactSecretValues("request failed: X-Api-Token: $secret (cdn.example.net)", listOf(config))
        assertFalse(error, error.contains(secret))
        assertTrue(error, error.contains("cdn.example.net"))
    }

    @Test
    fun `xhttp 的 alpn 只写 h3 时照写`() {
        // 单值 h3：Xray 客户端走 QUIC（UDP），映射入站同时转发 UDP 时可用（XL-35…39）
        val tls = build(bean(vless = true) {
            type = "xhttp"; path = "/xh"; security = "tls"; sni = "sni.example.com"; alpn = "h3"
        }).stream().getAsJsonObject("tlsSettings")
        assertEquals("""["h3"]""", tls.get("alpn").toString())
    }

    @Test
    fun `xhttp 的 streamSettings 里任何一层都没有 downloadSettings、sockopt、dialerProxy`() {
        val extra = """{"headers":{"X-Token":"abc123"},"xPaddingBytes":"100-200","xmux":{"maxConcurrency":"4-8"}}"""
        val text = gson.toJson(
            build(bean(vless = true) {
                type = "xhttp"; path = "/xh"; security = "tls"; sni = "sni.example.com"; xhttpExtra = extra
            }),
        ).lowercase()
        for (key in listOf("downloadsettings", "sockopt", "dialerproxy")) assertFalse(key, text.contains(key))
    }
}

