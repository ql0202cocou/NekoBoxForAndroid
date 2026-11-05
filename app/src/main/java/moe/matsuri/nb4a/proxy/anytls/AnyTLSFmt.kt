package moe.matsuri.nb4a.proxy.anytls

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.SING_BOX_UTLS_FINGERPRINTS
import io.nekohasekai.sagernet.fmt.buildAnyTLSCompatibilityTLS
import io.nekohasekai.sagernet.fmt.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.withHttpScheme
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// 私有兼容出站的类型名，必须与 libcore/anytlscompat/boxadapter/outbound.go 的 boxadapter.Type 相同
const val ANYTLS_COMPAT_OUTBOUND_TYPE = "neko-anytls"

// 旧 sing-box 节点迁移后为信任策略。覆写继续交给原生出站，保留它完整的会话与 TLS 配置语义。
// 新节点没有用到固定、兼容指纹或 ECH 时也可使用原生覆写；含私有字段的配置始终由兼容实现解释。
internal fun AnyTLSBean.usesNativeAnyTLSOverrides(): Boolean {
    if (customOutboundJson.isNullOrBlank() || !certificateFingerprint.isNullOrBlank()) return false
    val trust = certificatePolicy == AnyTLSBean.CERTIFICATE_POLICY_TRUST
    if (!trust) {
        if (certificatePolicy != AnyTLSBean.CERTIFICATE_POLICY_PIN || !certificates.isNullOrBlank()) return false
        if (!utlsFingerprint.isNullOrBlank() || enableECH == true || !echConfig.isNullOrBlank()) return false
    }
    if (!utlsFingerprint.isNullOrBlank() && !SING_BOX_UTLS_FINGERPRINTS.accepts(utlsFingerprint)) return false

    // 这里只辨认实现；JSON 错误仍在规划和 trial 的原有入口报告。
    val json = try {
        JsonParser.parseString(customOutboundJson)
    } catch (_: JsonParseException) {
        return false
    }
    if (!json.isJsonObject) return false
    for ((key, value) in json.asJsonObject.entrySet()) {
        if (key.equals("type", ignoreCase = true)) {
            if (!value.isJsonPrimitive) return false
            when (value.asString) {
                ANYTLS_COMPAT_OUTBOUND_TYPE, "neko-anytls-compat-experiment" -> return false
            }
        }
        if (!key.equals("tls", ignoreCase = true) || !value.isJsonObject) continue
        for ((field, setting) in value.asJsonObject.entrySet()) {
            when (field.trim('+').lowercase()) {
                "certificate_policy", "certificate_fingerprint" -> return false
                "certificate", "ech" -> if (!trust) return false
                "utls" -> {
                    if (!trust) return false
                    if (!setting.isJsonObject) continue
                    val utls = setting.asJsonObject
                    val enabled = utls["enabled"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: !utlsFingerprint.isNullOrBlank()
                    val fp = utls["fingerprint"]?.takeIf { it.isJsonPrimitive }?.asString
                    if (enabled && !fp.isNullOrBlank() && !SING_BOX_UTLS_FINGERPRINTS.accepts(fp)) return false
                }
            }
        }
    }
    return true
}

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean, globalAllowInsecure: Boolean): SingBoxOptions.Outbound_AnyTLSOptions {
    val native = bean.usesNativeAnyTLSOverrides()
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = if (native) "anytls" else ANYTLS_COMPAT_OUTBOUND_TYPE
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        tls = if (native) {
            buildSingBoxOutboundTLS(bean, globalAllowInsecure)
        } else {
            buildAnyTLSCompatibilityTLS(bean, globalAllowInsecure)
        }
    }
}

fun AnyTLSBean.toUri(): String {
    // 专属链接无法表达自定义信任策略，用通用链接完整保留，避免接收方按固定解释。
    if (certificatePolicy == AnyTLSBean.CERTIFICATE_POLICY_TRUST) return toUniversalLink()
    require(certificatePolicy == AnyTLSBean.CERTIFICATE_POLICY_PIN) { "unresolved AnyTLS certificate policy" }
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(password)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint)
    }
    if (!alpn.isNullOrBlank()) {
        builder.addQueryParameter("alpn", alpn.replace("\n", ","))
    }
    if (!certificates.isNullOrBlank()) {
        builder.addQueryParameter("cert", certificates)
    }
    if (!certificateFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("certfp", certificateFingerprint)
    }
    if (!echConfig.isNullOrBlank()) {
        builder.addQueryParameter("ech", echConfig)
    } else if (enableECH) {
        builder.addQueryParameter("ech", "1")
    }
    return builder.toLink("anytls")
}

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    // 报错不带原链接：userinfo 里是密码
    val link = url.withHttpScheme().toHttpUrlOrNull() ?: error("invalid anytls link")
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
        link.queryParameter("alpn")?.let {
            alpn = it
        }
        link.queryParameter("cert")?.let {
            certificates = it
        }
        (link.queryParameter("certfp") ?: link.queryParameter("certificateFingerprint"))?.let {
            certificateFingerprint = it
        }
        (link.queryParameter("ech") ?: link.queryParameter("echConfig"))?.let {
            // "1" marks enable-only; storing it as a config would make both
            // cores reject the profile
            if (it == "1") enableECH = true else echConfig = it
        }
    }
}
