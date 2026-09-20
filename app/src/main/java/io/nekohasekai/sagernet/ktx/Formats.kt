package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.requireValidEndpoint
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// 标准 UUID：vmess/vless 的 uuid 与 tuic v5 的用户名都按这个判别
val uuidRegex =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    val linkParsers: List<Triple<String, String, (String) -> AbstractBean>> = listOf(
        Triple("sn://", "universal", ::parseUniversal),
        Triple("socks://", "socks", ::parseSOCKS),
        Triple("socks4://", "socks", ::parseSOCKS),
        Triple("socks4a://", "socks", ::parseSOCKS),
        Triple("socks5://", "socks", ::parseSOCKS),
        Triple("vmess://", "v2ray", ::parseV2Ray),
        Triple("vless://", "vless", ::parseV2Ray),
        Triple("trojan://", "trojan", ::parseTrojan),
        Triple("trojan-go://", "trojan-go", ::parseTrojanGo),
        Triple("ss://", "shadowsocks", ::parseShadowsocks),
        Triple("naive+", "naive", ::parseNaive),
        Triple("hysteria://", "hysteria1", ::parseHysteria1),
        Triple("hysteria2://", "hysteria2", ::parseHysteria2),
        Triple("hy2://", "hysteria2", ::parseHysteria2),
        Triple("tuic://", "TUIC", ::parseTuic),
        Triple("anytls://", "anytls", ::parseAnytls),
    )

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (matches("(http|https)://.*".toRegex())) {
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w("Proxy link rejected: ${it.javaClass.simpleName}")
                val clashUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("install-config")
                    .addQueryParameter("url", this)
                    .build()
                    .toString()
                    .replaceFirst("https://", "clash://")
                throw (SubscriptionFoundException(clashUrl))
            }
            return
        }

        for ((prefix, _, parse) in linkParsers) {
            if (startsWith(prefix)) {
                runCatching {
                    entities.add(parse(this))
                }.onFailure {
                    Logs.w("Proxy link rejected: ${it.javaClass.simpleName}")
                }
                return
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
    // 端点校验必须在 initializeDefaultValues 之前：缺省会被填成
    // 127.0.0.1:1080，之后就验不出缺失；坏节点丢弃，不拖垮整批
    val validEntities = entities.filterValidEndpoint()
    val validEntitiesByLine = entitiesByLine.filterValidEndpoint()
    validEntities.forEach { it.initializeDefaultValues() }
    validEntitiesByLine.forEach { it.initializeDefaultValues() }
    return if (validEntities.size > validEntitiesByLine.size) validEntities else validEntitiesByLine
}

private fun List<AbstractBean>.filterValidEndpoint(): List<AbstractBean> = filter { bean ->
    runCatching { bean.requireValidEndpoint() }
        .onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
        .isSuccess
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}