package io.nekohasekai.sagernet.fmt.v2ray

import android.text.TextUtils
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.nekohasekai.sagernet.fmt.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
    var verify_cert: Boolean = true,
    // not a v2rayN standard field; "ech" matches our ducksoft query param, "echConfig" is the fallback
    @SerializedName("ech", alternate = ["echConfig"])
    var ech: String = "",
    // same story for REALITY: "pbk"/"sid" match the ducksoft query params
    @SerializedName("pbk", alternate = ["publicKey"])
    var pbk: String = "",
    @SerializedName("sid", alternate = ["shortId"])
    var sid: String = "",
    // "pqv" matches the ducksoft query param
    @SerializedName("pqv", alternate = ["mldsa65Verify"])
    var pqv: String = "",
    // "cert" matches the ducksoft query param
    @SerializedName("cert", alternate = ["certificates"])
    var cert: String = "",
    // "certfp" matches the ducksoft query param
    @SerializedName("certfp", alternate = ["certificateFingerprint"])
    var certfp: String = "",
    var packetEncoding: String = "",
    // ws early data, same names as the ducksoft query params
    var ed: Int = 0,
    var eh: String = "",
)

fun StandardV2RayBean.isTLS(): Boolean {
    return security == "tls"
}

fun StandardV2RayBean.setTLS(boolean: Boolean) {
    security = if (boolean) "tls" else ""
}

fun parseV2Ray(link: String): StandardV2RayBean {
    // Try parse stupid formats first

    if (!link.contains("?")) {
        try {
            return parseV2RayN(link)
        } catch (e: Exception) {
            Logs.i("try v2rayN: " + e.readableMessage)
        }
    }

    try {
        return tryResolveVmess4Kitsunebi(link)
    } catch (e: Exception) {
        Logs.i("try Kitsunebi: " + e.readableMessage)
    }

    // "std" format

    val bean = VMessBean().apply { if (link.startsWith("vless://")) alterId = -1 }
    // scheme swap by prefix, not replace(): a global replace also rewrites a
    // fragment or query that happens to contain the scheme string
    val url = link.withHttpScheme().toHttpUrl()

    if (url.password.isNotBlank()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26 (rarely use)
        bean.serverAddress = url.host
        bean.serverPort = url.port
        bean.name = url.fragment

        var protocol = url.username
        bean.type = protocol
        bean.alterId = url.password.substringAfterLast('-').toIntOrNull()
            ?: error("invalid link $link")
        bean.uuid = url.password.substringBeforeLast('-')

        if (protocol.endsWith("+tls")) {
            bean.security = "tls"
            protocol = protocol.substring(0, protocol.length - 4)

            url.queryParameter("tlsServerName")?.let {
                if (it.isNotBlank()) {
                    bean.sni = it
                }
            }
        }

        when (protocol) {
            "http" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it.split("|").joinToString(",")
                }
            }

            "ws", "httpupgrade" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "grpc" -> {
                url.queryParameter("serviceName")?.let {
                    bean.path = it
                }
            }
        }
    } else {
        // also vless format
        bean.parseDuckSoft(url)
    }

    // std 兜底也要过最小校验：vmess:// 后接非法 base64 时 parseV2RayN 与
    // Kitsunebi 都已失败，残片仍可能被 toHttpUrl 解析成「能导入但必挂」的
    // 节点；抛异常让 parseProxies 按解析失败跳过。地址与端口由
    // parseProxies 的 requireValidEndpoint 统一验，这里只管 uuid
    if (bean.uuid.isNullOrBlank() || !bean.uuid.matches(uuidRegex)) {
        error("invalid link $link")
    }

    return bean
}

