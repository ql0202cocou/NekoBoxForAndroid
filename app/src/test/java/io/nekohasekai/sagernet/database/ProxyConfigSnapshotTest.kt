package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import io.nekohasekai.sagernet.fmt.putBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 「配置变了」的判定口径：type、core、bean 内容（节点名不算，自定义覆写算）
class ProxyConfigSnapshotTest {

    private fun entity(bean: AbstractBean, core: Int = ProxyEntity.CORE_AUTO) =
        ProxyEntity(id = 1, groupId = 1).putBean(bean).also { it.core = core }

    private fun changed(old: ProxyEntity, new: ProxyEntity) = ProxyConfigSnapshot(old).differsFrom(new)

    @Test
    fun `内容完全相同：没变，状态列的差别也不算`() {
        val old = entity(CoreTestNodes.vmess()).also { it.status = 1; it.ping = 80; it.userOrder = 3 }
        val new = entity(CoreTestNodes.vmess()).also { it.status = 0; it.tx = 9; it.userOrder = 7 }
        assertFalse(changed(old, new))
    }

    @Test
    fun `只改节点名：没变`() {
        assertFalse(changed(entity(CoreTestNodes.vmess()), entity(CoreTestNodes.vmess { name = "renamed" })))
    }

    @Test
    fun `改服务器地址、端口、凭据、TLS 字段：变了`() {
        val old = entity(CoreTestNodes.vmess())
        assertTrue(changed(old, entity(CoreTestNodes.vmess { serverAddress = "other.example.com" })))
        assertTrue(changed(old, entity(CoreTestNodes.vmess { serverPort = 8443 })))
        assertTrue(changed(old, entity(CoreTestNodes.vmess { uuid = "00000000-0000-0000-0000-000000000002" })))
        assertTrue(changed(old, entity(CoreTestNodes.vmess { sni = "other.example.org" })))
    }

    @Test
    fun `改自定义覆写：变了；覆写相同：没变`() {
        val old = entity(CoreTestNodes.vmess { customOutboundJson = "{\"a\":1}" })
        assertTrue(changed(old, entity(CoreTestNodes.vmess { customOutboundJson = "{\"a\":2}" })))
        assertTrue(changed(old, entity(CoreTestNodes.vmess { customOutboundJson = "{\"a\":1}"; customConfigJson = "{}" })))
        assertFalse(changed(old, entity(CoreTestNodes.vmess { customOutboundJson = "{\"a\":1}" })))
    }

    @Test
    fun `改 core：变了`() {
        val bean = { CoreTestNodes.vmess() }
        assertTrue(changed(entity(bean(), ProxyEntity.CORE_AUTO), entity(bean(), ProxyEntity.CORE_XRAY)))
        assertFalse(changed(entity(bean(), ProxyEntity.CORE_XRAY), entity(bean(), ProxyEntity.CORE_XRAY)))
    }

    @Test
    fun `改协议类型：变了，同属一个 type 的 VMess 与 VLESS 按内容比也变`() {
        assertTrue(changed(entity(CoreTestNodes.vmess()), entity(CoreTestNodes.trojan())))
        assertTrue(changed(entity(CoreTestNodes.vmess()), entity(CoreTestNodes.vless())))
    }

    @Test
    fun `订阅更新的用法：先取快照，换 bean 之后比`() {
        val current = entity(CoreTestNodes.vmess())
        val same = ProxyConfigSnapshot(current)
        current.putBean(CoreTestNodes.vmess())
        assertFalse(same.differsFrom(current))
        val before = ProxyConfigSnapshot(current)
        current.putBean(CoreTestNodes.vmess { serverPort = 8443 })
        assertTrue(before.differsFrom(current))
    }
}
