package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.CoreConflict.MIHOMO_UTLS_FINGERPRINT
import io.nekohasekai.sagernet.fmt.CoreConflict.MIHOMO_V2RAY_PROTOCOL
import io.nekohasekai.sagernet.fmt.CoreConflict.PROTOCOL_CERTIFICATE_PIN
import io.nekohasekai.sagernet.fmt.CoreConflict.PROTOCOL_MLDSA65_VERIFY
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_CERTIFICATE_PIN
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_MLDSA65_VERIFY
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_MUX_COOL
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_QUIC_REALITY
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_QUIC_UTLS
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_QUIC_WITHOUT_TLS
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_REALITY_ECH
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_UTLS_FINGERPRINT
import io.nekohasekai.sagernet.fmt.CoreConflict.SING_BOX_VLESS_FLOW
import io.nekohasekai.sagernet.fmt.CoreConflict.TRANSPORT_UNKNOWN
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_ALLOW_INSECURE
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_ANYTLS
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_ECH_AUTO_QUERY
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_PACKETADDR
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_REALITY_TRANSPORT
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_REALITY_UTLS_FINGERPRINT
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_SING_MUX
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_TRANSPORT_H2
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_TRANSPORT_QUIC
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_UTLS_FINGERPRINT
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_VLESS_FLOW
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_WS_EARLY_DATA_HEADER
import io.nekohasekai.sagernet.fmt.CoreConflict.XRAY_WS_EARLY_DATA_PATH
import io.nekohasekai.sagernet.fmt.CoreTestNodes.anytls
import io.nekohasekai.sagernet.fmt.CoreTestNodes.reality
import io.nekohasekai.sagernet.fmt.CoreTestNodes.trojan
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vless
import io.nekohasekai.sagernet.fmt.CoreTestNodes.vmess
import io.nekohasekai.sagernet.fmt.DialCore.MIHOMO
import io.nekohasekai.sagernet.fmt.DialCore.SING_BOX
import io.nekohasekai.sagernet.fmt.DialCore.XRAY
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// 能力表的组合用例（plan.md K1）：每个用例断言选中的核心，或被拒绝时列出的冲突与字段
class CoreSelectionTest {

    private fun decide(bean: AbstractBean, core: Int = CORE_AUTO, global: Boolean = false): CoreDecision {
        val node = CoreTestNodes.node(bean, core, global)
        return decideCore(node.type, node.core, node.bean, node.global)
    }

    private fun assertSelected(expected: DialCore, decision: CoreDecision) {
        assertTrue("应选 $expected，实际 $decision", decision is CoreDecision.Selected && decision.core == expected)
    }

    private fun assertRejected(decision: CoreDecision, vararg expected: CoreConflict): CoreDecision.Rejected {
        assertTrue("应拒绝，实际 $decision", decision is CoreDecision.Rejected)
        decision as CoreDecision.Rejected
        assertEquals(expected.toSet(), decision.conflicts.map { it.id }.toSet())
        return decision
    }

    private fun StandardV2RayBean.mux(type: Int) {
        enableMux = true
        muxType = type
    }

    // ---- REALITY 与传输

    @Test
    fun `REALITY 配 tcp 或 grpc 时 Xray 优先`() {
        for (transport in listOf("tcp", "grpc")) {
            assertSelected(XRAY, decide(vless { reality(); type = transport }))
            assertSelected(XRAY, decide(vmess { reality(); type = transport }))
            assertSelected(XRAY, decide(trojan { reality(); type = transport }))
        }
    }

    @Test
    fun `REALITY 配 ws 或 httpupgrade 时 Xray 不行，自动落到 sing-box`() {
        for (transport in listOf("ws", "httpupgrade")) {
            assertSelected(SING_BOX, decide(vless { reality(); type = transport }))
            val rejected = assertRejected(decide(vless { reality(); type = transport }, CORE_XRAY), XRAY_REALITY_TRANSPORT)
            assertEquals(setOf(ProfileField.TRANSPORT, ProfileField.REALITY_PUBLIC_KEY), rejected.fields)
        }
    }

