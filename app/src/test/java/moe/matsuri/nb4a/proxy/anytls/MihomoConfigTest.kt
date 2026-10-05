package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.assemble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.yaml.snakeyaml.Yaml

// 固定设置与端口跑 mihomo 生成器（一组只有一个跳实例），把 YAML 解析回来断言关键结构，不比整段文本。
// 多个跳实例的合并配置见 ExternalRunPlanTest
class MihomoConfigTest {

    private val settings = ExternalCoreSettings(logLevel = 1, ipv6Mode = IPv6Mode.DISABLE, globalAllowInsecure = false)

    private fun bean(block: AnyTLSBean.() -> Unit = {}) = AnyTLSBean().apply {
        serverAddress = "server.example.test"
        serverPort = 8443
        password = "fake-password"
        initializeDefaultValues()
        block()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?> = Yaml().load<Any?>(text) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

    private fun Map<String, Any?>.proxy() = list("proxies").single()

    // 映射目标取 bean 的 finalAddress / finalPort，与 ExternalRunPlan.from 一样
    private fun hop(bean: AnyTLSBean) = ExternalHop(0, 0, 1L, bean, 20001, bean.finalAddress, bean.finalPort)

    private fun config(
        bean: AnyTLSBean,
        settings: ExternalCoreSettings = this.settings,
        controllerPort: Int? = null,
        controllerSecret: String = "",
    ) = buildMihomoConfig(
        listOf(hop(bean)),
        listOf(buildMihomoProxy(bean, bean.finalAddress, bean.finalPort, settings)),
        settings,
        controllerPort,
        controllerSecret,
    )

    private fun build(
        bean: AnyTLSBean = bean(),
        settings: ExternalCoreSettings = this.settings,
        controllerPort: Int? = null,
        controllerSecret: String = "",
    ) = parse(config(bean, settings, controllerPort, controllerSecret))

    @Test
    fun `基本结构：本机 socks 入口绑定唯一的 anytls 代理，其余流量拒绝`() {
        val config = build()
        assertEquals("rule", config["mode"])
        assertFalse(config.containsKey("external-controller"))
        assertFalse(config.containsKey("secret"))

        val listener = config.list("listeners").single()
        assertEquals("in-0", listener["name"])
        assertEquals("socks", listener["type"])
        assertEquals(LOCALHOST, listener["listen"])
        assertEquals(20001, listener["port"])
        assertEquals(true, listener["udp"])
        assertEquals("out-0", listener["proxy"])

        val proxy = config.proxy()
        assertEquals("out-0", proxy["name"])
        assertEquals("anytls", proxy["type"])
        assertEquals("server.example.test", proxy["server"])
        assertEquals(8443, proxy["port"])
        assertEquals("fake-password", proxy["password"])
        assertEquals(true, proxy["udp"])
        assertFalse(proxy.containsKey("skip-cert-verify"))
        assertFalse(proxy.containsKey("fingerprint"))
        assertFalse(proxy.containsKey("ech-opts"))

        assertEquals(listOf("MATCH,REJECT"), config["rules"])
    }

    @Test
    fun `日志档位按设置映射`() {
        fun level(logLevel: Int) = build(settings = settings.copy(logLevel = logLevel))["log-level"]
        assertEquals("warning", level(0))
        assertEquals("warning", level(1))
        assertEquals("info", level(2))
        assertEquals("debug", level(3))
        assertEquals("debug", level(4))
    }

    @Test
    fun `自测时带 Clash API 端口与 secret`() {
        val config = build(controllerPort = 29090, controllerSecret = "fake-secret")
        assertEquals("$LOCALHOST:29090", config["external-controller"])
        assertEquals("fake-secret", config["secret"])
    }

    @Test
    fun `经映射时拨本机映射端口，SNI 兜底为原服务器地址`() {
        val proxy = build(bean {
            finalAddress = LOCALHOST
            finalPort = 30001
        }).proxy()
        assertEquals(LOCALHOST, proxy["server"])
        assertEquals(30001, proxy["port"])
        assertEquals("server.example.test", proxy["sni"])
    }

    @Test
    fun `显式 SNI 与 ALPN 原样带上`() {
        val proxy = build(bean {
            sni = "sni.example.test"
            alpn = "h2,http/1.1"
        }).proxy()
        assertEquals("sni.example.test", proxy["sni"])
        assertEquals(listOf("h2", "http/1.1"), proxy["alpn"])
    }

    @Test
    fun `全局允许不安全叠加在节点开关之上`() {
        assertFalse(build(bean { allowInsecure = false }).proxy().containsKey("skip-cert-verify"))
        assertEquals(true, build(bean { allowInsecure = true }).proxy()["skip-cert-verify"])
        assertEquals(
            true,
            build(bean { allowInsecure = false }, settings.copy(globalAllowInsecure = true)).proxy()["skip-cert-verify"]
        )
    }

    @Test
    fun `证书指纹优先于允许不安全`() {
        val pin = "AA:".repeat(31) + "AA"
        val proxy = build(bean {
            certificateFingerprint = pin
            allowInsecure = true
        }, settings.copy(globalAllowInsecure = true)).proxy()
        assertEquals(pin, proxy["fingerprint"])
        assertFalse(proxy.containsKey("skip-cert-verify"))
    }

    @Test
    fun `自定义证书换成证书 SHA-256 固定`() {
        val proxy = build(bean { certificates = TEST_CERT_PEM }).proxy()
        assertEquals(TEST_CERT_SHA256, proxy["fingerprint"])
    }

    @Test
    fun `非法指纹与证书直接报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(bean { certificateFingerprint = "abc" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(bean { certificates = "not a certificate" })
        }
    }

    @Test
    fun `ECH 配置转成裸 base64，仅启用时只写 enable`() {
        val withConfig = build(bean {
            echConfig = "-----BEGIN ECH CONFIGS-----\nAEX+DQBB\nAAA=\n-----END ECH CONFIGS-----"
        }).proxy()
        assertEquals(mapOf("enable" to true, "config" to "AEX+DQBBAAA="), withConfig["ech-opts"])

        val enableOnly = build(bean { enableECH = true }).proxy()
        assertEquals(mapOf("enable" to true), enableOnly["ech-opts"])
    }

    @Test
    fun `uTLS 指纹写成 client-fingerprint`() {
        assertEquals("chrome", build(bean { utlsFingerprint = "chrome" }).proxy()["client-fingerprint"])
    }

    @Test
    fun `组装入口显式传入的设置与 Clash API 参数传到生成器`() {
        val hop = hop(bean())
        assertEquals("mihomo-plugin", hop.pluginId)
        val processes = ExternalRunPlan(listOf(hop)).assemble(
            { _, _ -> error("mihomo 不应申请临时文件") },
            29090 to "fake-secret",
            settings.copy(logLevel = 2, globalAllowInsecure = true),
        )
        val config = parse(processes.single().config)
        assertEquals("info", config["log-level"])
        assertEquals("$LOCALHOST:29090", config["external-controller"])
        assertEquals("fake-secret", config["secret"])
        assertEquals(true, config.proxy()["skip-cert-verify"])
    }

    private companion object {
        // 测试专用的自签名证书（CN=example.test），与任何真实服务无关
        const val TEST_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBIDCBxgIJAJN61FZ88jf9MAoGCCqGSM49BAMCMBcxFTATBgNVBAMMDGV4YW1w
bGUudGVzdDAgFw0yNjEwMDQxMzI1NDBaGA8yMTI2MDkxMDEzMjU0MFowFzEVMBMG
A1UEAwwMZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOkYJ
RyTnlMbz7eUkqTCRBVKXRcBt1tyHO261/UWI8dwMo6P+RZxFYvz4iL5QymOxF537
xNos8a107kkykAG7FzAKBggqhkjOPQQDAgNJADBGAiEAg3Hym8QEDNZIF+XNzmJd
JGzrIzgJAmRTTBwERADJubgCIQCWeay/GFH0CCj4EgZVg34bPEy1QMSow3efql0e
vmMamA==
-----END CERTIFICATE-----"""

        // openssl x509 -noout -fingerprint -sha256 的结果，去冒号、转小写
        const val TEST_CERT_SHA256 = "dd45a0d4b292465ca8b07066b4d029100569344a5393fdd1722c2db9054591e7"
    }
}
