package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.anytls.toUri
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCompatibilityTest {
    private fun node(testing: Boolean = true) = ProxyEntity(id = 1, groupId = 1).putBean(AnyTLSBean().apply {
        initializeDefaultValues()
        serverAddress = "node.example.org"
        serverPort = 443
        password = "fake-password"
        certificateFingerprint = "ab".repeat(32)
        utlsFingerprint = "chrome120"
        compatibilityTest = testing
    })

    private fun input(nodes: List<ProxyEntity>, mode: ConfigBuildMode, platform: FakeConfigPlatform): ConfigInput {
        val record = ProfileRecord.of(nodes.first())
        val group = ProxyGroup(id = 1).apply { name = "test"; proxyServerNameserver = "https://dns.example.org/dns-query" }
        val data = ConfigSnapshot.collect(MemoryConfigDataSource(listOf(group), nodes, emptyList()), record, mode)
        return ConfigInput(mode, record, testConfigSettings(), data, emptyMap(), platform)
    }

    @Test
    fun `普通节点仍选择 mihomo 测试节点只匹配内部兼容实现`() {
        assertEquals(DialCore.MIHOMO, (node(false).coreDecision(false) as CoreDecision.Selected).core)
        assertEquals(DialCore.SING_BOX, (node().coreDecision(false) as CoreDecision.Selected).core)
        for (core in listOf(ProxyEntity.CORE_XRAY, ProxyEntity.CORE_MIHOMO)) {
            assertTrue(node().apply { this.core = core }.coreDecision(false) is CoreDecision.Rejected)
        }
        assertFalse(node().needExternal(false))
    }

    @Test
    fun `运行和测速共用内部出站与 trial 不生成外核计划`() {
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val platform = FakeConfigPlatform()
            val result = buildConfig(input(listOf(node()), mode, platform))
            assertTrue(result.externalChains.all { it.hops.isEmpty() })
            assertFalse(result.delayTestOnMihomo)
            val trial = JsonParser.parseString(platform.trialQueries.single().second).asJsonObject
            assertEquals("neko-anytls-compat-experiment", trial["type"].asString)
            assertEquals("ab".repeat(32), trial["tls"].asJsonObject["certificate_fingerprint"].asString)
            assertTrue(result.config.contains("neko-anytls-compat-experiment"))
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
    fun `测试标记持久化但普通链接不能激活或丢掉它`() {
        val bean = node().requireBean() as AnyTLSBean
        val restored = KryoConverters.deserialize(AnyTLSBean(), KryoConverters.serialize(bean))
        assertTrue(restored.compatibilityTest)
        assertFalse(node().haveLink())
        assertThrows(IllegalStateException::class.java) { bean.toUri() }
        val ordinary = parseAnytls("anytls://fake@node.example.org:443?compatibilityTest=true")
        ordinary.initializeDefaultValues()
        assertFalse(ordinary.compatibilityTest)
    }

    @Test
    fun `通用链接不能导入或分享兼容测试标记`() {
        val testing = node().requireBean()
        val bytes = KryoConverters.serialize(testing)
        assertThrows(IllegalArgumentException::class.java) {
            universalBean(ProxyEntity.TYPE_ANYTLS, bytes) { false }
        }
        assertThrows(IllegalStateException::class.java) { testing.toUniversalLink() }
        val ordinary = universalBean(ProxyEntity.TYPE_ANYTLS, KryoConverters.serialize(node(false).requireBean())) { false }
        assertFalse((ordinary as AnyTLSBean).compatibilityTest)
        // 备份与数据库使用原始字节入口，显式创建的测试标记仍须保留。
        val restored = ProxyEntity(type = ProxyEntity.TYPE_ANYTLS).apply { putByteArray(bytes) }
        assertTrue(restored.anyTLSBean!!.compatibilityTest)
    }

    @Test
    fun `私有出站不从单节点或链导出 也不接受 JSON 覆盖`() {
        val chain = ProxyEntity(id = 2, groupId = 1).putBean(ChainBean().apply {
            initializeDefaultValues(); proxies = listOf(1L)
        })
        for (nodes in listOf(listOf(node()), listOf(chain, node()))) {
            val platform = FakeConfigPlatform()
            assertThrows(Exception::class.java) { buildConfig(input(nodes, ConfigBuildMode.EXPORT, platform)) }
            assertTrue(platform.ports.isEmpty())
        }
        for (setJson in listOf<(AbstractBean) -> Unit>({ it.customOutboundJson = "{}" }, { it.customConfigJson = "{}" })) {
            val entity = node()
            setJson(entity.requireBean())
            assertThrows(Exception::class.java) { buildConfig(input(listOf(entity), ConfigBuildMode.RUN, FakeConfigPlatform())) }
        }
    }

    @Test
    fun `ECH 仅接受验证过的确定性指纹组合`() {
        for (fp in listOf("", "none", "chrome", "firefox", "chrome120", "firefox120", "safari", "random", "randomized")) {
            val entity = node().apply { anyTLSBean!!.apply { enableECH = true; utlsFingerprint = fp } }
            assertEquals(fp, fp in listOf("", "none", "chrome", "firefox", "chrome120", "firefox120"), entity.coreDecision(false) is CoreDecision.Selected)
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
        val entity = node().apply { anyTLSBean!!.utlsFingerprint = "chrome_psk" }
        val rejected = entity.coreDecision(false) as CoreDecision.Rejected
        assertEquals(listOf(Conflict(DialCore.SING_BOX, CoreConflict.ANYTLS_COMPAT_UTLS, "chrome_psk")), rejected.conflicts)
    }
}
