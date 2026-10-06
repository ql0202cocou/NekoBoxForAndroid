package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
