package io.nekohasekai.sagernet.golden.collect

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.util.Base64

// 夹具里的地址、凭据与密钥全部是编的：地址只用 example.com / example.net / example.org 的子域、
// 192.0.2.0/24、198.51.100.0/24、203.0.113.0/24 与 2001:db8::/32，端口都在 30000 以下（避开
// 系统分配的临时端口）。密钥类字段只满足现有的格式校验，由固定的 ASCII 文本编码而来。
// 字符串里尽量不留连续五位以上的数字，免得和动态端口在文本上撞车
object Fx {

    // 自签的虚构证书（CN=golden.example.com，P-256），只为让 X.509 解析与 mihomo 指纹换算能跑通
    const val CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBbDCCARICCQCmY4oC6BBYATAKBggqhkjOPQQDAjA+MRswGQYDVQQDDBJnb2xk
ZW4uZXhhbXBsZS5jb20xHzAdBgNVBAoMFk5la29Cb3ggR29sZGVuIEZpeHR1cmUw
HhcNMjYxMDA0MTMzMjU0WhcNMzYxMDAxMTMzMjU0WjA+MRswGQYDVQQDDBJnb2xk
ZW4uZXhhbXBsZS5jb20xHzAdBgNVBAoMFk5la29Cb3ggR29sZGVuIEZpeHR1cmUw
WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASeYqbyaY6yV8EGYEXQtL2PX5SornWo
0ZTko/KUH3bpfGxSc7wK6QoAlMV3SQhOVqFXtKFpT4u802J7Pe5BEbR7MAoGCCqG
SM49BAMCA0gAMEUCIDSZKyuWEIam0/XT22j5huf3hclcKZaffyNM1n5NAP5UAiEA
taGTp3i+MsV/Dm9ZoX08QkQTOVZRnkIJs7xSOM98agY=
-----END CERTIFICATE-----"""

    // 编造的证书 SHA-256 指纹，带冒号、大写，覆盖 mihomo / Xray 的格式归一
    const val CERT_PIN = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    // 格式不对的指纹（63 位十六进制），触发校验报错
    val CERT_PIN_INVALID = "ab".repeat(31) + "c"

    const val UUID_A = "5a0c1e2f-3b4d-4e6f-8a9b-0c1d2e3f4a5b"
    const val UUID_B = "6b1d2f3a-4c5e-4f7a-9b0c-1d2e3f4a5b6c"
    const val UUID_C = "7c2e3a4b-5d6f-4a8b-ac1d-2e3f4a5b6c7d"

    // REALITY 公钥：32 字节的 RawURLEncoding（43 个字符）
    val REALITY_PUBLIC_KEY = rawUrlBase64("golden-reality-public-key-000001".toByteArray())
    const val REALITY_SHORT_ID = "0a1b2c3d4e5f6a7b"

    // ML-DSA-65 公钥：1952 字节的 RawURLEncoding（2603 个字符）
    val MLDSA65_VERIFY = rawUrlBase64(ByteArray(1952) { (it * 7 + 3).toByte() })

    // WireGuard 密钥：32 字节的标准 Base64
    val WG_PRIVATE_KEY = base64("golden-wireguard-private-key-001")
    val WG_PEER_PUBLIC_KEY = base64("golden-wireguard-peer-pubkey-001")
    val WG_PRESHARED_KEY = base64("golden-wireguard-preshared-k-001")

    // Shadowsocks 2022（aes-128）的 16 字节密钥
    val SS2022_KEY = base64("golden-ss2022-16")

    // ECH 配置：编造的字节，只经过 Base64 / PEM 互转，不做内容校验
    val ECH_CONFIG = base64("golden-ech-config-list-fixture-bytes-for-tests-only")

    val SSH_HOST_KEY = "ssh-ed25519 " + base64("golden-ssh-host-key-fixture-0001")
    val SSH_PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
        base64("golden-ssh-private-key-fixture-not-a-real-key").chunked(64).joinToString("\n") +
        "\n-----END OPENSSH PRIVATE KEY-----"

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun rawUrlBase64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun socks(
    name: String,
    server: String = "socks.example.com",
    port: Int = 1080,
    protocol: Int = SOCKSBean.PROTOCOL_SOCKS5,
    username: String = "golden-user",
    password: String = "golden-pass-socks",
    uot: Boolean = false,
) = SOCKSBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.protocol = protocol
    this.username = username
    this.password = password
    sUoT = uot
}

fun httpProxy(
    name: String,
    server: String = "http.example.com",
    port: Int = 8080,
    username: String = "golden-user",
    password: String = "golden-pass-http",
) = HttpBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.username = username
    this.password = password
}

fun shadowsocks(
    name: String,
    server: String = "ss.example.com",
    port: Int = 8388,
    method: String = "aes-256-gcm",
    password: String = "golden-pass-ss",
    plugin: String = "",
    uot: Boolean = false,
) = ShadowsocksBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.method = method
    this.password = password
    this.plugin = plugin
    sUoT = uot
}

fun vmess(
    name: String,
    server: String = "vmess.example.com",
    port: Int = 443,
    uuid: String = Fx.UUID_A,
    security: String = "auto",
) = VMessBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.uuid = uuid
    alterId = 0
    encryption = security
}

// VLESS 存在 VMessBean 里，alterId == -1；encryption 字段存 flow
fun vless(
    name: String,
    server: String = "vless.example.com",
    port: Int = 443,
    uuid: String = Fx.UUID_B,
    flow: String = "",
) = VMessBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.uuid = uuid
    alterId = -1
    encryption = flow
}

fun trojan(
    name: String,
    server: String = "trojan.example.com",
    port: Int = 443,
    password: String = "golden-pass-trojan",
) = TrojanBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.password = password
    security = "tls"
}

fun <T : StandardV2RayBean> T.transport(type: String, host: String = "", path: String = ""): T = apply {
    this.type = type
    this.host = host
    this.path = path
}

fun <T : StandardV2RayBean> T.ws(
    host: String = "cdn.example.org",
    path: String = "/golden-ws",
    maxEarlyData: Int = 0,
    headerName: String = "",
): T = apply {
    type = "ws"
    this.host = host
    this.path = path
    wsMaxEarlyData = maxEarlyData
    earlyDataHeaderName = headerName
}

fun <T : StandardV2RayBean> T.noTls(): T = apply { security = "none" }

fun <T : StandardV2RayBean> T.tls(
    sni: String = "",
    alpn: String = "",
    insecure: Boolean = false,
    utls: String = "",
    certificates: String = "",
    pin: String = "",
): T = apply {
    security = "tls"
    this.sni = sni
    this.alpn = alpn
    allowInsecure = insecure
    utlsFingerprint = utls
    this.certificates = certificates
    certificateFingerprint = pin
}

// insecure：节点的 allowInsecure（REALITY 下不起作用）；不开时不写这个字段，字节与以前相同
fun <T : StandardV2RayBean> T.reality(
    sni: String = "www.example.com",
    publicKey: String = Fx.REALITY_PUBLIC_KEY,
    shortId: String = Fx.REALITY_SHORT_ID,
    mldsa65Verify: String = "",
    utls: String = "",
    insecure: Boolean = false,
): T = apply {
    security = "tls"
    this.sni = sni
    realityPubKey = publicKey
    realityShortId = shortId
    realityMldsa65Verify = mldsa65Verify
    utlsFingerprint = utls
    if (insecure) allowInsecure = true
}

fun <T : StandardV2RayBean> T.ech(config: String = ""): T = apply {
    enableECH = true
    echConfig = config
}

// muxType：0 h2mux、1 smux、2 yamux（sing-mux，只有 sing-box），3 Mux.Cool（MUX_COOL，只有 Xray）
fun <T : StandardV2RayBean> T.mux(type: Int = 0, padding: Boolean = false, concurrency: Int = 1): T = apply {
    enableMux = true
    muxType = type
    muxPadding = padding
    muxConcurrency = concurrency
}

// 1 packetaddr、2 xudp
fun <T : StandardV2RayBean> T.packetEncoding(value: Int): T = apply { packetEncoding = value }

fun <T : AbstractBean> T.customOutbound(json: String): T = apply { customOutboundJson = json }

fun <T : AbstractBean> T.customConfig(json: String): T = apply { customConfigJson = json }

fun wireguard(
    name: String,
    server: String = "wg.example.com",
    port: Int = 21820,
    localAddress: String = "10.66.0.2/32\nfd00:66::2/128",
    mtu: Int = 1408,
    reserved: String = "",
    keepalive: Int = 0,
    allowedIps: String = "",
    preSharedKey: String = "",
) = WireGuardBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.localAddress = localAddress
    privateKey = Fx.WG_PRIVATE_KEY
    peerPublicKey = Fx.WG_PEER_PUBLIC_KEY
    peerPreSharedKey = preSharedKey
    this.mtu = mtu
    this.reserved = reserved
    peerKeepalive = keepalive
    peerAllowedIps = allowedIps
}

fun ssh(
    name: String,
    server: String = "ssh.example.com",
    port: Int = 22,
    username: String = "golden",
    authType: Int = SSHBean.AUTH_TYPE_PASSWORD,
    password: String = "golden-pass-ssh",
    privateKey: String = "",
    passphrase: String = "",
    hostKey: String = "",
) = SSHBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.username = username
    this.authType = authType
    this.password = password
    this.privateKey = privateKey
    privateKeyPassphrase = passphrase
    publicKey = hostKey
}

fun tuic(
    name: String,
    server: String = "tuic.example.com",
    port: Int = 8443,
    version: Int = 5,
    uuid: String = Fx.UUID_C,
    token: String = "golden-pass-tuic",
    sni: String = "",
    alpn: String = "",
    udpRelayMode: String = "native",
    congestion: String = "cubic",
    zeroRtt: Boolean = false,
    heartbeat: Int = 0,
    disableSni: Boolean = false,
    insecure: Boolean = false,
    ca: String = "",
    pin: String = "",
) = TuicBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    protocolVersion = version
    this.uuid = uuid
    this.token = token
    this.sni = sni
    this.alpn = alpn
    this.udpRelayMode = udpRelayMode
    congestionController = congestion
    reduceRTT = zeroRtt
    heartbeatInterval = heartbeat
    disableSNI = disableSni
    allowInsecure = insecure
    caText = ca
    certificateFingerprint = pin
}

fun hysteria2(
    name: String,
    server: String = "hy2.example.com",
    ports: String = "8443",
    password: String = "golden-pass-hy2",
    sni: String = "",
    obfs: String = "",
    up: Int = 0,
    down: Int = 0,
    hopInterval: Int = 10,
    insecure: Boolean = false,
    ca: String = "",
) = HysteriaBean().apply {
    this.name = name
    protocolVersion = 2
    serverAddress = server
    serverPorts = ports
    authPayload = password
    this.sni = sni
    obfuscation = obfs
    uploadMbps = up
    downloadMbps = down
    this.hopInterval = hopInterval
    allowInsecure = insecure
    caText = ca
}

// protocol：0 UDP（sing-box 可跑）、1 faketcp、2 wechat-video（后两者只能走 hysteria 插件）
fun hysteria1(
    name: String,
    server: String = "hy1.example.com",
    ports: String = "8443",
    protocol: Int = HysteriaBean.PROTOCOL_UDP,
    authType: Int = HysteriaBean.TYPE_STRING,
    auth: String = "golden-pass-hy1",
    sni: String = "",
    alpn: String = "hysteria",
    obfs: String = "",
    up: Int = 10,
    down: Int = 50,
    hopInterval: Int = 10,
    streamWindow: Int = 0,
    connectionWindow: Int = 0,
    disableMtuDiscovery: Boolean = false,
    insecure: Boolean = false,
    ca: String = "",
    pin: String = "",
) = HysteriaBean().apply {
    this.name = name
    protocolVersion = 1
    serverAddress = server
    serverPorts = ports
    this.protocol = protocol
    authPayloadType = authType
    authPayload = auth
    this.sni = sni
    this.alpn = alpn
    obfuscation = obfs
    uploadMbps = up
    downloadMbps = down
    this.hopInterval = hopInterval
    streamReceiveWindow = streamWindow
    connectionReceiveWindow = connectionWindow
    this.disableMtuDiscovery = disableMtuDiscovery
    allowInsecure = insecure
    caText = ca
    certificateFingerprint = pin
}

fun anytls(
    name: String,
    server: String = "anytls.example.com",
    port: Int = 8443,
    password: String = "golden-pass-anytls",
    sni: String = "",
    alpn: String = "",
    utls: String = "",
    insecure: Boolean = false,
    certificates: String = "",
    pin: String = "",
    enableEch: Boolean = false,
    echConfig: String = "",
) = AnyTLSBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.password = password
    this.sni = sni
    this.alpn = alpn
    utlsFingerprint = utls
    allowInsecure = insecure
    this.certificates = certificates
    certificateFingerprint = pin
    enableECH = enableEch
    this.echConfig = echConfig
}

fun shadowtls(
    name: String,
    server: String = "shadowtls.example.com",
    port: Int = 443,
    version: Int = 3,
    password: String = "golden-pass-shadowtls",
    sni: String = "www.example.org",
    alpn: String = "",
    utls: String = "",
) = ShadowTLSBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.version = version
    this.password = password
    security = "tls"
    this.sni = sni
    this.alpn = alpn
    utlsFingerprint = utls
}

fun trojanGo(
    name: String,
    server: String = "trojan-go.example.com",
    port: Int = 443,
    password: String = "golden-pass-trojan-go",
    sni: String = "",
    type: String = "original",
    host: String = "",
    path: String = "",
    encryption: String = "none",
    insecure: Boolean = false,
) = TrojanGoBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.password = password
    this.sni = sni
    this.type = type
    this.host = host
    this.path = path
    this.encryption = encryption
    allowInsecure = insecure
}

fun naive(
    name: String,
    server: String = "naive.example.com",
    port: Int = 443,
    proto: String = "https",
    username: String = "golden-user",
    password: String = "golden-pass-naive",
    sni: String = "",
    extraHeaders: String = "",
    certificates: String = "",
    insecureConcurrency: Int = 0,
    uot: Boolean = false,
) = NaiveBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.proto = proto
    this.username = username
    this.password = password
    this.sni = sni
    this.extraHeaders = extraHeaders
    this.certificates = certificates
    this.insecureConcurrency = insecureConcurrency
    sUoT = uot
}

fun mieru(
    name: String,
    server: String = "mieru.example.com",
    port: Int = 2999,
    protocol: String = "TCP",
    username: String = "golden-user",
    password: String = "golden-pass-mieru",
    mtu: Int = 1400,
) = MieruBean().apply {
    this.name = name
    serverAddress = server
    serverPort = port
    this.protocol = protocol
    this.username = username
    this.password = password
    this.mtu = mtu
}

// type 0：整份 sing-box 配置；type 1：一个出站的 JSON
fun customBean(name: String, type: Int, config: String) = ConfigBean().apply {
    this.name = name
    this.type = type
    this.config = config
}