    @Test
    fun `REALITY 配 h2 只有 sing-box`() {
        assertSelected(SING_BOX, decide(vless { reality(); type = "http" }))
        assertRejected(decide(vless { reality(); type = "http" }, CORE_XRAY), XRAY_TRANSPORT_H2)
    }

    @Test
    fun `REALITY 配 quic 没有可用核心`() {
        val rejected = assertRejected(decide(vless { reality(); type = "quic" }), SING_BOX_QUIC_REALITY, XRAY_TRANSPORT_QUIC)
        // 自动时按偏好顺序（Xray 在前）列出每个候选核心的冲突
        assertEquals(listOf(XRAY, SING_BOX), rejected.byCore().keys.toList())
        assertEquals(false, rejected.manual)
    }

    @Test
    fun `quic 的 TLS 组合`() {
        assertSelected(SING_BOX, decide(vless { type = "quic" }))
        assertRejected(decide(vless { type = "quic"; utlsFingerprint = "chrome" }), SING_BOX_QUIC_UTLS, XRAY_TRANSPORT_QUIC)
        assertRejected(decide(vmess { type = "quic"; security = "none" }), SING_BOX_QUIC_WITHOUT_TLS, XRAY_TRANSPORT_QUIC)
    }

    @Test
    fun `REALITY 节点的 allowInsecure 不算冲突`() {
        assertSelected(XRAY, decide(vless { reality(); allowInsecure = true }))
        assertSelected(XRAY, decide(vless { reality() }, global = true))
        assertSelected(XRAY, decide(vless { reality(); allowInsecure = true }, CORE_XRAY))
    }

    @Test
    fun `非 REALITY 的 allowInsecure 不能走 Xray`() {
        assertSelected(SING_BOX, decide(vless { allowInsecure = true }))
        val rejected = assertRejected(decide(vless {}, CORE_XRAY, global = true), XRAY_ALLOW_INSECURE)
        assertEquals(setOf(ProfileField.ALLOW_INSECURE, ProfileField.GLOBAL_ALLOW_INSECURE), rejected.fields)
        // 有证书指纹时固定优先，allowInsecure 不写
        assertSelected(XRAY, decide(vless { certificateFingerprint = CoreTestNodes.PIN; allowInsecure = true }))
    }

    // ---- 证书固定与各 TLS 形态

    @Test
    fun `证书固定配标准 TLS、uTLS、ECH 内联都走 Xray`() {
        val shapes: List<StandardV2RayBean.() -> Unit> = listOf(
            {},
            { utlsFingerprint = "firefox" },
            { enableECH = true; echConfig = CoreTestNodes.ECH },
            { certificates = CoreTestNodes.CERT },
        )
        for (shape in shapes) {
            assertSelected(XRAY, decide(vless { certificateFingerprint = CoreTestNodes.PIN; shape() }))
            assertSelected(XRAY, decide(vmess { certificateFingerprint = CoreTestNodes.PIN; shape() }))
            assertSelected(XRAY, decide(trojan { certificateFingerprint = CoreTestNodes.PIN; shape() }))
        }
    }

    @Test
    fun `证书固定在 sing-box 上是冲突`() {
        val rejected = assertRejected(
            decide(vmess { certificateFingerprint = CoreTestNodes.PIN }, CORE_SING_BOX), SING_BOX_CERTIFICATE_PIN,
        )
        assertEquals(setOf(ProfileField.CERTIFICATE_FINGERPRINT), rejected.fields)
        assertEquals(true, rejected.manual)
        // 关掉 TLS 后残留的指纹不算
        assertSelected(SING_BOX, decide(vmess { security = "none"; certificateFingerprint = CoreTestNodes.PIN }))
    }

    @Test
    fun `证书固定配 REALITY：Xray 忽略指纹，sing-box 仍是冲突`() {
        assertSelected(XRAY, decide(vless { reality(); certificateFingerprint = CoreTestNodes.PIN }))
        assertRejected(
            decide(vless { reality(); certificateFingerprint = CoreTestNodes.PIN }, CORE_SING_BOX), SING_BOX_CERTIFICATE_PIN,
        )
    }