// https://github.com/XTLS/Xray-core/issues/91
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl) {
    serverAddress = url.host
    serverPort = url.port
    name = url.fragment

    if (this is TrojanBean) {
        password = url.username
    } else {
        uuid = url.username
    }

    // not ducksoft fmt path
    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        // pathSegments drops the leading "/" ("ws" instead of "/ws")
        path = "/" + url.pathSegments.joinToString("/")
    }

    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"

    security = url.queryParameter("security")
    if (security.isNullOrBlank()) {
        security = if (this is TrojanBean) "tls" else "none"
    }

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            url.queryParameter("allowInsecure")?.let {
                allowInsecure = it == "1" || it == "true"
            }
            url.queryParameter("sni")?.let {
                sni = it
            }
            url.queryParameter("host")?.let {
                if (sni.isNullOrBlank()) sni = it
            }
            url.queryParameter("alpn")?.let {
                alpn = it
            }
            url.queryParameter("cert")?.let {
                certificates = it
            }
            // 自家参数，与 anytls 的 certfp 同名
            (url.queryParameter("certfp") ?: url.queryParameter("certificateFingerprint"))?.let {
                certificateFingerprint = it
            }
            url.queryParameter("pbk")?.let {
                realityPubKey = it
            }
            url.queryParameter("sid")?.let {
                realityShortId = it
            }
            (url.queryParameter("pqv") ?: url.queryParameter("mldsa65Verify"))?.let {
                realityMldsa65Verify = it
            }
            (url.queryParameter("ech") ?: url.queryParameter("echConfig"))?.let {
                enableECH = true
                // "1" marks enable-only (query DNS for the config), a real config is base64
                if (it != "1") echConfig = it
            }
        }
    }

    when (type) {
        "http", "ws", "httpupgrade" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
            if (type == "ws") {
                url.queryParameter("ed")?.let { ed ->
                    wsMaxEarlyData = ed.toIntOrNull()

                    url.queryParameter("eh")?.let {
                        earlyDataHeaderName = it
                    }
                }
            }
        }

        "grpc" -> {
            url.queryParameter("serviceName")?.let {
                path = it
            }
        }
    }

    // maybe from matsuri vmess exoprt
    if (this is VMessBean && !isVLESS) {
        url.queryParameter("encryption")?.let {
            encryption = it
        }
    }

    packetEncodingType(url.queryParameter("packetEncoding"))?.let { packetEncoding = it }

    url.queryParameter("flow")?.let {
        if (isVLESS) {
            encryption = it.removeSuffix("-udp443")
        }
    }

    url.queryParameter("fp")?.let {
        utlsFingerprint = it
    }
}

// 不确定是谁的格式
private fun tryResolveVmess4Kitsunebi(server: String): VMessBean {
    // vmess://YXV0bzo1YWY1ZDBlYy02ZWEwLTNjNDMtOTNkYi1jYTMwMDg1MDNiZGJAMTgzLjIzMi41Ni4xNjE6MTIwMg
    // ?remarks=*%F0%9F%87%AF%F0%9F%87%B5JP%20-355%20TG@moon365free&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D&path=/v2ray&obfs=websocket&alterId=0

    var result = server.removePrefix("vmess://")
    val indexSplit = result.indexOf("?")
    if (indexSplit > 0) {
        result = result.substring(0, indexSplit)
    }
    result = NGUtil.decode(result)

    val arr1 = result.split('@')
    if (arr1.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }
    val arr21 = arr1[0].split(':')
    val arr22 = arr1[1].split(':')
    if (arr21.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }

    return VMessBean().apply {
        serverAddress = arr22[0]
        serverPort = NGUtil.parseInt(arr22[1])
        uuid = arr21[1]
        encryption = arr21[0]
        // 与 parseV2Ray 的 std 兜底同一层最小校验：畸形链接不该导入成必坏
        // 节点。地址与端口同样交给 requireValidEndpoint
        if (!uuid.matches(uuidRegex)) {
            error("invalid kitsunebi vmess link")
        }
        if (indexSplit < 0) return@apply

        val url = ("https://localhost/path?" + server.substringAfter("?")).toHttpUrl()
        url.queryParameter("remarks")?.apply { name = this }
        url.queryParameter("alterId")?.apply { alterId = this.toIntOrNull() ?: 0 }
        url.queryParameter("path")?.apply { path = this }
        url.queryParameter("tls")?.apply { security = "tls" }
        url.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        url.queryParameter("obfs")?.apply {
            type = this.replace("websocket", "ws").replace("none", "tcp")
            if (type == "ws") {
                url.queryParameter("obfsParam")?.apply {
                    if (this.startsWith("{")) {
                        host = JSONObject(this).getStr("Host")
                    } else if (security == "tls") {
                        sni = this
                    }
                }
            }
        }
    }
}

