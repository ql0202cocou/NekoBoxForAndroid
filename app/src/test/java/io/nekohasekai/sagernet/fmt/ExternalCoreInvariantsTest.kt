package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Random

// 外核「哑管道」不变量（ExternalCoreInvariants）的单测：输入是手工构造的计划，不经过 ConfigBuild。
// 两部分：生成器现在的输出全部通过检查；检查本身有牙，每种违例都真的被抓到（反例）。基线里全部合并配置的检查在
// golden/GoldenExternalInvariantsTest
class ExternalCoreInvariantsTest {

    private val settings = ExternalCoreSettings(logLevel = 1, ipv6Mode = 0, globalAllowInsecure = false)
    private val auth = LocalSocksAuth.generate(Random(7))
    private val realityKey = "4D3ynC7iq5De6gng9hCVIfGAYuVTmjB52__M2pgyCnc"

    private fun vmess(block: VMessBean.() -> Unit) = VMessBean().apply {
        name = "vm"
        serverAddress = "vmess.example.com"
        serverPort = 8443
        uuid = "00000000-0000-0000-0000-0000000000ff"
        alterId = 0
        block()
        initializeDefaultValues()
    }

    private fun vless(block: VMessBean.() -> Unit) = VMessBean().apply {
        name = "vl"
        serverAddress = "vless.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = -1
        block()
        initializeDefaultValues()
    }

    private fun anytls(block: AnyTLSBean.() -> Unit = {}) = AnyTLSBean().apply {
        name = "at"
        serverAddress = "anytls.example.com"
        serverPort = 8443
        password = "fake-password"
        block()
        initializeDefaultValues()
    }

    // 每个节点一个跳实例：本机端口 21000 + 序号、映射端口 31000 + 序号；偶数序号不映射（外核直接拨服务器）
    private fun plan(vararg beans: AbstractBean) = ExternalRunPlan(beans.mapIndexed { i, bean ->
        val target = if (i % 2 == 0) ExternalDialTarget.Mapped(31000 + i) else ExternalDialTarget.Direct
        ExternalHop(i, i, 100L + i, bean, 21000 + i, target, localAuth = auth)
    })

    private fun noCacheFile(prefix: String, ext: String): File = error("不应申请临时文件：$prefix.$ext")

    private fun assemble(plan: ExternalRunPlan, controller: Pair<Int, String>? = null) =
        plan.assemble(::noCacheFile, controller, settings).associate { it.group.pluginId to it.config }

    private fun noViolations(label: String, report: InvariantReport) =
        assertTrue("$label 违反不变量：\n${report.violations.joinToString("\n")}", report.violations.isEmpty())

    // ---- 生成器现在的输出

    private val xrayBeans: List<VMessBean> = listOf(
        vless { },
        vless { security = "tls"; sni = "node.example.com"; alpn = "h2,http/1.1"; utlsFingerprint = "chrome" },
        vless { security = "tls"; realityPubKey = realityKey; realityShortId = "0123abcd"; sni = "www.example.org"; utlsFingerprint = "chrome"; encryption = "xtls-rprx-vision" },
        vless { security = "tls"; type = "grpc"; path = "svc"; enableMux = true; muxConcurrency = 4 },
        vless { security = "tls"; type = "httpupgrade"; host = "cdn.example.net"; path = "/up"; packetEncoding = 2 },
        vless { type = "http"; host = "a.example.com,b.example.com"; path = "/h" },
        vmess { type = "ws"; host = "cdn.example.com"; path = "/ws"; security = "tls"; enableMux = true },
        vmess { security = "tls"; certificateFingerprint = "d2fd6f6b04f88cb7cf03ce84064d413aeefc44b6dad87d0900cb90aeca364325" },
        vmess { security = "tls"; certificates = "-----BEGIN CERTIFICATE-----\nZmFrZQ==\n-----END CERTIFICATE-----" },
        vless { security = "tls"; enableECH = true; echConfig = "ZmFrZS1lY2gtY29uZmln" },
        // 开了 ECH 但没有内联配置：Xray 生成器什么都不写（不自己查 DNS）
        vless { security = "tls"; enableECH = true },
    )

