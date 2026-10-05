package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TUIC
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.CORE_NORMALIZED
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.MUX_COOL
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.WS_EARLY_DATA_HEADER
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import io.nekohasekai.sagernet.fmt.v2ray.resolveWsEarlyData
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_WebsocketOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL as MUX_COOL_VALUE

// 存量节点的一次性升级标注（LegacyProfileUpgrade.kt）：三条规则逐类的表格测试，外加幂等与「不改变 K1 之前的承载」
class LegacyProfileUpgradeTest {

    private fun upgrade(type: Int, core: Int, bean: AbstractBean, global: Boolean = false) =
        upgradeLegacyProfile(type, core, bean, global)

    private fun muxed(bean: VMessBean) = bean.apply {
        enableMux = true
        muxType = MUX_SMUX
    }

    // ---- 规则 a：mux 协议族。I3 报告 §4「逐类推演」表逐行一个用例（enableMux = true、不是 vision 流控）

    private class MuxCase(val name: String, val core: Int, val global: Boolean, val marked: Boolean, val bean: () -> VMessBean)

    private val muxCases = listOf(
        MuxCase("VLESS 自动，Xray 支持该传输，allowInsecure 不生效", CORE_AUTO, false, true) {
            CoreTestNodes.vless { type = "ws" }
        },
        MuxCase("VLESS 自动，allowInsecure 生效但有证书指纹", CORE_AUTO, true, true) {
            CoreTestNodes.vless { allowInsecure = true; certificateFingerprint = CoreTestNodes.PIN }
        },
        MuxCase("VLESS 自动，quic", CORE_AUTO, false, false) { CoreTestNodes.vless { type = "quic" } },
        MuxCase("VLESS 自动，h2（http + TLS）", CORE_AUTO, false, false) { CoreTestNodes.vless { type = "http" } },
        MuxCase("VLESS 自动，TLS + 节点 allowInsecure、没有指纹", CORE_AUTO, false, false) {
            CoreTestNodes.vless { allowInsecure = true }
        },
        MuxCase("VLESS 自动，TLS + 全局 allowInsecure、没有指纹（含 REALITY 的误判）", CORE_AUTO, true, false) {
            CoreTestNodes.vless { reality() }
        },
        MuxCase("VLESS 手动 Xray", CORE_XRAY, false, true) { CoreTestNodes.vless() },
        MuxCase("VLESS 手动 Xray，h2（当时构建报错，本来就跑不起来）", CORE_XRAY, false, true) {
            CoreTestNodes.vless { type = "http" }
        },
        MuxCase("VLESS 手动 sing-box", CORE_SING_BOX, false, false) { CoreTestNodes.vless() },
        MuxCase("VMess 自动，带证书指纹，Xray 支持该传输", CORE_AUTO, false, true) {
            CoreTestNodes.vmess { certificateFingerprint = CoreTestNodes.PIN; type = "grpc" }
        },
        MuxCase("VMess 自动，没有指纹", CORE_AUTO, false, false) { CoreTestNodes.vmess() },
        MuxCase("VMess 手动 Xray", CORE_XRAY, false, true) { CoreTestNodes.vmess { security = "none" } },
        MuxCase("VMess core = 3（mihomo）", CORE_MIHOMO, false, false) { CoreTestNodes.vmess() },
        MuxCase("VLESS core = 3（mihomo）", CORE_MIHOMO, false, false) { CoreTestNodes.vless() },
        MuxCase("VLESS core 为非法值 7", 7, false, false) { CoreTestNodes.vless() },
    )

    @Test
    fun `规则 a：VMess 类开了 mux、当时走 Xray 的标为 Mux Cool`() {
        for (case in muxCases) {
            val bean = muxed(case.bean())
            val result = upgrade(TYPE_VMESS, case.core, bean, case.global)
            assertEquals(case.name, case.marked, MUX_COOL in result.changes)
            assertEquals(case.name, if (case.marked) MUX_COOL_VALUE else MUX_SMUX, bean.muxType)
        }
    }

