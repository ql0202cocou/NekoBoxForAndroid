package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.golden.StrictNumericAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// 不映射的外核跳（hysteria 1 插件）经 sing-box 的本机 DNS 入站解析服务器域名：构建分给哪些跳实例、DNS 入站与它的
// 路由 / DNS 规则、运行计划的检查与插件配置。预期按代码逻辑手写
class ExternalServerResolverTest {

    @Test
    fun `解析服务器的 network 只能是 udp 或 tcp，端口在范围内`() {
        assertThrows(IllegalArgumentException::class.java) { ExternalServerResolver("https", "192.0.2.1", 53) }
        assertThrows(IllegalArgumentException::class.java) { ExternalServerResolver("udp", "192.0.2.1", 0) }
    }

    // ---- 构建 ----

    private val nameservers =
        "https://dns.example.org/dns-query\nquic://192.0.2.9\ntls://192.0.2.8\nh3://192.0.2.7/dns-query\n" +
            "udp://dns.example.net\n192.0.2.1\ntcp://192.0.2.2"

    private fun hysteria(id: Long, protocol: Int, groupId: Long = 1, server: String = "hy$id.example.com") =
        ProxyEntity(id = id, groupId = groupId, userOrder = id).putBean(HysteriaBean().apply {
            name = "hy1-$id"
            protocolVersion = 1
            serverAddress = server
            serverPorts = "8443"
            initializeDefaultValues()
            this.protocol = protocol
        })

