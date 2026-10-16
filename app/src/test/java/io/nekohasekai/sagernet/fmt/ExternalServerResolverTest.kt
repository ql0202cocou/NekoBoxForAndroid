package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.golden.StrictNumericAddress
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

// 不映射的外核跳用分组 DNS 解析服务器域名：分组 DNS 里选哪一台（externalServerResolverOf）、构建登记给哪些跳实例、
// 运行计划与 hysteria 1 插件配置。预期按代码逻辑手写
class ExternalServerResolverTest {

    private fun dns(type: String, server: String, port: Int? = null) = DNSServerOptions().apply {
        this.type = type
        this.server = server
        server_port = port
    }

    @Test
    fun `只选明文 DNS、IP 字面量、端口 53 的第一台`() {
        val servers = listOf(
            dns("https", "dns.example.org"),
            dns("https", "192.0.2.10"),
            dns("tls", "192.0.2.11"),
            dns("quic", "192.0.2.12"),
            dns("h3", "192.0.2.13"),
            dns("udp", "dns.example.net"),
            dns("udp", "192.0.2.14", 5353),
            dns("tcp", "192.0.2.15", 53),
            dns("udp", "192.0.2.16"),
        )
        assertEquals(ExternalServerResolver("tcp", "192.0.2.15", 53), externalServerResolverOf(servers))
    }

    @Test
    fun `没写端口按 53，IPv6 原样保留不带方括号`() {
        assertEquals(ExternalServerResolver("udp", "192.0.2.1", 53), externalServerResolverOf(listOf(dns("udp", "192.0.2.1"))))
        assertEquals(
            ExternalServerResolver("udp", "2001:db8::53", 53),
            externalServerResolverOf(listOf(dns("udp", "2001:db8::53", 53))),
        )
    }

    @Test
    fun `没有可用的时为 null`() {
        assertNull(externalServerResolverOf(emptyList()))
        assertNull(
            externalServerResolverOf(
                listOf(
                    dns("https", "192.0.2.10"), dns("tls", "192.0.2.11"), dns("quic", "192.0.2.12"),
                    dns("h3", "192.0.2.13"), dns("local", ""),
                )
            )
        )
        // 带前导零的 IPv4：Go 的 net.ParseIP 不认，插件会当成域名
        assertNull(externalServerResolverOf(listOf(dns("udp", "192.0.2.010"))))
        assertNull(externalServerResolverOf(listOf(DNSServerOptions().apply { type = "udp" })))
    }

    @Test
    fun `解析服务器的 network 只能是 udp 或 tcp，端口在范围内`() {
        assertThrows(IllegalArgumentException::class.java) { ExternalServerResolver("https", "192.0.2.1", 53) }
        assertThrows(IllegalArgumentException::class.java) { ExternalServerResolver("udp", "192.0.2.1", 0) }
    }

    // ---- 构建：只有主节点所在分组的域名节点、不映射的跳实例拿到解析服务器 ----

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

    @Test
    fun `免映射的 hysteria 1 插件节点用分组 DNS 里第一台明文 IP 服务器`() {
        val main = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        val expected = ExternalServerResolver("udp", "192.0.2.1", 53)
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val result = build(main, listOf(main), nameservers, mode)
            assertEquals("$mode", mapOf(1L to expected), result.resolvers())
            assertEquals("$mode", "udp://192.0.2.1:53", result.hysteriaConfigs().getValue(1).get("resolver").asString)
        }
    }

    @Test
    fun `导出不带：与内部核心跳、映射入站一样不经分组 DNS`() {
        val main = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        val result = build(main, listOf(main), nameservers, ConfigBuildMode.EXPORT)
        assertEquals(mapOf(1L to null), result.resolvers())
        val (text, _) = exportConfigText(result, "hy1-1") { _, _ -> error("no temp files expected") }
        assertFalse(text.contains("\"resolver\""))
    }

    @Test
    fun `分组没配 DNS、只有外核用不了的地址、节点是 IP 时都不带`() {
        val main = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO)
        assertEquals(mapOf(1L to null), build(main, listOf(main), "").resolvers())
        assertFalse(build(main, listOf(main), "").hysteriaConfigs().getValue(1).has("resolver"))
        val onlyEncrypted = "https://dns.example.org/dns-query\nquic://192.0.2.9\nh3://192.0.2.7/dns-query\ntls://192.0.2.8"
        assertEquals(mapOf(1L to null), build(main, listOf(main), onlyEncrypted).resolvers())
        val ipNode = hysteria(1, HysteriaBean.PROTOCOL_WECHAT_VIDEO, server = "203.0.113.5")
        assertEquals(mapOf(1L to null), build(ipNode, listOf(ipNode), nameservers).resolvers())
    }

    @Test
    fun `链上：最先拨号的本组节点带，别组节点不带，经映射的出口不带`() {
        // 链 10（分组 1）：2 wechat-video（本组、最先拨号、免映射）→ 3 faketcp（分组 2、不能映射）→ 4 wechat-video
        // （本组、出口、经映射）
        val profiles = listOf(
            chain(10, 2, 3, 4),
            hysteria(2, HysteriaBean.PROTOCOL_WECHAT_VIDEO),
            hysteria(3, HysteriaBean.PROTOCOL_FAKETCP, groupId = 2),
            hysteria(4, HysteriaBean.PROTOCOL_WECHAT_VIDEO),
        )
        val result = build(profiles[0], profiles, nameservers)
        assertEquals(
            mapOf(2L to ExternalServerResolver("udp", "192.0.2.1", 53), 3L to null, 4L to null),
            result.resolvers(),
        )
        val targets = result.externalChains.flatMap { it.hops }.associate { it.profileId to it.target }
        assertEquals(ExternalDialTarget.Direct, targets[2])
        assertEquals(ExternalDialTarget.Direct, targets[3])
        assertFalse(targets[4] == ExternalDialTarget.Direct)
        val configs = result.hysteriaConfigs()
        assertEquals("udp://192.0.2.1:53", configs.getValue(2).get("resolver").asString)
        assertFalse(configs.getValue(3).has("resolver"))
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

    @Test
    fun `经映射的跳实例不能带解析服务器`() {
        val resolver = ExternalServerResolver("udp", "192.0.2.1", 53)
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
}
