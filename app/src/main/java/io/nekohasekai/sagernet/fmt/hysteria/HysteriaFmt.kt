package io.nekohasekai.sagernet.fmt.hysteria

import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val link = ("https://" + url.substringAfter("://")).toHttpUrlOrNull() ?: error(
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
    val link = ("https://" + url.substringAfter("://")).toHttpUrlOrNull()
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
    if (ports.size > 1 || ports[0].first != ports[0].last) {
        builder.addQueryParameter("mport", ports.joinToString(",") {
            if (it.first == it.last) it.first.toString() else "${it.first}-${it.last}"
        })
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
    // TODO parse HY2 JSON+YAML
    return HysteriaBean().apply {
        protocolVersion = 1
        // Same authority criterion as parseWireGuard / makeDnsServer:
        // [v6]:port | host:port | bare v6 | host. A bare IPv6 address has no
        // port to split off and must keep all its colons; the default port
        // only applies when no port was given at all.
        val server = optString("server")
        when {
            server.startsWith("[") -> {
                val end = server.indexOf(']')
                if (end > 1) {
                    serverAddress = server.substring(1, end)
                    serverPorts = server.substring(end + 1)
                        .takeIf { it.startsWith(":") }?.substring(1)?.ifBlank { null } ?: "443"
                } else {
                    serverAddress = server
                    serverPorts = "443"
                }
            }

            server.count { it == ':' } == 1 -> {
                serverAddress = server.substringBefore(':')
                serverPorts = server.substringAfter(':', "").ifBlank { "443" }
            }

            else -> {
                // no colon at all, or a bare IPv6 address (multiple colons)
                serverAddress = server
                serverPorts = "443"
            }
        }
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
    val ports = parseHysteriaPorts(serverPorts).joinToString(",") {
        if (it.first == it.last) it.first.toString() else "${it.first}-${it.last}"
    }
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

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").substringBefore("-").toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    // sing-box has no compatible option (its certificate_public_key_sha256 is
    // an SPKI hash), so the pin is stored without effect on this core
    if (bean.certificateFingerprint.isNotBlank()) {
        Logs.w("certificate fingerprint pinning is not supported by sing-box, ignored")
    }
    val ports = parseHysteriaPorts(bean.serverPorts)
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            if (ports.size == 1 && ports[0].first == ports[0].last) {
                server_port = ports[0].first
            } else {
                server_ports = ports.map { "${it.first}:${it.last}" }
            }
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
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                if (bean.alpn.isNotBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            if (ports.size == 1 && ports[0].first == ports[0].last) {
                server_port = ports[0].first
            } else {
                server_ports = ports.map { "${it.first}:${it.last}" }
            }
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
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (bean.sni.isNotBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (bean.caText.isNotBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || DataStore.globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> =
    parseHysteriaPorts(s).map { "${it.first}:${it.last}" }
