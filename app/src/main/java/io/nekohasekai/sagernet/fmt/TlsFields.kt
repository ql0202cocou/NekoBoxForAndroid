package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.v2ray.requireValidReality
import io.nekohasekai.sagernet.ktx.blankAsNull
import moe.matsuri.nb4a.SingBoxOptions.OutboundECHOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundRealityOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundUTLSOptions
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

// 全局「允许不安全」叠加在节点自己的开关之上，各核心（sing-box、Xray、mihomo）一致。
// 外核生成器经 ExternalCoreSettings 传入全局值
fun effectiveAllowInsecure(allowInsecure: Boolean?, globalAllowInsecure: Boolean): Boolean =
    allowInsecure == true || globalAllowInsecure

// sing-box 出站与选核（xrayLacksAllowInsecure()）仍直接读 DataStore，R1b 改为注入
fun effectiveAllowInsecure(allowInsecure: Boolean?): Boolean =
    allowInsecure == true || DataStore.globalAllowInsecure

// bean -> sing-box outbound tls block, null when the bean does not use TLS
fun buildSingBoxOutboundTLS(bean: AbstractBean): OutboundTLSOptions? {
    val tls = tlsFields(bean) ?: return null
    // sing-box 的 certificate_public_key_sha256 是 SPKI 哈希，与证书 SHA-256 不通用。
    // 判断与报错在 ConfigBuild.buildHopOutbound（certificatePinUnsupported）；这里只兜底，
    // 防将来绕过它的调用方把固定静默丢掉
    check(tls.certificateFingerprint.isNullOrBlank()) {
        "certificate pin reached the sing-box TLS builder"
    }
    return OutboundTLSOptions().apply {
        enabled = true
        insecure = effectiveAllowInsecure(tls.allowInsecure)
        tls.sni.blankAsNull()?.let { server_name = it }
        tls.alpn.blankAsNull()?.let { alpn = it.listByLineOrComma() }
        tls.certificate.blankAsNull()?.let { certificate = it }
        tls.realityPublicKey.blankAsNull()?.let {
            requireValidReality(it, tls.realityShortId.orEmpty())
            reality = OutboundRealityOptions().apply {
                enabled = true
                public_key = it
                short_id = tls.realityShortId
            }
        }
        tls.utlsFingerprint.blankAsNull()?.let {
            utls = OutboundUTLSOptions().apply {
                enabled = true
                fingerprint = it
            }
        }
        if (tls.enableECH) {
            ech = OutboundECHOptions().apply {
                enabled = true
                // sing-box only accepts an "ECH CONFIGS" PEM block; a mihomo
                // subscription hands us bare base64
                tls.echConfig.blankAsNull()?.let { config = it.echAsPem().lines() }
            }
        }
    }
}
