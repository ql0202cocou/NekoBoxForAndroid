package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.v2ray.requireValidReality
import io.nekohasekai.sagernet.ktx.Logs
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

// The global "allow insecure" switch applies on top of the per-profile flag on
// every core (sing-box, Xray, mihomo).
fun effectiveAllowInsecure(allowInsecure: Boolean?): Boolean =
    allowInsecure == true || DataStore.globalAllowInsecure

// bean -> sing-box outbound tls block, null when the bean does not use TLS
fun buildSingBoxOutboundTLS(bean: AbstractBean): OutboundTLSOptions? {
    val tls = tlsFields(bean) ?: return null
    // sing-box has no compatible option: its certificate_public_key_sha256 is
    // an SPKI hash, not interchangeable with a certificate SHA-256, so the pin
    // is stored without effect on this core (Xray and mihomo honor it instead)
    if (!tls.certificateFingerprint.isNullOrBlank()) {
        Logs.w("certificate fingerprint pinning is not supported by sing-box, ignored")
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
