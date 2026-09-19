package io.nekohasekai.sagernet.fmt.hysteria

import android.util.Base64
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = url.withHttpScheme().toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url"
    )
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = link.host
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            // "authType" is our own marker (like "ca"/"hopInterval"); unknown
            // values are ignored and the payload stays a plaintext string
            authPayloadType = if (link.queryParameter("authType") == "base64") {
                HysteriaBean.TYPE_BASE64
            } else {
                HysteriaBean.TYPE_STRING
            }
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        // custom CA, our own invention (same "ca" name as tuic)
        link.queryParameter("ca")?.also {
            caText = it
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("hopInterval")?.also {
            hopInterval = it.toIntOrNull() ?: hopInterval
        }
        link.queryParameter("alpn")?.also {
            alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url.withHttpScheme().toHttpUrlOrNull()
        ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = link.host
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        // custom CA, our own invention (same "ca" name as tuic)
        link.queryParameter("ca")?.also {
            caText = it
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("hopInterval")?.also {
            hopInterval = it.toIntOrNull() ?: hopInterval
        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val ports = parseHysteriaPorts(serverPorts)
    val builder = linkBuilder()
        .host(serverAddress)
        .port(ports.first().first)
        .username(un)
        .password(pw)
    if (ports.singlePortOrNull() == null) {
        builder.addQueryParameter("mport", ports.joinHysteriaPorts())
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    // custom CA, our own invention (same "ca" name as tuic)
    if (caText.isNotBlank()) {
        builder.addQueryParameter("ca", caText)
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            if (authPayloadType == HysteriaBean.TYPE_BASE64) {
                // The URI "auth" parameter is plaintext by spec. A payload that
                // decodes to printable UTF-8 is written as-is for interoperability;
                // anything else keeps the base64 text with our own "authType"
                // marker (like "ca"/"hopInterval") so it round-trips.
                val decoded = runCatching { Base64.decode(authPayload, Base64.DEFAULT) }.getOrNull()
                val text = decoded?.let { String(it, Charsets.UTF_8) }
                if (text != null && text.isNotEmpty() && text.none(Char::isISOControl) &&
                    text.toByteArray(Charsets.UTF_8).contentEquals(decoded)
                ) {
                    builder.addQueryParameter("auth", text)
                } else {
                    builder.addQueryParameter("auth", authPayload)
                    builder.addQueryParameter("authType", "base64")
                }
            } else {
                builder.addQueryParameter("auth", authPayload)
            }
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        // custom parameter (like anytls's cert/certfp), not part of the standard URI
        builder.addQueryParameter("hopInterval", "$hopInterval")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn.replace("\n", ","))
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        builder.addQueryParameter("hopInterval", "$hopInterval")
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "salamander")
            builder.addQueryParameter("obfs-password", obfuscation)
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    return HysteriaBean().apply {
        protocolVersion = 1
        val server = getStr("server") ?: error("Missing hysteria1 server")
        // Only a bracketed value can fail to split, and the address is still the
        // bracketed part; an unterminated or empty bracket keeps the whole value,
        // the same as a bare IPv6 one. The default port applies whenever no port
        // was given.
        val (host, portText) = server.splitHostPort() ?: run {
            val end = server.indexOf(']')
            (if (end > 1) server.substring(1, end) else server) to null
        }
        serverAddress = host
        serverPorts = portText?.ifBlank { null } ?: "443"
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        sni = getStr("server_name")
        alpn = getStr("alpn")
        allowInsecure = getBool("insecure")

        streamReceiveWindow = getIntNya("recv_window_conn")
        connectionReceiveWindow = getIntNya("recv_window")
        disableMtuDiscovery = getBool("disable_mtu_discovery")
    }
}

// sing-box hysteria2 outbound:
// {"type": "hysteria2", "server", "server_port" | "server_ports", "password",
//  "up_mbps", "down_mbps", "hop_interval", "obfs": {...}, "tls": {...}, "tag"}
fun JSONObject.parseHysteria2Json(): HysteriaBean {
    return HysteriaBean().apply {
        protocolVersion = 2
        name = getStr("tag")
        val server = getStr("server") ?: error("Missing hysteria2 server")
        // Same authority criterion as parseHysteria1Json: a bare IPv6 address
        // keeps all its colons, and the default port applies whenever no port
        // was given.
        val (host, portText) = server.splitHostPort() ?: run {
            val end = server.indexOf(']')
            (if (end > 1) server.substring(1, end) else server) to null
        }
        serverAddress = host
        serverPorts = portText?.ifBlank { null } ?: "443"
        getIntNya("server_port")?.also {
            serverPorts = it.toString()
        }
        // sing-box spells hopping ranges "first:last"; the shared parser keeps
        // the bean's hysteria form, the same normalization as clash "ports"
        optJSONArray("server_ports")?.also { ranges ->
            val ports = runCatching {
                (0 until ranges.length()).map { ranges.optString(it) }
                    .filter { it.isNotBlank() }
                    .flatMap { parseHysteriaPorts(it) }
                    .joinHysteriaPorts()
            }.getOrNull()
            if (!ports.isNullOrBlank()) {
                serverPorts = ports
            }
        }
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        // sing-box duration text ("10s"); a value without the unit also parses
        getStr("hop_interval")?.also {
            hopInterval = it.removeSuffix("s").toIntOrNull() ?: hopInterval
        }
        authPayload = getStr("password")
        optJSONObject("obfs")?.also { obfs ->
            val type = obfs.getStr("type")
            // salamander is the only obfs sing-box and the hysteria2 builder know
            if (type == null || type == "salamander") {
                obfuscation = obfs.getStr("password")
            } else {
                Logs.w("unsupported hysteria2 obfs type $type, dropped")
            }
        }
        optJSONObject("tls")?.apply {
            sni = getStr("server_name")
            getBool("insecure")?.also { allowInsecure = it }
            // inline PEM only, like clash "ca-str"; "certificate_path" is a local
            // file and stays unimported
            caText = getStr("certificate")
            // no "alpn" here: hysteria2 mandates h3 and the sing-box builder hardcodes it
        }
    }.applyDefaultValues()
}

// apernet/hysteria v1.3.5 app/cmd/config.go clientConfig.Check(): the plugin (used for
// faketcp / wechat-video) rejects a non-zero receive window below 65536 and a non-zero
// hop_interval below 8 seconds; zero keeps the plugin defaults. The sing-box path has
// no such minimums, so the editor applies these only when the plugin will run.
fun isHysteria1PluginWindow(value: Int): Boolean = value == 0 || value >= 65536

fun isHysteria1PluginHopInterval(value: Int): Boolean = value == 0 || value >= 8

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    require(isHysteria1PluginWindow(streamReceiveWindow) && isHysteria1PluginWindow(connectionReceiveWindow)) {
        "hysteria 1 receive windows must be 0 or at least 65536"
    }
    require(isHysteria1PluginHopInterval(hopInterval)) {
        "hysteria 1 hop interval must be 0 or at least 8 seconds"
    }
    val ports = parseHysteriaPorts(serverPorts).joinHysteriaPorts()
    return JSONObject().apply {
        // When the node got a mapping inbound (chain member), finalAddress is
        // rewritten to LOCALHOST and the plugin must dial the mapping port —
        // displayAddress() would bypass the whole chain. Otherwise keep
        // displayAddress(): hysteria's serverPort int is not synced with
        // serverPorts, and port hopping only works on a direct dial.
        if (finalAddress == LOCALHOST && serverAddress != LOCALHOST) {
            put("server", "$LOCALHOST:$finalPort")
        } else {
            put("server", "${serverAddress.wrapIPV6Host()}:$ports")
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        // keep the SNI fallback local; writing it back to the bean would add a
        // peer the user never set to later share links
        val serverName = sni.ifBlank {
            if (finalAddress == LOCALHOST && !serverAddress.isIpAddress()) serverAddress else ""
        }
        if (serverName.isNotBlank()) {
            put("server_name", serverName)
        }
        // hysteria 1 offers exactly one ALPN (NextProtos: []string{config.ALPN}); a
        // Clash import joins several with "\n", which would corrupt the handshake
        alpn.listByLineOrComma().firstOrNull()?.let { put("alpn", it) }
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window_conn", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

// Same parser as everywhere else; a value the editor would have rejected keeps
// falling back to the default port instead of failing the caller.
fun getFirstPort(portStr: String): Int =
    runCatching { parseHysteriaPorts(portStr).first().first }.getOrDefault(443)

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    val ports = parseHysteriaPorts(bean.serverPorts)
    val singlePort = ports.singlePortOrNull()
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            if (singlePort != null) server_port = singlePort
            else server_ports = ports.toSingBoxPorts()
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            disable_mtu_discovery = bean.disableMtuDiscovery
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow > 0) {
                recv_window_conn = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow > 0) {
                recv_window = bean.connectionReceiveWindow.toLong()
            }
            tls = buildSingBoxOutboundTLS(bean)
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            if (singlePort != null) server_port = singlePort
            else server_ports = ports.toSingBoxPorts()
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = "salamander"
                    password = bean.obfuscation
                }
            }
            password = bean.authPayload
            // hysteria2 mandates h3 whatever the profile's alpn says
            tls = buildSingBoxOutboundTLS(bean)?.apply { alpn = listOf("h3") }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}
