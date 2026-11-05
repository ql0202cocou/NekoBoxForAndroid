package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.anytls.toUri
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCompatibilityTest {
    private fun node(testing: Boolean = false, core: Int = ProxyEntity.CORE_AUTO) = ProxyEntity(id = 1, groupId = 1, core = core).putBean(AnyTLSBean().apply {
        initializeDefaultValues()
        serverAddress = "node.example.org"
        serverPort = 443
        password = "fake-password"
        certificateFingerprint = "ab".repeat(32)
        utlsFingerprint = "chrome120"
        compatibilityTest = testing
    })

    private fun input(
        nodes: List<ProxyEntity>, mode: ConfigBuildMode, platform: FakeConfigPlatform,
        rules: List<RuleEntity> = emptyList(),
    ): ConfigInput {
        val record = ProfileRecord.of(nodes.first())
        val group = ProxyGroup(id = 1).apply { name = "test"; proxyServerNameserver = "https://dns.example.org/dns-query" }
        val data = ConfigSnapshot.collect(MemoryConfigDataSource(listOf(group), nodes, rules), record, mode)
        return ConfigInput(mode, record, testConfigSettings(), data, emptyMap(), platform)
    }

    @Test
    fun `普通与历史测试节点使用 sing-box`() {
        for (testing in listOf(false, true)) {
            for (core in listOf(ProxyEntity.CORE_AUTO, ProxyEntity.CORE_SING_BOX)) {
                val entity = node(testing, core)
                assertEquals(DialCore.SING_BOX, (entity.coreDecision(false) as CoreDecision.Selected).core)
            }
            assertTrue(node(testing, ProxyEntity.CORE_XRAY).coreDecision(false) is CoreDecision.Rejected)
            assertTrue(node(testing, ProxyEntity.CORE_MIHOMO).coreDecision(false) is CoreDecision.Rejected)
        }
        assertFalse(node().needExternal(false))
    }

    @Test
    fun `运行和测速共用内部出站与 trial 不生成外核计划`() {
        for (core in listOf(ProxyEntity.CORE_AUTO, ProxyEntity.CORE_SING_BOX)) for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val platform = FakeConfigPlatform()
            val result = buildConfig(input(listOf(node(core = core)), mode, platform))
            assertTrue(result.externalChains.all { it.hops.isEmpty() })
            val trial = JsonParser.parseString(platform.trialQueries.single().second).asJsonObject
            assertEquals("neko-anytls", trial["type"].asString)
            assertEquals("ab".repeat(32), trial["tls"].asJsonObject["certificate_fingerprint"].asString)
            assertTrue(result.config.contains("neko-anytls"))
        }
    }

    @Test
    fun `试建失败阻止运行和测速`() {
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val platform = FakeConfigPlatform(trials = { _, _ -> SingBoxTrialFailure(SingBoxTrialCategory.TLS_CERTIFICATE, null) })
            assertThrows(Exception::class.java) { buildConfig(input(listOf(node()), mode, platform)) }
            assertTrue(platform.ports.isEmpty())
        }
    }

    @Test
    fun `历史测试标记不再限制分享或改变选核`() {
        for (testing in listOf(false, true)) {
            val entity = node(testing)
            val bean = entity.requireBean() as AnyTLSBean
            val bytes = KryoConverters.serialize(bean)
            val restored = universalBean(ProxyEntity.TYPE_ANYTLS, bytes) { false } as AnyTLSBean
            assertEquals(testing, restored.compatibilityTest)
            assertTrue(entity.haveLink())
            val imported = parseAnytls(bean.toUri()).apply { initializeDefaultValues() }
            assertEquals(bean.certificateFingerprint, imported.certificateFingerprint)
            assertFalse(imported.compatibilityTest)
            assertEquals(DialCore.SING_BOX, (decideCore(ProxyEntity.TYPE_ANYTLS, ProxyEntity.CORE_AUTO, restored, false) as CoreDecision.Selected).core)
        }
    }

    @Test
    fun `内部扩展禁止可移植配置导出`() {
        val chain = ProxyEntity(id = 2, groupId = 1).putBean(ChainBean().apply {
            initializeDefaultValues(); proxies = listOf(1L)
        })
        for (nodes in listOf(listOf(node()), listOf(chain, node()))) {
            val platform = FakeConfigPlatform()
            assertThrows(Exception::class.java) { buildConfig(input(nodes, ConfigBuildMode.EXPORT, platform)) }
            assertTrue(platform.ports.isEmpty())
        }
    }

    @Test
    fun `合法 AnyTLS 规则目标及嵌套链保留规则且私有出站使整个导出失败`() {
        val main = ProxyEntity(id = 10, groupId = 1).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "main.example.org"; serverPort = 1080
        })
        val native = node().apply { anyTLSBean!!.apply {
            certificateFingerprint = ""
            utlsFingerprint = ""
            certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
            customOutboundJson = """{"idle_session_timeout":"90s"}"""
        } }
        val cases = listOf(node() to false, node(core = ProxyEntity.CORE_SING_BOX) to false, native to true)
        for ((anyTls, portable) in cases) {
            anyTls.anyTLSBean!!.name = "rule-anytls"
            for (nested in listOf(false, true)) {
                val inner = ProxyEntity(id = 2, groupId = 1).putBean(ChainBean().apply {
                    initializeDefaultValues(); proxies = listOf(1L)
                })
                val outer = ProxyEntity(id = 3, groupId = 1).putBean(ChainBean().apply {
                    initializeDefaultValues(); proxies = listOf(2L)
                })
                val target = if (nested) outer.id else anyTls.id
                val nodes = listOf(main, anyTls, inner, outer)
                val rules = listOf(RuleEntity(id = 1, enabled = true, ip = "198.51.100.0/24", outbound = target))
                fun assertRulePreserved(result: ConfigBuildResult) {
                    val config = JsonParser.parseString(result.config).asJsonObject
                    val rule = config["route"].asJsonObject["rules"].asJsonArray.single {
                        it.asJsonObject["ip_cidr"]?.asJsonArray?.any { cidr -> cidr.asString == "198.51.100.0/24" } == true
                    }.asJsonObject
                    assertEquals(result.profileTagMap.getValue(target), rule["outbound"].asString)
                    assertTrue(result.diagnostics.isEmpty())
                }
                assertRulePreserved(buildConfig(input(nodes, ConfigBuildMode.RUN, FakeConfigPlatform(), rules)))
                val platform = FakeConfigPlatform()
                val diagnostics = ArrayList<ConfigBuildDiagnostic>()
                val exportInput = input(nodes, ConfigBuildMode.EXPORT, platform, rules)
                if (portable) {
                    val exported = buildConfig(exportInput, diagnostics)
                    assertRulePreserved(exported)
                    val text = exportConfigText(exported, "test", platform::createTempFile).first
                    assertTrue(text.contains("198.51.100.0/24"))
                    assertTrue(text.contains("anytls"))
                } else {
                    val error = assertThrows(ProfileBuildException::class.java) { buildConfig(exportInput, diagnostics) }
                    assertEquals("rule-anytls", error.profileName)
                    assertTrue(error.cause is ConfigExportException)
                    assertTrue(error.message!!.contains("NekoBox extension"))
                    assertTrue(platform.warnings.isEmpty())
                }
                assertTrue(diagnostics.isEmpty())
            }
        }
    }

    @Test
    fun `旧 sing-box 节点的 JSON 在迁移后仍由原生出站试建运行测速和导出`() {
        val overrides = """{"idle_session_timeout":"90s","min_idle_session":2,"tls":{"fragment":true,"min_version":"1.3"}}"""
        val entity = node().apply {
            anyTLSBean!!.apply {
                certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_LEGACY
                certificateFingerprint = ""
                utlsFingerprint = ""
                certificates = CoreTestNodes.CERT
                customOutboundJson = overrides
                preserveLegacyAnyTLSCertificatePolicy(this, ProxyEntity.CORE_SING_BOX)
            }
        }
        for (mode in ConfigBuildMode.entries) {
            val platform = FakeConfigPlatform()
            val result = buildConfig(input(listOf(entity), mode, platform))
            val trial = JsonParser.parseString(platform.trialQueries.single().second).asJsonObject
            val outbound = JsonParser.parseString(result.config).asJsonObject["outbounds"].asJsonArray
                .map { it.asJsonObject }.single { it["type"].asString == "anytls" }
            for (options in listOf(trial, outbound)) {
                assertEquals("anytls", options["type"].asString)
                assertEquals("90s", options["idle_session_timeout"].asString)
                assertEquals(2, options["min_idle_session"].asInt)
                val tls = options["tls"].asJsonObject
                assertTrue(tls["fragment"].asBoolean)
                assertEquals("1.3", tls["min_version"].asString)
                assertEquals(CoreTestNodes.CERT, tls["certificate"].asString)
                assertFalse(tls.has("certificate_policy"))
            }
        }
        assertEquals(overrides, entity.anyTLSBean!!.customOutboundJson)
    }

    @Test
    fun `原生覆写使用原生指纹表且显式固定优先`() {
        val entity = node().apply { anyTLSBean!!.apply {
            certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
            certificateFingerprint = ""
            utlsFingerprint = "chrome_psk"
            customOutboundJson = """{"idle_session_check_interval":"10s"}"""
        } }
        assertEquals(DialCore.SING_BOX, (entity.coreDecision(false) as CoreDecision.Selected).core)
        assertEquals("anytls", buildSingBoxOutbound(entity.requireBean(), false).asMap()["type"])
        entity.anyTLSBean!!.certificateFingerprint = CoreTestNodes.PIN
        assertTrue(entity.coreDecision(false) is CoreDecision.Rejected)
        entity.anyTLSBean!!.utlsFingerprint = "chrome"
        val compat = buildSingBoxOutbound(entity.requireBean(), false).asMap()
        assertEquals("neko-anytls", compat["type"])
        assertEquals(CoreTestNodes.PIN, (compat["tls"] as Map<*, *>)["certificate_fingerprint"])
    }

    @Test
    fun `普通覆写可用原生字段且不能把固定证书解释为信任根`() {
        val bean = CoreTestNodes.anytls { utlsFingerprint = ""; customOutboundJson = """{"min_idle_session":2}""" }
        fun type() = buildSingBoxOutbound(bean, false).asMap()["type"]
        assertEquals("anytls", type())
        bean.certificates = CoreTestNodes.CERT
        assertEquals("neko-anytls", type())
        bean.certificates = ""
        bean.customOutboundJson = """{"tls":{"certificate":"test-pem"}}"""
        assertEquals("neko-anytls", type())
        bean.certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
        bean.customOutboundJson = """{"tls":{"utls":{"enabled":true,"fingerprint":"chrome120"}}}"""
        assertEquals("neko-anytls", type())
        bean.customOutboundJson = """{"tls":{"certificate_fingerprint":"test-pin"}}"""
        assertEquals("neko-anytls", type())
        bean.customOutboundJson = """{"type":"neko-anytls-compat-experiment"}"""
        assertEquals("neko-anytls", type())
    }

    @Test
    fun `普通节点的自定义 JSON 同样进入内部 trial`() {
        val platform = FakeConfigPlatform()
        val entity = node().apply { anyTLSBean!!.customOutboundJson = "{\"tls\":{\"server_name\":\"override.example\"}}" }
        buildConfig(input(listOf(entity), ConfigBuildMode.RUN, platform))
        val trial = JsonParser.parseString(platform.trialQueries.single().second).asJsonObject
        assertEquals("override.example", trial["tls"].asJsonObject["server_name"].asString)
        assertEquals("ab".repeat(32), trial["tls"].asJsonObject["certificate_fingerprint"].asString)
    }

    @Test
    fun `ECH 仅接受验证过的确定性指纹组合`() {
        for (fp in listOf("", "none", "chrome", "firefox", "chrome120", "firefox120", "safari", "random", "randomized")) {
            val entity = node(core = ProxyEntity.CORE_SING_BOX).apply { anyTLSBean!!.apply { enableECH = true; utlsFingerprint = fp } }
            assertEquals(fp, fp in listOf("", "none", "chrome", "firefox", "chrome120", "firefox120"), entity.coreDecision(false) is CoreDecision.Selected)
        }
    }

    @Test
    fun `自动与手动选核都拒绝无法握手的 Safari ECH`() {
        val entity = node().apply { anyTLSBean!!.apply { enableECH = true; utlsFingerprint = "safari" } }
        val original = KryoConverters.serialize(entity.requireBean())
        for (core in listOf(ProxyEntity.CORE_AUTO, ProxyEntity.CORE_SING_BOX)) {
            entity.core = core
            val rejected = entity.coreDecision(false) as CoreDecision.Rejected
            assertEquals(listOf(CoreConflict.SING_BOX_ANYTLS_ECH), rejected.conflicts.map { it.id })
            assertArrayEquals(original, KryoConverters.serialize(entity.requireBean()))
        }
    }

    @Test
    fun `未知客户端指纹不进入诊断文本`() {
        val entity = node().apply { anyTLSBean!!.utlsFingerprint = "accidentally-pasted-secret" }
        val rejected = entity.coreDecision(false) as CoreDecision.Rejected
        assertFalse(rejected.message(entity.core).contains("accidentally-pasted-secret"))
        assertTrue(rejected.conflicts.any { it.value == CONFLICT_VALUE_NOT_SHOWN })
    }

    @Test
    fun `兼容出站拦下的指纹照常回显名字`() {
        val entity = node(core = ProxyEntity.CORE_SING_BOX).apply { anyTLSBean!!.utlsFingerprint = "chrome_psk" }
        val rejected = entity.coreDecision(false) as CoreDecision.Rejected
        assertEquals(listOf(Conflict(DialCore.SING_BOX, CoreConflict.SING_BOX_ANYTLS_UTLS, "chrome_psk")), rejected.conflicts)
    }

    @Test
    fun `坏规则目标与含坏跳的链在运行和导出都按相同原因跳过`() {
        val main = ProxyEntity(id = 10, groupId = 1).putBean(SOCKSBean().apply { initializeDefaultValues() })
        val bad = node().apply { anyTLSBean!!.certificateFingerprint = "invalid" }
        val valid = node().apply { id = 2 }
        val chain = ProxyEntity(id = 3, groupId = 1).putBean(ChainBean().apply {
            initializeDefaultValues()
            // 先规划出口的合法 AnyTLS，再试建坏跳。
            proxies = listOf(bad.id, valid.id)
        })
        fun platform() = FakeConfigPlatform(trials = { _, json ->
            if (json.contains("\"invalid\"")) SingBoxTrialFailure(SingBoxTrialCategory.TLS_CERTIFICATE, null) else null
        })
        val selected = platform()
        val failure = assertThrows(ProfileBuildException::class.java) {
            buildConfig(input(listOf(bad), ConfigBuildMode.EXPORT, selected))
        }
        assertTrue(failure.cause is SingBoxTrialException)
        assertTrue(selected.ports.isEmpty())
        for (target in listOf(bad, chain)) {
            val rule = RuleEntity(id = 1, enabled = true, domains = "audit.example", outbound = target.id)
            for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.EXPORT)) {
                val platform = platform()
                val result = buildConfig(input(listOf(main, valid, bad, chain), mode, platform, listOf(rule)))
                assertFalse(result.config.contains("audit.example"))
                assertFalse(result.config.contains("neko-anytls"))
                assertEquals(listOf("ProfileSkipped", "RuleOutboundMissing"), result.diagnostics.map { it.javaClass.simpleName })
                assertTrue(platform.trialQueries.any { it.second.contains("\"invalid\"") })
            }
        }
    }

    @Test
    fun `旧 mihomo 的本地 SOCKS 覆写明确拒绝直到清空`() {
        val entity = node().apply { anyTLSBean!!.apply {
            certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_LEGACY
            customOutboundJson = """{"network":"tcp"}"""
            preserveLegacyAnyTLSCertificatePolicy(this, ProxyEntity.CORE_MIHOMO)
        } }
        for (core in listOf(ProxyEntity.CORE_AUTO, ProxyEntity.CORE_SING_BOX)) {
            entity.core = core
            assertEquals(listOf(CoreConflict.SING_BOX_ANYTLS_LOCAL_SOCKS_OVERRIDE),
                (entity.coreDecision(false) as CoreDecision.Rejected).conflicts.map { it.id })
            for (mode in ConfigBuildMode.entries) {
                assertThrows(ProfileBuildException::class.java) {
                    buildConfig(input(listOf(entity), mode, FakeConfigPlatform()))
                }
            }
        }
        entity.anyTLSBean!!.customOutboundJson = ""
        entity.core = ProxyEntity.CORE_AUTO
        assertEquals(CoreDecision.Selected(DialCore.SING_BOX, false), entity.coreDecision(false))
    }
}