// SagerNet's
// Do not support some format and then throw exception
fun parseV2RayN(link: String): VMessBean {
    val result = link.substringAfter("vmess://").decodeBase64UrlSafe()
    if (result.contains("= vmess")) {
        return parseCsvVMess(result)
    }
    val bean = VMessBean()
    val vmessQRCode = Gson().fromJson(result, VmessQRCode::class.java)

    // Although VmessQRCode fields are non null, looks like Gson may still create null fields
    if (TextUtils.isEmpty(vmessQRCode.add)
        || TextUtils.isEmpty(vmessQRCode.port)
        || TextUtils.isEmpty(vmessQRCode.id)
        || TextUtils.isEmpty(vmessQRCode.net)
    ) {
        throw Exception("invalid VmessQRCode")
    }

    bean.name = vmessQRCode.ps
    bean.serverAddress = vmessQRCode.add
    // reject rather than fall back to the 1080 default: a bogus profile looks imported
    bean.serverPort = vmessQRCode.port.toIntOrNull() ?: throw Exception("invalid VmessQRCode port")
    bean.encryption = vmessQRCode.scy
    bean.uuid = vmessQRCode.id
    bean.alterId = vmessQRCode.aid.toIntOrNull()
    bean.type = vmessQRCode.net
    bean.host = vmessQRCode.host
    bean.path = vmessQRCode.path
    val headerType = vmessQRCode.type

    when (bean.type) {
        "tcp" -> {
            if (headerType == "http") {
                bean.type = "http"
            }
        }

        // v2rayN spells the h2 transport "h2"; the bean keeps "http"
        "h2" -> bean.type = "http"
    }
    when (vmessQRCode.tls) {
        "tls", "reality" -> {
            bean.security = "tls"
            bean.sni = vmessQRCode.sni
            if (bean.sni.isNullOrBlank()) bean.sni = bean.host
            bean.alpn = vmessQRCode.alpn
            bean.utlsFingerprint = vmessQRCode.fp
            if (!vmessQRCode.cert.isNullOrBlank()) bean.certificates = vmessQRCode.cert
            if (!vmessQRCode.certfp.isNullOrBlank()) bean.certificateFingerprint = vmessQRCode.certfp
            if (!vmessQRCode.verify_cert) bean.allowInsecure = true
            if (!vmessQRCode.ech.isNullOrBlank()) {
                bean.enableECH = true
                // "1" marks enable-only, a real config is base64
                if (vmessQRCode.ech != "1") bean.echConfig = vmessQRCode.ech
            }
            if (vmessQRCode.tls == "reality") {
                bean.realityPubKey = vmessQRCode.pbk
                bean.realityShortId = vmessQRCode.sid
                if (!vmessQRCode.pqv.isNullOrBlank()) bean.realityMldsa65Verify = vmessQRCode.pqv
            }
        }
    }

    packetEncodingType(vmessQRCode.packetEncoding)?.let { bean.packetEncoding = it }

    if (bean.type == "ws" && vmessQRCode.ed > 0) {
        bean.wsMaxEarlyData = vmessQRCode.ed
        if (!vmessQRCode.eh.isNullOrBlank()) {
            bean.earlyDataHeaderName = vmessQRCode.eh
        }
    }

    return bean
}