    private val mihomoBeans: List<AnyTLSBean> = listOf(
        anytls { },
        anytls { sni = "node.example.com"; alpn = "h2"; utlsFingerprint = "chrome" },
        anytls { allowInsecure = true },
        anytls { certificateFingerprint = "d2fd6f6b04f88cb7cf03ce84064d413aeefc44b6dad87d0900cb90aeca364325" },
        anytls { enableECH = true; echConfig = "ZmFrZS1lY2gtY29uZmln" },
    )

    @Test
    fun `Xray 生成器的输出全部满足不变量`() {
        for ((i, bean) in xrayBeans.withIndex()) {
            val config = assemble(plan(bean)).getValue("xray-plugin")
            noViolations("Xray 节点 #$i", ExternalCoreInvariants.xray(config))
        }
        // 全部放进同一份合并配置
        val merged = assemble(plan(*xrayBeans.toTypedArray())).getValue("xray-plugin")
        val report = ExternalCoreInvariants.xray(merged)
        noViolations("Xray 合并配置", report)
        assertTrue("没有例外", report.exceptions.isEmpty())
    }

    @Test
    fun `mihomo 生成器的输出全部满足不变量，测速控制器单独断言`() {
        for ((i, bean) in mihomoBeans.withIndex()) {
            val config = assemble(plan(bean)).getValue("mihomo-plugin")
            noViolations("mihomo 节点 #$i", ExternalCoreInvariants.mihomo(config, ControllerExpectation.Absent))
        }
        val all = plan(*mihomoBeans.toTypedArray())
        noViolations("mihomo 合并配置", ExternalCoreInvariants.mihomo(assemble(all).getValue("mihomo-plugin"), ControllerExpectation.Absent))
        // 测速：控制器只在这时出现
        val withController = assemble(all, 29090 to "fake-secret").getValue("mihomo-plugin")
        noViolations("mihomo 测速配置", ExternalCoreInvariants.mihomo(withController, ControllerExpectation.Present(29090, "fake-secret")))
        assertTrue("运行 / 导出的配置里不该有控制器", ExternalCoreInvariants.mihomo(withController, ControllerExpectation.Absent).violations.isNotEmpty())
    }

    @Test
    fun `mihomo 只开 ECH 没有内联配置时命中 ECH 的 DNS 例外`() {
        val config = assemble(plan(anytls { enableECH = true })).getValue("mihomo-plugin")
        val report = ExternalCoreInvariants.mihomo(config, ControllerExpectation.Absent)
        noViolations("mihomo ECH enable-only", report)
        assertEquals(listOf(ExternalCoreInvariants.ECH_DNS_EXCEPTION), report.exceptions)
        // 带内联配置的不算例外
        val inline = assemble(plan(anytls { enableECH = true; echConfig = "ZmFrZQ==" })).getValue("mihomo-plugin")
        assertTrue(ExternalCoreInvariants.mihomo(inline, ControllerExpectation.Absent).exceptions.isEmpty())
    }

