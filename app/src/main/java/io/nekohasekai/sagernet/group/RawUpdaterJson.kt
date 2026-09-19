package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2Json
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject

fun parseJSON(json: Any, depth: Int = 0): List<AbstractBean> {
    require(depth <= 32) { "JSON subscription nesting exceeds limit" }
    val proxies = ArrayList<AbstractBean>()

    if (json is JSONObject) {
        when {
            // before the hysteria 1 branch: a sing-box hysteria2 outbound also
            // carries "server" and may carry "up_mbps"
            json.getStr("type") == "hysteria2" -> {
                return listOf(json.parseHysteria2Json())
            }

            json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                return listOf(json.parseHysteria1Json())
            }

            json.has("method") -> {
                return listOf(json.parseShadowsocks())
            }

            // SIP008 online config: {"version": 1, "servers": [{server, server_port,
            // password, method, plugin, plugin_opts, remarks}, ...]}
            json.optJSONArray("servers") != null -> {
                return json.getJSONArray("servers")
                    .filterIsInstance<JSONObject>()
                    .filter { it.has("server") }
                    .mapNotNull { entry -> runCatching { entry.parseShadowsocks() }.getOrNull() }
            }

            json.has("remote_addr") -> {
                return listOf(json.parseTrojanGo())
            }

            json.has("outbounds") -> {
                return json.getJSONArray("outbounds")
                    .filterIsInstance<JSONObject>()
                    .mapNotNull {
                        val ty = it.getStr("type")
                        if (ty == null || ty == "" ||
                            ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                        ) {
                            null
                        } else {
                            it
                        }
                    }.map {
                        ConfigBean().apply {
                            applyDefaultValues()
                            type = 1
                            config = it.toStringPretty()
                            name = it.getStr("tag")
                        }
                    }
            }

            json.has("server") && json.has("server_port") -> {
                return listOf(ConfigBean().applyDefaultValues().apply {
                    type = 1
                    config = json.toStringPretty()
                })
            }
        }
    } else if (json is JSONArray) {
        // Scalars and malformed nodes must not discard valid siblings.
        json.forEach { _, entry ->
            if (entry is JSONObject || entry is JSONArray) {
                runCatching { parseJSON(entry, depth + 1) }
                    .onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
                    .getOrNull()?.let { proxies.addAll(it) }
            }
        }
    }

    proxies.forEach { it.initializeDefaultValues() }
    return proxies
}
