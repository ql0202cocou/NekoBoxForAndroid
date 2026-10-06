package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.putBean
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
            Triple(CoreTestNodes.anytls(), CORE_MIHOMO, CoreTestNodes.anytls { serverPort = 9443 }),
            Triple(CoreTestNodes.trojan(), CORE_AUTO, CoreTestNodes.trojan { sni = "other.example.org" }),
        )
        for ((old, core, new) in cases) {
            val entity = existing(old, core)
            entity.replaceSubscriptionBean(new)
            assertEquals("${old.javaClass.simpleName} core=$core", core, entity.core)
            assertSame(new, entity.requireBean())
        }
    }
}
