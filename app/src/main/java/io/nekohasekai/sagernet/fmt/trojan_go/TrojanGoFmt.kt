package io.nekohasekai.sagernet.fmt.trojan_go

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.Protocols
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun parseTrojanGo(server: String): TrojanGoBean {
    val link = server.withHttpScheme().toHttpUrlOrNull() ?: error(
        "invalid trojan-link link $server"
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

fun TrojanGoBean.buildTrojanGoConfig(port: Int): String {
    return JSONObject().apply {
        put("run_type", "client")
        put("local_addr", LOCALHOST)
        put("local_port", port)
        put("remote_addr", finalAddress)
        put("remote_port", finalPort)
        put("password", JSONArray().apply {
            put(password)
        })
        put("log_level", if (DataStore.logLevel > 0) 0 else 2)
        put("tcp", JSONObject().apply {
            put("prefer_ipv4", DataStore.ipv6Mode <= IPv6Mode.ENABLE)
        })

        when (type) {
            "original" -> {
            }
            "ws" -> put("websocket", JSONObject().apply {
                put("enabled", true)
                put("host", host)
                put("path", path)
            })
        }

        // keep the SNI fallback local; writing it back to the bean would add a
        // sni the user never set to later share links
        val sslSni = sni.ifBlank {
            if (finalAddress == LOCALHOST && !serverAddress.isIpAddress()) serverAddress else ""
        }

        put("ssl", JSONObject().apply {
            if (sslSni.isNotBlank()) put("sni", sslSni)
            if (allowInsecure) put("verify", false)
        })

        when {
            encryption == "none" -> {
            }
            encryption.startsWith("ss;") -> put("shadowsocks", JSONObject().apply {
                put("enabled", true)
                put("method", encryption.substringAfter(";").substringBefore(":"))
                put("password", encryption.substringAfter(":", ""))
            })
        }
    }.toStringPretty()
}

fun JSONObject.parseTrojanGo(): TrojanGoBean {
    return TrojanGoBean().applyDefaultValues().apply {
        serverAddress = getStr("remote_addr") ?: error("Missing trojan-go server")
        serverPort = optInt("remote_port", serverPort)
        // 入口校验：越界端口留到 toUri 的 okhttp port() 才抛，分组导出无兜底
        if (serverPort !in 1..65535) error("invalid trojan-go port")
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