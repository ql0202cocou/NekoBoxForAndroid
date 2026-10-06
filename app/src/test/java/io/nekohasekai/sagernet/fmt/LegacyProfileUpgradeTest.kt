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
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.UTLS_FIREFOX
import io.nekohasekai.sagernet.fmt.LegacyProfileChange.WS_EARLY_DATA_HEADER
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStreamSettings
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import io.nekohasekai.sagernet.fmt.v2ray.resolveWsEarlyData
import moe.matsuri.nb4a.SingBoxOptions.V2RayTransportOptions_WebsocketOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL as MUX_COOL_VALUE

// 存量节点的一次性升级标注（LegacyProfileUpgrade.kt）：四条规则逐类的表格测试，外加幂等与「不改变 K1 之前的承载」
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
        // 这个节点会换到 sing-box，规则 d 另补指纹；这里只看 mux
        assertFalse(MUX_COOL in upgrade(TYPE_VMESS, CORE_AUTO, bean).changes)
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
        // 填了指纹，只看规则 b（没填时规则 d 另补 firefox，见下面规则 d 的用例）
        val bean = ws { utlsFingerprint = "chrome" }
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

    // ---- 规则 d：uTLS 指纹补 firefox（维护者 2026-10-06 决定）

    private class FingerprintCase(
        val name: String,
        val core: Int,
        val global: Boolean,
        val filled: Boolean,
        val bean: () -> AbstractBean,
    ) {
        val type get() = CoreTestNodes.node(bean()).type
    }

    private val fingerprintCases = listOf(
        FingerprintCase("VLESS 自动，TLS，tcp", CORE_AUTO, false, true) { CoreTestNodes.vless() },
        FingerprintCase("VLESS 自动，TLS，ws", CORE_AUTO, false, true) { CoreTestNodes.vless { type = "ws"; path = "/ws" } },
        FingerprintCase("VLESS 自动，TLS，grpc", CORE_AUTO, false, true) { CoreTestNodes.vless { type = "grpc"; path = "svc" } },
        FingerprintCase("VLESS 自动，TLS，httpupgrade", CORE_AUTO, false, true) { CoreTestNodes.vless { type = "httpupgrade" } },
        FingerprintCase("VLESS 自动，TLS + vision 流控（sing-box 也支持）", CORE_AUTO, false, true) {
            CoreTestNodes.vless { encryption = StandardV2RayBean.FLOW_VISION }
        },
        FingerprintCase("VLESS 自动，TLS + packetaddr（Xray 没有，换到 sing-box）", CORE_AUTO, false, true) {
            CoreTestNodes.vless { packetEncoding = 1 }
        },
        FingerprintCase("VLESS 自动，TLS + ECH 自动查询", CORE_AUTO, false, true) { CoreTestNodes.vless { enableECH = true } },
        FingerprintCase("VLESS 自动，TLS + certificates", CORE_AUTO, false, true) {
            CoreTestNodes.vless { certificates = CoreTestNodes.CERT }
        },
        FingerprintCase("VLESS 自动，TLS + 开了 mux（标为 Mux.Cool，留在 Xray）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { enableMux = true; muxType = MUX_SMUX }
        },
        FingerprintCase("VLESS 自动，TLS + 证书指纹（留在 Xray）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { certificateFingerprint = CoreTestNodes.PIN }
        },
        FingerprintCase("VLESS 自动，TLS + 只有 Xray 认的 flow（留在 Xray）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { encryption = "xtls-rprx-vision-udp443" }
        },
        FingerprintCase("VLESS 自动，填了指纹 chrome", CORE_AUTO, false, false) { CoreTestNodes.vless { utlsFingerprint = "chrome" } },
        FingerprintCase("VLESS 自动，填了只有 Xray 认的指纹", CORE_AUTO, false, false) {
            CoreTestNodes.vless { utlsFingerprint = "hellochrome_131" }
        },
        FingerprintCase("VLESS 手动 Xray", CORE_XRAY, false, false) { CoreTestNodes.vless() },
        FingerprintCase("VLESS 手动 sing-box（当时就走 sing-box）", CORE_SING_BOX, false, false) { CoreTestNodes.vless() },
        FingerprintCase("VLESS core = 3，规范成手动 sing-box", CORE_MIHOMO, false, false) { CoreTestNodes.vless() },
        FingerprintCase("VLESS 自动，REALITY（两个核心没填都按 chrome）", CORE_AUTO, false, false) { CoreTestNodes.vless { reality() } },
        // 以下三条冻结规则判为 Xray、K1 换到 sing-box，但 REALITY 不补（前提另见下面的 REALITY 用例）
        FingerprintCase("VLESS 自动，REALITY + ws（换到 sing-box）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { reality(); type = "ws" }
        },
        FingerprintCase("VLESS 自动，REALITY + httpupgrade（换到 sing-box）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { reality(); type = "httpupgrade" }
        },
        FingerprintCase("VLESS 自动，REALITY + packetaddr（换到 sing-box）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { reality(); packetEncoding = 1 }
        },
        FingerprintCase("VLESS 自动，没开 TLS", CORE_AUTO, false, false) { CoreTestNodes.vless { security = "none" } },
        FingerprintCase("VLESS 自动，TLS + 节点 allowInsecure（当时走 sing-box）", CORE_AUTO, false, false) {
            CoreTestNodes.vless { allowInsecure = true }
        },
        FingerprintCase("VLESS 自动，TLS + 全局 allowInsecure（当时走 sing-box）", CORE_AUTO, true, false) { CoreTestNodes.vless() },
        FingerprintCase("VLESS 自动，quic（当时走 sing-box）", CORE_AUTO, false, false) { CoreTestNodes.vless { type = "quic" } },
        FingerprintCase("VMess 自动，TLS 没有证书指纹（当时走 sing-box）", CORE_AUTO, false, false) { CoreTestNodes.vmess() },
        FingerprintCase("VMess 自动，TLS + 证书指纹（留在 Xray）", CORE_AUTO, false, false) {
            CoreTestNodes.vmess { certificateFingerprint = CoreTestNodes.PIN }
        },
        FingerprintCase("VMess 自动，关了 TLS、残留证书指纹（换到 sing-box，但没有 TLS）", CORE_AUTO, false, false) {
            CoreTestNodes.vmess { certificateFingerprint = CoreTestNodes.PIN; security = "none" }
        },
        FingerprintCase("Trojan（当时一律 sing-box）", CORE_AUTO, false, false) { CoreTestNodes.trojan() },
        FingerprintCase("AnyTLS 没填指纹", CORE_AUTO, false, false) { CoreTestNodes.anytls() },
    )

    @Test
    fun `规则 d：会换到 sing-box、没填指纹的 TLS 节点补 firefox`() {
        for (case in fingerprintCases) {
            val bean = case.bean()
            val before = (bean as? StandardV2RayBean)?.utlsFingerprint ?: (bean as? AnyTLSBean)?.utlsFingerprint
            val result = upgrade(case.type, case.core, bean, case.global)
            assertEquals(case.name, case.filled, UTLS_FIREFOX in result.changes)
            val after = (bean as? StandardV2RayBean)?.utlsFingerprint ?: (bean as? AnyTLSBean)?.utlsFingerprint
            assertEquals(case.name, if (case.filled) "firefox" else before, after)
            // 补了指纹之后仍由 sing-box 承载（firefox 在 sing-box 名单里，也不触发 quic + uTLS 之类的组合规则）
            if (case.filled) {
                assertEquals(case.name, CoreDecision.Selected(DialCore.SING_BOX, false), decideCore(case.type, result.core, bean, case.global))
            }
        }
    }

    // REALITY 排除的前提：这三种节点冻结规则走 Xray、K1 自动选核换到 sing-box，其余条件（TLS、没填指纹、自动）都满足，
    // 只靠 REALITY 这一条不补。sing-box 生成器给没填指纹的 REALITY 补 chrome，与 Xray 没填时相同
    @Test
    fun `规则 d：换到 sing-box 的 REALITY 节点不补`() {
        val cases = listOf<() -> VMessBean>(
            { CoreTestNodes.vless { reality(); type = "ws" } },
            { CoreTestNodes.vless { reality(); type = "httpupgrade" } },
            { CoreTestNodes.vless { reality(); packetEncoding = 1 } },
        )
        for (make in cases) {
            val bean = make()
            val where = "type=${bean.type} packetEncoding=${bean.packetEncoding}"
            assertEquals(where, LegacyCoreSelection.Carrier.XRAY, LegacyCoreSelection.carrier(TYPE_VMESS, CORE_AUTO, bean, false))
            assertEquals(where, CoreDecision.Selected(DialCore.SING_BOX, false), decideCore(TYPE_VMESS, CORE_AUTO, bean, false))
            assertEquals(where, "", bean.utlsFingerprint)
            val result = upgrade(TYPE_VMESS, CORE_AUTO, bean)
            assertFalse(where, UTLS_FIREFOX in result.changes)
            assertEquals(where, "", bean.utlsFingerprint)
        }
    }

    @Test
    fun `规则 d：与规则 b 同时生效时按改写后的节点判断`() {
        val bean = ws()
        assertEquals(setOf(WS_EARLY_DATA_HEADER, UTLS_FIREFOX), upgrade(TYPE_VMESS, CORE_AUTO, bean).changes)
        assertEquals("Sec-WebSocket-Protocol", bean.earlyDataHeaderName)
        assertEquals("firefox", bean.utlsFingerprint)
    }

    @Test
    fun `规则 d：firefox 在三个核心的 uTLS 名单里都认`() {
        assertEquals("firefox", LEGACY_UTLS_FINGERPRINT)
        assertTrue(SING_BOX_UTLS_FINGERPRINTS.accepts(LEGACY_UTLS_FINGERPRINT))
        assertTrue(XRAY_UTLS_FINGERPRINTS.accepts(LEGACY_UTLS_FINGERPRINT))
        assertTrue(MIHOMO_UTLS_FINGERPRINTS.accepts(LEGACY_UTLS_FINGERPRINT))
    }

    // ---- 整体性质

    // 标注只改字段的协议族 / 携带方式与 core 的写法，不改变 K1 之前的承载；再调用一次什么都不改
    @Test
    fun `随机存量节点：承载不变且幂等`() {
        val random = Random(20261006)
        var marked = 0
        var fingerprinted = 0
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
            // 规则 d 补过指纹的，K1 的选核仍是 sing-box；没补的，凡是自动选核、当时走 Xray、换到 sing-box 的 TLS
            // （非 REALITY）节点都已经填了指纹
            val decision = decideCore(node.type, first.core, bean, node.global)
            // REALITY 节点不论换不换核都不补
            if (bean is StandardV2RayBean && !bean.realityPubKey.isNullOrBlank()) {
                assertFalse(UTLS_FIREFOX in first.changes)
            }
            if (UTLS_FIREFOX in first.changes) {
                fingerprinted++
                assertEquals(CoreDecision.Selected(DialCore.SING_BOX, false), decision)
            } else if (bean is StandardV2RayBean && first.core == CORE_AUTO && bean.security == "tls" &&
                bean.realityPubKey.isNullOrBlank() && before == LegacyCoreSelection.Carrier.XRAY &&
                decision == CoreDecision.Selected(DialCore.SING_BOX, false)
            ) {
                assertFalse(bean.utlsFingerprint.isNullOrBlank())
            }
        }
        assertTrue(marked > 0)
        assertTrue(fingerprinted > 0)
    }
}