private fun parseCsvVMess(csv: String): VMessBean {

    // Quantumult writes "name = vmess, host, port, ..." with a space after every
    // comma; untrimmed, the port never parsed and the host kept its leading space
    val args = csv.split(",").map { it.trim() }

    val bean = VMessBean()

    bean.serverAddress = args[1]
    bean.serverPort = args[2].toIntOrNull() ?: error("invalid port in csv vmess")
    bean.encryption = args[3]
    bean.uuid = args[4].replace("\"", "")

    args.subList(5, args.size).forEach {

        when {
            it == "over-tls=true" -> bean.security = "tls"
            it.startsWith("tls-host=") -> bean.host = it.substringAfter("=")
            it.startsWith("obfs=") -> bean.type = it.substringAfter("=")
            it.startsWith("obfs-path=") || it.contains("Host:") -> {
                // each marker guarded on its own: substringAfter() without a match
                // returns the whole field, which used to land in host/path
                if (it.startsWith("obfs-path=")) runCatching {
                    bean.path = it.substringAfter("obfs-path=\"").substringBefore("\"obfs")
                }
                if (it.contains("Host:")) runCatching {
                    bean.host = it.substringAfter("Host:").substringBefore("[")
                }
            }

        }

    }

    return bean

}

fun VMessBean.toV2rayN(): String {
    val bean = this
    return "vmess://" + VmessQRCode().apply {
        v = "2"
        ps = bean.name
        add = bean.serverAddress
        port = bean.serverPort.toString()
        id = bean.uuid
        aid = bean.alterId.toString()
        net = bean.type
        host = bean.host
        path = bean.path

        when (net) {
            "http" -> {
                if (isTLS()) {
                    // v2rayN spells the h2 transport "h2"; "http" means the
                    // tcp fake-http header
                    net = "h2"
                } else {
                    type = "http"
                    net = "tcp"
                }
            }
        }

        if (isTLS()) {
            tls = "tls"
            if (bean.certificates.isNotBlank()) {
                cert = bean.certificates
            }
            if (bean.certificateFingerprint.isNotBlank()) {
                certfp = bean.certificateFingerprint
            }
            if (bean.realityPubKey.isNotBlank()) {
                tls = "reality"
                pbk = bean.realityPubKey
                sid = bean.realityShortId
                if (bean.realityMldsa65Verify.isNotBlank()) {
                    pqv = bean.realityMldsa65Verify
                }
            }
        }

        packetEncodingName(bean.packetEncoding)?.let { packetEncoding = it }

        if (net == "ws" && bean.wsMaxEarlyData > 0) {
            ed = bean.wsMaxEarlyData
            if (bean.earlyDataHeaderName.isNotBlank()) {
                eh = bean.earlyDataHeaderName
            }
        }

        scy = bean.encryption
        sni = bean.sni
        alpn = bean.alpn.replace("\n", ",")
        fp = bean.utlsFingerprint
        verify_cert = !bean.allowInsecure
        if (bean.enableECH) {
            // "1" marks enable-only (no pinned config)
            ech = bean.echConfig.ifBlank { "1" }
        }
    }.let {
        NGUtil.encode(Gson().toJson(it))
    }
}

