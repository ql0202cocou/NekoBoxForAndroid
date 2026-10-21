package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NAIVE
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN_GO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TUIC
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.CoreTestNodes.CERT
import io.nekohasekai.sagernet.fmt.CoreTestNodes.ECH
import io.nekohasekai.sagernet.fmt.CoreTestNodes.MLDSA65
import io.nekohasekai.sagernet.fmt.CoreTestNodes.PIN
import io.nekohasekai.sagernet.fmt.CoreTestNodes.REALITY_KEY
import io.nekohasekai.sagernet.fmt.CoreTestNodes.SHORT_ID
import io.nekohasekai.sagernet.fmt.CoreTestNodes.UUID
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseV2RayNJson
import io.nekohasekai.sagernet.fmt.v2ray.toV2rayNJson
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import java.util.Base64

// 有专属分享链接格式的协议：字段齐全的 bean（按变体拆开，一个链接装不下互斥的分支）、导出函数、解析函数，
// 以及「有意不进链接」的字段清单。ProtocolLinkTest 逐个变体做 导出 → 解析 → 逐字段比较，并核对：
// - 清单里的字段确实没被链接带走（填成非默认值导出，解析回来不同）；
// - 不在清单里的每个字段至少在一个变体里是非默认值（否则漏导出这个字段测不出来）。
// 新增 bean 字段又没进链接时，这两条会让测试失败，要求在这里补变体或写进清单并说明原因

class LinkCase(val id: String, val bean: () -> AbstractBean)

class LinkSpec(
    val protocol: String,
    val type: Int,
    val beanClass: Class<out AbstractBean>,
    // 链接开头
    val prefix: String,
    // 有意不进链接的字段 -> 原因
    val neverInLink: Map<String, String>,
    val cases: List<LinkCase>,
    // 链接格式本身决定取值、而该值恰好就是默认值的字段 -> 原因（没有变体能把它设成非默认值又仍属于这个格式）
    val fixedByFormat: Map<String, String> = emptyMap(),
    // 导出：生产的导出函数（SS、VMess 的 base64 在 JVM 上跑不了，见各自的说明）
    val export: (AbstractBean) -> String,
    // 解析：生产的解析入口，返回的 bean 已走过 initializeDefaultValues
    val parse: (String) -> AbstractBean,
)

object ProtocolLinkCases {

    private const val GENERIC = "AbstractBean 的高级 JSON 覆盖（自定义出站 / 自定义配置），不进分享链接"
    private const val MUX = "Mux 设置不进分享链接（各协议的链接格式都没有 mux 参数）"
    private const val XHTTP = "XHTTP 的 mode / extra 导入导出稍后随分享链接一起加入，目前链接格式不带这两项"

    private fun generic() = mapOf(
        "customOutboundJson" to GENERIC,
        "customConfigJson" to GENERIC,
    )

