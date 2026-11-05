package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.putBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

// 订阅更新换上新 bean 时的手动核心值：协议类型变了改回自动，没变保留
class SubscriptionCoreResetTest {

    private fun existing(bean: AbstractBean, core: Int) =
        ProxyEntity(id = 7, groupId = 3, userOrder = 2).putBean(bean).also { it.core = core }

    @Test
    fun `协议类型变了，core 改回自动`() {
        val cases = listOf(
            Triple(CoreTestNodes.vmess(), CORE_XRAY, CoreTestNodes.trojan()),
            Triple(CoreTestNodes.vless(), CORE_SING_BOX, CoreTestNodes.anytls()),
            Triple(CoreTestNodes.anytls(), CORE_MIHOMO, CoreTestNodes.vless()),
            Triple(CoreTestNodes.trojan(), CORE_XRAY, CoreTestNodes.vmess()),
        )
        for ((old, core, new) in cases) {
            val entity = existing(old, core)
            val oldType = entity.type
            entity.replaceSubscriptionBean(new)
            val where = "${old.javaClass.simpleName} → ${new.javaClass.simpleName}"
            assertEquals(where, ProxyEntity().putBean(new).type, entity.type)
            assertNotEquals(where, oldType, entity.type)
            assertEquals(where, CORE_AUTO, entity.core)
            assertSame(where, new, entity.requireBean())
        }
    }

    @Test
    fun `协议类型相同，core 保留`() {
        val cases = listOf(
            Triple(CoreTestNodes.vmess(), CORE_XRAY, CoreTestNodes.vmess { serverPort = 8443 }),
            // VMess 与 VLESS 同属 TYPE_VMESS
            Triple(CoreTestNodes.vmess(), CORE_XRAY, CoreTestNodes.vless()),
            Triple(CoreTestNodes.anytls(), CORE_SING_BOX, CoreTestNodes.anytls { serverPort = 9443 }),
            Triple(CoreTestNodes.trojan(), CORE_AUTO, CoreTestNodes.trojan { sni = "other.example.org" }),
        )
        for ((old, core, new) in cases) {
            val entity = existing(old, core)
            entity.replaceSubscriptionBean(new)
            assertEquals("${old.javaClass.simpleName} core=$core", core, entity.core)
            assertSame(new, entity.requireBean())
        }
    }
    @Test
    fun `AnyTLS 订阅刷新保留独立证书策略`() {
        val old = CoreTestNodes.anytls { certificatePolicy = 1; certificatePolicySource = AnyTLSBean.POLICY_MIGRATED; certificates = "old-ca" }
        val entity = existing(old, CORE_AUTO)
        val updated = CoreTestNodes.anytls { certificates = "new-ca" }
        entity.replaceSubscriptionBean(updated)
        assertEquals(1, updated.certificatePolicy)
        assertEquals("new-ca", updated.certificates)
        assertEquals(CORE_AUTO, entity.core)
    }

    @Test
    fun `AnyTLS 本地策略与覆写不会造成虚假订阅变化`() {
        val previous = CoreTestNodes.anytls {
            certificatePolicy = 1
            certificatePolicySource = AnyTLSBean.POLICY_LOCAL
            customOutboundJson = "{\"tag\":\"local\"}"
            customConfigJson = "{\"log\":{\"level\":\"debug\"}}"
        }
        val incoming = CoreTestNodes.anytls()
        assertNotEquals(previous, incoming)
        incoming.preserveSubscriptionOverridesFrom(previous)
        assertEquals(previous, incoming)
    }

    @Test
    fun `订阅写入保留下载期间修改的策略与覆写`() {
        val previous = CoreTestNodes.anytls { certificatePolicy = 1 }
        val entity = existing(previous, CORE_SING_BOX)
        val incoming = CoreTestNodes.anytls { certificates = "new-ca"; serverPort = 9443 }
        incoming.preserveSubscriptionOverridesFrom(previous)
        previous.certificatePolicySource = AnyTLSBean.POLICY_LOCAL
        previous.certificatePolicy = 0
        previous.customOutboundJson = "{\"tag\":\"edited\"}"
        previous.customConfigJson = "{\"log\":{\"level\":\"warn\"}}"

        entity.replaceSubscriptionBean(incoming)

        assertEquals(0, incoming.certificatePolicy)
        assertEquals(previous.customOutboundJson, incoming.customOutboundJson)
        assertEquals(previous.customConfigJson, incoming.customConfigJson)
        assertEquals("new-ca", incoming.certificates)
        assertEquals(9443, incoming.serverPort)
        assertEquals(CORE_SING_BOX, entity.core)
    }

    @Test
    fun `同一份证书跟随订阅的显式策略但保留本地手动覆写`() {
        fun incoming(policy: Int) = CoreTestNodes.anytls {
            certificates = CoreTestNodes.CERT
            certificatePolicy = policy
            certificatePolicySource = AnyTLSBean.POLICY_PUBLISHED
        }
        for (source in listOf(AnyTLSBean.POLICY_DEFAULT, AnyTLSBean.POLICY_PUBLISHED, AnyTLSBean.POLICY_MIGRATED, AnyTLSBean.POLICY_LOCAL)) {
            val entity = existing(incoming(0).apply { certificatePolicySource = source }, CORE_AUTO)
            val update = incoming(1)
            entity.replaceSubscriptionBean(update)
            assertEquals(if (source == AnyTLSBean.POLICY_LOCAL) 0 else 1, entity.anyTLSBean!!.certificatePolicy)
            val again = incoming(1).apply { preserveSubscriptionOverridesFrom(entity.requireBean()) }
            assertEquals(update, again)
            entity.replaceSubscriptionBean(incoming(0))
            assertEquals(0, entity.anyTLSBean!!.certificatePolicy)
        }
        val old = existing(incoming(0).apply { certificatePolicySource = AnyTLSBean.POLICY_SOURCE_UNKNOWN }, CORE_AUTO)
        assertThrows(IllegalArgumentException::class.java) { old.replaceSubscriptionBean(incoming(1)) }
        assertEquals(0, old.anyTLSBean!!.certificatePolicy)
    }
}
