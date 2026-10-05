package io.nekohasekai.sagernet.fmt.trojan_go

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.dialAddress
import io.nekohasekai.sagernet.fmt.dialPort
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.JavaUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun parseTrojanGo(server: String): TrojanGoBean {
    val link = server.withHttpScheme().toHttpUrlOrNull() ?: error(
        "invalid trojan-go link"
    )
    return TrojanGoBean().apply {
        serverAddress = link.host
        serverPort = link.port
        password = link.username
        link.queryParameter("sni")?.let {
            sni = it
        }
        link.queryParameter("allowInsecure")?.let {
            if (it == "1" || it == "true") allowInsecure = true
        }
        link.queryParameter("type")?.let { lType ->
            type = lType

            when (type) {
                "ws" -> {
                    link.queryParameter("host")?.let {
                        host = it
                    }
                    link.queryParameter("path")?.let {
                        path = it
                    }
                }
                else -> {
                }
            }
        }
        link.queryParameter("encryption")?.let {
            encryption = it
        }
        link.queryParameter("plugin")?.let {
            plugin = it
        }
        link.fragment.takeIf { !it.isNullOrBlank() }?.let {
            name = it
        }
    }
}

fun TrojanGoBean.toUri(): String {
    val builder = linkBuilder().username(password).host(serverAddress).port(serverPort)
    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (allowInsecure) {
        builder.addQueryParameter("allowInsecure", "1")
    }
    if (type.isNotBlank() && type != "original") {
        builder.addQueryParameter("type", type)

        when (type) {
            "ws" -> {
                if (host.isNotBlank()) {
                    builder.addQueryParameter("host", host)
                }
                if (path.isNotBlank()) {
                    builder.addQueryParameter("path", path)
                }
            }
        }
    }
    if (encryption.isNotBlank() && encryption != "none") {
        builder.addQueryParameter("encryption", encryption)
    }
    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("trojan-go")
}

// port 是本机 socks 入站的端口，target 是跳实例的拨号目标（经映射时拨本机的映射入站，否则拨服务器本身）
fun TrojanGoBean.buildTrojanGoConfig(port: Int, target: ExternalDialTarget, settings: ExternalCoreSettings): String {
    // 值为 null 的键由 Gson 省略
    val config = LinkedHashMap<String, Any?>()
    config["run_type"] = "client"
    config["local_addr"] = LOCALHOST
    config["local_port"] = port
    config["remote_addr"] = target.dialAddress(this)
    config["remote_port"] = target.dialPort(this)
    config["password"] = arrayListOf(password)
    // 与 ConfigBuilder 的 sing-box 档位一致；trojan-go 用数字，0 最详细、2 为 warn
    config["log_level"] = when (settings.logLevel) {
        2 -> 1
        3, 4 -> 0
        else -> 2
    }
    config["tcp"] = linkedMapOf<String, Any>("prefer_ipv4" to (settings.ipv6Mode <= IPv6Mode.ENABLE))

    when (type) {
        "original" -> {
        }
        "ws" -> config["websocket"] = linkedMapOf<String, Any>(
            "enabled" to true,
            "host" to host,
            "path" to path,
        )
    }

    // 经映射时拨的是本机，SNI 回退到服务器域名。回退值只留在局部变量里；写回 bean 会让用户没设过的 sni
    // 出现在之后导出的分享链接里
    val sslSni = sni.ifBlank {
        if (target is ExternalDialTarget.Mapped && !serverAddress.isIpAddress()) serverAddress else ""
    }

    val ssl = LinkedHashMap<String, Any>()
    if (sslSni.isNotBlank()) ssl["sni"] = sslSni
    if (allowInsecure) ssl["verify"] = false
    config["ssl"] = ssl

    when {
        encryption == "none" -> {
        }
        encryption.startsWith("ss;") -> config["shadowsocks"] = linkedMapOf<String, Any>(
            "enabled" to true,
            "method" to encryption.substringAfter(";").substringBefore(":"),
            "password" to encryption.substringAfter(":", ""),
        )
    }
    return JavaUtil.gson.toJson(config)
}

fun JSONObject.parseTrojanGo(): TrojanGoBean {
    return TrojanGoBean().applyDefaultValues().apply {
        serverAddress = getStr("remote_addr") ?: error("Missing trojan-go server")
        // 默认值给 0 而不是 serverPort：applyDefaultValues 已把它填成 1080，
        // 缺 remote_port 时用它会瞒过 parseJSON 出口的 requireValidEndpoint
        serverPort = optInt("remote_port", 0)
        when (val pass = get("password")) {
            is String -> {
                password = pass
            }
            is JSONArray -> {
                password = pass.getString(0)
            }
        }
        optJSONObject("ssl")?.apply {
            sni = optString("sni", sni)
        }
        optJSONObject("websocket")?.apply {
            if (optBoolean("enabled", false)) {
                type = "ws"
                host = optString("host", host)
                path = optString("path", path)
            }
        }
        optJSONObject("shadowsocks")?.apply {
            if (optBoolean("enabled", false)) {
                encryption = "ss;${optString("method", "")}:${optString("password", "")}"
            }
        }
    }
}