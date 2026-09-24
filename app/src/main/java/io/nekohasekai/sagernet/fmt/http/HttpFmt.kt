package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.ktx.urlSafe
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseHttp(link: String): HttpBean {
    val httpUrl = link.toHttpUrlOrNull() ?: error("Invalid http(s) link")

    if (httpUrl.encodedPath != "/") error("Not http proxy")

    return HttpBean().apply {
        serverAddress = httpUrl.host
        serverPort = httpUrl.port
        username = httpUrl.username
        password = httpUrl.password
        sni = httpUrl.queryParameter("sni")
        name = httpUrl.fragment
        setTLS(httpUrl.scheme == "https")
        if (isTLS()) {
            httpUrl.queryParameter("allowInsecure")?.let {
                if (it == "1" || it == "true") allowInsecure = true
            }
            httpUrl.queryParameter("cert")?.let {
                certificates = it
            }
            httpUrl.queryParameter("certfp")?.let {
                certificateFingerprint = it
            }
            httpUrl.queryParameter("fp")?.let {
                utlsFingerprint = it
            }
            httpUrl.queryParameter("alpn")?.let {
                alpn = it
            }
            (httpUrl.queryParameter("ech") ?: httpUrl.queryParameter("echConfig"))?.let {
                enableECH = true
                // "1" marks enable-only (query DNS for the config), a real config is base64
                if (it != "1") echConfig = it
            }
        }
    }
}

fun HttpBean.toUri(): String {
    val builder = HttpUrl.Builder().scheme(if (isTLS()) "https" else "http").host(serverAddress)
        .port(serverPort)

    if (username.isNotBlank()) {
        builder.username(username)
    }
    if (password.isNotBlank()) {
        builder.password(password)
    }
    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (isTLS()) {
        if (allowInsecure) {
            builder.addQueryParameter("allowInsecure", "1")
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (certificateFingerprint.isNotBlank()) {
            builder.addQueryParameter("certfp", certificateFingerprint)
        }
        if (utlsFingerprint.isNotBlank()) {
            builder.addQueryParameter("fp", utlsFingerprint)
        }
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn.replace("\n", ","))
        }
        if (enableECH) {
            // "1" marks enable-only (no pinned config)
            builder.addQueryParameter("ech", echConfig.ifBlank { "1" })
        }
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toString()
}