    @Test
    fun `证书固定配 Xray 没有的传输时没有可用核心`() {
        assertRejected(
            decide(vmess { certificateFingerprint = CoreTestNodes.PIN; type = "quic" }),
            SING_BOX_CERTIFICATE_PIN, XRAY_TRANSPORT_QUIC,
        )
        assertRejected(
            decide(trojan { certificateFingerprint = CoreTestNodes.PIN; type = "http" }),
            SING_BOX_CERTIFICATE_PIN, XRAY_TRANSPORT_H2,
        )
    }

    @Test
    fun `AnyTLS 的证书固定走 mihomo`() {
        assertSelected(MIHOMO, decide(anytls { certificateFingerprint = CoreTestNodes.PIN }))
        assertRejected(decide(anytls { certificateFingerprint = CoreTestNodes.PIN }, CORE_SING_BOX), SING_BOX_CERTIFICATE_PIN)
    }

    // ---- ECH

    @Test
    fun `ECH 自动查询只有 sing-box 能表达`() {
        assertSelected(SING_BOX, decide(vless { enableECH = true }))
        val rejected = assertRejected(decide(vless { enableECH = true }, CORE_XRAY), XRAY_ECH_AUTO_QUERY)
        assertEquals(GapKind.GENERATOR, rejected.conflicts.single().id.kind)
        assertRejected(
            decide(vless { enableECH = true; certificateFingerprint = CoreTestNodes.PIN }),
            SING_BOX_CERTIFICATE_PIN, XRAY_ECH_AUTO_QUERY,
        )
    }

    @Test
    fun `ECH 内联两个核心都行`() {
        assertSelected(SING_BOX, decide(vless { enableECH = true; echConfig = CoreTestNodes.ECH }))
        assertSelected(XRAY, decide(vless { enableECH = true; echConfig = CoreTestNodes.ECH }, CORE_XRAY))
        // 没开 enableECH 时 echConfig 不生效
        assertSelected(XRAY, decide(vless { echConfig = CoreTestNodes.ECH }, CORE_XRAY))
    }

    @Test
    fun `ECH 配 REALITY：sing-box 不行，Xray 忽略`() {
        for (configure in listOf<StandardV2RayBean.() -> Unit>({ enableECH = true }, { enableECH = true; echConfig = CoreTestNodes.ECH })) {
            assertSelected(XRAY, decide(vless { reality(); configure() }))
            assertRejected(decide(vless { reality(); configure() }, CORE_SING_BOX), SING_BOX_REALITY_ECH)
        }
    }

    @Test
    fun `AnyTLS 的 ECH 自动查询走 sing-box，手动 mihomo 也行`() {
        assertSelected(SING_BOX, decide(anytls { enableECH = true }))
        assertSelected(MIHOMO, decide(anytls { enableECH = true }, CORE_MIHOMO))
    }

    // ---- mldsa65Verify

    @Test
    fun `mldsa65Verify 只有 Xray 支持`() {
        assertSelected(XRAY, decide(vless { reality(mldsa = true) }))
        assertSelected(XRAY, decide(vmess { reality(mldsa = true) }))
        assertSelected(XRAY, decide(trojan { reality(mldsa = true) }))
        val rejected = assertRejected(decide(vless { reality(mldsa = true) }, CORE_SING_BOX), SING_BOX_MLDSA65_VERIFY)
        assertEquals(setOf(ProfileField.MLDSA65_VERIFY), rejected.fields)
        // Xray 不能配的传输：没有可用核心
        assertRejected(decide(vless { reality(mldsa = true); type = "ws" }), XRAY_REALITY_TRANSPORT, SING_BOX_MLDSA65_VERIFY)
        // 关掉 TLS 后残留的字段不算
        assertSelected(SING_BOX, decide(vless { reality(mldsa = true); security = "none" }))
    }