    @Test
    fun `规则 a：Trojan 任何 core 都不标`() {
        for (core in CoreTestNodes.CORE_SAMPLES) {
            val bean = CoreTestNodes.trojan { enableMux = true; muxType = MUX_SMUX; reality() }
            val result = upgrade(TYPE_TROJAN, core, bean)
            assertFalse("core=$core", MUX_COOL in result.changes)
            assertEquals(MUX_SMUX, bean.muxType)
        }
    }

    @Test
    fun `规则 a：没开 mux 的不标`() {
        val bean = CoreTestNodes.vless { enableMux = false; muxType = MUX_SMUX }
        assertFalse(upgrade(TYPE_VMESS, CORE_AUTO, bean).changed)
        assertEquals(MUX_SMUX, bean.muxType)
    }

    // vision 流控时两个核心都不写 mux，标不标都不影响运行；照常标注，以后关掉 vision 时就是 Mux.Cool（I3 §4）
    @Test
    fun `规则 a：vision 流控也照常标注`() {
        val bean = muxed(CoreTestNodes.vless { reality(); encryption = StandardV2RayBean.FLOW_VISION })
        assertEquals(setOf(MUX_COOL), upgrade(TYPE_VMESS, CORE_AUTO, bean).changes)
    }

    // ---- 规则 b：ws early data 的携带方式

    private fun ws(block: VMessBean.() -> Unit = {}) = CoreTestNodes.vless {
        type = "ws"
        path = "/ws"
        wsMaxEarlyData = 2048
        earlyDataHeaderName = ""
        block()
    }

    private val xraySettings = ExternalCoreSettings(logLevel = 0, ipv6Mode = 0, globalAllowInsecure = false)

    @Suppress("UNCHECKED_CAST")
    private fun xrayWsPath(bean: VMessBean): String {
        val stream = buildXrayOutbound(bean, "127.0.0.1", 20000, xraySettings)["streamSettings"] as Map<String, Any?>
        return (stream["wsSettings"] as Map<String, Any?>)["path"] as String
    }

    private fun singBoxWs(bean: VMessBean) = buildSingBoxOutboundStreamSettings(bean) as V2RayTransportOptions_WebsocketOptions

    // 标注依据的事实，钉住两个生成器对这种输入的实际输出：sing-box 不写头名（核心把 early data 拼在路径后，
    // transport/v2raywebsocket/conn.go），Xray 写 ?ed=N（核心放进 Sec-WebSocket-Protocol 头，websocket/dialer.go）
    @Test
    fun `规则 b 的前提：两个生成器对没有头名的 early data 输出不同`() {
        val bean = ws()
        val singBox = singBoxWs(bean)
        assertEquals("/ws", singBox.path)
        assertEquals(2048, singBox.max_early_data)
        assertNull(singBox.early_data_header_name)
        assertEquals("/ws?ed=2048", xrayWsPath(bean))
    }

    @Test
    fun `规则 b：当时走 Xray 的标上 Sec-WebSocket-Protocol，标注后 sing-box 也用这个头`() {
        val bean = ws()
        assertEquals(setOf(WS_EARLY_DATA_HEADER), upgrade(TYPE_VMESS, CORE_AUTO, bean).changes)
        assertEquals("Sec-WebSocket-Protocol", bean.earlyDataHeaderName)
        val singBox = singBoxWs(bean)
        assertEquals("/ws", singBox.path)
        assertEquals(2048, singBox.max_early_data)
        assertEquals("Sec-WebSocket-Protocol", singBox.early_data_header_name)
        // Xray 一侧不变
        assertEquals("/ws?ed=2048", xrayWsPath(bean))
    }