fun StandardV2RayBean.toUriVMessVLESSTrojan(isTrojan: Boolean): String {
    // VMess
    if (this is VMessBean && !isVLESS) {
        return toV2rayN()
    }

    // VLESS & Trojan (ducksoft fmt)
    val builder = linkBuilder()
        .username(if (this is TrojanBean) password else uuid)
        .host(serverAddress)
        .port(serverPort)
        .addQueryParameter("type", type)

    if (isVLESS) {
        builder.addQueryParameter("encryption", "none")
        if (encryption.isNotBlank() && encryption != "auto") builder.addQueryParameter("flow", encryption)
    }

    when (type) {
        "tcp" -> {}
        "ws", "http", "httpupgrade" -> {
            if (host.isNotBlank()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotBlank()) {
                builder.addQueryParameter("path", path)
            }
            if (type == "ws") {
                if (wsMaxEarlyData > 0) {
                    builder.addQueryParameter("ed", "$wsMaxEarlyData")
                    if (earlyDataHeaderName.isNotBlank()) {
                        builder.addQueryParameter("eh", earlyDataHeaderName)
                    }
                }
            } else if (type == "http" && !isTLS()) {
                builder.setQueryParameter("type", "tcp")
                builder.addQueryParameter("headerType", "http")
            }
        }

        "grpc" -> {
            if (path.isNotBlank()) {
                builder.setQueryParameter("serviceName", path)
            }
        }
    }

    // "none" must be written for trojan: the parser defaults a missing
    // security param back to "tls" for trojan links
    if (security.isNotBlank() && (security != "none" || isTrojan)) {
        builder.addQueryParameter("security", security)
        when (security) {
            "tls" -> {
                if (sni.isNotBlank()) {
                    builder.addQueryParameter("sni", sni)
                }
                if (alpn.isNotBlank()) {
                    builder.addQueryParameter("alpn", alpn.replace("\n", ","))
                }
                if (certificates.isNotBlank()) {
                    builder.addQueryParameter("cert", certificates)
                }
                if (certificateFingerprint.isNotBlank()) {
                    builder.addQueryParameter("certfp", certificateFingerprint)
                }
                if (allowInsecure) {
                    builder.addQueryParameter("allowInsecure", "1")
                }
                if (utlsFingerprint.isNotBlank()) {
                    builder.addQueryParameter("fp", utlsFingerprint)
                }
                if (enableECH) {
                    // "1" marks enable-only (no pinned config)
                    builder.addQueryParameter("ech", echConfig.ifBlank { "1" })
                }
                if (realityPubKey.isNotBlank()) {
                    builder.setQueryParameter("security", "reality")
                    builder.addQueryParameter("pbk", realityPubKey)
                    builder.addQueryParameter("sid", realityShortId)
                    if (realityMldsa65Verify.isNotBlank()) {
                        builder.addQueryParameter("pqv", realityMldsa65Verify)
                    }
                }
            }
        }
    }

    packetEncodingName(packetEncoding)?.let { builder.addQueryParameter("packetEncoding", it) }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink(if (isTrojan) "trojan" else "vless")
}

// WS early-data convention shared by all cores: "?ed=N" / "&ed=N" embedded in the
// path, overridden by the explicit wsMaxEarlyData/earlyDataHeaderName fields.
class WsEarlyData(val path: String, val maxEarlyData: Int?, val headerName: String?)

fun StandardV2RayBean.resolveWsEarlyData(): WsEarlyData {
    // Remove only the early-data parameter, preserving unrelated query values.
    val queryIndex = path.indexOf('?').takeIf { it >= 0 } ?: path.indexOf("&ed=")
    val parameters = if (queryIndex >= 0) path.substring(queryIndex + 1).split('&') else emptyList()
    val embedded = parameters.firstOrNull { it.startsWith("ed=") }
    val basePath = if (embedded != null) {
        val remaining = parameters.filterNot { it.startsWith("ed=") }.joinToString("&")
        path.substring(0, queryIndex).ifBlank { "/" } +
            if (remaining.isNotEmpty()) "?$remaining" else ""
    } else path.ifBlank { "/" }
    val maxEarlyData = wsMaxEarlyData.takeIf { it > 0 } ?: embedded?.let {
        it.substringAfter("=").toIntOrNull() ?: 2048
    }
    val headerName = earlyDataHeaderName.takeIf { it.isNotBlank() }
        ?: if (embedded != null) "Sec-WebSocket-Protocol" else null
    return WsEarlyData(basePath, maxEarlyData, headerName)
}

