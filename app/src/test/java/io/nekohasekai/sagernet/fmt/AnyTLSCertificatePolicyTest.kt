package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCertificatePolicyTest {
    @Test
    fun `旧节点按原核心保留策略且不会再次覆盖`() {
        for (core in listOf(ProxyEntity.CORE_AUTO, ProxyEntity.CORE_SING_BOX, ProxyEntity.CORE_MIHOMO)) {
            for (testing in listOf(false, true)) {
                val bean = CoreTestNodes.anytls { certificates = CoreTestNodes.CERT; compatibilityTest = testing; certificatePolicy = -1 }
                preserveLegacyAnyTLSCertificatePolicy(bean, core)
                val expected = if (!testing && core == ProxyEntity.CORE_SING_BOX) 1 else 0
                assertEquals(expected, bean.certificatePolicy)
                preserveLegacyAnyTLSCertificatePolicy(bean, ProxyEntity.CORE_MIHOMO)
                assertEquals(expected, bean.certificatePolicy)
            }
        }
        val fallback = CoreTestNodes.anytls { certificates = CoreTestNodes.CERT; utlsFingerprint = "chrome_psk"; certificatePolicy = -1 }
        preserveLegacyAnyTLSCertificatePolicy(fallback, ProxyEntity.CORE_AUTO)
        assertEquals(1, fallback.certificatePolicy)
    }

    @Test
    fun `信任模式与显式固定都由 sing-box 承载`() {
        val bean = CoreTestNodes.anytls { certificates = CoreTestNodes.CERT; certificatePolicy = 1 }
        val entity = ProxyEntity(core = ProxyEntity.CORE_AUTO).putBean(bean)
        assertEquals(CoreDecision.Selected(DialCore.SING_BOX, false), entity.coreDecision(false))
        bean.enableECH = true; bean.utlsFingerprint = "safari"
        assertTrue(entity.coreDecision(false) is CoreDecision.Rejected)
        bean.enableECH = false
        bean.certificateFingerprint = CoreTestNodes.PIN
        assertEquals(CoreDecision.Selected(DialCore.SING_BOX, false), entity.coreDecision(false))
    }

    @Test
    fun `策略随备份编辑克隆与通用分享保留`() {
        val bean = CoreTestNodes.anytls { certificates = CoreTestNodes.CERT; certificatePolicy = 1 }
        assertEquals(1, (bean.clone() as AnyTLSBean).certificatePolicy)
        assertFalse(hasStandardLink(bean))
        val shared = universalBean(ProxyEntity.TYPE_ANYTLS, KryoConverters.serialize(bean)) { false } as AnyTLSBean
        assertEquals(1, shared.certificatePolicy)
        val legacy = KryoSamples.samples.first { it.id == "AnyTLSBean/compat-v3/ordinary" }
        for (core in listOf(ProxyEntity.CORE_SING_BOX, ProxyEntity.CORE_MIHOMO)) {
            val restored = ProxyEntity(type = ProxyEntity.TYPE_ANYTLS, core = core).apply { putByteArray(legacy.bytes) }
            BackupRestore.upgradeLegacyProfiles(listOf(restored)) { error("AnyTLS 不读取全局设置") }
            assertEquals(if (core == ProxyEntity.CORE_SING_BOX) 1 else 0, restored.anyTLSBean!!.certificatePolicy)
            assertEquals(ProxyEntity.CORE_AUTO.takeIf { core == ProxyEntity.CORE_MIHOMO } ?: core, restored.core)
        }
    }

    @Test
    fun `旧备份先规范历史核心值再保留信任策略`() {
        val legacy = KryoSamples.samples.first { it.id == "AnyTLSBean/5d74526b/anytls" }
        for (core in listOf(ProxyEntity.CORE_XRAY, 99)) {
            val restored = ProxyEntity(type = ProxyEntity.TYPE_ANYTLS, core = core).apply { putByteArray(legacy.bytes) }
            assertEquals(LegacyCoreSelection.Carrier.SING_BOX,
                LegacyCoreSelection.carrier(restored.type, core, restored.requireBean(), false))
            assertEquals(1, BackupRestore.upgradeLegacyProfiles(listOf(restored)) { error("AnyTLS 不读取全局设置") })
            assertEquals(ProxyEntity.CORE_SING_BOX, restored.core)
            assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, restored.anyTLSBean!!.certificatePolicy)
        }
    }

    @Test
    fun `未知或未迁移策略在生成前拒绝`() {
        for (policy in listOf(-1, 99)) {
            val bean = CoreTestNodes.anytls { certificatePolicy = policy }
            val rejected = ProxyEntity().putBean(bean).coreDecision(false) as CoreDecision.Rejected
            assertTrue(rejected.conflicts.all { it.id == CoreConflict.ANYTLS_CERTIFICATE_POLICY_INVALID })
            assertThrows(IllegalStateException::class.java) { buildAnyTLSCompatibilityTLS(bean, false) }
        }
    }
}
