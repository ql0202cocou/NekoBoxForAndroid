package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.effectiveAllowInsecure
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.echAsBase64
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest
import java.security.cert.CertificateFactory

const val MIHOMO_PROXY_NAME = "anytls-out"

// Builds a mihomo client config for an AnyTLS profile:
// a local socks listener chained from sing-box, and the profile as proxy.
// controllerPort/controllerSecret enable the Clash API (external-controller),
// used by URL test so mihomo measures the delay through the proxy itself.
fun buildMihomoConfig(
    bean: AnyTLSBean, port: Int, controllerPort: Int? = null, controllerSecret: String = ""
): String {
    val proxy = LinkedHashMap<String, Any?>()
    proxy["name"] = MIHOMO_PROXY_NAME
    proxy["type"] = "anytls"
    proxy["server"] = bean.finalAddress
    proxy["port"] = bean.finalPort
    proxy["password"] = bean.password
    proxy["udp"] = true
    // 经 mapping 外核只能拨到本地地址，TLS SNI 需要显式兜底；
    // 与 sing-box 对齐：sni 为空时兜底为 serverAddress（IP 也一样）
    val sni = bean.sni.takeIf { it.isNotBlank() }
        ?: bean.serverAddress.takeIf { it.isNotBlank() }
    if (sni != null) proxy["sni"] = sni
    if (bean.alpn.isNotBlank()) proxy["alpn"] = bean.alpn.listByLineOrComma()
    // mihomo has no custom-CA option ("certificate" is the mTLS client cert), so pin the
    // server certificate's SHA-256 instead; a CA cert only matches if the server sends it
    // in-chain. Pinning wins over allowInsecure: mihomo implements `fingerprint` as
    // InsecureSkipVerify + VerifyConnection, so a leaf hash match already skips the
    // name/expiry checks that make users reach for allowInsecure.
    val explicitPin = bean.certificateFingerprint.takeIf { it.isNotBlank() }
    if (explicitPin != null) require(isCertificateFingerprint(explicitPin)) {
        "Invalid AnyTLS certificate fingerprint: expected a SHA-256 digest of 64 hex characters (colons allowed)"
    }
    val certPin = explicitPin ?: bean.certificates.takeIf { it.isNotBlank() }?.let(::certificateSha256)
    if (certPin != null) {
        proxy["fingerprint"] = certPin
    } else if (effectiveAllowInsecure(bean.allowInsecure)) {
        proxy["skip-cert-verify"] = true
    }
    if (bean.utlsFingerprint.isNotBlank()) proxy["client-fingerprint"] = bean.utlsFingerprint
    // mihomo outbound.ECHOptions{enable, config}; sing-box gets the same value via tls.ech.config
    if (bean.echConfig.isNotBlank()) {
        proxy["ech-opts"] = linkedMapOf<String, Any?>(
            "enable" to true,
            // mihomo base64-decodes this string; a sing-box style PEM fails
            "config" to bean.echConfig.echAsBase64(),
        )
    } else if (bean.enableECH) {
        proxy["ech-opts"] = linkedMapOf<String, Any?>("enable" to true)
    }

    val listener = LinkedHashMap<String, Any?>()
    listener["name"] = "socks-in"
    listener["type"] = "socks"
    listener["listen"] = "127.0.0.1"
    listener["port"] = port
    listener["udp"] = true

    val config = LinkedHashMap<String, Any?>()
    config["log-level"] = if (DataStore.logLevel > 0) "debug" else "warning"
    config["mode"] = "rule"
    if (controllerPort != null) {
        config["external-controller"] = "127.0.0.1:$controllerPort"
        config["secret"] = controllerSecret
    }
    config["listeners"] = listOf(listener)
    config["proxies"] = listOf(proxy)
    config["rules"] = listOf("MATCH,$MIHOMO_PROXY_NAME")

    return Yaml().dump(config)
}

// mihomo `fingerprint` (component/ca/fingerprint.go): colons stripped, whitespace
// trimmed, hex-decoded to exactly 32 bytes; compared with every certificate in the
// served chain, so a CA hash only matches when the server sends that CA.
fun isCertificateFingerprint(value: String): Boolean {
    val hex = value.replace(":", "").trim()
    return hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

// SHA-256 (lowercase hex) of the first certificate in the PEM, matching
// mihomo's `fingerprint` pinning format.
private fun certificateSha256(pem: String): String = runCatching {
    val der = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()).encoded
    JavaUtil.bytesToHex(MessageDigest.getInstance("SHA-256").digest(der))
}.getOrElse {
    throw IllegalArgumentException("Invalid AnyTLS certificate for mihomo fingerprint pinning", it)
}