    private fun chain(id: Long, vararg members: Long, groupId: Long = 1) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    private fun build(
        main: ProxyEntity,
        profiles: List<ProxyEntity>,
        nameserver: String,
        mode: ConfigBuildMode = ConfigBuildMode.RUN,
    ): ConfigBuildResult {
        val groups = listOf(ProxyGroup(id = 1, proxyServerNameserver = nameserver), ProxyGroup(id = 2))
        val source = MemoryConfigDataSource(groups, profiles, emptyList())
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, mode)
        val platform = FakeConfigPlatform(parse = { StrictNumericAddress.parse(it) })
        return buildConfig(ConfigInput(mode, record, testConfigSettings(), snapshot, emptyMap(), platform))
    }

    private fun ConfigBuildResult.resolvers(): Map<Long, ExternalServerResolver?> =
        externalChains.flatMap { it.hops }.associate { it.profileId to it.serverResolver }

    // 运行计划组装出的 hysteria 插件配置，按节点 id
    private fun ConfigBuildResult.hysteriaConfigs(): Map<Long, JsonObject> {
        val plan = ExternalRunPlan.from(this)
        val processes = plan.assemble({ _, _ -> error("no temp files expected") }, null, requireExternalCoreSettings())
        return processes.associate { it.group.hops.single().profileId to JsonParser.parseString(it.config).asJsonObject }
    }

    private fun ConfigBuildResult.json(): JsonObject = JsonParser.parseString(config).asJsonObject

    private fun JsonObject.array(vararg path: String): JsonArray {
        var node: JsonObject = this
        for (key in path.dropLast(1)) node = node.getAsJsonObject(key)
        return node.getAsJsonArray(path.last()) ?: JsonArray()
    }

    private fun JsonObject.dnsInbounds() = array("inbounds").map { it.asJsonObject }
        .filter { it.get("tag")?.asString == TAG_PLUGIN_DNS }

    private fun JsonObject.inboundOf(rule: JsonObject) = rule.getAsJsonArray("inbound")?.map { it.asString }

    @Test
    fun `免映射的 hysteria 1 插件节点经本机 DNS 入站解析，本组域名走分组 DNS 的有序链`() {
        val main = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val result = build(main, listOf(main), nameservers, mode)
            // 端口次序：本机 socks 端口 50001，之后是 DNS 入站 50002
            val expected = ExternalServerResolver("udp", LOCALHOST, 50002)
            assertEquals("$mode", mapOf(1L to expected), result.resolvers())
            assertEquals("$mode", "udp://127.0.0.1:50002", result.hysteriaConfigs().getValue(1).get("resolver").asString)

            val json = result.json()
            val inbound = json.dnsInbounds().single()
            assertEquals("direct", inbound.get("type").asString)
            assertEquals(LOCALHOST, inbound.get("listen").asString)
            assertEquals(50002, inbound.get("listen_port").asInt)
            assertEquals("udp", inbound.get("network").asString)

            // 最前面的路由规则把它交给 DNS
            val first = json.array("route", "rules").first().asJsonObject
            assertEquals(listOf(TAG_PLUGIN_DNS), json.inboundOf(first))
            assertEquals("hijack-dns", first.get("action").asString)

            // 最前面的两条 DNS 规则：本组的域名 → 分组 DNS 的有序链，其余 → dns-direct；策略同 default_domain_resolver
            val rules = json.array("dns", "rules").map { it.asJsonObject }
            val resolver = json.getAsJsonObject("route").getAsJsonObject("default_domain_resolver")
            assertEquals(listOf(TAG_PLUGIN_DNS), json.inboundOf(rules[0]))
            assertEquals(listOf("hy1.example.com"), rules[0].getAsJsonArray("domain").map { it.asString })
            assertEquals("dns-node-1", rules[0].get("server").asString)
            assertEquals(listOf(TAG_PLUGIN_DNS), json.inboundOf(rules[1]))
            assertFalse(rules[1].has("domain"))
            assertEquals("dns-direct", rules[1].get("server").asString)
            for (rule in rules.take(2)) assertEquals("$mode", resolver.get("strategy"), rule.get("strategy"))
            assertTrue(json.array("dns", "servers").any { it.asJsonObject.get("tag").asString == "dns-node-1" })
            // 其它 DNS 规则都不按这个入站匹配
            assertTrue(rules.drop(2).none { json.inboundOf(it)?.contains(TAG_PLUGIN_DNS) == true })
        }
    }

    @Test
    fun `没配分组 DNS 或只有加密 DNS 时同样带，前者只走 dns-direct`() {
        val main = hysteria(1, HysteriaBean.PROTOCOL_FAKETCP)
        val noGroupDns = build(main, listOf(main), "")
        assertEquals(mapOf(1L to ExternalServerResolver("udp", LOCALHOST, 50002)), noGroupDns.resolvers())
        assertEquals("udp://127.0.0.1:50002", noGroupDns.hysteriaConfigs().getValue(1).get("resolver").asString)
        val rules = noGroupDns.json().array("dns", "rules").map { it.asJsonObject }
            .filter { noGroupDns.json().inboundOf(it)?.contains(TAG_PLUGIN_DNS) == true }
        assertEquals(1, rules.size)
        assertEquals("dns-direct", rules.single().get("server").asString)

        // 以前只认明文 IP:53，加密 DNS 现在也由 sing-box 的有序链解析
        val onlyEncrypted = "https://dns.example.org/dns-query\nquic://192.0.2.9\nh3://192.0.2.7/dns-query\ntls://192.0.2.8"
        val encrypted = build(main, listOf(main), onlyEncrypted)
        assertEquals(mapOf(1L to ExternalServerResolver("udp", LOCALHOST, 50002)), encrypted.resolvers())
        assertEquals("dns-node-1", encrypted.json().array("dns", "rules").first().asJsonObject.get("server").asString)
    }

    @Test
    fun `节点是 IP 与导出时都不带，也不开 DNS 入站`() {
        val ipNode = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO, server = "203.0.113.5")
        val ip = build(ipNode, listOf(ipNode), nameservers)
        assertEquals(mapOf(1L to null), ip.resolvers())
        assertTrue(ip.json().dnsInbounds().isEmpty())
        assertFalse(ip.hysteriaConfigs().getValue(1).has("resolver"))

        val main = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        val export = build(main, listOf(main), nameservers, ConfigBuildMode.EXPORT)
        assertEquals(mapOf(1L to null), export.resolvers())
        assertTrue(export.json().dnsInbounds().isEmpty())
        assertFalse(export.config.contains(TAG_PLUGIN_DNS))
        val (text, _) = exportConfigText(export, "hy1-1") { _, _ -> error("no temp files expected") }
        assertFalse(text.contains("\"resolver\""))
    }

    @Test
    fun `链上：不映射的跳共用一个 DNS 入站，只有本组的域名走分组 DNS，经映射的出口不带`() {
        // 链 10（分组 1）：2 wechat-video（本组、最先拨号、免映射）→ 3 faketcp（分组 2、不能映射）→ 4 wechat-video
        // （本组、出口、经映射）
        val profiles = listOf(
            chain(10, 2, 3, 4),
            hysteria(2, HysteriaBean.PROTOCOL_WECHAT_VIDEO),
            hysteria(3, HysteriaBean.PROTOCOL_FAKETCP, groupId = 2),
            hysteria(4, HysteriaBean.PROTOCOL_WECHAT_VIDEO),
        )
        val result = build(profiles[0], profiles, nameservers)
        val resolvers = result.resolvers()
        assertNull(resolvers[4])
        val shared = resolvers.getValue(2)!!
        assertEquals(shared, resolvers[3])
        assertEquals(LOCALHOST, shared.address)
        val targets = result.externalChains.flatMap { it.hops }.associate { it.profileId to it.target }
        assertEquals(ExternalDialTarget.Direct, targets[2])
        assertEquals(ExternalDialTarget.Direct, targets[3])
        assertFalse(targets[4] == ExternalDialTarget.Direct)

        val json = result.json()
        assertEquals(shared.port, json.dnsInbounds().single().get("listen_port").asInt)
        val groupRule = json.array("dns", "rules").first().asJsonObject
        assertEquals(listOf("hy2.example.com"), groupRule.getAsJsonArray("domain").map { it.asString })

        val configs = result.hysteriaConfigs()
        assertEquals("udp://127.0.0.1:${shared.port}", configs.getValue(2).get("resolver").asString)
        assertEquals("udp://127.0.0.1:${shared.port}", configs.getValue(3).get("resolver").asString)
        assertFalse(configs.getValue(4).has("resolver"))
    }

    // ---- 运行计划 ----

    private fun hysteriaBean() = HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = "hy.example.com"
        serverPorts = "8443"
        initializeDefaultValues()
        protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
    }

    private val resolver = ExternalServerResolver("udp", LOCALHOST, 40003)

    @Test
    fun `经映射的跳实例不能带解析服务器`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(0, 0, 1, hysteriaBean(), 40001, ExternalDialTarget.Mapped(40002), serverResolver = resolver)))
        }
        assertEquals(
            "external hop 0 (hysteria-plugin) dials through a mapping inbound but carries a server resolver",
            error.message,
        )
        // 不映射的可以
        ExternalRunPlan(listOf(ExternalHop(0, 0, 1, hysteriaBean(), 40001, ExternalDialTarget.Direct, serverResolver = resolver)))
    }

    @Test
    fun `核心不接受解析服务器的跳实例不能带`() {
        val trojanGo = TrojanGoBean().apply {
            serverAddress = "t.example.org"
            serverPort = 4443
            password = "fake-password"
            initializeDefaultValues()
        }
        val error = assertThrows(IllegalStateException::class.java) {
            ExternalRunPlan(listOf(ExternalHop(0, 0, 1, trojanGo, 40001, ExternalDialTarget.Direct, serverResolver = resolver)))
        }
        assertEquals("external hop 0 (trojan-go-plugin) does not take a server resolver", error.message)
    }
}