    // 原 Mldsa65VerifyUnsupportedTest 的用例（函数已并入能力表）：只有 REALITY 真正生效且填了 mldsa65Verify 才算要求
    @Test
    fun `mldsa65Verify 只在 REALITY 生效时才算要求`() {
        // 关掉 TLS 后残留的 REALITY 字段不算（Trojan 同 VLESS）
        assertSelected(SING_BOX, decide(trojan { reality(mldsa = true); security = "none" }))
        // 没有公钥即没开 REALITY
        assertSelected(SING_BOX, decide(vless { reality(mldsa = true); realityPubKey = "" }))
        // mldsa65Verify 是空白
        val blank = trojan { reality(); realityMldsa65Verify = " " }
        assertTrue(!coreRequirements(TYPE_TROJAN, blank, false).mldsa65Verify)
        // 不是 V2Ray 系的协议没有这个字段
        assertEquals(CoreDecision.Fixed, decide(io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply { initializeDefaultValues() }))
    }

    // ---- mux 与 packet encoding

    @Test
    fun `mux 协议族决定核心`() {
        assertSelected(SING_BOX, decide(vmess { mux(MUX_SMUX) }))
        assertSelected(XRAY, decide(vmess { mux(MUX_COOL) }))
        assertSelected(XRAY, decide(trojan { mux(MUX_COOL) }))
        assertRejected(decide(vless { mux(MUX_COOL) }, CORE_SING_BOX), SING_BOX_MUX_COOL)
        assertRejected(decide(vless { mux(MUX_H2MUX) }, CORE_XRAY), XRAY_SING_MUX)
        // REALITY 偏好 Xray，但 sing-mux 只能走 sing-box
        assertSelected(SING_BOX, decide(vless { reality(); mux(MUX_SMUX) }))
        assertSelected(SING_BOX, decide(trojan { reality(); mux(MUX_SMUX) }))
        // 没有完整路径
        assertRejected(decide(vless { reality(mldsa = true); mux(MUX_SMUX) }), XRAY_SING_MUX, SING_BOX_MLDSA65_VERIFY)
        assertRejected(
            decide(vmess { certificateFingerprint = CoreTestNodes.PIN; mux(MUX_SMUX) }), XRAY_SING_MUX, SING_BOX_CERTIFICATE_PIN,
        )
        assertRejected(decide(vless { reality(); type = "ws"; mux(MUX_COOL) }), XRAY_REALITY_TRANSPORT, SING_BOX_MUX_COOL)
    }

    @Test
    fun `mux 不生效时不算要求`() {
        assertSelected(SING_BOX, decide(vless { muxType = MUX_COOL }, CORE_SING_BOX))
        // vision 流控时两个核心都不写 mux
        assertSelected(SING_BOX, decide(vless { encryption = StandardV2RayBean.FLOW_VISION; mux(MUX_COOL) }, CORE_SING_BOX))
        assertSelected(XRAY, decide(vless { reality(); encryption = StandardV2RayBean.FLOW_VISION; mux(MUX_SMUX) }))
    }

    @Test
    fun `packet encoding 与协议`() {
        assertSelected(SING_BOX, decide(vless { reality(); packetEncoding = 1 }))
        assertRejected(decide(vless { packetEncoding = 1 }, CORE_XRAY), XRAY_PACKETADDR)
        assertRejected(decide(vless { reality(mldsa = true); packetEncoding = 1 }), XRAY_PACKETADDR, SING_BOX_MLDSA65_VERIFY)
        assertSelected(XRAY, decide(vless { reality(); packetEncoding = 2 }))
        assertSelected(XRAY, decide(vless { packetEncoding = 2 }, CORE_XRAY))
        // Trojan 没有 packet encoding，两个核心都不读
        assertSelected(XRAY, decide(trojan { reality(); packetEncoding = 1 }))
        assertEquals(null, coreRequirements(TYPE_TROJAN, trojan { packetEncoding = 1 }, false).packetEncoding)
    }

    // ---- uTLS 指纹

