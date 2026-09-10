package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.echAsPem
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions {
    require(bean.certificateFingerprint.isNullOrBlank()) {
        "AnyTLS certificate fingerprint requires the mihomo core; sing-box only supports public-key pinning"
    }
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.blankAsNull()
            if (bean.allowInsecure || DataStore.globalAllowInsecure) insecure = true
            alpn = bean.alpn.blankAsNull()?.listByLineOrComma()
            bean.certificates.blankAsNull()?.let {
                certificate = it
            }
            bean.utlsFingerprint.blankAsNull()?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            if (bean.enableECH || !bean.echConfig.isNullOrBlank()) {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    bean.echConfig.blankAsNull()?.let {
                        // sing-box only accepts an "ECH CONFIGS" PEM block; a mihomo
                        // subscription hands us bare base64
                        config = it.echAsPem().lines()
                    }
                }
            }
        }
    }
}

fun AnyTLSBean.toUri(): String {
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
    val link = ("https://" + url.substringAfter("://")).toHttpUrlOrNull() ?: error(
        "invalid anytls link $url"
    )
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
