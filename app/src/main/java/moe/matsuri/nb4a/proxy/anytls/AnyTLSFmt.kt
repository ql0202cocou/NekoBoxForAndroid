package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.buildAnyTLSCompatibilityTLS
import io.nekohasekai.sagernet.fmt.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.withHttpScheme
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// 显式新建的 AnyTLS 兼容测试节点：只由 libcore 的私有兼容出站承载，不能分享、导出，也不能从链接导入
val AbstractBean?.isAnyTlsCompatibilityTest: Boolean get() = this is AnyTLSBean && compatibilityTest == true

// 私有兼容出站的类型名，必须与 libcore/anytlscompat/boxadapter/outbound.go 的 boxadapter.Type 相同
const val ANYTLS_COMPAT_OUTBOUND_TYPE = "neko-anytls-compat-experiment"

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean, globalAllowInsecure: Boolean): SingBoxOptions.Outbound_AnyTLSOptions {
    val compat = bean.isAnyTlsCompatibilityTest
    require(compat || bean.certificateFingerprint.isNullOrBlank()) {
        "AnyTLS certificate fingerprint requires the mihomo core; sing-box only supports public-key pinning"
    }
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = if (compat) ANYTLS_COMPAT_OUTBOUND_TYPE else "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        tls = if (compat) buildAnyTLSCompatibilityTLS(bean, globalAllowInsecure)
        else buildSingBoxOutboundTLS(bean, globalAllowInsecure)
    }
}

fun AnyTLSBean.toUri(): String {
    check(!isAnyTlsCompatibilityTest) { "AnyTLS compatibility test profiles cannot be shared as ordinary AnyTLS links" }
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
