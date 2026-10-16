package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// 选择器成员 / 路由规则目标的规划检查（planChain 每跳之后的 ConfigBuild.checkMemberHop）的几条不变式，用
// FakeConfigPlatform 的记录核对：
// 试生成外核配置时建的临时文件构建结束时都已删除；预检不分配端口；选核判定与试生成用的是构建自己的设置；
// 先试生成、后确认插件可用。另有内部核心跳的 sing-box 试建（planHop 里，三种根都做）：成员 / 规则目标不过时跳过、
// 选中的节点不过时整次构建失败、同一份 JSON 一次构建只问一次、没有结论时放行且只告警一次
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
    private fun hysteria(id: Long, groupId: Long, ports: String = "8443") = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(HysteriaBean().apply {
            name = "hy1-$id"
            protocolVersion = 1
            serverAddress = "hy$id.example.com"
            serverPorts = ports
            initializeDefaultValues()
            protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
            caText = "-----BEGIN CERTIFICATE-----\nfake-$id\n-----END CERTIFICATE-----"
        })

    // VLESS + TLS，没有证书指纹；core 为 null 时自动选核。reality 为真时带 REALITY（自动选核走 Xray），
    // 为假时是普通 TLS（K1 起自动选核走 sing-box）
    private fun vless(id: Long, groupId: Long, core: Int? = null, reality: Boolean = true) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id)
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
                if (reality) {
                    realityPubKey = CoreTestNodes.REALITY_KEY
                    realityShortId = CoreTestNodes.SHORT_ID
                }
            })

    private fun rule(id: Long, outbound: Long) =
        RuleEntity(id = id, name = "rule-$id", userOrder = id, enabled = true, domains = "r$id.example.com", outbound = outbound)

    private fun build(
        main: ProxyEntity,
        source: MemoryConfigDataSource,
        platform: ConfigPlatform,
        settings: ConfigSettings = testConfigSettings(),
        diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
        mode: ConfigBuildMode = ConfigBuildMode.RUN,
    ): ConfigBuildResult {
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, mode)
        return buildConfig(ConfigInput(mode, record, settings, snapshot, emptyMap(), platform), diagnostics)
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
    fun `选核判定与试生成用构建自己的设置：手动 Xray 的成员按全局允许不安全跳过，REALITY 成员照常通过`() {
        // 全局「允许不安全」打开（构建的设置快照）：成员 2 手动指定 Xray、普通 TLS、没有证书指纹，能力表判定 Xray
        // 承载不了生效的 allowInsecure，规划时就拒绝（以前要到试生成时才由 buildXrayConfig 拒绝）。成员 3 是自动选核的
        // REALITY，allowInsecure 对它不起作用：判定选 Xray，试生成也按同一份设置通过（以前生成器把 REALITY 误判成
        // 不支持 allowInsecure）
        val source = MemoryConfigDataSource(
            groups = listOf(ProxyGroup(id = 1, isSelector = true)),
            profiles = listOf(main, vless(2, 1, core = ProxyEntity.CORE_XRAY, reality = false), vless(3, 1)),
            rules = emptyList(),
        )
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val settings = testConfigSettings(globalAllowInsecure = true)
        val result = build(main, source, platform, settings, diagnostics)

        // 规划里的拒绝经 withProfileName 包上节点名（ProfileBuildException），预检原样记下
        val reason = "vless-2: the manually chosen core Xray cannot run this profile: " +
            "[allowInsecure, globalAllowInsecure] Xray removed allowInsecure; pin the certificate fingerprint instead"
        assertEquals(listOf(ConfigBuildDiagnostic.ProfileSkipped(2, "vless-2", reason, true)), diagnostics)
        assertEquals(listOf("profile 2 skipped: ${ProfileBuildException::class.java.name}: $reason"), platform.warnings)
        // 选择器里剩主节点与成员 3；成员 3 是唯一的外核跳实例
        assertEquals(setOf(1L, 3L), result.profileTagMap.keys)
        val selector = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it.string("type") == "selector" }
        assertEquals(
            listOf(result.profileTagMap.getValue(1), result.profileTagMap.getValue(3)),
            selector.getAsJsonArray("outbounds").map { it.asString },
        )
        assertEquals(listOf(3L), ExternalRunPlan.from(result).hops.map { it.profileId })
        // 只有成员 3 分了端口：本机 socks 端口与映射端口
        assertEquals(2, platform.ports.size)
        assertEquals(listOf("pluginError(xray-plugin)"), platform.pluginQueries)

        // 同样的成员不开全局「允许不安全」：成员 2 也通过
        val secure = FakeConfigPlatform()
        val secureDiagnostics = ArrayList<ConfigBuildDiagnostic>()
        val secureResult = build(main, source, secure, testConfigSettings(globalAllowInsecure = false), secureDiagnostics)
        assertTrue(secureDiagnostics.isEmpty())
        assertEquals(setOf(1L, 2L, 3L), secureResult.profileTagMap.keys)
    }

    @Test
    fun `成员检查先试生成外核配置、后确认插件可用：两样都不过时报试生成的错，不查插件可用性`() {
        // 选择器分组 1：主节点 1、hysteria 1 成员 2，端口写成非法值，hysteria-plugin 也没装
        val source = MemoryConfigDataSource(
            groups = listOf(ProxyGroup(id = 1, isSelector = true)),
            profiles = listOf(main, hysteria(2, 1, ports = "abc")),
            rules = emptyList(),
        )
        val platform = FakeConfigPlatform(
            pluginErrors = mapOf("hysteria-plugin" to IllegalStateException("plugin hysteria-plugin is not installed")),
            tempDir = tmp.newFolder(),
        )
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, source, platform, diagnostics = diagnostics)

        // 跳过的原因是试生成时的端口错误，不是插件未装
        assertEquals(listOf(ConfigBuildDiagnostic.ProfileSkipped(2, "hy1-2", "hy1-2: Invalid Hysteria port", true)), diagnostics)
        // 规划时查了外部插件 app；试生成失败后不再确认插件可用
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), platform.pluginQueries)
        assertEquals(setOf(1L), result.profileTagMap.keys)
    }

    @Test
    fun `bean 损坏的成员照常跳过，诊断用节点 id 代替名字`() {
        // 成员 2 类型是 VMess 但没存 bean（损坏数据）：取名字也会抛，跳过与诊断不能因此落空
        val broken = ProxyEntity(id = 2, groupId = 1, userOrder = 2, type = ProxyEntity.TYPE_VMESS)
        val source = MemoryConfigDataSource(
            groups = listOf(ProxyGroup(id = 1, isSelector = true)),
            profiles = listOf(main, broken),
            rules = emptyList(),
        )
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, source, FakeConfigPlatform(), diagnostics = diagnostics)

        val skipped = diagnostics.single() as ConfigBuildDiagnostic.ProfileSkipped
        assertEquals(2L, skipped.profileId)
        assertEquals("profile 2", skipped.profileName)
        assertEquals(setOf(1L), result.profileTagMap.keys)
    }

    // ---- sing-box 试建

    // members 按用户填写的顺序：首个是最先拨号的一跳
    private fun chain(id: Long, groupId: Long, vararg members: Long) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    // 选择器分组 1：主节点 1、socks 成员 2、链成员 4（分组 3 里的 REALITY 节点 8 最先拨号，走 Xray 经映射；socks 5 是出口）；
    // 分组 2 里的 socks 7 是路由规则目标。只有 1、2、5、7 是内部核心跳
    private fun trialSource() = MemoryConfigDataSource(
        groups = listOf(ProxyGroup(id = 1, isSelector = true), ProxyGroup(id = 2), ProxyGroup(id = 3)),
        profiles = listOf(main, socks(2, 1), chain(4, 1, 8, 5), socks(5, 3), socks(7, 2), vless(8, 3)),
        rules = listOf(rule(1, outbound = 7)),
    )

    // 一次全部通过的构建实际发出的试建，按 server 找到某个节点的那一份（种类，JSON）
    private fun trialOf(server: String): Pair<SingBoxTrialKind, String> {
        val platform = FakeConfigPlatform()
        build(main, trialSource(), platform)
        return platform.trialQueries.single { JsonParser.parseString(it.second).asJsonObject.string("server") == server }
    }

    @Test
    fun `成员与规则目标试建不过时跳过并记诊断，原因只有类别说明与字段路径；整条链规划失败不分端口`() {
        // 链 4 的出口 socks 5 不是最先拨号的一跳，试建 JSON 带 detour 占位；表按实际发出的那份 JSON 匹配
        val chainExit = trialOf("192.0.2.5")
        assertEquals(SING_BOX_TRIAL_DETOUR, JsonParser.parseString(chainExit.second).asJsonObject.string("detour"))
        val platform = FakeConfigPlatform(
            trials = FakeConfigPlatform.trialTable(
                mapOf(
                    trialOf("192.0.2.2") to SingBoxTrialFailure(SingBoxTrialCategory.TLS_CERTIFICATE, null),
                    chainExit to SingBoxTrialFailure(SingBoxTrialCategory.PASSWORD_MISSING, null),
                    trialOf("192.0.2.7") to SingBoxTrialFailure(SingBoxTrialCategory.DECODE, "tls.certificate_path"),
                ),
            ),
        )
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, trialSource(), platform, diagnostics = diagnostics)

        val prefix = "sing-box rejected the outbound options: "
        assertEquals(
            listOf(
                ConfigBuildDiagnostic.ProfileSkipped(2, "socks-2", "socks-2: ${prefix}certificate is not a valid PEM certificate", true),
                // 出错的是链里的 socks 5，原因以它的名字开头，不是成员自己的名字
                ConfigBuildDiagnostic.ProfileSkipped(4, "chain-4", "socks-5: ${prefix}password is empty", false),
                ConfigBuildDiagnostic.ProfileSkipped(7, "socks-7", "socks-7: ${prefix}invalid option at tls.certificate_path", true),
                // 规则目标被跳过，规则照旧记「目标缺失」
                ConfigBuildDiagnostic.RuleOutboundMissing(1, "rule-1", 7),
            ),
            diagnostics,
        )
        assertEquals(
            listOf(
                "profile 2 skipped: ${ProfileBuildException::class.java.name}: socks-2: ${prefix}certificate is not a valid PEM certificate",
                "profile 4 skipped: ${ProfileBuildException::class.java.name}: socks-5: ${prefix}password is empty",
                "profile 7 skipped: ${ProfileBuildException::class.java.name}: socks-7: ${prefix}invalid option at tls.certificate_path",
            ),
            platform.warnings,
        )
        // 主节点照常；跳过的成员不进选择器，规则目标不建；链 4 的 Xray 跳没有分端口、没有外核条目
        assertEquals(setOf(1L), result.profileTagMap.keys)
        assertTrue(platform.ports.isEmpty())
        assertTrue(ExternalRunPlan.from(result).hops.isEmpty())
        val selector = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it.string("type") == "selector" }
        assertEquals(listOf(result.profileTagMap.getValue(1)), selector.getAsJsonArray("outbounds").map { it.asString })
    }

    @Test
    fun `选中的节点试建不过时运行、测试、导出三种模式都整次构建失败，不记诊断`() {
        for (mode in ConfigBuildMode.entries) {
            // 按 server 认主节点：三种模式下主节点的试建 JSON 未必逐字相同，这里只关心它被拦下
            val platform = FakeConfigPlatform(trials = { _, json ->
                if (JsonParser.parseString(json).asJsonObject.string("server") == "192.0.2.1") {
                    SingBoxTrialFailure(SingBoxTrialCategory.SS_KEY_LENGTH, null)
                } else {
                    null
                }
            })
            val diagnostics = ArrayList<ConfigBuildDiagnostic>()
            val e = assertThrows(ProfileBuildException::class.java) {
                build(main, trialSource(), platform, diagnostics = diagnostics, mode = mode)
            }
            assertEquals(
                "$mode",
                "socks-1: sing-box rejected the outbound options: Shadowsocks 2022 key length does not match the method",
                e.message,
            )
            assertTrue("$mode", e.cause is SingBoxTrialException)
            assertTrue("$mode", diagnostics.isEmpty())
            assertTrue("$mode", platform.ports.isEmpty())
        }
    }

    @Test
    fun `同一份试建 JSON 一次构建只问一次平台`() {
        // 选择器分组 1 带前置 socks 9（分组 2）：主节点与两个成员的链都以它最先拨号，三次规划出同一份 JSON
        val source = MemoryConfigDataSource(
            groups = listOf(ProxyGroup(id = 1, isSelector = true, frontProxy = 9), ProxyGroup(id = 2)),
            profiles = listOf(main, socks(2, 1), socks(3, 1), socks(9, 2)),
            rules = emptyList(),
        )
        val platform = FakeConfigPlatform()
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, source, platform, diagnostics = diagnostics)
        assertTrue(diagnostics.isEmpty())
        assertEquals(setOf(1L, 2L, 3L), result.profileTagMap.keys)
        val servers = platform.trialQueries.map { JsonParser.parseString(it.second).asJsonObject.string("server") }
        assertEquals(listOf("192.0.2.1", "192.0.2.9", "192.0.2.2", "192.0.2.3"), servers)
        assertEquals(platform.trialQueries.size, platform.trialQueries.toSet().size)
    }

    @Test
    fun `试建没有结论时放行，每种只告警一次`() {
        // 平台照 Android 实现的样子经 SingBoxTrialResultReader 读 libcore 的返回值：成员 2 的构造 panic，其余基础实例建不起来
        val fake = FakeConfigPlatform()
        val platform = object : ConfigPlatform by fake {
            private val reader = SingBoxTrialResultReader { fake.warn(it) }
            override fun trialSingBoxOutbound(kind: SingBoxTrialKind, json: String): SingBoxTrialFailure? {
                fake.trialQueries += kind to json
                val server = JsonParser.parseString(json).asJsonObject.string("server")
                return reader.read(if (server == "192.0.2.2") "PANIC" else "UNAVAILABLE")
            }
        }
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build(main, trialSource(), platform, diagnostics = diagnostics)
        assertTrue(diagnostics.isEmpty())
        assertEquals(setOf(1L, 2L, 4L, 7L), result.profileTagMap.keys)
        assertEquals(4, fake.trialQueries.size)
        assertEquals(
            listOf("sing-box outbound trial gave no verdict: UNAVAILABLE", "sing-box outbound trial gave no verdict: PANIC"),
            fake.warnings,
        )
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