    // Xray 的 Trojan 出站还没有生成器（K1 接入）：用与 buildXrayOutbound 同形的手写出站走 buildXrayConfig，
    // 保证白名单已经容纳 settings.servers 的写法，也保证接入后的输出会被同一套检查约束
    private fun trojanOutbound(
        network: String = "tcp",
        port: Int = 31000,
        extra: Map<String, Any?> = emptyMap(),
        server: Map<String, Any?> = linkedMapOf("address" to "127.0.0.1", "port" to port, "password" to "example-pass-1"),
    ): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("protocol", "trojan")
        put("settings", linkedMapOf("servers" to listOf(server)))
        put("streamSettings", LinkedHashMap<String, Any?>().apply {
            put("network", network)
            when (network) {
                "ws" -> put("wsSettings", linkedMapOf("path" to "/ws?ed=2048", "host" to "cdn.example.net"))
                "grpc" -> put("grpcSettings", linkedMapOf("serviceName" to "svc"))
            }
            put("security", "tls")
            put("tlsSettings", linkedMapOf("serverName" to "node.example.com", "alpn" to listOf("http/1.1"), "fingerprint" to "chrome"))
        })
        putAll(extra)
    }

    private fun trojanConfig(vararg outbounds: Map<String, Any?>): String {
        val hops = outbounds.indices.map { i ->
            ExternalHop(i, i, 100L + i, vless { }, 21000 + i, ExternalDialTarget.Mapped(31000 + i), localAuth = auth)
        }
        return buildXrayConfig(hops, outbounds.toList(), settings)
    }

    @Test
    fun `Xray 的 Trojan 出站（servers 写法）满足不变量`() {
        val config = trojanConfig(
            trojanOutbound(),
            trojanOutbound(network = "ws", port = 31001),
            trojanOutbound(network = "grpc", port = 31002, extra = mapOf("mux" to linkedMapOf("enabled" to true, "concurrency" to 8))),
        )
        noViolations("Xray Trojan", ExternalCoreInvariants.xray(config))
        // 与 vmess / vless 混在一份配置里
        val mixed = trojanConfig(trojanOutbound(), vlessOutbound())
        noViolations("Xray 混合", ExternalCoreInvariants.xray(mixed))
    }

    private fun vlessOutbound(): Map<String, Any?> =
        buildXrayOutbound(vless { }, LOCALHOST, 31001, settings)

    @Test
    fun `白名单表列出的协议与类型`() {
        assertEquals(setOf("vmess", "vless", "trojan"), ExternalCoreInvariants.XRAY_OUTBOUND_PROTOCOLS.keys)
        assertEquals(setOf("anytls"), ExternalCoreInvariants.MIHOMO_PROXIES.keys)
        assertEquals(setOf("socks"), ExternalCoreInvariants.MIHOMO_LISTENER_TYPES)
        // Trojan 的 settings 是 servers，三个键，没有 flow
        val trojan = ExternalCoreInvariants.XRAY_OUTBOUND_PROTOCOLS.getValue("trojan")
        assertEquals("servers", trojan.listKey)
        assertEquals(setOf("address", "port", "password"), trojan.entry.required)
        assertTrue(trojan.entry.optional.isEmpty())
    }

    // ---- 反例：检查要抓得到

    private fun xrayBase(): JsonObject = JsonParser.parseString(
        trojanConfig(trojanOutbound(), vlessOutbound()),
    ).asJsonObject

    private fun JsonObject.sub(vararg path: String): JsonObject =
        path.fold(this) { o, key -> o.get(key).let { if (it.isJsonArray) it.asJsonArray[0].asJsonObject else it.asJsonObject } }

    private fun JsonObject.outbound(i: Int) = getAsJsonArray("outbounds")[i].asJsonObject

    private fun expectXray(name: String, fragment: String, mutate: JsonObject.() -> Unit) {
        val config = xrayBase().apply(mutate)
        val violations = ExternalCoreInvariants.xray(gson.toJson(config)).violations
        assertTrue("$name：应报出「$fragment」，实际：$violations", violations.any { fragment in it })
    }

    @Test
    fun `Xray 反例：每种违例都被抓到`() {
        noViolations("反例的基础配置", ExternalCoreInvariants.xray(gson.toJson(xrayBase())))
        expectXray("顶层 dns", "禁用的键 dns") { add("dns", JsonObject()) }
        expectXray("顶层 policy", "禁用的键 policy") { add("policy", JsonObject()) }
        expectXray("顶层 api", "禁用的键 api") { add("api", JsonObject()) }
        expectXray("顶层未知键", "表外的键 version") { addProperty("version", "1") }
        expectXray("日志写文件", "log.access") { sub("log").addProperty("access", "/tmp/access.log") }
        expectXray("入站嗅探", "禁用的键 sniffing") { sub("inbounds").add("sniffing", JsonObject()) }
        expectXray("入站监听全部地址", "listen") { sub("inbounds").addProperty("listen", "0.0.0.0") }
        expectXray("入站不是 socks", "protocol") { sub("inbounds").addProperty("protocol", "http") }
        expectXray("入站不认证", "settings.auth") { sub("inbounds", "settings").addProperty("auth", "noauth") }
        expectXray("入站 settings 多出 ip", "表外的键 ip") { sub("inbounds", "settings").addProperty("ip", "127.0.0.1") }
        expectXray("blackhole 不在第一项", "outbounds[0].protocol") { outbound(0).addProperty("protocol", "freedom") }
        expectXray("出站协议 freedom", "只许") { outbound(1).addProperty("protocol", "freedom") }
        expectXray("出站协议 shadowsocks", "只许") { outbound(1).addProperty("protocol", "shadowsocks") }
        expectXray("出站 sendThrough", "禁用的键 sendThrough") { outbound(1).addProperty("sendThrough", "0.0.0.0") }
        expectXray("出站 proxySettings", "禁用的键 proxySettings") { outbound(1).add("proxySettings", JsonObject()) }
        expectXray("streamSettings sockopt", "禁用的键 sockopt") { outbound(1).sub("streamSettings").add("sockopt", JsonObject()) }
        expectXray("streamSettings dialerProxy", "禁用的键 dialerProxy") { outbound(1).sub("streamSettings").addProperty("dialerProxy", "x") }
        expectXray("streamSettings 未知网络", "network") { outbound(1).sub("streamSettings").addProperty("network", "quic") }
        expectXray("tls 里的未知键", "表外的键 echForceQuery") { outbound(1).sub("streamSettings", "tlsSettings").addProperty("echForceQuery", "full") }
        expectXray("tls 里的 dns 键", "表外的键 dns") { outbound(1).sub("streamSettings", "tlsSettings").add("dns", JsonObject()) }
        expectXray("tls 写 allowInsecure", "禁用的键 allowInsecure") { outbound(1).sub("streamSettings", "tlsSettings").addProperty("allowInsecure", true) }
        expectXray("security 与设置对不上", "realitySettings") { outbound(1).sub("streamSettings").add("realitySettings", JsonObject()) }
        expectXray("mux 未知键", "表外的键 protocol") { outbound(1).add("mux", JsonObject().apply { addProperty("enabled", true); addProperty("concurrency", 8); addProperty("protocol", "x") }) }
        expectXray("Trojan 写 flow", "表外的键 flow") { outbound(1).sub("settings", "servers").addProperty("flow", "xtls-rprx-vision") }
        expectXray("Trojan 两个 server", "应恰好一项") {
            val servers = outbound(1).getAsJsonObject("settings").getAsJsonArray("servers")
            servers.add(servers[0].deepCopy())
        }
        expectXray("Trojan 没有密码", "缺少键 password") { outbound(1).sub("settings", "servers").remove("password") }
        expectXray("VLESS 的 encryption 不是 none", "应为 none") { outbound(2).sub("settings", "vnext", "users").addProperty("encryption", "aes") }
        expectXray("routing 写分流条件", "表外的键 domain") { sub("routing", "rules").add("domain", JsonObject()) }
        expectXray("routing 写 balancers", "禁用的键 balancers") { sub("routing").add("balancers", JsonObject()) }
        expectXray("规则指向 blackhole", "不能指向 blackhole") { sub("routing", "rules").addProperty("outboundTag", "block") }
        expectXray("入站没有规则", "不是一一对应") { sub("routing").add("rules", JsonArray()) }
    }

    private fun mihomoBase(): MutableMap<String, Any?> {
        val config = assemble(plan(anytls { }, anytls { })).getValue("mihomo-plugin")
        @Suppress("UNCHECKED_CAST")
        return Yaml().load<Any?>(config) as MutableMap<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.first(key: String) = (this[key] as List<MutableMap<String, Any?>>)[0]

    private fun expectMihomo(
        name: String,
        fragment: String,
        controller: ControllerExpectation = ControllerExpectation.Absent,
        mutate: MutableMap<String, Any?>.() -> Unit,
    ) {
        val config = mihomoBase().apply(mutate)
        val violations = ExternalCoreInvariants.mihomo(Yaml().dump(config), controller).violations
        assertTrue("$name：应报出「$fragment」，实际：$violations", violations.any { fragment in it })
    }

    @Test
    fun `mihomo 反例：每种违例都被抓到`() {
        noViolations("反例的基础配置", ExternalCoreInvariants.mihomo(Yaml().dump(mihomoBase()), ControllerExpectation.Absent))
        expectMihomo("dns", "禁用的键 dns") { put("dns", mapOf("enable" to true)) }
        expectMihomo("tun", "禁用的键 tun") { put("tun", mapOf("enable" to true)) }
        expectMihomo("sniffer", "禁用的键 sniffer") { put("sniffer", mapOf("enable" to true)) }
        expectMihomo("hosts", "禁用的键 hosts") { put("hosts", mapOf("a.example.com" to "192.0.2.1")) }
        expectMihomo("proxy-groups", "禁用的键 proxy-groups") { put("proxy-groups", emptyList<Any>()) }
        expectMihomo("rule-providers", "禁用的键 rule-providers") { put("rule-providers", emptyMap<String, Any>()) }
        expectMihomo("mixed-port", "禁用的键 mixed-port") { put("mixed-port", 7890) }
        expectMihomo("allow-lan", "禁用的键 allow-lan") { put("allow-lan", true) }
        expectMihomo("全局认证", "禁用的键 authentication") { put("authentication", listOf("u:p")) }
        expectMihomo("interface-name", "禁用的键 interface-name") { put("interface-name", "en0") }
        expectMihomo("未知顶层键", "表外的键 ipv6") { put("ipv6", true) }
        expectMihomo("模式不是 rule", "mode") { put("mode", "global") }
        expectMihomo("规则直接绑定代理", "rules") { put("rules", listOf("MATCH,out-0")) }
        expectMihomo("规则多一条", "rules") { put("rules", listOf("DOMAIN,example.com,out-0", "MATCH,REJECT")) }
        expectMihomo("规则为空", "rules") { put("rules", emptyList<String>()) }
        expectMihomo("运行时出现控制器", "不是测速") {
            put("external-controller", "127.0.0.1:9090")
            put("secret", "s")
        }
        expectMihomo("控制器监听全部地址", "127.0.0.1", ControllerExpectation.Either) {
            put("external-controller", "0.0.0.0:9090")
            put("secret", "s")
        }
        expectMihomo("控制器缺 secret", "成对", ControllerExpectation.Either) { put("external-controller", "127.0.0.1:9090") }
        expectMihomo("测速却没有控制器", "external-controller", ControllerExpectation.Present(9090, "s")) { }
        expectMihomo("listener 是 tun", "只许") { first("listeners")["type"] = "tun" }
        expectMihomo("listener 挂规则", "禁用的键 rule") { first("listeners")["rule"] = "sub" }
        expectMihomo("listener 监听全部地址", "listen") { first("listeners")["listen"] = "0.0.0.0" }
        expectMihomo("listener 不认证", "应恰好一项") { first("listeners")["users"] = emptyList<Any>() }
        expectMihomo("listener 指向不存在的代理", "指向不存在的代理") { first("listeners")["proxy"] = "nobody" }
        expectMihomo("代理 dialer-proxy", "禁用的键 dialer-proxy") { first("proxies")["dialer-proxy"] = "out-1" }
        expectMihomo("代理 interface-name", "禁用的键 interface-name") { first("proxies")["interface-name"] = "en0" }
        expectMihomo("代理 routing-mark", "禁用的键 routing-mark") { first("proxies")["routing-mark"] = 1 }
        expectMihomo("代理 ip-version", "禁用的键 ip-version") { first("proxies")["ip-version"] = "ipv4" }
        expectMihomo("代理类型不在表里", "只许") { first("proxies")["type"] = "vless" }
        // Xray 的键集不能套到 mihomo 上：mihomo 的 listener 写 Xray 式的 tag / settings 要被拒绝
        expectMihomo("套用 Xray 键", "表外的键 tag") { first("listeners")["tag"] = "in-0" }
    }
}
