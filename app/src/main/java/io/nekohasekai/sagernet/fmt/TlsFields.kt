package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.requireValidReality
import io.nekohasekai.sagernet.ktx.blankAsNull
import moe.matsuri.nb4a.SingBoxOptions.AnyTLSCompatibilityTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundRealityOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundUTLSOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.isAnyTlsCompatibilityTest
import moe.matsuri.nb4a.utils.echAsPem
import moe.matsuri.nb4a.utils.listByLineOrComma

// The TLS settings a bean carries, read through one view so the sing-box TLS
// block is built in one place instead of once per protocol. Each bean spells
// the fields differently (certificates vs caText; uTLS, ECH and REALITY exist
// only on some), and tlsFields() in ProtocolHandlers does the mapping. Null or
// blank means "not set".
class TlsFields(
    val sni: String?,
    val alpn: String?,
    val certificate: String?,
    val allowInsecure: Boolean?,
    val certificateFingerprint: String?,
    val utlsFingerprint: String? = null,
    val enableECH: Boolean = false,
    val echConfig: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
)

// 全局「允许不安全」叠加在节点自己的开关之上，各核心（sing-box、Xray、mihomo、Hysteria 1 与 Trojan-Go 插件）一致。
// Naive、Mieru 没有这个语义，不涉及
// 全局值都由调用方传入：外核生成器经 ExternalCoreSettings，sing-box 出站与选核判定经构建的设置快照
fun effectiveAllowInsecure(allowInsecure: Boolean?, globalAllowInsecure: Boolean): Boolean =
    allowInsecure == true || globalAllowInsecure

// bean -> sing-box 出站的 tls 块，不用 TLS 的 bean 返回 null；globalAllowInsecure 是全局「允许不安全」
fun buildSingBoxOutboundTLS(bean: AbstractBean, globalAllowInsecure: Boolean): OutboundTLSOptions? {
    val tls = tlsFields(bean) ?: return null
    // sing-box 的 certificate_public_key_sha256 是 SPKI 哈希，与证书 SHA-256 不通用。
    // 判断与报错在 requireBuildableHop（ChainPlan.kt，能力表的选核判定）；这里只兜底，
    // 防将来绕过它的调用方把固定静默丢掉
    check(tls.certificateFingerprint.isNullOrBlank()) {
        "certificate pin reached the sing-box TLS builder"
    }
    return OutboundTLSOptions().apply {
        applyCommonTls(tls, globalAllowInsecure)
        tls.realityPublicKey.blankAsNull()?.let {
            requireValidReality(it, tls.realityShortId.orEmpty())
            reality = OutboundRealityOptions().apply {
                enabled = true
                public_key = it
                short_id = tls.realityShortId
            }
        }
        tls.utlsFingerprint.blankAsNull()?.let {
            // uTLS 指纹只在 sing-box 创建出站时才校验。VMess / VLESS / Trojan / AnyTLS 在选核阶段就会因名单外的指纹
            // 报冲突，但 HTTP、ShadowTLS 是 OTHER 协议，能力表不为它们产生要求，没有选核冲突可拦；导入又原样保留
            // 链接里的 fp=。这里按同一份名单（SING_BOX_UTLS_FINGERPRINTS）预检，让未选中的成员被跳过，
            // 而不是一个陌生指纹拖垮整份配置。对前几类协议这是第二道防线
            if (!SING_BOX_UTLS_FINGERPRINTS.accepts(it)) {
                throw UnsupportedSingBoxValueException("uTLS fingerprint", it)
            }
            utls = OutboundUTLSOptions().apply {
                enabled = true
                fingerprint = it
            }
        }
    }
}

// 兼容测试采用 mihomo 的证书固定与指纹名单，普通 sing-box TLS 构建保持原有规则。
fun buildAnyTLSCompatibilityTLS(bean: AnyTLSBean, globalAllowInsecure: Boolean): OutboundTLSOptions {
    check(bean.isAnyTlsCompatibilityTest)
    val fields = tlsFields(bean)!!
    return AnyTLSCompatibilityTLSOptions().apply {
        applyCommonTls(fields, globalAllowInsecure)
        certificate_fingerprint = fields.certificateFingerprint.blankAsNull()
        fields.utlsFingerprint.blankAsNull()?.let {
            require(ANYTLS_COMPAT_UTLS_FINGERPRINTS.accepts(it)) { "unsupported compatibility client fingerprint" }
            utls = OutboundUTLSOptions().apply { enabled = true; fingerprint = it }
        }
    }
}

// 两个 TLS 构建共有的部分：开关、生效的 allowInsecure、SNI、ALPN、证书与 ECH
private fun OutboundTLSOptions.applyCommonTls(fields: TlsFields, globalAllowInsecure: Boolean) {
    enabled = true
    insecure = effectiveAllowInsecure(fields.allowInsecure, globalAllowInsecure)
    fields.sni.blankAsNull()?.let { server_name = it }
    fields.alpn.blankAsNull()?.let { alpn = it.listByLineOrComma() }
    fields.certificate.blankAsNull()?.let { certificate = it }
    if (fields.enableECH) {
        ech = OutboundECHOptions().apply {
            enabled = true
            // sing-box 只认「ECH CONFIGS」PEM 块，mihomo 订阅给的是裸 base64
            fields.echConfig.blankAsNull()?.let { config = it.echAsPem().lines() }
        }
    }
}
