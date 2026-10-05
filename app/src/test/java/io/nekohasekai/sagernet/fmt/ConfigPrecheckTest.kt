package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 选择器成员 / 路由规则目标的预检（ConfigBuild.precheck）的三条不变式，用 FakeConfigPlatform 的记录核对：
// 试生成外核配置时建的临时文件构建结束时都已删除；预检不分配端口；预检用的是构建自己的外核设置
class ConfigPrecheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun socks(id: Long, groupId: Long) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    // hysteria 1 走插件核心（微信视频伪装不能用 sing-box），带 CA：试生成配置时要写 CA 临时文件
    private fun hysteria(id: Long, groupId: Long) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(HysteriaBean().apply {
            name = "hy1-$id"
            protocolVersion = 1
            serverAddress = "hy$id.example.com"
            serverPorts = "8443"
            initializeDefaultValues()
            protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
            caText = "-----BEGIN CERTIFICATE-----\nfake-$id\n-----END CERTIFICATE-----"
        })

    // VLESS + TLS，没有证书指纹；core 为 null 时自动选核
    private fun vless(id: Long, groupId: Long, core: Int? = null) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .apply { if (core != null) this.core = core }
        .putBean(VMessBean().apply {
            name = "vless-$id"
            serverAddress = "x$id.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-0000000000%02x".format(id)
            alterId = -1
            initializeDefaultValues()
            security = "tls"
            sni = "x$id.example.com"
        })

    private fun rule(id: Long, outbound: Long) =
        RuleEntity(id = id, name = "rule-$id", userOrder = id, enabled = true, domains = "r$id.example.com", outbound = outbound)

    private fun build(
        main: ProxyEntity,
        source: MemoryConfigDataSource,
        platform: FakeConfigPlatform,
        settings: ConfigSettings = testConfigSettings(),
        diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
    ): ConfigBuildResult {
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.RUN)
        return buildConfig(ConfigInput(ConfigBuildMode.RUN, record, settings, snapshot, emptyMap(), platform), diagnostics)
    }

    // 选择器分组 1：主节点 1（sing-box）、hysteria 1 成员 2、Xray 成员 3；分组 2 里的 hysteria 1 节点 7、
    // Xray 节点 8 是路由规则目标。主节点以外的四个都要预检
    private val main = socks(1, 1)
    private fun source() = MemoryConfigDataSource(
        groups = listOf(ProxyGroup(id = 1, isSelector = true), ProxyGroup(id = 2)),
        profiles = listOf(main, hysteria(2, 1), vless(3, 1), hysteria(7, 2), vless(8, 2)),
        rules = listOf(rule(1, outbound = 7), rule(2, outbound = 8)),
    )

    @Test
    fun `预检试生成外核配置时建的临时文件构建结束时都已删除`() {
        val platform = FakeConfigPlatform(tempDir = tmp.newFolder())
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, source(), platform, diagnostics = diagnostics)
        assertTrue("四个都通过预检：$diagnostics", diagnostics.isEmpty())
        assertEquals(setOf(1L, 2, 3, 7, 8), result.profileTagMap.keys)
        // 构建本身不写 CA（那是组装时的事），只有两个 hysteria 1 节点（成员 2、规则目标 7）的预检各建一个
        assertEquals(2, platform.tempFiles.size)
        val left = platform.tempFiles.filter { it.exists() }
        assertTrue("预检留下了临时文件：$left", left.isEmpty())
    }

    @Test
    fun `预检不分配端口`() {
        val platform = FakeConfigPlatform(tempDir = tmp.newFolder())
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, source(), platform, diagnostics = diagnostics)
        assertTrue("四个都通过预检：$diagnostics", diagnostics.isEmpty())
        // 实际构建的分配：每个外核跳实例一个 socks 端口；Xray 节点经映射，再加一个映射端口；hysteria 1 在
        // 没有外部插件 app 时免映射。成员 2、3 与规则目标 7、8 各成一条链：1 + 2 + 1 + 2 = 6
        assertEquals(6, platform.ports.size)
        // 分到的每个端口都用在了配置里：本机 socks 出站的 server_port 与映射入站的 listen_port
        val config = JsonParser.parseString(result.config).asJsonObject
        val socksPorts = config.getAsJsonArray("outbounds").map { it.asJsonObject }
            .filter { it.string("type") == "socks" && it.string("server") == LOCALHOST }
            .map { it["server_port"].asInt }
        val mappingPorts = config.getAsJsonArray("inbounds").map { it.asJsonObject }
            .filter { it.string("tag")?.contains("-mapping-") == true }
            .map { it["listen_port"].asInt }
        assertEquals(4, socksPorts.size)
        assertEquals(2, mappingPorts.size)
        assertEquals(platform.ports.sorted(), (socksPorts + mappingPorts).sorted())
        assertEquals(platform.ports.sorted(), ExternalRunPlan.from(result).hops.map { it.localPort }.plus(mappingPorts).sorted())
    }

    @Test
    fun `预检用构建自己的外核设置，组装会拒绝的成员在预检里跳过`() {
        // 全局「允许不安全」打开：成员 2 强制走 Xray、开了 TLS、没有证书指纹，Xray 生成配置时拒绝 allowInsecure。
        // 自动选核的节点会因此改走 sing-box，强制的不会，只能在预检里按构建的设置试生成时发现
        val source = MemoryConfigDataSource(
            groups = listOf(ProxyGroup(id = 1, isSelector = true)),
            profiles = listOf(main, vless(2, 1, core = ProxyEntity.CORE_XRAY)),
            rules = emptyList(),
        )
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val settings = testConfigSettings(globalAllowInsecure = true)
        val result = build(main, source, platform, settings, diagnostics)

        // buildXrayConfig 的拒绝经组装入口包上节点名（ProfileBuildException），预检原样记下
        val reason = "vless-2: xray-core no longer supports allowInsecure, " +
            "use a certificate fingerprint or the sing-box core for this profile"
        assertEquals(listOf(ConfigBuildDiagnostic.ProfileSkipped(2, "vless-2", reason, true)), diagnostics)
        assertEquals(listOf("profile 2 skipped: ${ProfileBuildException::class.java.name}: $reason"), platform.warnings)
        // 选择器里只剩主节点，没有外核跳实例，组装不会再失败
        assertEquals(setOf(1L), result.profileTagMap.keys)
        val selector = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it.string("type") == "selector" }
        assertEquals(listOf(result.profileTagMap.getValue(1)), selector.getAsJsonArray("outbounds").map { it.asString })
        assertTrue(ExternalRunPlan.from(result).hops.isEmpty())
        assertTrue("预检不分配端口", platform.ports.isEmpty())
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