    // 走 parseProxies 的前缀分派：链接前缀 -> 解析器 -> 端点校验 -> initializeDefaultValues，与导入路径同一条
    private fun viaParseProxies(link: String): AbstractBean {
        val beans = runBlocking { parseProxies(link) { false } }
        check(beans.size == 1) { "parseProxies 对 ${link.substringBefore("://")}:// 链接返回了 ${beans.size} 个节点" }
        return beans.single()
    }

    private fun standard(bean: AbstractBean) = standardLink(bean)

    // ---- SOCKS

    private fun socks(block: SOCKSBean.() -> Unit) = SOCKSBean().apply {
        name = "socks-node"
        serverAddress = "socks.example.com"
        serverPort = 1081
        block()
        initializeDefaultValues()
    }

    private val socksSpec = LinkSpec(
        "SOCKS", TYPE_SOCKS, SOCKSBean::class.java, "socks",
        neverInLink = generic(),
        cases = listOf(
            LinkCase("socks5") {
                socks { protocol = SOCKSBean.PROTOCOL_SOCKS5; username = "user"; password = "pass-word"; sUoT = true }
            },
            LinkCase("socks4a") { socks { protocol = SOCKSBean.PROTOCOL_SOCKS4A; username = "user4" } },
            LinkCase("socks4") { socks { protocol = SOCKSBean.PROTOCOL_SOCKS4 } },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- HTTP / HTTPS（HttpBean 继承 StandardV2RayBean，用到的只有 TLS 那部分）

    private fun http(block: HttpBean.() -> Unit) = HttpBean().apply {
        name = "http-node"
        serverAddress = "http.example.com"
        serverPort = 3128
        username = "user"
        password = "pass-word"
        block()
        initializeDefaultValues()
    }

    private val httpSpec = LinkSpec(
        "HTTP", TYPE_HTTP, HttpBean::class.java, "http",
        neverInLink = generic() + mapOf(
            "uuid" to "HTTP 代理没有 uuid", "encryption" to "HTTP 代理没有加密 / flow",
            "type" to "HTTP 代理没有传输层", "host" to "HTTP 代理没有传输层", "path" to "HTTP 代理没有传输层",
            "xhttpMode" to "HTTP 代理没有传输层", "xhttpExtra" to "HTTP 代理没有传输层",
            "realityPubKey" to "HTTP 代理没有 REALITY", "realityShortId" to "HTTP 代理没有 REALITY",
            "realityMldsa65Verify" to "HTTP 代理没有 REALITY",
            "wsMaxEarlyData" to "HTTP 代理没有 WebSocket", "earlyDataHeaderName" to "HTTP 代理没有 WebSocket",
            "enableMux" to MUX, "muxPadding" to MUX, "muxType" to MUX, "muxConcurrency" to MUX,
            "packetEncoding" to "HTTP 代理没有 packet encoding",
        ),
        cases = listOf(
            LinkCase("https-full") {
                http {
                    security = "tls"; sni = "sni.example.com"; allowInsecure = true; certificates = CERT
                    certificateFingerprint = PIN; utlsFingerprint = "chrome"; alpn = "h2,http/1.1"
                    enableECH = true; echConfig = ECH
                }
            },
            LinkCase("http-plain") { http { } },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- Shadowsocks

    private fun shadowsocks(block: ShadowsocksBean.() -> Unit) = ShadowsocksBean().apply {
        name = "ss-node"
        serverAddress = "ss.example.com"
        serverPort = 8388
        method = "chacha20-ietf-poly1305"
        password = "pass-word"
        block()
        initializeDefaultValues()
    }

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    // 导出的 userinfo 是 method:password 的 URL-safe base64（生产走 android.util.Base64，JVM 上换成 java.util.Base64）
    private fun ssExport(bean: AbstractBean): String =
        (bean as ShadowsocksBean).toUri { urlEncoder.encodeToString(it.toByteArray()) }

    // 解析的 base64 解码同样走 android.util.Base64：把 userinfo 还原成 ss-android 的明文写法（method:password@host），
    // 解析器的这条分支（密码非空）不碰 base64；再走 parseProxies 的 ss:// 分派
    private fun ssParse(link: String): AbstractBean {
        val userInfo = link.substringAfter("ss://").substringBefore("@")
        val plain = String(urlDecoder.decode(userInfo))
        return viaParseProxies("ss://" + plain + "@" + link.substringAfter("@"))
    }

    private val shadowsocksSpec = LinkSpec(
        "Shadowsocks", TYPE_SS, ShadowsocksBean::class.java, "ss",
        neverInLink = generic() + mapOf(
            "sUoTVersion" to "ss 链接只有 uot=1 一个开关，没有版本参数；链接导入的节点取默认版本 2（只有 Clash 订阅会写 1）",
        ),
        cases = listOf(
            LinkCase("ss-plugin-uot") {
                shadowsocks { plugin = "obfs-local;obfs=http;obfs-host=cdn.example.com"; sUoT = true }
            },
            LinkCase("ss-plain") { shadowsocks { } },
        ),
        export = ::ssExport,
        parse = ::ssParse,
    )

    // ---- VMess（v2rayN 的 base64 JSON）与 VLESS（ducksoft 链接）

    private fun vmess(block: VMessBean.() -> Unit) = VMessBean().apply {
        name = "vmess-node"
        serverAddress = "vmess.example.com"
        serverPort = 8443
        uuid = UUID
        alterId = 4
        encryption = "chacha20-poly1305"
        block()
        initializeDefaultValues()
    }

    private fun vless(block: VMessBean.() -> Unit) = VMessBean().apply {
        name = "vless-node"
        serverAddress = "vless.example.com"
        serverPort = 8443
        uuid = UUID
        alterId = -1
        encryption = "xtls-rprx-vision" // VLESS 的 flow
        block()
        initializeDefaultValues()
    }

    private val vmessSpec = LinkSpec(
        "VMess", TYPE_VMESS, VMessBean::class.java, "vmess",
        neverInLink = generic() + mapOf(
            "enableMux" to MUX, "muxPadding" to MUX, "muxType" to MUX, "muxConcurrency" to MUX,
            "xhttpMode" to XHTTP, "xhttpExtra" to XHTTP,
        ),
        cases = listOf(
            LinkCase("vmess-ws-tls-full") {
                vmess {
                    type = "ws"; host = "cdn.example.com"; path = "/ws"
                    wsMaxEarlyData = 2048; earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    security = "tls"; sni = "sni.example.com"; alpn = "h2,http/1.1"; utlsFingerprint = "chrome"
                    allowInsecure = true; certificates = CERT; certificateFingerprint = PIN
                    enableECH = true; echConfig = ECH; packetEncoding = 2
                }
            },
            LinkCase("vmess-reality-tcp") {
                vmess {
                    security = "tls"; sni = "sni.example.com"; utlsFingerprint = "firefox"
                    realityPubKey = REALITY_KEY; realityShortId = SHORT_ID; realityMldsa65Verify = MLDSA65
                    packetEncoding = 1
                }
            },
            LinkCase("vmess-h2-tls") { vmess { type = "http"; host = "h2.example.com"; path = "/h2"; security = "tls"; sni = "h2.example.com" } },
            LinkCase("vmess-tcp-http-header") { vmess { type = "http"; host = "fake.example.com"; path = "/" } },
        ),
        // v2rayN 链接 = "vmess://" + base64(JSON)；base64 走 android.util.Base64，这里比 JSON 这一段
        export = { (it as VMessBean).toV2rayNJson() },
        parse = { parseV2RayNJson(it).apply { initializeDefaultValues() } },
    )

    private val vlessSpec = LinkSpec(
        "VLESS", TYPE_VMESS, VMessBean::class.java, "vless",
        neverInLink = generic() + mapOf(
            "enableMux" to MUX, "muxPadding" to MUX, "muxType" to MUX, "muxConcurrency" to MUX,
            "xhttpMode" to XHTTP, "xhttpExtra" to XHTTP,
        ),
        cases = listOf(
            LinkCase("vless-ws-tls-full") {
                vless {
                    type = "ws"; host = "cdn.example.com"; path = "/ws"
                    wsMaxEarlyData = 2048; earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    security = "tls"; sni = "sni.example.com"; alpn = "h2,http/1.1"; utlsFingerprint = "chrome"
                    allowInsecure = true; certificates = CERT; certificateFingerprint = PIN
                    enableECH = true; echConfig = ECH; packetEncoding = 2
                }
            },
            LinkCase("vless-reality-tcp") {
                vless {
                    security = "tls"; sni = "sni.example.com"; utlsFingerprint = "firefox"
                    realityPubKey = REALITY_KEY; realityShortId = SHORT_ID; realityMldsa65Verify = MLDSA65
                    packetEncoding = 1
                }
            },
            LinkCase("vless-grpc") { vless { type = "grpc"; path = "grpc-service"; security = "tls"; sni = "grpc.example.com" } },
            LinkCase("vless-httpupgrade") {
                vless { type = "httpupgrade"; host = "up.example.com"; path = "/up"; security = "tls"; sni = "up.example.com" }
            },
            LinkCase("vless-tcp-http-header") { vless { type = "http"; host = "fake.example.com"; path = "/" } },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- Trojan

    private fun trojan(block: TrojanBean.() -> Unit) = TrojanBean().apply {
        name = "trojan-node"
        serverAddress = "trojan.example.com"
        serverPort = 443
        password = "pass-word"
        block()
        initializeDefaultValues()
    }

    private val trojanSpec = LinkSpec(
        "Trojan", TYPE_TROJAN, TrojanBean::class.java, "trojan",
        neverInLink = generic() + mapOf(
            "uuid" to "Trojan 用 password，没有 uuid", "encryption" to "Trojan 没有加密 / flow",
            "enableMux" to MUX, "muxPadding" to MUX, "muxType" to MUX, "muxConcurrency" to MUX,
            "xhttpMode" to XHTTP, "xhttpExtra" to XHTTP,
        ),
        cases = listOf(
            LinkCase("trojan-ws-tls-full") {
                trojan {
                    type = "ws"; host = "cdn.example.com"; path = "/ws"
                    wsMaxEarlyData = 2048; earlyDataHeaderName = "Sec-WebSocket-Protocol"
                    security = "tls"; sni = "sni.example.com"; alpn = "h2,http/1.1"; utlsFingerprint = "chrome"
                    allowInsecure = true; certificates = CERT; certificateFingerprint = PIN
                    enableECH = true; echConfig = ECH; packetEncoding = 2
                }
            },
            LinkCase("trojan-reality-grpc") {
                trojan {
                    type = "grpc"; path = "grpc-service"
                    security = "tls"; sni = "sni.example.com"; utlsFingerprint = "firefox"
                    realityPubKey = REALITY_KEY; realityShortId = SHORT_ID; realityMldsa65Verify = MLDSA65
                    packetEncoding = 1
                }
            },
            LinkCase("trojan-plain") { trojan { security = "none" } },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- Trojan-Go

    private val trojanGoSpec = LinkSpec(
        "Trojan-Go", TYPE_TROJAN_GO, TrojanGoBean::class.java, "trojan-go",
        neverInLink = generic(),
        cases = listOf(
            LinkCase("trojan-go-ws") {
                TrojanGoBean().apply {
                    name = "trojan-go-node"; serverAddress = "tg.example.com"; serverPort = 443
                    password = "pass-word"; sni = "sni.example.com"; allowInsecure = true
                    type = "ws"; host = "cdn.example.com"; path = "/tg"
                    encryption = "ss;aes-128-gcm:secret"; plugin = "obfs-local"
                    initializeDefaultValues()
                }
            },
            LinkCase("trojan-go-original") {
                TrojanGoBean().apply {
                    name = "trojan-go-plain"; serverAddress = "tg.example.com"; serverPort = 443
                    password = "pass-word"; initializeDefaultValues()
                }
            },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- Naive

    private val naiveSpec = LinkSpec(
        "Naive", TYPE_NAIVE, NaiveBean::class.java, "naive+",
        neverInLink = generic(),
        cases = listOf(
            LinkCase("naive-quic-full") {
                NaiveBean().apply {
                    name = "naive-node"; serverAddress = "naive.example.com"; serverPort = 8443
                    proto = "quic"; username = "user"; password = "pass-word"; sni = "sni.example.com"
                    certificates = CERT; extraHeaders = "X-A: b\nX-B: c"; insecureConcurrency = 3; sUoT = true
                    initializeDefaultValues()
                }
            },
            LinkCase("naive-https") {
                NaiveBean().apply {
                    name = "naive-plain"; serverAddress = "naive.example.com"; serverPort = 443
                    username = "user"; password = "pass-word"; initializeDefaultValues()
                }
            },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- Hysteria 1 / 2（同一个 bean 类，两种链接）

    private const val V2_ONLY = "Hysteria 2 的链接格式没有这个参数"
    private const val SERVER_PORT = "Hysteria 的真实端口在 serverPorts（支持端口跳跃），serverPort 是过时的默认值"
    private const val V1_ONLY = "Hysteria 1 的链接格式没有这个参数"

    private val hysteria1Spec = LinkSpec(
        "Hysteria1", TYPE_HYSTERIA, HysteriaBean::class.java, "hysteria",
        neverInLink = generic() + mapOf(
            "serverPort" to SERVER_PORT,
            "streamReceiveWindow" to "链接格式没有 QUIC 窗口参数",
            "connectionReceiveWindow" to "链接格式没有 QUIC 窗口参数",
            "disableMtuDiscovery" to "链接格式没有该参数",
        ),
        cases = listOf(
            LinkCase("hysteria1-faketcp-full") {
                HysteriaBean().apply {
                    name = "hy1-node"; serverAddress = "hy1.example.com"; serverPorts = "4443"
                    protocolVersion = 1; authPayloadType = HysteriaBean.TYPE_STRING; authPayload = "secret-auth"
                    protocol = HysteriaBean.PROTOCOL_FAKETCP; obfuscation = "obfs-pass"; sni = "sni.example.com"
                    alpn = "hysteria"; allowInsecure = true; caText = CERT; certificateFingerprint = PIN
                    uploadMbps = 77; downloadMbps = 188; hopInterval = 25
                    initializeDefaultValues()
                }
            },
            LinkCase("hysteria1-mport") {
                HysteriaBean().apply {
                    name = "hy1-hop"; serverAddress = "hy1.example.com"; serverPorts = "443,5000-6000"
                    protocolVersion = 1; authPayloadType = HysteriaBean.TYPE_STRING; authPayload = "secret-auth"
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                    initializeDefaultValues()
                }
            },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    private val hysteria2Spec = LinkSpec(
        "Hysteria2", TYPE_HYSTERIA, HysteriaBean::class.java, "hy2",
        neverInLink = generic() + mapOf(
            "serverPort" to SERVER_PORT,
            "authPayloadType" to "Hysteria 2 的认证串固定是明文字符串，类型字段只用于 Hysteria 1",
            "protocol" to V1_ONLY + "（faketcp / wechat-video 是 Hysteria 1 的传输）",
            "alpn" to V2_ONLY,
            "streamReceiveWindow" to "链接格式没有 QUIC 窗口参数",
            "connectionReceiveWindow" to "链接格式没有 QUIC 窗口参数",
            "disableMtuDiscovery" to "链接格式没有该参数",
        ),
        cases = listOf(
            LinkCase("hysteria2-full") {
                HysteriaBean().apply {
                    name = "hy2-node"; serverAddress = "hy2.example.com"; serverPorts = "443,5000-6000"
                    protocolVersion = 2; authPayload = "user:pass-word"; obfuscation = "salamander-pass"
                    sni = "sni.example.com"; allowInsecure = true; caText = CERT; certificateFingerprint = PIN
                    uploadMbps = 77; downloadMbps = 188; hopInterval = 25
                    initializeDefaultValues()
                }
            },
            LinkCase("hysteria2-single-port") {
                HysteriaBean().apply {
                    name = "hy2-plain"; serverAddress = "hy2.example.com"; serverPorts = "8443"
                    protocolVersion = 2; authPayload = "pass-word"
                    initializeDefaultValues()
                }
            },
        ),
        fixedByFormat = mapOf("protocolVersion" to "hy2:// 与 hysteria2:// 就是 Hysteria 2，默认值也是 2；v1 由另一个规格覆盖"),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- TUIC

    private val tuicSpec = LinkSpec(
        "TUIC", TYPE_TUIC, TuicBean::class.java, "tuic",
        neverInLink = generic() + mapOf(
            "mtu" to "链接格式没有 MTU 参数",
            "fastConnect" to "链接格式没有该参数",
            "customJSON" to "链接格式没有自定义 JSON",
            "heartbeatInterval" to "链接格式没有心跳参数",
            "protocolVersion" to "链接格式只有 TUIC v5，解析固定写 5（v4 链接直接拒绝）",
        ),
        cases = listOf(
            LinkCase("tuic-full") {
                TuicBean().apply {
                    name = "tuic-node"; serverAddress = "tuic.example.com"; serverPort = 8443
                    protocolVersion = 5; uuid = UUID; token = "pass-word"; sni = "sni.example.com"
                    caText = CERT; certificateFingerprint = PIN; congestionController = "bbr"; udpRelayMode = "quic"
                    alpn = "h3"; allowInsecure = true; disableSNI = true; reduceRTT = true
                    initializeDefaultValues()
                }
            },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    // ---- AnyTLS

    private val anytlsSpec = LinkSpec(
        "AnyTLS", TYPE_ANYTLS, AnyTLSBean::class.java, "anytls",
        neverInLink = generic(),
        cases = listOf(
            // ECH 配置与「只开启 ECH」是两种写法：链接里 ech=<配置> 解析回来只有 echConfig，enableECH 不置位
            LinkCase("anytls-full-ech-config") {
                AnyTLSBean().apply {
                    name = "anytls-node"; serverAddress = "anytls.example.com"; serverPort = 8443
                    password = "pass-word"; sni = "sni.example.com"; alpn = "h2,http/1.1"; certificates = CERT
                    certificateFingerprint = PIN; utlsFingerprint = "chrome"; allowInsecure = true; echConfig = ECH
                    initializeDefaultValues()
                }
            },
            LinkCase("anytls-ech-enable-only") {
                AnyTLSBean().apply {
                    name = "anytls-ech"; serverAddress = "anytls.example.com"; serverPort = 8443
                    password = "pass-word"; enableECH = true
                    initializeDefaultValues()
                }
            },
        ),
        export = ::standard,
        parse = ::viaParseProxies,
    )

    /** 按协议（不是按类型：VMess / VLESS、Hysteria 1 / 2 各一项）列出的链接规格。 */
    val SPECS: List<LinkSpec> = listOf(
        socksSpec, httpSpec, shadowsocksSpec, vmessSpec, vlessSpec, trojanSpec, trojanGoSpec,
        naiveSpec, hysteria1Spec, hysteria2Spec, tuicSpec, anytlsSpec,
    )

    /**
     * 有专属链接格式的类型。其余类型的分享走通用链接（sn://），见 ProtocolContractTest：
     * Mieru / SSH / WireGuard / ShadowTLS / Config 没有专属格式；Chain / Neko 不分享。
     */
    val DEDICATED_TYPES: Set<Int> get() = SPECS.map { it.type }.toSet()
}
