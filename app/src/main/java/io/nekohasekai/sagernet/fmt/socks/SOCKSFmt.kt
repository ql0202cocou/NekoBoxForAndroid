package io.nekohasekai.sagernet.fmt.socks

import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.unUrlSafe
import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.withHttpScheme
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseSOCKS(link: String): SOCKSBean {
    val url = link.withHttpScheme("http").toHttpUrlOrNull()
        ?: error("Not supported: $link")

    return SOCKSBean().apply {
        protocol = when {
            link.startsWith("socks4://") -> SOCKSBean.PROTOCOL_SOCKS4
            link.startsWith("socks4a://") -> SOCKSBean.PROTOCOL_SOCKS4A
            else -> SOCKSBean.PROTOCOL_SOCKS5
        }
        name = url.fragment
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        // v2rayN fmt
        if (password.isNullOrBlank() && !username.isNullOrBlank()) {
            try {
                val n = username.decodeBase64UrlSafe()
                // Only adopt the decoded value when it really is a v2rayN
                // "user:pass" blob; a plain-text username can itself be valid
                // base64 ("user" etc.) and must not be rewritten.
                if (n.contains(":")) {
                    username = n.substringBefore(":")
                    password = n.substringAfter(":")
                }
            } catch (_: Exception) {
            }
        }
        url.queryParameter("uot")?.let {
            if (it == "1" || it == "true") sUoT = true
        }
    }
}

fun SOCKSBean.toUri(): String {

    val builder = HttpUrl.Builder().scheme("http").host(serverAddress).port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (sUoT) builder.addQueryParameter("uot", "1")
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("socks${protocolVersionName()}")

}

fun buildSingBoxOutboundSocksBean(bean: SOCKSBean): SingBoxOptions.Outbound_SocksOptions {
    return SingBoxOptions.Outbound_SocksOptions().apply {
        type = "socks"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        version = bean.protocolVersionName()
    }
}
