package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.dialAddress
import io.nekohasekai.sagernet.fmt.dialPort
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.utils.JavaUtil.gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    // proto 只有 https/quic 两个合法值；其余 scheme（包括不带 "+proto" 的
    // 写法）导入即成死节点，入口直接拒绝
    if (proto != "https" && proto != "quic") error("unsupported naive proto: $proto")
    val url = link.withHttpScheme().toHttpUrlOrNull()
        ?: error("Invalid naive link")
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

// 分享链接：服务器地址端口与全部参数
fun NaiveBean.toUri(): String = buildUri(null)

// 本地 naive 进程要连的上游（buildNaiveConfig 的 proxy）：地址端口由调用方按拨号目标显式给出，只带凭据
private fun NaiveBean.toUpstreamUri(host: String, port: Int): String = buildUri(host to port)

// upstream 为 null 时是分享链接，否则是上游地址端口
private fun NaiveBean.buildUri(upstream: Pair<String, Int>?): String {
    val builder = if (upstream != null) {
        linkBuilder().host(upstream.first).port(upstream.second)
    } else {
        linkBuilder().host(serverAddress).port(serverPort)
    }
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (upstream == null) {
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
    return builder.toLink(if (upstream != null) proto else "naive+$proto", false)
}

// port 是本机 socks 入站的端口，target 是跳实例的拨号目标（经映射时拨本机的映射入站，否则拨服务器本身）
fun NaiveBean.buildNaiveConfig(port: Int, target: ExternalDialTarget, settings: ExternalCoreSettings): String {
    return LinkedHashMap<String, Any>().apply {
        // 地址改写只进局部变量（与 buildTrojanGoConfig 的 SNI 回退一致）：
        // 写回 bean 会把 IPv6 方括号持久化，被 SNI 顶替的地址也会混进之后的分享链接。
        // serverAddress 为 null 时上一行已抛错，拨号地址在这里总是非空
        val wrappedServer = serverAddress.wrapIPV6Host()
        var address = target.dialAddress(this@buildNaiveConfig).orEmpty().wrapIPV6Host()

        // process sni
        if (sni.isNotBlank()) {
            this["host-resolver-rules"] = "MAP $sni $address"
            address = sni
        } else {
            if (wrappedServer.isIpAddress()) {
                // for naive, using IP as SNI name hardly happens
                // and host-resolver-rules cannot resolve the SNI problem
                // so do nothing
            } else {
                this["host-resolver-rules"] = "MAP $wrappedServer $address"
                address = wrappedServer
            }
        }

        this["listen"] = "socks://$LOCALHOST:$port"
        this["proxy"] = toUpstreamUri(address, target.dialPort(this@buildNaiveConfig))
        if (extraHeaders.isNotBlank()) {
            this["extra-headers"] = extraHeaders.split("\n").joinToString("\r\n")
        }
        if (settings.logLevel > 0) {
            this["log"] = ""
        }
        if (insecureConcurrency > 0) {
            this["insecure-concurrency"] = insecureConcurrency
        }
    }.let { gson.toJson(it) }
}