    @Test
    fun `uTLS 指纹按各核心的名单`() {
        assertSelected(XRAY, decide(vless { utlsFingerprint = "randomizednoalpn" }))
        assertSelected(XRAY, decide(vless { utlsFingerprint = "Chrome" }))
        assertRejected(decide(vless { utlsFingerprint = "netscape" }), SING_BOX_UTLS_FINGERPRINT, XRAY_UTLS_FINGERPRINT)
        assertSelected(MIHOMO, decide(anytls { utlsFingerprint = "chrome120" }))
        assertSelected(MIHOMO, decide(anytls { utlsFingerprint = "none" }))
        assertRejected(decide(anytls { utlsFingerprint = "netscape" }), SING_BOX_UTLS_FINGERPRINT, MIHOMO_UTLS_FINGERPRINT)
        // REALITY 不能用 unsafe：Xray 拒绝，sing-box 不认
        assertRejected(
            decide(vless { reality(); utlsFingerprint = "unsafe" }), XRAY_REALITY_UTLS_FINGERPRINT, SING_BOX_UTLS_FINGERPRINT,
        )
        // 关掉 TLS 时指纹不生效
        assertSelected(SING_BOX, decide(vless { security = "none"; utlsFingerprint = "netscape" }))
    }

    // ---- VLESS flow 与 ws early data

    @Test
    fun `VLESS flow`() {
        assertSelected(SING_BOX, decide(vless { encryption = StandardV2RayBean.FLOW_VISION }))
        assertSelected(XRAY, decide(vless { encryption = "xtls-rprx-vision-udp443" }))
        assertRejected(decide(vless { encryption = "xtls-rprx-direct" }), SING_BOX_VLESS_FLOW, XRAY_VLESS_FLOW)
        assertSelected(SING_BOX, decide(vless { encryption = "auto" }, CORE_SING_BOX))
    }

    @Test
    fun `ws early data 的携带方式`() {
        val path: StandardV2RayBean.() -> Unit = { type = "ws"; path = "/ws"; wsMaxEarlyData = 2048 }
        assertSelected(SING_BOX, decide(vless(path)))
        val rejected = assertRejected(decide(vless(path), CORE_XRAY), XRAY_WS_EARLY_DATA_PATH)
        assertEquals(GapKind.SEMANTICS, rejected.conflicts.single().id.kind)
        assertSelected(XRAY, decide(vless { path(); earlyDataHeaderName = "Sec-WebSocket-Protocol" }, CORE_XRAY))
        assertSelected(XRAY, decide(vless { path(); this.path = "/ws?ed=2048"; wsMaxEarlyData = 0 }, CORE_XRAY))
        assertRejected(decide(vless { path(); earlyDataHeaderName = "X-Early-Data" }, CORE_XRAY), XRAY_WS_EARLY_DATA_HEADER)
        // 没开 early data 时头名不算要求
        assertSelected(XRAY, decide(vless { type = "ws"; earlyDataHeaderName = "X-Early-Data" }, CORE_XRAY))
    }

    // ---- AnyTLS

    @Test
    fun `AnyTLS 选核`() {
        assertSelected(SING_BOX, decide(anytls()))
        assertSelected(SING_BOX, decide(anytls { allowInsecure = true }))
        // 暂定：带 certificates 时 mihomo 优先，手动选哪个都允许
        assertSelected(MIHOMO, decide(anytls { certificates = CoreTestNodes.CERT }))
        assertSelected(SING_BOX, decide(anytls { certificates = CoreTestNodes.CERT }, CORE_SING_BOX))
        assertSelected(MIHOMO, decide(anytls(), CORE_MIHOMO))
        val rejected = assertRejected(decide(anytls(), CORE_XRAY), XRAY_ANYTLS)
        assertEquals(setOf(ProfileField.CORE), rejected.fields)
    }

    // ---- 手动核心值