// TLS fingerprint policy shared by all cores: REALITY requires uTLS, defaulting to chrome.
fun StandardV2RayBean.effectiveUtlsFingerprint(): String? {
    if (!utlsFingerprint.isNullOrBlank()) return utlsFingerprint
    return if (!realityPubKey.isNullOrBlank()) "chrome" else null
}

fun buildSingBoxOutboundStreamSettings(bean: StandardV2RayBean): V2RayTransportOptions? {
    when (bean.type) {
        "tcp" -> {
            return null
        }

        "ws" -> {
            return V2RayTransportOptions_WebsocketOptions().apply {
                type = "ws"
                headers = mutableMapOf()

                if (bean.host.isNotBlank()) {
                    headers["Host"] = bean.host
                }

                val ed = bean.resolveWsEarlyData()
                path = ed.path
                ed.maxEarlyData?.let { max_early_data = it }
                ed.headerName?.let { early_data_header_name = it }
            }
        }

        "http" -> {
            return V2RayTransportOptions_HTTPOptions().apply {
                type = "http"
                if (!bean.isTLS()) method = "GET" // v2ray tcp header
                if (bean.host.isNotBlank()) {
                    // clash h2-opts/http-opts hosts arrive newline-joined
                    host = bean.host.listByLineOrComma()
                }
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
            }
        }

        "quic" -> {
            return V2RayTransportOptions().apply {
                type = "quic"
            }
        }

        "grpc" -> {
            return V2RayTransportOptions_GRPCOptions().apply {
                type = "grpc"
                service_name = bean.path
            }
        }

        "httpupgrade" -> {
            return V2RayTransportOptions_HTTPUpgradeOptions().apply {
                type = "httpupgrade"
                host = bean.host
                path = bean.path
            }
        }
    }

    return null
}

// StandardV2RayBean.muxType <-> multiplex protocol name (sing-box and mihomo
// spell them the same); 0 is h2mux, mihomo's default
fun muxProtocolName(type: Int): String = when (type) {
    1 -> "smux"
    2 -> "yamux"
    else -> "h2mux"
}

fun muxProtocolType(name: String?): Int = when (name) {
    "smux" -> 1
    "yamux" -> 2
    else -> 0
}

// StandardV2RayBean.packetEncoding <-> 分享链接 / sing-box 的 packet encoding 名；
// 0 与未知值都返回 null，由调用处决定省略还是写空串
fun packetEncodingName(type: Int?): String? = when (type) {
    1 -> "packetaddr"
    2 -> "xudp"
    else -> null
}

// 分享链接解析用；未知值返回 null，调用处保持字段不动
fun packetEncodingType(name: String?): Int? = when (name) {
    // 我们导出 "packetaddr"，v2rayN 写的是 "packet"
    "packetaddr", "packet" -> 1
    "xudp" -> 2
    else -> null
}

fun buildSingBoxOutboundStandardV2RayBean(bean: StandardV2RayBean): Outbound {
    when (bean) {
        is HttpBean -> {
            return Outbound_HTTPOptions().apply {
                type = "http"
                server = bean.serverAddress
                server_port = bean.serverPort
                username = bean.username
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
            }
        }

        is VMessBean -> {
            if (bean.isVLESS) return Outbound_VLESSOptions().apply {
                type = "vless"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                if (bean.encryption.isNotBlank() && bean.encryption != "auto") {
                    flow = bean.encryption
                }
                packet_encoding =
                    if (bean.packetEncoding == 0) "" else packetEncodingName(bean.packetEncoding)
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
            return Outbound_VMessOptions().apply {
                type = "vmess"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                alter_id = bean.alterId
                security = bean.encryption.takeIf { it.isNotBlank() } ?: "auto"
                packet_encoding =
                    if (bean.packetEncoding == 0) "" else packetEncodingName(bean.packetEncoding)
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        is TrojanBean -> {
            return Outbound_TrojanOptions().apply {
                type = "trojan"
                server = bean.serverAddress
                server_port = bean.serverPort
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        else -> throw IllegalStateException("can't reach")
    }
}
