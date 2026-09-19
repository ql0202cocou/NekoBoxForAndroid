package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.muxProtocolType
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import org.yaml.snakeyaml.error.YAMLException

@Suppress("UNCHECKED_CAST")
fun parseClash(text: String): List<AbstractBean> {
    val proxies = mutableListOf<AbstractBean>()

    // SafeConstructor: never instantiate arbitrary classes from a
    // remote subscription (CVE-2022-1471). loadAs(Map) is unsupported
    // under it, but load() yields the same LinkedHashMap structure.
    // A valid YAML whose root is not a map (plain cast would throw a
    // ClassCastException out of the YAMLException catch below) falls
    // back to the base64 / share-link parsing like any non-clash body.
    val yaml = clashYaml().load(text) as? Map<*, *> ?: throw YAMLException("Root node is not a map")

    val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

    // List<*> without the Map type argument: a generic List<Map> cast is
    // erased, so the compiler checkcasts each element AFTER the loop's
    // runCatching guard and one "- ss://..." string entry would kill the
    // whole update with a ClassCastException
    for (proxyEntry in (yaml["proxies"] as? List<*> ?: error(
        app.getString(R.string.no_proxies_found_in_file)
    ))) {

        // Skip a single broken node instead of failing the whole update
        runCatching {
            val proxy = proxyEntry as? Map<String, Any?>
                ?: error("proxy entry is not a map")
            // A blank/absent server would silently become 127.0.0.1 in
            // initializeDefaultValues; reject the node instead
            if (proxy["server"]?.toString().isNullOrBlank()) error("missing server")
            when (proxy["type"] as String) {
                "socks5" -> {
                    proxies.add(SOCKSBean().apply {
                        serverAddress = proxy["server"] as String
                        serverPort = proxy["port"].toString().toInt()
                        username = proxy["username"]?.toString()
                        password = proxy["password"]?.toString()
                        name = proxy["name"]?.toString()
                    })
                }

                "http" -> {
                    proxies.add(HttpBean().apply {
                        serverAddress = proxy["server"] as String
                        serverPort = proxy["port"].toString().toInt()
                        username = proxy["username"]?.toString()
                        password = proxy["password"]?.toString()
                        setTLS(proxy["tls"].clashBoolean())
                        sni = proxy["sni"]?.toString()
                        name = proxy["name"]?.toString()
                        allowInsecure = proxy["skip-cert-verify"].clashBoolean()
                    })
                }

                "ss" -> {
                    val ssPlugin = mutableListOf<String>()
                    val ssPluginName = proxy["plugin"]?.toString().blankAsNull()
                    if (ssPluginName != null) {
                        // plugin-opts is optional in clash; without it the
                        // plugin still applies with its default options
                        val opts = proxy["plugin-opts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                        when (ssPluginName) {
                            "obfs" -> {
                                ssPlugin.apply {
                                    add("obfs-local")
                                    add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                    add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                }
                            }

                            "v2ray-plugin" -> {
                                ssPlugin.apply {
                                    add("v2ray-plugin")
                                    add("mode=" + (opts["mode"]?.toString() ?: ""))
                                    if (opts["tls"].clashBoolean()) add("tls")
                                    add("host=" + (opts["host"]?.toString() ?: ""))
                                    add("path=" + (opts["path"]?.toString() ?: ""))
                                    if (opts["mux"].clashBoolean()) add("mux=8")
                                }
                            }

                            // shadow-tls/restls are configured through their own
                            // *-opts keys and have no sip003 plugin here; importing
                            // the node without them yields something that looks fine
                            // and can never connect, so drop it loudly instead
                            else -> error("unsupported shadowsocks plugin: $ssPluginName")
                        }
                    }
                    proxies.add(ShadowsocksBean().apply {
                        serverAddress = proxy["server"] as String
                        serverPort = proxy["port"].toString().toInt()
                        password = proxy["password"]?.toString()
                        method = clashCipher(proxy["cipher"] as String)
                        plugin = ssPlugin.joinToString(";")
                        sUoT = proxy["udp-over-tcp"].clashBoolean()
                        name = proxy["name"]?.toString()
                    })
                }

                "vmess", "vless", "trojan" -> {
                    val bean = when (proxy["type"] as String) {
                        "vmess" -> VMessBean()
                        "vless" -> VMessBean().apply {
                            alterId = -1 // make it VLESS
                            packetEncoding = 2 // clash meta default XUDP
                        }

                        "trojan" -> TrojanBean().apply {
                            security = "tls"
                        }

                        else -> error("impossible")
                    }

                    // error() instead of continue: continuing the outer
                    // loop from an inline lambda is experimental; the
                    // runCatching wrapper skips this node either way.
                    bean.serverAddress = proxy["server"]?.toString()
                        ?: error("missing server")
                    bean.serverPort = proxy["port"]?.toString()?.toIntOrNull()
                        ?: error("missing port")

                    for (opt in proxy) {
                        when (opt.key) {
                            "name" -> bean.name = opt.value?.toString()
                            "password" -> if (bean is TrojanBean) bean.password =
                                opt.value?.toString()

                            "uuid" -> if (bean is VMessBean) bean.uuid =
                                opt.value?.toString()

                            "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                opt.value?.toString()?.toIntOrNull()

                            "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                (opt.value as? String)

                            "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                (opt.value as? String)?.let {
                                    if (it.contains(StandardV2RayBean.FLOW_VISION)) {
                                        bean.encryption = StandardV2RayBean.FLOW_VISION
                                    }
                                }
                            }

                            "packet-encoding" -> if (bean is VMessBean) {
                                bean.packetEncoding = when ((opt.value as? String)) {
                                    "packetaddr" -> 1
                                    "xudp" -> 2
                                    else -> 0
                                }
                            }

                            "tls" -> if (bean is VMessBean) {
                                bean.security =
                                    if (opt.value.clashBoolean()) "tls" else ""
                            }

                            "servername", "sni" -> bean.sni = opt.value?.toString()

                            "alpn" -> bean.alpn =
                                (opt.value as? List<Any>)?.joinToString("\n")

                            "skip-cert-verify" -> bean.allowInsecure =
                                opt.value.clashBoolean()

                            "fingerprint" -> bean.certificateFingerprint =
                                parseCertificateFingerprint(opt.value)

                            "client-fingerprint" -> bean.utlsFingerprint =
                                opt.value as String

                            "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (realityOpt in it) {
                                    bean.security = "tls"

                                    when (realityOpt.key) {
                                        "public-key" -> bean.realityPubKey =
                                            realityOpt.value?.toString()

                                        "short-id" -> bean.realityShortId =
                                            realityOpt.value?.toString()
                                    }
                                }
                            }

                            "network" -> {
                                when (opt.value) {
                                    "h2", "http" -> bean.type = "http"
                                    "ws", "grpc" -> bean.type = opt.value as String
                                }
                            }

                            "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (wsOpt in it) {
                                    when (wsOpt.key) {
                                        "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                            when (key.toString().lowercase()) {
                                                "host" -> {
                                                    bean.host = value?.toString()
                                                }
                                            }
                                        }

                                        "path" -> {
                                            bean.path = wsOpt.value?.toString()
                                        }

                                        "max-early-data" -> {
                                            bean.wsMaxEarlyData =
                                                wsOpt.value?.toString()?.toIntOrNull()
                                        }

                                        "early-data-header-name" -> {
                                            bean.earlyDataHeaderName =
                                                wsOpt.value?.toString()
                                        }

                                        "v2ray-http-upgrade" -> {
                                            if (wsOpt.value.clashBoolean()) {
                                                bean.type = "httpupgrade"
                                            }
                                        }
                                    }
                                }
                            }

                            "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (h2Opt in it) {
                                    when (h2Opt.key) {
                                        "host" -> bean.host =
                                            (h2Opt.value as? List<Any>)?.joinToString("\n")

                                        "path" -> bean.path = h2Opt.value?.toString()
                                    }
                                }
                            }

                            "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (httpOpt in it) {
                                    when (httpOpt.key) {
                                        // a list mihomo picks from at random;
                                        // the builders carry a single path
                                        "path" -> bean.path =
                                            (httpOpt.value as? List<*>)?.firstOrNull()
                                                ?.toString()

                                        "headers" -> {
                                            // Map<*, *>: a typed value cast is erased and the
                                            // destructuring checkcasts it, so a scalar
                                            // "Host: example.com" would drop the whole node
                                            (httpOpt.value as? Map<*, *>)?.forEach { (key, value) ->
                                                when (key.toString().lowercase()) {
                                                    "host" -> bean.host = when (value) {
                                                        is List<*> -> value.mapNotNull { it?.toString() }
                                                            .joinToString("\n")

                                                        else -> value?.toString()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (grpcOpt in it) {
                                    when (grpcOpt.key) {
                                        "grpc-service-name" -> bean.path =
                                            grpcOpt.value?.toString()
                                    }
                                }
                            }

                            "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                for (smuxOpt in it) {
                                    when (smuxOpt.key) {
                                        "enabled" -> bean.enableMux =
                                            smuxOpt.value.clashBoolean()

                                        "max-streams" -> bean.muxConcurrency =
                                            smuxOpt.value.toString().toInt()

                                        "padding" -> bean.muxPadding =
                                            smuxOpt.value.clashBoolean()

                                        "protocol" -> bean.muxType =
                                            muxProtocolType(smuxOpt.value?.toString())
                                    }
                                }
                            }

                            "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                for (echOpt in it) {
                                    when (echOpt.key) {
                                        "enable" -> bean.enableECH =
                                            echOpt.value.clashBoolean()

                                        "config" -> bean.echConfig =
                                            echOpt.value?.toString() ?: ""
                                    }
                                }
                            }
                        }
                    }
                    proxies.add(bean)
                }

                "anytls" -> {
                    val bean = AnyTLSBean()
                    for (opt in proxy) {
                        if (opt.value == null) continue
                        when (opt.key.replace("_", "-")) {
                            "name" -> bean.name = opt.value.toString()
                            "server" -> bean.serverAddress = opt.value as String
                            "port" -> bean.serverPort = opt.value.toString().toInt()
                            "password" -> bean.password = opt.value.toString()
                            "client-fingerprint" -> bean.utlsFingerprint =
                                opt.value as String

                            "sni" -> bean.sni = opt.value.toString()
                            "skip-cert-verify" -> bean.allowInsecure =
                                opt.value.clashBoolean()

                            // mihomo's "certificate"/"private-key" are the mTLS
                            // CLIENT cert (ca.GetTLSConfig -> GetClientCertificate),
                            // not a custom CA — mapping them to bean.certificates
                            // would become a server-cert pin that can never match.
                            "certificate", "private-key" -> Logs.w(
                                "clash anytls mTLS client cert is unsupported, dropped"
                            )

                            "fingerprint" -> bean.certificateFingerprint =
                                parseCertificateFingerprint(opt.value)

                            "alpn" -> {
                                val alpn = (opt.value as? (List<String>))
                                bean.alpn = alpn?.joinToString("\n")
                            }

                            "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                val enable = it["enable"]
                                bean.enableECH = enable.clashBoolean()
                                // mihomo turns ECH on from the config
                                // alone when "enable" is absent
                                if (enable == null || bean.enableECH) {
                                    bean.echConfig =
                                        it["config"]?.toString() ?: ""
                                }
                            }
                        }
                    }
                    proxies.add(bean)
                }

                "hysteria" -> {
                    val bean = HysteriaBean()
                    bean.protocolVersion = 1
                    var hopPorts = ""
                    for (opt in proxy) {
                        if (opt.value == null) continue
                        when (opt.key.replace("_", "-")) {
                            "name" -> bean.name = opt.value.toString()
                            "server" -> bean.serverAddress = opt.value as String
                            "port" -> bean.serverPorts = opt.value.toString()
                            "ports" -> hopPorts = opt.value.toString()

                            "obfs" -> bean.obfuscation = opt.value.toString()

                            "auth-str" -> {
                                bean.authPayloadType = HysteriaBean.TYPE_STRING
                                bean.authPayload = opt.value.toString()
                            }

                            "auth" -> {
                                bean.authPayloadType = HysteriaBean.TYPE_BASE64
                                bean.authPayload = opt.value.toString()
                            }

                            "protocol" -> {
                                when (opt.value.toString()) {
                                    "faketcp" -> bean.protocol =
                                        HysteriaBean.PROTOCOL_FAKETCP

                                    "wechat-video" -> bean.protocol =
                                        HysteriaBean.PROTOCOL_WECHAT_VIDEO
                                }
                            }

                            // clash "ca" is a local file path, only
                            // "ca-str" carries an inline PEM
                            "ca-str" -> bean.caText = opt.value.toString()

                            "sni" -> bean.sni = opt.value.toString()

                            "fingerprint" -> bean.certificateFingerprint =
                                parseCertificateFingerprint(opt.value)

                            "skip-cert-verify" -> bean.allowInsecure =
                                opt.value.clashBoolean()

                            "up" -> bean.uploadMbps =
                                opt.value.toString().substringBefore(" ").toIntOrNull()
                                    ?: 100

                            "down" -> bean.downloadMbps =
                                opt.value.toString().substringBefore(" ").toIntOrNull()
                                    ?: 100

                            "recv-window-conn" -> bean.streamReceiveWindow =
                                opt.value.toString().toIntOrNull() ?: 0

                            "recv-window" -> bean.connectionReceiveWindow =
                                opt.value.toString().toIntOrNull() ?: 0

                            "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                opt.value.clashBoolean() || opt.value.toString() == "1"

                            "hop-interval" -> bean.hopInterval =
                                opt.value.toString().toIntOrNull() ?: bean.hopInterval

                            "alpn" -> {
                                val alpn = (opt.value as? (List<String>))
                                bean.alpn = alpn?.joinToString("\n") ?: "h3"
                            }
                        }
                    }
                    if (hopPorts.isNotBlank()) {
                        bean.serverPorts = hopPorts
                    }
                    proxies.add(bean)
                }

                "hysteria2", "hy2" -> {
                    val bean = HysteriaBean()
                    bean.protocolVersion = 2
                    var hopPorts = ""
                    for (opt in proxy) {
                        if (opt.value == null) continue
                        when (opt.key.replace("_", "-")) {
                            "name" -> bean.name = opt.value.toString()
                            "server" -> bean.serverAddress = opt.value as String
                            "port" -> bean.serverPorts = opt.value.toString()
                            "ports" -> hopPorts = opt.value.toString()

                            "obfs-password" -> bean.obfuscation = opt.value.toString()

                            "password" -> bean.authPayload = opt.value.toString()

                            "sni" -> bean.sni = opt.value.toString()

                            "fingerprint" -> bean.certificateFingerprint =
                                parseCertificateFingerprint(opt.value)

                            "skip-cert-verify" -> bean.allowInsecure =
                                opt.value.clashBoolean()

                            "up" -> bean.uploadMbps =
                                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                            "down" -> bean.downloadMbps =
                                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                            "ca-str" -> bean.caText = opt.value.toString()

                            "hop-interval" -> bean.hopInterval =
                                opt.value.toString().toIntOrNull() ?: bean.hopInterval

                            // no "alpn" here: hysteria2 mandates h3 and the
                            // sing-box builder hardcodes it
                        }
                    }
                    if (hopPorts.isNotBlank()) {
                        bean.serverPorts = hopPorts
                    }
                    proxies.add(bean)
                }

                "tuic" -> {
                    val bean = TuicBean()
                    var ip = ""
                    for (opt in proxy) {
                        if (opt.value == null) continue
                        when (opt.key.replace("_", "-")) {
                            "name" -> bean.name = opt.value.toString()
                            "server" -> bean.serverAddress = opt.value.toString()
                            "ip" -> ip = opt.value.toString()
                            "port" -> bean.serverPort = opt.value.toString().toInt()

                            // mihomo treats a "token" node as TUIC v4, which the
                            // core dropped; importing it would only fail at connect
                            // time, so skip it like an unsupported ss plugin
                            "token" -> {
                                Logs.w("Skipping TUIC v4 (token) node: v4 is not supported")
                                error("unsupported TUIC v4 (token) node")
                            }

                            "uuid" -> bean.uuid = opt.value.toString()

                            "password" -> bean.token = opt.value.toString()

                            "skip-cert-verify" -> bean.allowInsecure =
                                opt.value.clashBoolean()

                            "disable-sni" -> bean.disableSNI =
                                opt.value.clashBoolean()

                            "reduce-rtt" -> bean.reduceRTT =
                                opt.value.clashBoolean()

                            "sni" -> bean.sni = opt.value.toString()

                            "ca-str" -> bean.caText = opt.value.toString()

                            "fingerprint" -> bean.certificateFingerprint =
                                parseCertificateFingerprint(opt.value)

                            "fast-open" -> bean.fastConnect =
                                opt.value.clashBoolean()

                            "alpn" -> {
                                val alpn = (opt.value as? (List<String>))
                                bean.alpn = alpn?.joinToString("\n")
                            }

                            "congestion-controller" -> bean.congestionController =
                                opt.value.toString()

                            "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                            // mihomo milliseconds, sing-box seconds (rounded)
                            "heartbeat-interval" -> bean.heartbeatInterval =
                                opt.value.toString().toLongOrNull()
                                    ?.let { ((it + 500) / 1000).toInt().coerceAtLeast(0) }
                                    ?: bean.heartbeatInterval

                        }
                    }
                    if (ip.isNotBlank()) {
                        val domain = bean.serverAddress
                        bean.serverAddress = ip
                        if (bean.sni.isNullOrBlank() && !domain.isNullOrBlank() && !domain.isIpAddress()) {
                            bean.sni = domain
                        }
                    }
                    proxies.add(bean)
                }

                "wireguard" -> {
                    val bean = WireGuardBean().applyDefaultValues()
                    val localAddresses = mutableListOf<String>()
                    for (opt in proxy) {
                        if (opt.value == null) continue
                        when (opt.key.replace("_", "-")) {
                            "name" -> bean.name = opt.value.toString()
                            "server" -> bean.serverAddress = opt.value.toString()
                            "port" -> bean.serverPort = opt.value.toString().toInt()
                            "ip", "ipv6" -> {
                                val address = opt.value.toString()
                                if (address.isNotBlank()) localAddresses.add(address)
                            }

                            "private-key" -> bean.privateKey = opt.value.toString()
                            "public-key" -> bean.peerPublicKey = opt.value.toString()
                            "pre-shared-key" -> bean.peerPreSharedKey =
                                opt.value.toString()

                            "mtu" -> bean.mtu =
                                opt.value.toString().toIntOrNull() ?: bean.mtu

                            // mihomo writes the three reserved bytes as a
                            // list; the bean keeps genReservedList's comma form
                            "reserved" -> bean.reserved =
                                (opt.value as? List<*>)?.joinToString(",") {
                                    it?.toString() ?: ""
                                } ?: opt.value.toString()

                            "persistent-keepalive" -> bean.peerKeepalive =
                                opt.value.toString().toIntOrNull() ?: 0

                            // stored for export round-trips only; the builder
                            // keeps the default route allowed_ips
                            "allowed-ips" -> bean.peerAllowedIps =
                                (opt.value as? List<*>)?.mapNotNull { it?.toString() }
                                    ?.joinToString(",") ?: opt.value.toString()

                            "remote-dns-resolve", "amnezia-wg-option" -> Logs.w(
                                "clash wireguard ${opt.key.replace("_", "-")} is unsupported, dropped"
                            )
                        }
                    }
                    bean.localAddress = localAddresses.joinToString("\n")
                    proxies.add(bean)
                }
            }
        }.onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
    }

    // Fix ent
    proxies.forEach {
        it.initializeDefaultValues()
        if (it is StandardV2RayBean) {
            // 1. SNI; h2-opts/http-opts hosts arrive newline-joined and the
            // SNI can only carry one of them
            val host = it.host.substringBefore("\n")
            if (it.isTLS() && it.sni.isNullOrBlank() && host.isNotBlank() && !host.isIpAddress()) {
                it.sni = host
            }
            // 2. globalClientFingerprint
            if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                it.utlsFingerprint = globalClientFingerprint
                if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
            }
        }
    }
    return proxies.takeIf { it.isNotEmpty() } ?: error("Not found")
}

private fun clashCipher(cipher: String): String {
    return when (cipher) {
        "dummy" -> "none"
        else -> cipher
    }
}

// mihomo "fingerprint" is the SHA-256 hash of a served certificate; a value
// in any other shape would only fail inside the core, so it is dropped here
private fun parseCertificateFingerprint(value: Any?): String {
    val text = value?.toString() ?: ""
    if (text.isNotEmpty() && !isCertificateFingerprint(text)) {
        Logs.w("clash fingerprint is not a SHA-256 digest, dropped")
        return ""
    }
    return text
}