    @Test
    fun `手动值不是该协议的候选核心`() {
        assertRejected(decide(vmess(), CORE_MIHOMO), MIHOMO_V2RAY_PROTOCOL)
        assertRejected(decide(trojan(), CORE_MIHOMO), MIHOMO_V2RAY_PROTOCOL)
        assertRejected(decide(vless(), 7), CoreConflict.CORE_VALUE_UNKNOWN)
        assertRejected(decide(vless(), -1), CoreConflict.CORE_VALUE_UNKNOWN)
        assertSelected(XRAY, decide(trojan(), CORE_XRAY))
    }

    @Test
    fun `未知传输两个核心都不行`() {
        val bean = vless().apply { type = "xhttp" }
        val rejected = assertRejected(decideCore(coreRequirements(TYPE_VMESS, bean, false), CORE_AUTO), TRANSPORT_UNKNOWN)
        assertEquals(listOf(SING_BOX, XRAY), rejected.conflicts.map { it.core })
        assertEquals("xhttp", rejected.conflicts.first().value)
    }

    @Test
    fun `非法的 mux 与 packet encoding 取值`() {
        assertRejected(decide(vmess { mux(9) }), CoreConflict.MUX_TYPE_UNKNOWN)
        assertRejected(decide(vmess { packetEncoding = 5 }), CoreConflict.PACKET_ENCODING_UNKNOWN)
    }

    // ---- 不能选核的协议：两条跨协议检查与 1.8.0-a3 结论相同

    @Test
    fun `不能选核的协议`() {
        assertEquals(CoreDecision.Fixed, decide(HysteriaBean().apply { initializeDefaultValues() }))
        assertRejected(decide(HysteriaBean().apply { initializeDefaultValues(); certificateFingerprint = CoreTestNodes.PIN }), PROTOCOL_CERTIFICATE_PIN)
        assertRejected(decide(TuicBean().apply { initializeDefaultValues(); certificateFingerprint = CoreTestNodes.PIN }), PROTOCOL_CERTIFICATE_PIN)
        val http = HttpBean().apply { initializeDefaultValues(); reality(mldsa = true) }
        assertRejected(decide(http), PROTOCOL_MLDSA65_VERIFY)
        assertRejected(decide(HysteriaBean().apply { initializeDefaultValues() }, CORE_XRAY), CoreConflict.CORE_NOT_SELECTABLE)
    }

    @Test
    fun `不能选核的协议与旧规则的跨协议检查结论相同`() {
        val random = Random(20261007)
        repeat(10_000) {
            // 规范化之后不能选核的协议 core 都是自动
            val node = CoreTestNodes.randomOther(random).copy(core = CORE_AUTO)
            val decision = decideCore(node.type, node.core, node.bean, node.global)
            val conflicts = (decision as? CoreDecision.Rejected)?.conflicts?.map { it.id }?.toSet().orEmpty()
            assertEquals(
                LegacyCoreSelection.certificatePinUnsupported(node.type, node.core, node.bean, node.global),
                PROTOCOL_CERTIFICATE_PIN in conflicts,
            )
            assertEquals(
                LegacyCoreSelection.mldsa65VerifyUnsupported(node.type, node.core, node.bean, node.global),
                PROTOCOL_MLDSA65_VERIFY in conflicts,
            )
        }
    }

    // ---- 构建时的拒绝原因（requireBuildableHop 抛出的文本）

    @Test
    fun `拒绝原因：手动指定写明核心，列出字段键与原因`() {
        val rejected = assertRejected(decide(vmess { allowInsecure = true; packetEncoding = 1 }, CORE_XRAY), XRAY_ALLOW_INSECURE, XRAY_PACKETADDR)
        assertEquals(
            "the manually chosen core Xray cannot run this profile: " +
                "[allowInsecure, globalAllowInsecure] Xray removed allowInsecure; pin the certificate fingerprint instead; " +
                "[packetEncoding] Xray has no packetaddr packet encoding",
            rejected.message(CORE_XRAY),
        )
        // 协议本身不能用这个核心
        assertEquals(
            "the manually chosen core mihomo cannot run this profile: [profileCore] This app cannot run VMess, VLESS or Trojan on mihomo",
            assertRejected(decide(vmess(), CORE_MIHOMO), MIHOMO_V2RAY_PROTOCOL).message(CORE_MIHOMO),
        )
        // 不认识的核心值
        assertEquals(
            "the manually chosen core 7 cannot run this profile: [profileCore] Unknown core (7)",
            assertRejected(decide(vless(), 7), CoreConflict.CORE_VALUE_UNKNOWN).message(7),
        )
    }

