package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_YAMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.muxProtocolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// sing-box 一侧的 mux 防线（plan.md D15）：没开 mux、vision 流控时不构造多路复用选项；Mux.Cool 与不认识的
// muxType 走到 sing-box 时抛异常，不再悄悄当作 h2mux
class SingMuxTest {

    private fun entity(bean: AbstractBean) = ProxyEntity().putBean(bean)

    private fun StandardV2RayBean.mux(enabled: Boolean, type: Int) = apply {
        enableMux = enabled
        muxType = type
    }

    @Test
    fun `没开 mux 时为 null，muxType 取什么值都不读`() {
        for (type in listOf(MUX_H2MUX, MUX_SMUX, MUX_YAMUX, MUX_COOL, 7, -1)) {
            assertNull("vless muxType=$type", entity(CoreTestNodes.vless().mux(false, type)).singMux())
            assertNull("vmess muxType=$type", entity(CoreTestNodes.vmess().mux(false, type)).singMux())
            assertNull("trojan muxType=$type", entity(CoreTestNodes.trojan().mux(false, type)).singMux())
        }
    }

    @Test
    fun `开了 mux 但是 vision 流控时为 null`() {
        for (type in listOf(MUX_H2MUX, MUX_COOL, 7)) {
            val bean = CoreTestNodes.vless { encryption = StandardV2RayBean.FLOW_VISION }.mux(true, type)
            assertNull("muxType=$type", entity(bean).singMux())
        }
    }

    @Test
    fun `开了 sing-mux 时照旧构造选项`() {
        val names = mapOf(MUX_H2MUX to "h2mux", MUX_SMUX to "smux", MUX_YAMUX to "yamux")
        for ((type, name) in names) {
            for (bean in listOf(CoreTestNodes.vless(), CoreTestNodes.vmess(), CoreTestNodes.trojan())) {
                bean.mux(true, type).apply {
                    muxPadding = true
                    muxConcurrency = 4
                }
                val options = entity(bean).singMux()!!
                assertEquals(true, options.enabled)
                assertEquals(name, options.protocol)
                assertEquals(true, options.padding)
                assertEquals(4, options.max_streams)
            }
        }
    }

    @Test
    fun `Mux Cool 与不认识的取值走到 sing-box 时抛异常`() {
        val cool = assertThrows(IllegalStateException::class.java) {
            entity(CoreTestNodes.vless().mux(true, MUX_COOL)).singMux()
        }
        assertTrue(cool.message!!.contains("Mux.Cool"))
        assertThrows(IllegalStateException::class.java) {
            entity(CoreTestNodes.trojan().mux(true, MUX_COOL)).singMux()
        }
        val unknown = assertThrows(IllegalStateException::class.java) {
            entity(CoreTestNodes.vmess().mux(true, 7)).singMux()
        }
        assertEquals("unknown mux type 7", unknown.message)
        assertThrows(IllegalStateException::class.java) { muxProtocolName(-1) }
    }

    @Test
    fun `不支持 mux 的协议为 null`() {
        assertNull(entity(CoreTestNodes.anytls()).singMux())
    }
}
