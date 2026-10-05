package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.effectiveAllowInsecure
import io.nekohasekai.sagernet.fmt.requireDistinctHops
import io.nekohasekai.sagernet.fmt.requireLocalAuth
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.echAsBase64
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest
import java.security.cert.CertificateFactory

// mihomo 的内置代理名，listener 与代理都不能用
private val MIHOMO_RESERVED_NAMES = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE", "GLOBAL")

// 一组跳实例的 mihomo 配置（plan.md K0 做法 2）：每个跳实例一个本机 socks listener，用 proxy 字段固定走它自己的
// 代理，不经过 rules。rules 只留 MATCH,REJECT 兜底：不写 rules 时没绑定代理的 listener 会走直连。
// proxies 与 hops 一一对应，是 buildMihomoProxy 的结果。端口重复时 mihomo 只记一行错误、不退出，由这里先保证。
// 每个 listener 用自己的 users 要求跳实例的本机 socks 凭据（ExternalHop.localAuth），拿不到就报错；不写全局的
// authentication。controllerPort / controllerSecret 打开 Clash API（external-controller），测速时由 mihomo
// 自己经代理测延迟
fun buildMihomoConfig(
    hops: List<ExternalHop>,
    proxies: List<Map<String, Any?>>,
    settings: ExternalCoreSettings,
    controllerPort: Int? = null,
    controllerSecret: String = "",
): String {
    require(hops.size == proxies.size) { "${hops.size} hops but ${proxies.size} proxies" }
    requireDistinctHops(hops, MIHOMO_RESERVED_NAMES)
    val auths = hops.map { it.requireLocalAuth() }
    val config = LinkedHashMap<String, Any?>()
    // 与 ConfigBuilder 的 sing-box 档位一致；mihomo 没有 trace，最高到 debug
    config["log-level"] = when (settings.logLevel) {
        2 -> "info"
        3, 4 -> "debug"
        else -> "warning"
    }
    config["mode"] = "rule"
    if (controllerPort != null) {
        config["external-controller"] = "$LOCALHOST:$controllerPort"
        config["secret"] = controllerSecret
    }
    config["listeners"] = hops.mapIndexed { i, hop ->
        val listener = LinkedHashMap<String, Any?>()
        listener["name"] = hop.inboundTag
        listener["type"] = "socks"
        listener["listen"] = LOCALHOST
        listener["port"] = hop.localPort
        listener["udp"] = true
        listener["proxy"] = hop.outboundTag
        // users 为空或不写时 listener 不认证、-t 也不报错，所以恰好写一项
        listener["users"] = listOf(linkedMapOf("username" to auths[i].username, "password" to auths[i].password))
        listener
    }
    config["proxies"] = hops.mapIndexed { i, hop ->
        LinkedHashMap<String, Any?>().apply {
            put("name", hop.outboundTag)
            putAll(proxies[i])
        }
    }
    config["rules"] = listOf("MATCH,REJECT")

    return Yaml().dump(config)
}

// mihomo 配置里一个跳实例的代理（name 由 buildMihomoConfig 写入）：拨向 finalAddress:finalPort，
// 其余与 K0 之前的单节点配置相同，节点本身的校验也在这里报错
fun buildMihomoProxy(
    bean: AnyTLSBean,
    finalAddress: String,
    finalPort: Int,
    settings: ExternalCoreSettings,
): LinkedHashMap<String, Any?> {
    val proxy = LinkedHashMap<String, Any?>()
    proxy["type"] = "anytls"
    proxy["server"] = finalAddress
    proxy["port"] = finalPort
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
    } else if (effectiveAllowInsecure(bean.allowInsecure, settings.globalAllowInsecure)) {
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
    return proxy
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