    @Test
    fun `拒绝原因：自动选核按核心分组，每组以核心名开头，带取值`() {
        val rejected = assertRejected(
            decide(vless { type = "quic"; utlsFingerprint = "chrome"; certificateFingerprint = CoreTestNodes.PIN }),
            SING_BOX_QUIC_UTLS, SING_BOX_CERTIFICATE_PIN, XRAY_TRANSPORT_QUIC,
        )
        assertEquals(
            "no core can fully run this profile. " +
                "sing-box: [certificateFingerprint] sing-box cannot pin a whole-certificate SHA-256 (it only pins public keys); " +
                "[type, utlsFingerprint] sing-box cannot use a uTLS fingerprint over QUIC: every connection fails. " +
                "Xray: [type] Xray removed the QUIC transport",
            rejected.message(CORE_AUTO),
        )
        val fingerprint = assertRejected(decide(anytls { utlsFingerprint = "netscape" }), SING_BOX_UTLS_FINGERPRINT, MIHOMO_UTLS_FINGERPRINT)
        assertEquals(
            "no core can fully run this profile. " +
                "sing-box: [utlsFingerprint] sing-box does not know this uTLS fingerprint (netscape). " +
                "mihomo: [utlsFingerprint] mihomo does not know this uTLS fingerprint and would silently use plain Go TLS (netscape)",
            fingerprint.message(CORE_AUTO),
        )
    }

    @Test
    fun `拒绝原因：不能选核的协议只列冲突`() {
        val pinned = HysteriaBean().apply { initializeDefaultValues(); certificateFingerprint = CoreTestNodes.PIN }
        assertEquals(
            "this profile cannot run as configured: [certificateFingerprint] The core for this protocol cannot pin certificates",
            assertRejected(decide(pinned), PROTOCOL_CERTIFICATE_PIN).message(CORE_AUTO),
        )
        // 手动值也报出来，核心名按取值写
        assertEquals(
            "the manually chosen core Xray cannot run this profile: " +
                "[profileCore] The core cannot be chosen for this protocol (2); " +
                "[certificateFingerprint] The core for this protocol cannot pin certificates",
            assertRejected(decide(pinned, CORE_XRAY), CoreConflict.CORE_NOT_SELECTABLE, PROTOCOL_CERTIFICATE_PIN)
                .message(CORE_XRAY),
        )
    }

    // ---- 整体性质

    // 随机节点：判定不崩（能力表没有漏格），手动时只评估指定核心，自动时冲突按偏好顺序列出
    @Test
    fun `随机节点的判定形状`() {
        val random = Random(20261008)
        repeat(30_000) {
            val node = CoreTestNodes.randomSelectable(random)
            val requirements = coreRequirements(node.type, node.bean, node.global)
            when (val decision = decideCore(requirements, node.core)) {
                is CoreDecision.Selected -> {
                    assertEquals(node.core != CORE_AUTO, decision.manual)
                    assertTrue(coreConflicts(decision.core, requirements).isEmpty())
                    if (!decision.manual) {
                        // 偏好顺序里排在前面的核心都有冲突
                        val order = preferenceOrder(requirements)
                        for (earlier in order.takeWhile { it != decision.core }) {
                            assertTrue(coreConflicts(earlier, requirements).isNotEmpty())
                        }
                    }
                }

                is CoreDecision.Rejected -> {
                    if (decision.manual) {
                        val cores = decision.conflicts.map { it.core }.toSet()
                        assertTrue("$cores", cores.size == 1)
                    } else {
                        assertEquals(preferenceOrder(requirements), decision.byCore().keys.toList())
                    }
                }

                CoreDecision.Fixed -> error("能选核的协议不会是 Fixed")
            }
        }
    }
}