    @Test
    fun `规则 b：其余情形不标`() {
        val cases = listOf(
            "当时走 sing-box（VMess 没有指纹）" to { CoreTestNodes.vmess { type = "ws"; path = "/ws"; wsMaxEarlyData = 2048 } },
            "当时走 sing-box（手动）" to { ws() },
            "已有头名" to { ws { earlyDataHeaderName = "X-Early-Data" } },
            "路径里内嵌 ed=" to { ws { path = "/ws?ed=2048" } },
            "路径里内嵌 &ed=" to { ws { path = "/ws?a=1&ed=1024" } },
            "没开 early data" to { ws { wsMaxEarlyData = 0 } },
            "不是 ws" to { ws { type = "httpupgrade" } },
        )
        for ((name, make) in cases) {
            val bean = make()
            val core = if (name == "当时走 sing-box（手动）") CORE_SING_BOX else CORE_AUTO
            val before = bean.earlyDataHeaderName
            assertFalse(name, WS_EARLY_DATA_HEADER in upgrade(TYPE_VMESS, core, bean).changes)
            assertEquals(name, before, bean.earlyDataHeaderName)
        }
    }

    // 内嵌 ed= 的识别是 1.8.0-a3 resolveWsEarlyData 的冻结副本：现在两者一致
    @Test
    fun `规则 b：内嵌 ed 的识别与 resolveWsEarlyData 一致`() {
        val paths = listOf("", "/", "/ws", "/ws?ed=2048", "/ws?a=1&ed=1024", "/ws?a=1", "/ws&ed=10", "/ws?eda=1", "/ws?x=ed=1")
        for (path in paths) {
            val bean = ws { this.path = path; wsMaxEarlyData = 2048 }
            val expectedMarked = bean.resolveWsEarlyData().headerName == null
            assertEquals(path, expectedMarked, WS_EARLY_DATA_HEADER in upgrade(TYPE_VMESS, CORE_AUTO, bean).changes)
        }
    }

    // ---- 规则 c：手动核心值规范化

    @Test
    fun `规则 c：按协议规范手动核心值`() {
        val expected = mapOf(
            TYPE_VMESS to mapOf(-1 to 1, 0 to 0, 1 to 1, 2 to 2, 3 to 1, 4 to 1, 99 to 1),
            TYPE_ANYTLS to mapOf(-1 to 1, 0 to 0, 1 to 1, 2 to 1, 3 to 3, 4 to 1, 99 to 1),
            TYPE_TROJAN to mapOf(-1 to 0, 0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 99 to 0),
            TYPE_SOCKS to mapOf(-1 to 0, 0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0, 99 to 0),
            TYPE_HYSTERIA to mapOf(-1 to 0, 0 to 0, 2 to 0, 3 to 0),
            TYPE_TUIC to mapOf(1 to 0, 3 to 0),
            TYPE_HTTP to mapOf(2 to 0),
        )
        for ((type, table) in expected) for ((core, normalized) in table) {
            assertEquals("type=$type core=$core", normalized, normalizedLegacyCore(type, core))
        }
        val result = upgrade(TYPE_ANYTLS, CORE_XRAY, CoreTestNodes.anytls())
        assertEquals(CORE_SING_BOX, result.core)
        assertEquals(setOf(CORE_NORMALIZED), result.changes)
        val unchanged = upgrade(TYPE_ANYTLS, CORE_MIHOMO, CoreTestNodes.anytls())
        assertEquals(CORE_MIHOMO, unchanged.core)
        assertFalse(unchanged.changed)
    }

    // ---- 整体性质

    // 标注只改字段的协议族 / 携带方式与 core 的写法，不改变 K1 之前的承载；再调用一次什么都不改
    @Test
    fun `随机存量节点：承载不变且幂等`() {
        val random = Random(20261006)
        var marked = 0
        repeat(20_000) {
            val node = CoreTestNodes.randomSelectable(random)
            val bean = node.bean.clone()
            val before = LegacyCoreSelection.carrier(node.type, node.core, node.bean, node.global)
            val first = upgrade(node.type, node.core, bean, node.global)
            if (first.changed) marked++
            assertEquals(before, LegacyCoreSelection.carrier(node.type, first.core, bean, node.global))
            val again = upgrade(node.type, first.core, bean, node.global)
            assertFalse(again.changed)
            assertEquals(first.core, again.core)
            // 改了 bean 就一定报告了改动
            assertEquals(first.changes.any { it != CORE_NORMALIZED }, bean != node.bean)
        }
        assertTrue(marked > 0)
    }
}
