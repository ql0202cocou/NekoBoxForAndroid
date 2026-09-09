package io.nekohasekai.sagernet.fmt.shadowsocks

import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun ShadowsocksBean.fixPluginName() {
    if (plugin.startsWith("simple-obfs")) {
        plugin = plugin.replaceFirst("simple-obfs", "obfs-local")
    }
}

// Error messages carry no link on purpose: they end up in neko.log, and the
// v2rayN form (ss://<base64 of method:password@host:port>) has no "@" for
// redactSecrets to mask, so the credentials would land there in the clear.
fun parseShadowsocks(url: String): ShadowsocksBean {

    if (url.substringBefore("#").contains("@")) {
        var link = ("https://" + url.substringAfter("://")).toHttpUrlOrNull() ?: error(
            "invalid ss-android link"
        )

        if (link.username.isBlank()) { // fix justmysocks's shit link
            link = (("https://" + url.substringAfter("ss://")
                .substringBefore("#")
                .decodeBase64UrlSafe()).toHttpUrlOrNull()
                ?: error("invalid jms link")
                    ).newBuilder().apply {
                // substringAfter("#") returns the whole url when there is no '#'
                url.substringAfter("#", "").takeIf { it.isNotEmpty() }?.let { fragment(it) }
            }.build()
        }

        // ss-android style

        if (link.password.isNotBlank()) {
            return ShadowsocksBean().apply {
                serverAddress = link.host
                serverPort = link.port
                method = link.username
                password = link.password
                plugin = link.queryParameter("plugin") ?: ""
                link.queryParameter("uot")?.let {
                    if (it == "1" || it == "true") sUoT = true
                }
                name = link.fragment
                fixPluginName()
            }
        }

        val methodAndPswd = link.username.decodeBase64UrlSafe()
        if (!methodAndPswd.contains(":")) error("invalid ss method/password")

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = methodAndPswd.substringBefore(":")
            password = methodAndPswd.substringAfter(":")
            plugin = link.queryParameter("plugin") ?: ""
            link.queryParameter("uot")?.let {
                if (it == "1" || it == "true") sUoT = true
            }
            name = link.fragment
            fixPluginName()
        }
    } else {
        // v2rayN style
        var v2Url = url

        if (v2Url.contains("#")) v2Url = v2Url.substringBefore("#")

        val link = ("https://" + v2Url.substringAfter("ss://")
            .decodeBase64UrlSafe()).toHttpUrlOrNull() ?: error("invalid v2rayN ss link")

        return ShadowsocksBean().apply {
            serverAddress = link.host
            serverPort = link.port
            method = link.username
            password = link.password
            plugin = ""
            val remarks = url.substringAfter("#", "").unUrlSafe()
            if (remarks.isNotBlank()) name = remarks
        }
    }

}

fun ShadowsocksBean.toUri(): String {

    val builder = linkBuilder().username(Util.b64EncodeUrlSafe("$method:$password"))
        .host(serverAddress)
        .port(serverPort)

    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    if (sUoT) {
        builder.addQueryParameter("uot", "1")
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    // drop the "/" path after the authority; first match only, since a plugin or
    // fragment value containing "<port>/" must survive (userinfo is URL-safe base64
    // and hosts carry no "/", so the authority is always the first match)
    return builder.toLink("ss").replaceFirst("$serverPort/", "$serverPort")

}

fun JSONObject.parseShadowsocks(): ShadowsocksBean {
    return ShadowsocksBean().apply {
        serverAddress = getStr("server")
        serverPort = getIntNya("server_port")
        password = getStr("password")
        method = getStr("method")
        name = optString("remarks", "")

        val pId = getStr("plugin")
        if (!pId.isNullOrBlank()) {
            plugin = pId + ";" + optString("plugin_opts", "")
        }
    }
}

fun buildSingBoxOutboundShadowsocksBean(bean: ShadowsocksBean): SingBoxOptions.Outbound_ShadowsocksOptions {
    return SingBoxOptions.Outbound_ShadowsocksOptions().apply {
        type = "shadowsocks"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password
        method = bean.method
        if (bean.plugin.isNotBlank()) {
            plugin = bean.plugin.substringBefore(";")
            plugin_opts = bean.plugin.substringAfter(";", "")
            if (plugin == "none") {
                plugin = null
                plugin_opts = null
            }
        }
    }
}
