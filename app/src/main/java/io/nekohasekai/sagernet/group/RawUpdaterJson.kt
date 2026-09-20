package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.filterValidEndpoint
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
            // 必须排在 hysteria 1 分支之前：sing-box 的 hysteria2 outbound
            // 同样带 "server"，也可能带 "up_mbps"
            json.getStr("type") == "hysteria2" -> {
                proxies.add(json.parseHysteria2Json())
            }

            json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                proxies.add(json.parseHysteria1Json())
            }

            json.has("method") -> {
                proxies.add(json.parseShadowsocks())
            }

            // SIP008 在线配置：{"version": 1, "servers": [{server, server_port,
            // password, method, plugin, plugin_opts, remarks}, ...]}
            json.optJSONArray("servers") != null -> {
                proxies.addAll(
                    json.getJSONArray("servers")
                        .filterIsInstance<JSONObject>()
                        .filter { it.has("server") }
                        .mapNotNull { entry -> runCatching { entry.parseShadowsocks() }.getOrNull() }
                )
            }

            json.has("remote_addr") -> {
                proxies.add(json.parseTrojanGo())
            }

            json.has("outbounds") -> {
                proxies.addAll(
                    json.getJSONArray("outbounds")
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
                )
            }

            json.has("server") && json.has("server_port") -> {
                proxies.add(ConfigBean().applyDefaultValues().apply {
                    type = 1
                    config = json.toStringPretty()
                })
            }
        }
    } else if (json is JSONArray) {
        // 标量或坏节点不能拖垮同层的有效节点
        json.forEach { _, entry ->
            if (entry is JSONObject || entry is JSONArray) {
                runCatching { parseJSON(entry, depth + 1) }
                    .onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
                    .getOrNull()?.let { proxies.addAll(it) }
            }
        }
    }

    // 单一出口：所有分支的节点统一在这里校验 + 归一化，不再依赖各叶子
    // parser 自觉。嵌套层原样上交，由 depth 0 一次做完；校验必须早于
    // initializeDefaultValues，否则缺省被填成 127.0.0.1:1080 就验不出缺失。
    // 坏节点丢弃后若整批为空，parseRaw 会按「这条策略没解析出东西」继续往
    // 下试，与叶子 parser 直接抛异常的效果一致
    if (depth > 0) return proxies
    val valid = proxies.filterValidEndpoint()
    valid.forEach { it.initializeDefaultValues() }
    return valid
}
