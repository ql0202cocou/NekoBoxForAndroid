package io.nekohasekai.sagernet.fmt.tuic

import io.nekohasekai.sagernet.fmt.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.withHttpScheme
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val tuicUuidRegex =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.withHttpScheme().toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    return TuicBean().apply {
        protocolVersion = 5

        name = link.fragment
        serverAddress = link.host
        serverPort = link.port

        val rawUser = link.username
        val rawPass = link.password

        if (rawUser.contains(":")) {
            val parts = rawUser.split(":", limit = 2)
            uuid = parts[0]
            token = parts.getOrElse(1) { "" }
        } else {
            // a v4 link carries only a token in the userinfo, while a v5 uuid
            // is a standard UUID; without it the link cannot be a valid v5 node
            if (rawPass.isEmpty() && !rawUser.matches(tuicUuidRegex)) {
                error("TUIC v4 link (token only) is not supported")
            }
            uuid = rawUser
            token = rawPass
        }

        link.queryParameter("sni")?.let {
            sni = it
        }
        // short name is our own invention, matching the AnyTLS "cert" param
        link.queryParameter("ca")?.let {
            caText = it
        }
        // 证书 SHA-256 指纹，自家参数（与 anytls 的 certfp 同名）
        link.queryParameter("certfp")?.let {
            certificateFingerprint = it
        }
        link.queryParameter("congestion_control")?.let {
            congestionController = it
        }
        link.queryParameter("udp_relay_mode")?.let {
            udpRelayMode = it
        }
        link.queryParameter("alpn")?.let {
            alpn = it
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1") allowInsecure = true
        }
        link.queryParameter("disable_sni")?.let {
            if (it == "1") disableSNI = true
        }
        // short name is our own invention, fall back to the full sing-box name
        (link.queryParameter("zero_rtt") ?: link.queryParameter("zero_rtt_handshake"))?.let {
            if (it == "1") reduceRTT = true
        }
    }
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (caText.isNotBlank()) builder.addQueryParameter("ca", caText)
    if (certificateFingerprint.isNotBlank()) builder.addQueryParameter("certfp", certificateFingerprint)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn.replace("\n", ","))
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (reduceRTT) builder.addQueryParameter("zero_rtt", "1")
    if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())

    return builder.toLink("tuic")
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    return SingBoxOptions.Outbound_TUICOptions().apply {
        type = "tuic"
        server = bean.serverAddress
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.token
        congestion_control = bean.congestionController
        when (bean.udpRelayMode) {
            "quic" -> udp_relay_mode = "quic"
        }
        zero_rtt_handshake = bean.reduceRTT
        if (bean.heartbeatInterval > 0) {
            heartbeat = "${bean.heartbeatInterval}s"
        }
        tls = buildSingBoxOutboundTLS(bean)?.apply { disable_sni = bean.disableSNI }
    }
}
