package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = link.withHttpScheme().toHttpUrlOrNull()
        ?: error("Invalid naive link: $link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        url.queryParameter("uot")?.let {
            if (it == "1" || it == "true") sUoT = true
        }
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false, proxyHost: String = finalAddress): String {
    // finalAddress/finalPort 是 transient，配置构建时会被改写（映射地址、SNI），
    // 所以分享链接必须用 serverAddress/serverPort；proxyOnly 时由调用方传入
    // 构建期的局部地址，避免把改写写回 bean
    val builder = if (proxyOnly) {
        linkBuilder().host(proxyHost).port(finalPort)
    } else {
        linkBuilder().host(serverAddress).port(serverPort)
    }
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders.isNotBlank()) {
            builder.addQueryParameter("extra-headers", extraHeaders)
        }
        if (sUoT) {
            builder.addQueryParameter("uot", "1")
        }
        if (name.isNotBlank()) {
            builder.encodedFragment(name.urlSafe())
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().apply {
        // 地址改写只进局部变量（与 buildTrojanGoConfig 的 SNI 回退一致）：
        // serverAddress 非 transient，写回 bean 会把 IPv6 方括号持久化，
        // finalAddress 被 SNI 顶替后也会混进之后的分享链接
        val wrappedServer = serverAddress.wrapIPV6Host()
        var address = finalAddress.wrapIPV6Host()

        // process sni
        if (sni.isNotBlank()) {
            put("host-resolver-rules", "MAP $sni $address")
            address = sni
        } else {
            if (wrappedServer.isIpAddress()) {
                // for naive, using IP as SNI name hardly happens
                // and host-resolver-rules cannot resolve the SNI problem
                // so do nothing
            } else {
                put("host-resolver-rules", "MAP $wrappedServer $address")
                address = wrappedServer
            }
        }

        put("listen", "socks://$LOCALHOST:$port")
        put("proxy", toUri(true, address))
        if (extraHeaders.isNotBlank()) {
            put("extra-headers", extraHeaders.split("\n").joinToString("\r\n"))
        }
        if (DataStore.logLevel > 0) {
            put("log", "")
        }
        if (insecureConcurrency > 0) {
            put("insecure-concurrency", insecureConcurrency)
        }
    }.toStringPretty()
}