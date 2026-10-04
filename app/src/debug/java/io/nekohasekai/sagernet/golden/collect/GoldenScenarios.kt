package io.nekohasekai.sagernet.golden.collect

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean.FLOW_VISION
import java.util.Base64

// 场景表：一个场景一组输入（设置、分组、节点、规则、选中的节点），三种模式各采一次。
// 新增场景只往这里加；场景 id 只用小写字母、数字和连字符，一经入库不要改名（基线按 id 对照）。
// 主节点默认是 id 1，分组默认是 1（普通分组）
fun goldenScenarios(): List<Scenario> = scenarioTable {
    singBoxNodes()
    xrayNodes()
    mihomoNodes()
    pluginNodes()
    customConfigs()
    chains()
    frontAndLanding()
    selectors()
    routeRules()
    settingsVariants()
    groupNameservers()
}

private fun ScenarioTable.single(
    id: String,
    description: String,
    core: Int = CORE_AUTO,
    bean: () -> AbstractBean,
) = scenario(id, description) {
    node(1, bean(), core = core)
}

// 一份自洽的完整 sing-box 配置（全配置型自定义节点）
private const val FULL_CONFIG = """{
  "log": {
    "level": "warn"
  },
  "inbounds": [
    {
      "type": "mixed",
      "tag": "mixed-in",
      "listen": "127.0.0.1",
      "listen_port": 2080
    }
  ],
  "outbounds": [
    {
      "type": "vless",
      "tag": "proxy",
      "server": "full-config.example.com",
      "server_port": 443,
      "uuid": "${Fx.UUID_B}",
      "tls": {
        "enabled": true,
        "server_name": "full-config.example.com"
      }
    },
    {
      "type": "direct",
      "tag": "direct"
    }
  ],
  "route": {
    "final": "proxy"
  }
}"""

private const val OUTBOUND_JSON =
    """{"type":"shadowsocks","server":"custom.example.com","server_port":8389,"method":"aes-128-gcm","password":"golden-pass-custom"}"""

private val HY1_AUTH_BASE64 = Base64.getEncoder().encodeToString("golden-hy1-auth-bytes".toByteArray())

private fun ScenarioTable.singBoxNodes() {
    single("sb-socks5", "SOCKS5，带用户名密码，域名服务器") { socks("golden-socks5") }
    single("sb-socks4", "SOCKS4，IPv4 服务器，无认证") {
        socks("golden-socks4", server = "192.0.2.11", protocol = SOCKSBean.PROTOCOL_SOCKS4, username = "", password = "")
    }
    single("sb-socks4a", "SOCKS4a") { socks("golden-socks4a", protocol = SOCKSBean.PROTOCOL_SOCKS4A) }
    single("sb-socks5-uot-ipv6", "SOCKS5 + UDP over TCP，IPv6 服务器") {
        socks("golden-socks5-uot", server = "2001:db8::11", uot = true)
    }
    single("sb-http", "HTTP 代理，带认证") { httpProxy("golden-http") }
    single("sb-https", "HTTPS 代理（SNI、ALPN）") {
        httpProxy("golden-https", port = 8443).tls(sni = "proxy.example.com", alpn = "h2,http/1.1")
    }
    single("sb-ss-aead", "Shadowsocks aes-256-gcm") { shadowsocks("golden-ss-aead") }
    single("sb-ss-2022-uot", "Shadowsocks 2022 + UDP over TCP") {
        shadowsocks("golden-ss-2022", method = "2022-blake3-aes-128-gcm", password = Fx.SS2022_KEY, uot = true)
    }
    single("sb-ss-plugin-obfs", "Shadowsocks + obfs-local 插件参数") {
        shadowsocks("golden-ss-obfs", plugin = "obfs-local;obfs=http;obfs-host=cdn.example.org")
    }
    single("sb-ss-plugin-v2ray", "Shadowsocks + v2ray-plugin 插件参数") {
        shadowsocks("golden-ss-v2ray-plugin", plugin = "v2ray-plugin;mode=websocket;tls;host=ws.example.org;path=/golden")
    }
    single("sb-ss-plugin-none", "Shadowsocks 插件写作 none") { shadowsocks("golden-ss-plugin-none", plugin = "none") }

    // VMess 未设证书指纹时自动选 sing-box
    single("sb-vmess-tcp", "VMess tcp") { vmess("golden-vmess-tcp") }
    single("sb-vmess-tcp-tls", "VMess tcp + TLS") { vmess("golden-vmess-tls").tls(sni = "vmess.example.com") }
    single("sb-vmess-security-aes", "VMess 加密方式 aes-128-gcm") {
        vmess("golden-vmess-aes", security = "aes-128-gcm")
    }
    single("sb-vmess-ws", "VMess ws，路径里带 ?ed=2048") { vmess("golden-vmess-ws").ws(path = "/golden-ws?ed=2048") }
    single("sb-vmess-ws-tls-earlydata", "VMess ws + TLS，显式 early data 与头名") {
        vmess("golden-vmess-ws-ed").ws(maxEarlyData = 2048, headerName = "Sec-WebSocket-Protocol")
            .tls(sni = "cdn.example.org")
    }
    single("sb-vmess-http", "VMess http 传输不带 TLS（tcp 伪 HTTP 头），多个 Host") {
        vmess("golden-vmess-http").transport("http", host = "a.example.org,b.example.org", path = "/golden-http")
    }
    single("sb-vmess-h2", "VMess http 传输带 TLS（h2）") {
        vmess("golden-vmess-h2").transport("http", host = "h2.example.org", path = "/golden-h2").tls(sni = "h2.example.org")
    }
    single("sb-vmess-grpc-tls", "VMess gRPC + TLS") {
        vmess("golden-vmess-grpc").transport("grpc", path = "golden-grpc").tls(sni = "grpc.example.org", alpn = "h2")
    }
    single("sb-vmess-httpupgrade", "VMess httpupgrade") {
        vmess("golden-vmess-hu").transport("httpupgrade", host = "hu.example.org", path = "/golden-hu")
    }
    single("sb-vmess-quic-tls", "VMess quic + TLS") {
        vmess("golden-vmess-quic").transport("quic").tls(sni = "quic.example.org")
    }
    single("sb-vmess-tls-insecure", "VMess TLS allowInsecure") {
        vmess("golden-vmess-insecure").tls(sni = "vmess.example.com", insecure = true)
    }
    single("sb-vmess-tls-utls", "VMess TLS uTLS 指纹") {
        vmess("golden-vmess-utls").tls(sni = "vmess.example.com", utls = "firefox")
    }
    single("sb-vmess-tls-ech-config", "VMess TLS + ECH（内联配置）") {
        vmess("golden-vmess-ech").tls(sni = "vmess.example.com").ech(Fx.ECH_CONFIG)
    }
    single("sb-vmess-tls-ech-auto", "VMess TLS + ECH（不带配置）") {
        vmess("golden-vmess-ech-auto").tls(sni = "vmess.example.com").ech()
    }
    single("sb-vmess-tls-certificates", "VMess TLS 自定义 CA") {
        vmess("golden-vmess-ca").tls(sni = "golden.example.com", certificates = Fx.CERT_PEM)
    }
    single("sb-vmess-reality", "VMess + REALITY 跑在 sing-box 上（默认 uTLS chrome）") {
        vmess("golden-vmess-reality").reality()
    }
    single("sb-vmess-mux-h2mux", "VMess mux h2mux + padding") {
        vmess("golden-vmess-h2mux").tls(sni = "vmess.example.com").mux(type = 0, padding = true, concurrency = 4)
    }
    single("sb-vmess-mux-smux", "VMess mux smux") { vmess("golden-vmess-smux").mux(type = 1) }
    single("sb-vmess-mux-yamux", "VMess mux yamux") { vmess("golden-vmess-yamux").mux(type = 2, concurrency = 8) }
    single("sb-vmess-packetaddr", "VMess packet encoding packetaddr") {
        vmess("golden-vmess-packetaddr").packetEncoding(1)
    }
    single("sb-vmess-ipv6-server", "VMess IPv6 服务器") { vmess("golden-vmess-v6", server = "2001:db8::20").ws() }
    single("sb-vmess-bracketed-ipv6", "VMess 地址带方括号（初始化时去掉）") {
        vmess("golden-vmess-bracket", server = "[2001:db8::21]")
    }
    single("sb-vmess-unknown-transport", "存量节点的未知传输方式 xhttp：应报错") {
        vmess("golden-vmess-xhttp").transport("xhttp", host = "x.example.org", path = "/x")
    }

    single("sb-vless-tcp-tls", "VLESS 手动指定 sing-box，tcp + TLS", core = CORE_SING_BOX) {
        vless("golden-sb-vless-tls").tls(sni = "vless.example.com", alpn = "h2")
    }
    single("sb-vless-reality-vision-xudp", "VLESS REALITY + vision + xudp，手动指定 sing-box", core = CORE_SING_BOX) {
        vless("golden-sb-vless-reality", flow = FLOW_VISION).reality().packetEncoding(2)
    }
    single("sb-vless-vision-mux", "VLESS vision 开 mux：sing-box 上不输出 multiplex", core = CORE_SING_BOX) {
        vless("golden-sb-vless-vision-mux", flow = FLOW_VISION).tls(sni = "vless.example.com").mux(type = 1)
    }
    single("sb-vless-ws-tls", "VLESS ws + TLS，手动指定 sing-box", core = CORE_SING_BOX) {
        vless("golden-sb-vless-ws").ws().tls(sni = "cdn.example.org")
    }
    single("sb-vless-grpc-mux", "VLESS gRPC + TLS + mux，手动指定 sing-box", core = CORE_SING_BOX) {
        vless("golden-sb-vless-grpc").transport("grpc", path = "golden-grpc").tls(sni = "grpc.example.org").mux()
    }
    single("sb-vless-httpupgrade", "VLESS httpupgrade，手动指定 sing-box", core = CORE_SING_BOX) {
        vless("golden-sb-vless-hu").transport("httpupgrade", host = "hu.example.org", path = "/golden-hu")
    }
    single("sb-vless-quic-auto", "VLESS quic：Xray 不支持，自动落到 sing-box") {
        vless("golden-vless-quic").transport("quic").tls(sni = "quic.example.org")
    }
    single("sb-vless-h2-auto", "VLESS h2：Xray 不支持，自动落到 sing-box") {
        vless("golden-vless-h2").transport("http", host = "h2.example.org", path = "/h2").tls(sni = "h2.example.org")
    }
    single("sb-vless-insecure-auto", "VLESS allowInsecure：Xray 不支持，自动落到 sing-box") {
        vless("golden-vless-insecure").tls(sni = "vless.example.com", insecure = true)
    }
    single("sb-vless-reality-mldsa65", "VLESS mldsa65Verify 手动指定 sing-box：应报错", core = CORE_SING_BOX) {
        vless("golden-sb-vless-mldsa", flow = FLOW_VISION).reality(mldsa65Verify = Fx.MLDSA65_VERIFY)
    }

    single("sb-trojan-tcp", "Trojan tcp + TLS") { trojan("golden-trojan").tls(sni = "trojan.example.com") }
    single("sb-trojan-no-tls", "Trojan 关闭 TLS") { trojan("golden-trojan-plain").noTls() }
    single("sb-trojan-ws-tls", "Trojan ws + TLS") { trojan("golden-trojan-ws").ws().tls(sni = "cdn.example.org") }
    single("sb-trojan-grpc-tls", "Trojan gRPC + TLS") {
        trojan("golden-trojan-grpc").transport("grpc", path = "golden-grpc").tls(sni = "grpc.example.org")
    }
    single("sb-trojan-httpupgrade-tls", "Trojan httpupgrade + TLS") {
        trojan("golden-trojan-hu").transport("httpupgrade", host = "hu.example.org", path = "/golden-hu")
            .tls(sni = "hu.example.org")
    }
    single("sb-trojan-h2", "Trojan h2") {
        trojan("golden-trojan-h2").transport("http", host = "h2.example.org", path = "/h2").tls(sni = "h2.example.org")
    }
    single("sb-trojan-quic", "Trojan quic") { trojan("golden-trojan-quic").transport("quic").tls(sni = "quic.example.org") }
    single("sb-trojan-insecure-alpn", "Trojan allowInsecure + ALPN") {
        trojan("golden-trojan-insecure").tls(sni = "trojan.example.com", alpn = "h2\nhttp/1.1", insecure = true)
    }
    single("sb-trojan-utls", "Trojan uTLS") { trojan("golden-trojan-utls").tls(sni = "trojan.example.com", utls = "ios") }
    single("sb-trojan-reality", "Trojan + REALITY（sing-box）") { trojan("golden-trojan-reality").reality(utls = "edge") }
    single("sb-trojan-mux-smux-padding", "Trojan mux smux + padding") {
        trojan("golden-trojan-mux").tls(sni = "trojan.example.com").mux(type = 1, padding = true, concurrency = 2)
    }
    single("sb-trojan-certpin", "Trojan 证书固定：sing-box 不支持，应报错") {
        trojan("golden-trojan-pin").tls(sni = "trojan.example.com", pin = Fx.CERT_PIN)
    }
    single("sb-trojan-mldsa65", "Trojan mldsa65Verify：没有 Xray 路径，应报错") {
        trojan("golden-trojan-mldsa").reality(mldsa65Verify = Fx.MLDSA65_VERIFY)
    }
    single("sb-trojan-reality-bad-key", "REALITY 公钥格式不对：应报错") {
        trojan("golden-trojan-bad-key").reality(publicKey = "golden-short-key")
    }
    single("sb-trojan-reality-bad-short-id", "REALITY short ID 奇数位：应报错") {
        trojan("golden-trojan-bad-sid").reality(shortId = "abc")
    }

    single("sb-wireguard", "WireGuard：双栈本地地址、reserved、keepalive、allowed IPs、PSK") {
        wireguard(
            "golden-wg", reserved = "1,2,3", keepalive = 25, allowedIps = "0.0.0.0/0\n::/0",
            preSharedKey = Fx.WG_PRESHARED_KEY,
        )
    }
    single("sb-wireguard-defaults", "WireGuard：allowed IPs 留空、无 reserved") {
        wireguard("golden-wg-defaults", server = "198.51.100.30", localAddress = "10.66.0.3/32")
    }
    single("sb-wireguard-reserved-brackets", "WireGuard reserved 写成 [a, b, c]") {
        wireguard("golden-wg-reserved", reserved = "[0, 128, 255]", allowedIps = "10.0.0.0/8,fd00::/8")
    }
    single("sb-wireguard-bad-allowed-ips", "WireGuard allowed IPs 不是 CIDR：应报错") {
        wireguard("golden-wg-bad-ips", allowedIps = "0.0.0.0")
    }
    single("sb-wireguard-bad-reserved", "WireGuard reserved 不是三个字节：应报错") {
        wireguard("golden-wg-bad-reserved", reserved = "1,2")
    }

    single("sb-ssh-password", "SSH 密码认证") { ssh("golden-ssh-password") }
    single("sb-ssh-private-key", "SSH 私钥认证 + host key") {
        ssh(
            "golden-ssh-key", server = "203.0.113.25", authType = SSHBean.AUTH_TYPE_PRIVATE_KEY,
            privateKey = Fx.SSH_PRIVATE_KEY, passphrase = "golden-passphrase", hostKey = Fx.SSH_HOST_KEY,
        )
    }

    single("sb-tuic-v5", "TUIC v5：ALPN、quic 中继、0-RTT、心跳、自定义 CA") {
        tuic(
            "golden-tuic", sni = "tuic.example.com", alpn = "h3", udpRelayMode = "quic", congestion = "bbr",
            zeroRtt = true, heartbeat = 10, ca = Fx.CERT_PEM,
        )
    }
    single("sb-tuic-v5-disable-sni-insecure", "TUIC v5：IP 服务器、禁用 SNI、allowInsecure") {
        tuic("golden-tuic-ip", server = "203.0.113.40", disableSni = true, insecure = true)
    }
    single("sb-tuic-v4", "TUIC v4：已不支持，应报错") { tuic("golden-tuic-v4", version = 4) }
    single("sb-tuic-certpin", "TUIC 证书固定：应报错") { tuic("golden-tuic-pin", pin = Fx.CERT_PIN) }

    single("sb-hy2", "Hysteria 2：obfs、SNI、allowInsecure") {
        hysteria2("golden-hy2", sni = "hy2.example.com", obfs = "golden-obfs", insecure = true)
    }
    single("sb-hy2-hopping", "Hysteria 2 端口跳跃与带宽") {
        hysteria2("golden-hy2-hop", ports = "20000-20100,21000", hopInterval = 30, up = 100, down = 500)
    }
    single("sb-hy2-ca", "Hysteria 2 自定义 CA") { hysteria2("golden-hy2-ca", sni = "golden.example.com", ca = Fx.CERT_PEM) }
    single("sb-hy2-certpin", "Hysteria 2 证书固定：应报错") {
        hysteria2("golden-hy2-pin").apply { certificateFingerprint = Fx.CERT_PIN }
    }
    single("sb-hy1-udp", "Hysteria 1（UDP，sing-box）：obfs、接收窗口、关闭 MTU 探测") {
        hysteria1(
            "golden-hy1-udp", obfs = "golden-obfs", streamWindow = 131072, connectionWindow = 262144,
            disableMtuDiscovery = true, sni = "hy1.example.com",
        )
    }
    single("sb-hy1-udp-base64-hopping", "Hysteria 1（UDP）：base64 认证、端口跳跃") {
        hysteria1(
            "golden-hy1-b64", authType = HysteriaBean.TYPE_BASE64, auth = HY1_AUTH_BASE64,
            ports = "20000-20010", hopInterval = 15,
        )
    }

    single("sb-anytls", "AnyTLS 手动指定 sing-box：SNI、ALPN、uTLS、ECH", core = CORE_SING_BOX) {
        anytls("golden-sb-anytls", sni = "anytls.example.com", alpn = "h2,http/1.1", utls = "chrome", echConfig = Fx.ECH_CONFIG)
    }
    single("sb-anytls-certificates", "AnyTLS 手动指定 sing-box：自定义 CA + allowInsecure", core = CORE_SING_BOX) {
        anytls("golden-sb-anytls-ca", certificates = Fx.CERT_PEM, insecure = true)
    }
    single("sb-anytls-certpin", "AnyTLS 手动指定 sing-box 又设证书指纹：应报错", core = CORE_SING_BOX) {
        anytls("golden-sb-anytls-pin", pin = Fx.CERT_PIN)
    }

    single("sb-shadowtls-v3", "ShadowTLS v3：ALPN、uTLS") { shadowtls("golden-shadowtls", alpn = "h2", utls = "chrome") }
    single("sb-shadowtls-v2", "ShadowTLS v2") { shadowtls("golden-shadowtls-v2", version = 2) }
}

private fun ScenarioTable.xrayNodes() {
    // VLESS 默认走 Xray
    single("xray-vless-reality-vision", "VLESS REALITY + vision") {
        vless("golden-xray-reality", flow = FLOW_VISION).reality()
    }
    single("xray-vless-reality-mldsa65", "VLESS REALITY + mldsa65Verify + uTLS") {
        vless("golden-xray-mldsa", flow = FLOW_VISION).reality(mldsa65Verify = Fx.MLDSA65_VERIFY, utls = "firefox")
    }
    single("xray-vless-reality-bad-mldsa65", "mldsa65Verify 长度不对：应报错") {
        vless("golden-xray-bad-mldsa").reality(mldsa65Verify = "golden-too-short")
    }
    single("xray-vless-tls-tcp", "VLESS tcp + TLS + ALPN") {
        vless("golden-xray-tls").tls(sni = "vless.example.com", alpn = "h2,http/1.1")
    }
    single("xray-vless-tls-no-sni-ip", "VLESS TLS 不填 SNI、IP 服务器：SNI 兜底为服务器地址") {
        vless("golden-xray-ip", server = "192.0.2.50").tls()
    }
    single("xray-vless-ws-tls-earlydata", "VLESS ws（?ed=2048）+ TLS") {
        vless("golden-xray-ws").ws(path = "/golden-ws?ed=2048").tls(sni = "cdn.example.org")
    }
    single("xray-vless-ws-maxearlydata", "VLESS ws 显式 early data，路径已有查询参数") {
        vless("golden-xray-ws-ed").ws(path = "/golden-ws?x=1", maxEarlyData = 1024).tls(sni = "cdn.example.org")
    }
    single("xray-vless-grpc-tls", "VLESS gRPC + TLS + uTLS") {
        vless("golden-xray-grpc").transport("grpc", path = "golden-grpc").tls(sni = "grpc.example.org", utls = "safari")
    }
    single("xray-vless-httpupgrade", "VLESS httpupgrade（路径留空）") {
        vless("golden-xray-hu").transport("httpupgrade", host = "hu.example.org")
    }
    single("xray-vless-http-tcp-header", "VLESS http 传输不带 TLS（tcp 伪 HTTP 头），多个 Host") {
        vless("golden-xray-http").transport("http", host = "a.example.org\nb.example.org")
    }
    single("xray-vless-mux", "VLESS mux（并发 0 → 8）") {
        vless("golden-xray-mux").tls(sni = "vless.example.com").mux(concurrency = 0)
    }
    single("xray-vless-xudp", "VLESS packet encoding xudp") {
        vless("golden-xray-xudp").tls(sni = "vless.example.com").packetEncoding(2)
    }
    single("xray-vless-mux-xudp", "VLESS mux + xudp") {
        vless("golden-xray-mux-xudp").tls(sni = "vless.example.com").mux(concurrency = 4).packetEncoding(2)
    }
    single("xray-vless-vision-mux", "VLESS vision 开 mux：Xray 上不输出 mux") {
        vless("golden-xray-vision-mux", flow = FLOW_VISION).tls(sni = "vless.example.com").mux()
    }
    single("xray-vless-packetaddr", "VLESS packetaddr：Xray 上静默忽略") {
        vless("golden-xray-packetaddr").tls(sni = "vless.example.com").packetEncoding(1)
    }
    single("xray-vless-ech", "VLESS TLS + ECH（内联配置）") {
        vless("golden-xray-ech").tls(sni = "vless.example.com").ech(Fx.ECH_CONFIG)
    }
    single("xray-vless-ech-auto", "VLESS TLS + ECH 不带配置：Xray 不输出 ECH") {
        vless("golden-xray-ech-auto").tls(sni = "vless.example.com").ech()
    }
    single("xray-vless-certificates", "VLESS TLS 自定义证书") {
        vless("golden-xray-ca").tls(sni = "golden.example.com", certificates = Fx.CERT_PEM)
    }
    single("xray-vless-ipv6-server", "VLESS REALITY，IPv6 服务器") {
        vless("golden-xray-v6", server = "2001:db8::50").reality()
    }
    single("xray-vless-unknown-transport", "VLESS 未知传输方式 splithttp：应报错") {
        vless("golden-xray-splithttp").transport("splithttp", path = "/s").tls(sni = "vless.example.com")
    }

    // VMess 手动指定 Xray
    single("xray-vmess-tcp", "VMess 手动指定 Xray，tcp", core = CORE_XRAY) { vmess("golden-xray-vmess") }
    single("xray-vmess-ws", "VMess 手动指定 Xray，ws", core = CORE_XRAY) {
        vmess("golden-xray-vmess-ws").ws(host = "", path = "")
    }
    single("xray-vmess-grpc-tls", "VMess 手动指定 Xray，gRPC + TLS", core = CORE_XRAY) {
        vmess("golden-xray-vmess-grpc").transport("grpc", path = "golden-grpc").tls(sni = "grpc.example.org")
    }
    single("xray-vmess-httpupgrade-tls", "VMess 手动指定 Xray，httpupgrade + TLS", core = CORE_XRAY) {
        vmess("golden-xray-vmess-hu").transport("httpupgrade", host = "hu.example.org", path = "/golden-hu")
            .tls(sni = "hu.example.org")
    }
    single("xray-vmess-http-tcp-header", "VMess 手动指定 Xray，tcp 伪 HTTP 头", core = CORE_XRAY) {
        vmess("golden-xray-vmess-http").transport("http", host = "a.example.org", path = "/golden-http")
    }
    single("xray-vmess-security-mux", "VMess 手动指定 Xray，加密方式 + mux", core = CORE_XRAY) {
        vmess("golden-xray-vmess-mux", security = "chacha20-poly1305").mux(concurrency = 3)
    }
    single("xray-vmess-quic", "VMess 手动指定 Xray 却用 quic：应报错", core = CORE_XRAY) {
        vmess("golden-xray-vmess-quic").transport("quic").tls(sni = "quic.example.org")
    }
    single("xray-vmess-h2", "VMess 手动指定 Xray 却用 h2：应报错", core = CORE_XRAY) {
        vmess("golden-xray-vmess-h2").transport("http", path = "/h2").tls(sni = "h2.example.org")
    }
    single("xray-vmess-insecure", "VMess 手动指定 Xray 却开 allowInsecure：应报错", core = CORE_XRAY) {
        vmess("golden-xray-vmess-insecure").tls(sni = "vmess.example.com", insecure = true)
    }
    single("xray-vmess-certpin", "VMess 设证书指纹，自动选 Xray") {
        vmess("golden-xray-vmess-pin").tls(sni = "vmess.example.com", pin = Fx.CERT_PIN)
    }
    single("xray-vmess-certpin-insecure", "证书指纹优先于 allowInsecure") {
        vmess("golden-xray-vmess-pin-insecure").tls(sni = "vmess.example.com", insecure = true, pin = Fx.CERT_PIN)
    }
    single("xray-vmess-certpin-invalid", "证书指纹格式不对：应报错") {
        vmess("golden-xray-vmess-bad-pin").tls(sni = "vmess.example.com", pin = Fx.CERT_PIN_INVALID)
    }
    single("xray-vmess-certpin-quic", "证书指纹 + quic：落到 sing-box 后无法固定，应报错") {
        vmess("golden-vmess-pin-quic").transport("quic").tls(sni = "quic.example.org", pin = Fx.CERT_PIN)
    }
}

private fun ScenarioTable.mihomoNodes() {
    // AnyTLS 默认走 mihomo；单节点测速时 TestInstance 给 mihomo 开 Clash API
    single("mihomo-anytls", "AnyTLS：SNI 留空，兜底为服务器域名") { anytls("golden-anytls") }
    single("mihomo-anytls-full", "AnyTLS：SNI、多行 ALPN、uTLS") {
        anytls("golden-anytls-full", sni = "anytls.example.com", alpn = "h2\nhttp/1.1", utls = "chrome")
    }
    single("mihomo-anytls-certpin", "AnyTLS 证书指纹（带冒号、大写）") {
        anytls("golden-anytls-pin", sni = "anytls.example.com", pin = Fx.CERT_PIN)
    }
    single("mihomo-anytls-certificates", "AnyTLS 自定义证书换算成指纹") {
        anytls("golden-anytls-ca", sni = "golden.example.com", certificates = Fx.CERT_PEM)
    }
    single("mihomo-anytls-certpin-over-insecure", "证书指纹优先于 allowInsecure") {
        anytls("golden-anytls-pin-insecure", pin = Fx.CERT_PIN, insecure = true)
    }
    single("mihomo-anytls-insecure", "AnyTLS allowInsecure") { anytls("golden-anytls-insecure", insecure = true) }
    single("mihomo-anytls-ech-config", "AnyTLS ECH 内联配置") { anytls("golden-anytls-ech", echConfig = Fx.ECH_CONFIG) }
    single("mihomo-anytls-ech-enable", "AnyTLS 只开 ECH") { anytls("golden-anytls-ech-on", enableEch = true) }
    single("mihomo-anytls-ip-server", "AnyTLS IPv4 服务器") { anytls("golden-anytls-ip", server = "203.0.113.60") }
    single("mihomo-anytls-ipv6-server", "AnyTLS IPv6 服务器") { anytls("golden-anytls-v6", server = "2001:db8::60") }
    single("mihomo-anytls-bad-certificate", "AnyTLS 证书解析失败：应报错") {
        anytls("golden-anytls-bad-ca", certificates = "-----BEGIN CERTIFICATE-----\ngolden-not-a-cert\n-----END CERTIFICATE-----")
    }
    single("mihomo-anytls-certpin-invalid", "AnyTLS 证书指纹格式不对：应报错") {
        anytls("golden-anytls-bad-pin", pin = Fx.CERT_PIN_INVALID)
    }
    scenario("mihomo-anytls-group-missing", "AnyTLS 所在分组不存在：测速不开 Clash API") {
        node(1, anytls("golden-anytls-orphan"), group = 9)
        missingGroups += 9L
    }
}

private fun ScenarioTable.pluginNodes() {
    single("ext-trojango", "Trojan-Go original + SNI") { trojanGo("golden-trojan-go", sni = "trojan-go.example.com") }
    single("ext-trojango-no-sni", "Trojan-Go 不填 SNI：经映射时兜底为服务器域名") { trojanGo("golden-trojan-go-nosni") }
    single("ext-trojango-ws-ss-insecure", "Trojan-Go ws + Shadowsocks 加密 + allowInsecure") {
        trojanGo(
            "golden-trojan-go-ws", type = "ws", host = "cdn.example.org", path = "/golden-tg",
            encryption = "ss;aes-128-gcm:golden-pass-tg-ss", insecure = true,
        )
    }
    single("ext-trojango-ip-server", "Trojan-Go IPv4 服务器") { trojanGo("golden-trojan-go-ip", server = "198.51.100.70") }
    single("ext-naive-https", "Naive https：SNI、额外请求头、insecure-concurrency、证书") {
        naive(
            "golden-naive", sni = "naive-sni.example.com", extraHeaders = "X-Golden: a\nX-Fixture: b",
            insecureConcurrency = 2, certificates = Fx.CERT_PEM,
        )
    }
    single("ext-naive-quic-uot", "Naive quic + UDP over TCP") { naive("golden-naive-quic", proto = "quic", uot = true) }
    single("ext-naive-ip-server", "Naive IPv4 服务器、无 SNI") { naive("golden-naive-ip", server = "198.51.100.71") }
    single("ext-naive-ipv6-server", "Naive IPv6 服务器") { naive("golden-naive-v6", server = "2001:db8::71") }
    single("ext-mieru-tcp", "Mieru TCP") { mieru("golden-mieru") }
    single("ext-mieru-udp", "Mieru UDP + MTU") { mieru("golden-mieru-udp", protocol = "UDP", mtu = 1350) }
    single("ext-hy1-faketcp", "Hysteria 1 faketcp（不能映射）+ base64 认证") {
        hysteria1(
            "golden-hy1-faketcp", protocol = HysteriaBean.PROTOCOL_FAKETCP,
            authType = HysteriaBean.TYPE_BASE64, auth = HY1_AUTH_BASE64,
        )
    }
    single("ext-hy1-wechat-ca-hopping", "Hysteria 1 wechat-video：CA 临时文件、多端口、接收窗口、obfs") {
        hysteria1(
            "golden-hy1-wechat", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO, ports = "20000-20010",
            ca = Fx.CERT_PEM, streamWindow = 131072, connectionWindow = 262144, hopInterval = 20,
            insecure = true, obfs = "golden-obfs", alpn = "h3,hysteria",
        )
    }
    single("ext-hy1-bad-window", "Hysteria 1 插件接收窗口过小：应报错") {
        hysteria1("golden-hy1-bad-window", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO, streamWindow = 1000)
    }
    single("ext-hy1-bad-hop-interval", "Hysteria 1 插件跳跃间隔过短：应报错") {
        hysteria1("golden-hy1-bad-hop", protocol = HysteriaBean.PROTOCOL_FAKETCP, hopInterval = 5)
    }
    single("ext-hy1-certpin", "Hysteria 1 插件证书固定：应报错") {
        hysteria1("golden-hy1-pin", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO, pin = Fx.CERT_PIN)
    }
}

private fun ScenarioTable.customConfigs() {
    single("custom-full-config", "全配置型自定义节点：整份配置原样运行") {
        customBean("golden-full-config", 0, FULL_CONFIG)
    }
    single("custom-outbound-json", "自定义出站 JSON 节点") { customBean("golden-custom-outbound", 1, OUTBOUND_JSON) }
    single("custom-outbound-json-invalid", "自定义出站 JSON 解析失败：应报错") {
        customBean("golden-custom-broken", 1, "{golden-broken")
    }
    single("custom-outbound-json-array", "自定义出站 JSON 不是对象：应报错") {
        customBean("golden-custom-array", 1, "[1, 2]")
    }
    single("custom-node-outbound-json", "节点自带的自定义出站 JSON") {
        vmess("golden-vmess-custom-outbound").ws().customOutbound("""{"connect_timeout":"5s","tcp_fast_open":true}""")
    }
    single("custom-node-outbound-json-invalid", "节点自带的自定义出站 JSON 解析失败：应报错") {
        vmess("golden-vmess-custom-outbound-broken").customOutbound("{golden-broken")
    }
    single("custom-node-config-json", "节点自带的整份自定义配置（合并进最终配置）") {
        vmess("golden-vmess-custom-config")
            .customConfig("""{"log":{"timestamp":true},"route":{"final":"direct"}}""")
    }
    single("custom-node-config-json-not-object", "节点自带的自定义配置不是对象：应报错") {
        vmess("golden-vmess-custom-config-array").customConfig("[1]")
    }
    single("custom-xray-node-outbound-json", "外核节点的自定义出站 JSON 合并到本机 socks 出站") {
        vless("golden-xray-custom-outbound").reality().customOutbound("""{"udp_fragment":true}""")
    }
    scenario("custom-global-config", "全局自定义配置：只在运行模式合并") {
        node(1, vmess("golden-vmess-global-config").ws())
        settings {
            copy(globalCustomConfig = """{"experimental":{"cache_file":{"enabled":true}},"log":{"timestamp":true}}""")
        }
    }
    scenario("custom-full-config-in-chain", "全配置型节点作链成员：应报错") {
        node(2, customBean("golden-full-config-member", 0, FULL_CONFIG))
        node(3, vmess("golden-chain-exit"))
        chain(1, 2, 3)
    }
    scenario("custom-outbound-json-in-chain", "自定义出站 JSON 节点作链成员") {
        node(2, customBean("golden-custom-outbound-member", 1, OUTBOUND_JSON))
        node(3, vmess("golden-chain-entry").ws())
        chain(1, 3, 2)
    }
}

private fun ScenarioTable.chains() {
    scenario("chain-2-internal", "两跳全内核") {
        node(2, vmess("golden-chain-vmess").ws().tls(sni = "cdn.example.org"))
        node(3, trojan("golden-chain-trojan").tls(sni = "trojan.example.com"))
        chain(1, 2, 3)
    }
    // 三跳混合：2 sing-box、3 Xray、4 mihomo，按不同顺序组链
    for ((suffix, order) in listOf(
        "sb-xray-mihomo" to longArrayOf(2, 3, 4),
        "xray-sb-mihomo" to longArrayOf(3, 2, 4),
        "mihomo-xray-sb" to longArrayOf(4, 3, 2),
        "xray-mihomo-sb" to longArrayOf(3, 4, 2),
        "mihomo-sb-xray" to longArrayOf(4, 2, 3),
    )) scenario("chain-3-$suffix", "三跳混合链，顺序 $suffix") {
        node(2, shadowsocks("golden-chain-ss"))
        node(3, vless("golden-chain-xray").reality())
        node(4, anytls("golden-chain-anytls"))
        chain(1, *order)
    }
    scenario("chain-2-xray-xray", "两跳都走 Xray") {
        node(2, vless("golden-chain-xray-a").reality())
        node(3, vmess("golden-chain-xray-b").ws(), core = CORE_XRAY)
        chain(1, 2, 3)
    }
    scenario("chain-all-external", "全外核链：Trojan-Go、Naive、Mieru、Hysteria 1、Xray、mihomo") {
        allExternalChain()
    }
    scenario("chain-nested", "链里嵌套链") {
        node(2, shadowsocks("golden-nested-ss"))
        node(3, vless("golden-nested-xray").reality())
        node(4, anytls("golden-nested-anytls"))
        chain(5, 3, 4, name = "golden-inner-chain")
        chain(1, 2, 5)
    }
    scenario("chain-nested-twice", "同一子链出现两次") {
        node(2, shadowsocks("golden-twice-ss"))
        node(3, trojan("golden-twice-trojan").tls(sni = "trojan.example.com"))
        chain(5, 2, 3, name = "golden-inner-twice")
        node(4, vmess("golden-twice-vmess"))
        chain(1, 5, 4, 5)
    }
    scenario("chain-shared-node", "同一个 Xray 节点被两条链共享（主链与路由目标链）") {
        node(2, vmess("golden-shared-exit-a"))
        node(3, vless("golden-shared-xray").reality())
        node(4, anytls("golden-shared-exit-b"))
        chain(1, 3, 2)
        chain(5, 3, 4, name = "golden-shared-chain-b")
        rule(1, outbound = 5) { domains = "domain:shared.example.org" }
    }
    scenario("chain-same-external-twice", "同一外核节点在一条链里出现两次：应报错") {
        node(2, vmess("golden-dup-exit"))
        node(3, vless("golden-dup-xray").reality())
        chain(1, 3, 2, 3)
    }
    scenario("chain-same-internal-twice", "同一内核节点在一条链里出现两次（tag 加序号）") {
        node(2, shadowsocks("golden-dup-ss"))
        node(3, trojan("golden-dup-trojan").tls(sni = "trojan.example.com"))
        chain(1, 2, 3, 2)
    }
    scenario("chain-empty", "空链：应报错") { chain(1) }
    scenario("chain-missing-member", "成员缺失：跳过缺失的成员") {
        node(2, shadowsocks("golden-missing-ss"))
        node(3, vmess("golden-missing-vmess"))
        chain(1, 2, 99, 3)
    }
    scenario("chain-all-missing", "成员全部缺失：应报错") { chain(1, 98, 99) }
    scenario("chain-loop", "循环引用：应报错") {
        node(2, shadowsocks("golden-loop-ss"))
        chain(1, 2, 3)
        chain(3, 1, name = "golden-loop-inner")
    }
    scenario("chain-self-loop", "链引用自己：应报错") { chain(1, 1) }
    scenario("chain-single-member", "只有一个成员的链") {
        node(2, vmess("golden-single-member").ws())
        chain(1, 2)
    }
    scenario("chain-hy1-faketcp-middle", "faketcp 在链中间（不能映射，改走 detour）") {
        node(2, shadowsocks("golden-ft-ss"))
        node(3, hysteria1("golden-ft-hy1", protocol = HysteriaBean.PROTOCOL_FAKETCP))
        node(4, vmess("golden-ft-vmess"))
        chain(1, 2, 3, 4)
    }
    scenario("chain-hy1-faketcp-first", "faketcp 是最先拨号的一跳") {
        node(2, vmess("golden-ft-first-exit"))
        node(3, hysteria1("golden-ft-first", protocol = HysteriaBean.PROTOCOL_FAKETCP))
        chain(1, 3, 2)
    }
    scenario("chain-hy1-wechat-first", "Hysteria 1 插件节点是最先拨号的一跳：免映射") {
        node(2, vmess("golden-hy1-first-exit"))
        node(3, hysteria1("golden-hy1-first", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO, ca = Fx.CERT_PEM))
        chain(1, 3, 2)
    }
    scenario("chain-hy1-wechat-exit", "Hysteria 1 插件节点是出口：经映射") {
        node(2, shadowsocks("golden-hy1-exit-entry"))
        node(3, hysteria1("golden-hy1-exit", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO))
        chain(1, 2, 3)
    }
    scenario("chain-cross-group-member", "链成员在别的分组") {
        group(1)
        group(2)
        node(2, vmess("golden-cross-other-group"), group = 2)
        node(3, trojan("golden-cross-same-group").tls(sni = "trojan.example.com"))
        chain(1, 2, 3)
    }
}

// 全外核链：2 Trojan-Go → 3 Naive → 4 Mieru → 5 Hysteria 1 → 6 Xray → 7 mihomo（出口）
private fun ScenarioBuilder.allExternalChain() {
    node(2, trojanGo("golden-ext-trojan-go", sni = "trojan-go.example.com"))
    node(3, naive("golden-ext-naive", sni = "naive-sni.example.com"))
    node(4, mieru("golden-ext-mieru"))
    node(5, hysteria1("golden-ext-hy1", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO, ca = Fx.CERT_PEM))
    node(6, vless("golden-ext-xray").reality())
    node(7, anytls("golden-ext-anytls"))
    chain(1, 2, 3, 4, 5, 6, 7, name = "golden-all-external")
}

private fun ScenarioTable.frontAndLanding() {
    scenario("group-front", "分组前置代理") {
        group(1, front = 10)
        group(2)
        node(1, vmess("golden-front-main").ws())
        node(10, shadowsocks("golden-front"), group = 2)
    }
    scenario("group-landing", "分组落地代理") {
        group(1, landing = 11)
        group(2)
        node(1, vmess("golden-landing-main").ws())
        node(11, trojan("golden-landing").tls(sni = "trojan.example.com"), group = 2)
    }
    scenario("group-front-landing", "前置与落地同时存在") {
        group(1, front = 10, landing = 11)
        group(2)
        node(1, vmess("golden-fl-main").ws())
        node(10, shadowsocks("golden-fl-front"), group = 2)
        node(11, trojan("golden-fl-landing").tls(sni = "trojan.example.com"), group = 2)
    }
    scenario("group-front-chain", "前置本身是链") {
        group(1, front = 12)
        group(2)
        node(1, vmess("golden-fc-main").ws())
        node(13, shadowsocks("golden-fc-a"), group = 2)
        node(14, socks("golden-fc-b"), group = 2)
        chain(12, 13, 14, group = 2, name = "golden-front-chain")
    }
    scenario("group-front-xray", "前置是 Xray 节点") {
        group(1, front = 10)
        group(2)
        node(1, vmess("golden-fx-main").ws())
        node(10, vless("golden-fx-front").reality(), group = 2)
    }
    scenario("group-landing-mihomo", "主节点走 Xray，落地是 mihomo 节点") {
        group(1, landing = 11)
        group(2)
        node(1, vless("golden-lm-main").reality())
        node(11, anytls("golden-lm-landing"), group = 2)
    }
    scenario("group-front-anytls-main", "AnyTLS 走 mihomo 且分组有前置：测速不开 Clash API") {
        group(1, front = 10)
        group(2)
        node(1, anytls("golden-fa-main"))
        node(10, shadowsocks("golden-fa-front"), group = 2)
    }
    scenario("group-front-plugin", "前置是 Trojan-Go 节点") {
        group(1, front = 10)
        group(2)
        node(1, vmess("golden-fp-main").ws())
        node(10, trojanGo("golden-fp-front"), group = 2)
    }
    scenario("group-front-main-chain", "主节点是链，分组有前置") {
        group(1, front = 10)
        group(2)
        node(2, vmess("golden-fmc-a"))
        node(3, vless("golden-fmc-b").reality())
        chain(1, 2, 3)
        node(10, shadowsocks("golden-fmc-front"), group = 2)
    }
    scenario("group-front-missing", "前置节点已不存在：忽略") {
        group(1, front = 99)
        node(1, vmess("golden-fm-main").ws())
    }
    scenario("group-landing-empty-chain", "落地是没有成员的链：应报错") {
        group(1, landing = 12)
        group(2)
        node(1, vmess("golden-lec-main").ws())
        chain(12, group = 2, name = "golden-empty-landing")
    }
}

private fun ScenarioTable.selectors() {
    fun ScenarioBuilder.mixedMembers() {
        group(1, selector = true)
        node(1, vmess("golden-sel-vmess").ws().tls(sni = "cdn.example.org"))
        node(2, vless("golden-sel-xray").reality())
        node(3, anytls("golden-sel-anytls"))
        node(4, trojan("golden-sel-trojan").tls(sni = "trojan.example.com"))
        node(5, hysteria2("golden-sel-hy2", ports = "20000-20100"))
    }
    scenario("selector-mixed", "选择器：内核与外核成员混合，选中内核成员") { mixedMembers() }
    scenario("selector-main-xray", "选择器：选中 Xray 成员") {
        mixedMembers()
        main = 2
    }
    scenario("selector-main-anytls", "选择器：选中 mihomo 成员（测速时单节点开 Clash API）") {
        mixedMembers()
        main = 3
    }
    scenario("selector-bad-members", "选择器：预检失败的成员被跳过") {
        group(1, selector = true)
        node(1, vmess("golden-sel-good").ws())
        node(2, tuic("golden-sel-tuic-v4", version = 4))
        node(3, trojan("golden-sel-trojan-pin").tls(sni = "trojan.example.com", pin = Fx.CERT_PIN))
        node(4, wireguard("golden-sel-wg-bad", reserved = "1,2"))
        node(5, vless("golden-sel-mldsa").reality(mldsa65Verify = Fx.MLDSA65_VERIFY), core = CORE_SING_BOX)
        node(6, customBean("golden-sel-custom-broken", 1, "{golden-broken"))
        node(7, vmess("golden-sel-xhttp").transport("xhttp"))
        node(8, anytls("golden-sel-anytls-bad-ca", certificates = "golden-not-a-cert"))
    }
    scenario("selector-main-bad", "选择器：选中的节点本身构建失败（不预检，照常报错）") {
        group(1, selector = true)
        node(1, tuic("golden-sel-main-tuic-v4", version = 4))
        node(2, vmess("golden-sel-other").ws())
    }
    scenario("selector-plugin-members", "选择器：需要插件 app 的成员（模拟器上未安装，被预检跳过）") {
        group(1, selector = true)
        node(1, vmess("golden-selp-vmess").ws())
        node(2, trojanGo("golden-selp-trojan-go"))
        node(3, naive("golden-selp-naive"))
        node(4, mieru("golden-selp-mieru"))
        node(5, hysteria1("golden-selp-hy1-wechat", protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO))
        node(6, hysteria1("golden-selp-hy1-faketcp", protocol = HysteriaBean.PROTOCOL_FAKETCP))
    }
    scenario("selector-main-plugin", "选择器：选中需要插件的成员（选中的不预检）") {
        group(1, selector = true)
        node(1, trojanGo("golden-selmp-trojan-go"))
        node(2, vmess("golden-selmp-vmess").ws())
    }
    scenario("selector-full-config-member", "选择器：全配置型成员被跳过") {
        group(1, selector = true)
        node(1, vmess("golden-self-vmess").ws())
        node(2, customBean("golden-self-full", 0, FULL_CONFIG))
        node(3, customBean("golden-self-outbound", 1, OUTBOUND_JSON))
    }
    scenario("selector-main-full-config", "选择器：选中全配置型成员时整份配置单独运行") {
        group(1, selector = true)
        node(1, customBean("golden-selmf-full", 0, FULL_CONFIG))
        node(2, vmess("golden-selmf-vmess").ws())
    }
    scenario("selector-chain-members", "选择器：链成员的首跳 / 出口同时是组内成员") {
        group(1, selector = true)
        node(1, vmess("golden-selc-vmess").ws())
        node(2, vless("golden-selc-xray").reality())
        chain(3, 2, 1, name = "golden-selc-chain")
        chain(4, 1, name = "golden-selc-single")
        node(5, shadowsocks("golden-selc-ss"))
    }
    scenario("selector-name-collision", "选择器：成员名与内置 tag / 生成 tag 撞名、重名") {
        group(1, selector = true)
        node(1, vmess("proxy").ws())
        node(2, shadowsocks("g-2"))
        node(3, trojan("direct").tls(sni = "trojan.example.com"))
        node(4, socks("dup"))
        node(5, httpProxy("dup"))
        node(6, socks("c-9-x", server = "192.0.2.66"))
    }
    scenario("selector-front-landing", "选择器分组带前置与落地") {
        group(1, selector = true, front = 10, landing = 11)
        group(2)
        node(1, vmess("golden-selfl-vmess").ws())
        node(2, vless("golden-selfl-xray").reality())
        node(10, shadowsocks("golden-selfl-front"), group = 2)
        node(11, trojan("golden-selfl-landing").tls(sni = "trojan.example.com"), group = 2)
    }
    scenario("selector-rule-targets", "选择器：路由规则指向组内成员与组外节点") {
        group(1, selector = true)
        group(2)
        node(1, vmess("golden-selr-vmess").ws())
        node(2, vless("golden-selr-xray").reality())
        node(10, shadowsocks("golden-selr-outside"), group = 2)
        node(11, anytls("golden-selr-outside-mihomo"), group = 2)
        rule(1, outbound = 2) { domains = "domain:member.example.org" }
        rule(2, outbound = 10) { domains = "domain:outside.example.org" }
        rule(3, outbound = 11) { domains = "domain:outside-mihomo.example.org" }
        rule(4, outbound = 1) { domains = "domain:main.example.org" }
    }
}

// 规则类型样本：域名各前缀、IP / geoip、端口与范围、源端口 / 源 IP、网络、协议、
// 自定义规则 JSON、停用的规则、重复的 rule set、空规则
private fun ScenarioBuilder.ruleTypeSamples() {
    node(1, vmess("golden-rules-main").ws().tls(sni = "cdn.example.org"))
    rule(1, outbound = -2, name = "golden-block-ads") { domains = "geosite:category-ads-all" }
    rule(2, outbound = -1, name = "golden-direct-domains") {
        domains = "full:direct.example.com\ndomain:example.net\nregexp:^golden\\d+\\.example\\.org$\n" +
            "keyword:golden-kw\ngeosite:cn\nPlain.Example.ORG"
    }
    rule(3, outbound = -1, name = "golden-direct-ip") { ip = "192.0.2.0/24\n2001:db8::/32\ngeoip:private\ngeoip:cn" }
    rule(4, outbound = 0, name = "golden-proxy-ports") {
        port = "443,8443,1000:2000"
        network = "tcp"
    }
    rule(5, outbound = -2, name = "golden-block-quic") {
        port = "443"
        network = "udp"
    }
    rule(6, outbound = 0, name = "golden-source") {
        sourcePort = "12000,13000:14000"
        source = "10.0.0.0/8,fd00::/8"
    }
    rule(7, outbound = -1, name = "golden-protocols") { protocol = "bittorrent\nstun" }
    rule(8, outbound = 0, name = "golden-domain-and-ip") {
        domains = "geosite:google"
        ip = "geoip:us"
    }
    rule(9, outbound = -1, name = "golden-disabled", enabled = false) { domains = "disabled.example.com" }
    rule(10, outbound = 0, name = "golden-custom-rule") {
        domains = "domain:invert.example.com"
        config = """{"invert":true}"""
    }
    rule(11, outbound = 0, name = "golden-config-only") { config = """{"ip_version":6}""" }
    rule(12, outbound = -1, name = "golden-duplicate-rule-set") { domains = "geosite:cn" }
    rule(13, outbound = 0, name = "golden-empty")
    rule(14, outbound = 0, name = "golden-proxy-domains") { domains = "domain:proxy.example.org" }
}

private fun ScenarioTable.routeRules() {
    scenario("rules-types", "各类规则（含 DNS 规则）") { ruleTypeSamples() }
    scenario("rules-types-dns-routing-off", "各类规则，关闭 DNS 分流") {
        ruleTypeSamples()
        settings { copy(enableDnsRouting = false) }
    }
    scenario("rules-types-fakedns-off", "各类规则，关闭 FakeDNS") {
        ruleTypeSamples()
        settings { copy(enableFakeDns = false) }
    }
    scenario("rules-types-proxy-mode", "各类规则，仅代理模式") {
        ruleTypeSamples()
        settings { copy(serviceMode = Key.MODE_PROXY) }
    }
    scenario("rules-to-node-sb", "规则目标是另一个内核节点") {
        node(1, vmess("golden-rt-main").ws())
        node(2, shadowsocks("golden-rt-ss"))
        rule(1, outbound = 2) { domains = "domain:ss.example.org" }
    }
    scenario("rules-to-node-xray", "规则目标是 Xray 节点") {
        node(1, vmess("golden-rtx-main").ws())
        node(2, vless("golden-rtx-xray").reality())
        rule(1, outbound = 2) { domains = "domain:xray.example.org" }
    }
    scenario("rules-to-node-mihomo", "规则目标是 mihomo 节点") {
        node(1, vmess("golden-rtm-main").ws())
        node(2, anytls("golden-rtm-anytls"))
        rule(1, outbound = 2) { ip = "198.51.100.0/24" }
    }
    scenario("rules-to-chain", "规则目标是另一条链") {
        node(1, vmess("golden-rtc-main").ws())
        node(2, shadowsocks("golden-rtc-ss"))
        node(3, vless("golden-rtc-xray").reality())
        chain(4, 2, 3, name = "golden-rtc-chain")
        rule(1, outbound = 4) { domains = "domain:chain.example.org" }
    }
    scenario("rules-to-missing", "规则目标节点不存在") {
        node(1, vmess("golden-rtmiss-main").ws())
        rule(1, outbound = 99) { domains = "domain:missing.example.org" }
    }
    scenario("rules-to-main", "规则目标就是选中的节点") {
        node(1, vmess("golden-rtself-main").ws())
        rule(1, outbound = 1) { domains = "domain:self.example.org" }
    }
    scenario("rules-to-broken-node", "规则目标预检失败：跳过并按目标不存在处理") {
        node(1, vmess("golden-rtb-main").ws())
        node(2, tuic("golden-rtb-tuic-v4", version = 4))
        rule(1, outbound = 2) { domains = "domain:broken.example.org" }
    }
    scenario("rules-to-plugin-node", "规则目标需要插件 app（运行时被预检跳过，导出不查插件）") {
        node(1, vmess("golden-rtp-main").ws())
        node(2, trojanGo("golden-rtp-trojan-go"))
        rule(1, outbound = 2) { domains = "domain:plugin.example.org" }
    }
    scenario("rules-to-full-config", "规则目标是全配置型节点：预检跳过") {
        node(1, vmess("golden-rtf-main").ws())
        node(2, customBean("golden-rtf-full", 0, FULL_CONFIG))
        rule(1, outbound = 2) { domains = "domain:full.example.org" }
    }
    scenario("rules-to-node-with-front", "规则目标所在分组有前置") {
        group(1)
        group(2, front = 10)
        node(1, vmess("golden-rtfront-main").ws())
        node(2, shadowsocks("golden-rtfront-target"), group = 2)
        node(10, trojan("golden-rtfront-front").tls(sni = "trojan.example.com"), group = 2)
        rule(1, outbound = 2) { domains = "domain:front.example.org" }
    }
    scenario("rules-two-to-same-node", "两条规则指向同一节点") {
        node(1, vmess("golden-rt2-main").ws())
        node(2, vless("golden-rt2-xray").reality())
        rule(1, outbound = 2) { domains = "domain:a.example.org" }
        rule(2, outbound = 2) { ip = "203.0.113.0/24" }
    }
    // 包名只用 UID 由平台固定的系统包：android / com.android.providers.settings 1000、
    // com.android.phone 1001、com.android.shell 2000
    scenario("rules-apps-vpn", "按应用分流（VPN 模式，UID 去重）") {
        node(1, vmess("golden-apps-main").ws())
        rule(1, outbound = 0) {
            packages = linkedSetOf("android", "com.android.providers.settings", "com.android.phone", "com.android.shell")
            domains = "domain:apps.example.org"
        }
        rule(2, outbound = -1) { packages = linkedSetOf("com.android.shell") }
    }
    scenario("rules-apps-proxy-mode", "按应用分流的规则在仅代理模式下") {
        node(1, vmess("golden-apps-proxy-main").ws())
        rule(1, outbound = 0) {
            packages = linkedSetOf("android", "com.android.phone")
            domains = "domain:apps.example.org"
        }
        settings { copy(serviceMode = Key.MODE_PROXY) }
    }
    scenario("rules-apps-not-installed", "按应用分流，应用都未安装：整条跳过") {
        node(1, vmess("golden-apps-missing-main").ws())
        rule(1, outbound = 0) {
            packages = linkedSetOf("com.example.golden.notinstalled")
            domains = "domain:apps.example.org"
        }
    }
    scenario("rules-apps-partly-installed", "按应用分流，部分应用未安装") {
        node(1, vmess("golden-apps-partly-main").ws())
        rule(1, outbound = -2) { packages = linkedSetOf("com.example.golden.notinstalled", "android") }
    }
    scenario("rules-bad-port", "规则端口不是数字：应报错") {
        node(1, vmess("golden-badport-main").ws())
        rule(1, outbound = 0) { port = "443,https" }
    }
    scenario("rules-bad-source-port", "规则源端口不是数字：应报错") {
        node(1, vmess("golden-badsport-main").ws())
        rule(1, outbound = 0) { sourcePort = "golden" }
    }
    scenario("rules-custom-config-not-object", "规则自定义 JSON 不是对象：应报错") {
        node(1, vmess("golden-badrule-main").ws())
        rule(1, outbound = 0) {
            domains = "domain:bad.example.org"
            config = "[1]"
        }
    }
}

// 设置变体的公共夹具：内核主节点（域名服务器）+ 指向 Xray 节点的规则 + 直连 / 拦截规则
private fun ScenarioBuilder.settingsBase() {
    node(1, vmess("golden-set-main").ws().tls(sni = "cdn.example.org"))
    node(2, vless("golden-set-xray").reality())
    rule(1, outbound = 2) { domains = "domain:xray.example.org" }
    rule(2, outbound = -1) { domains = "domain:direct.example.org\ngeosite:cn" }
    rule(3, outbound = -2) { domains = "domain:block.example.org" }
}

private fun ScenarioTable.settingsVariants() {
    val variants = listOf<Triple<String, String, GoldenSettings.() -> GoldenSettings>>(
        Triple("defaults", "除 DNS、mixed 端口与 Clash secret 外都不写，按代码默认值") {
            GoldenSettings(
                serviceMode = null, allowAccess = null, bypassLanInCore = null, enableDnsRouting = null,
                enableFakeDns = null, trafficSniffing = null, resolveDestination = null, ipv6Mode = null,
                logLevel = null, mtu = null, tunImplementation = null, globalCustomConfig = null,
                enableClashAPI = null, globalAllowInsecure = null, domainStrategyRemote = null,
                domainStrategyDirect = null, domainStrategyServer = null,
            )
        },
        Triple("fakedns-off", "关闭 FakeDNS") { copy(enableFakeDns = false) },
        Triple("ipv6-enable", "IPv6 启用") { copy(ipv6Mode = IPv6Mode.ENABLE) },
        Triple("ipv6-prefer", "IPv6 优先") { copy(ipv6Mode = IPv6Mode.PREFER) },
        Triple("ipv6-only", "仅 IPv6") { copy(ipv6Mode = IPv6Mode.ONLY) },
        Triple("ipv6-out-of-range", "IPv6 模式值越界") { copy(ipv6Mode = 7) },
        Triple("proxy-mode", "仅代理模式") { copy(serviceMode = Key.MODE_PROXY) },
        Triple("dns-routing-off", "关闭 DNS 分流") { copy(enableDnsRouting = false) },
        Triple("sniff-off", "关闭嗅探") { copy(trafficSniffing = 0) },
        Triple("allow-access", "允许局域网访问") { copy(allowAccess = true) },
        Triple("bypass-lan-in-core", "内核绕过局域网") { copy(bypassLanInCore = true) },
        Triple("resolve-destination", "解析目标地址（IPv6 禁用）") { copy(resolveDestination = true) },
        Triple("resolve-destination-ipv6-prefer", "解析目标地址（IPv6 优先）") {
            copy(resolveDestination = true, ipv6Mode = IPv6Mode.PREFER)
        },
        Triple("resolve-destination-ipv6-out-of-range", "解析目标地址（IPv6 模式越界）") {
            copy(resolveDestination = true, ipv6Mode = 7)
        },
        Triple("clash-api", "开启 Clash API（固定 secret）") { copy(enableClashAPI = true) },
        Triple("global-insecure", "全局 allowInsecure（VLESS 由 Xray 落到 sing-box）") { copy(globalAllowInsecure = true) },
        Triple("loglevel-1", "日志级别 warn") { copy(logLevel = 1) },
        Triple("loglevel-2", "日志级别 info") { copy(logLevel = 2) },
        Triple("loglevel-3", "日志级别 debug") { copy(logLevel = 3) },
        Triple("loglevel-4", "日志级别 trace") { copy(logLevel = 4) },
        Triple("tun-system", "tun 实现 system") { copy(tunImplementation = TunImplementation.SYSTEM) },
        Triple("tun-mixed", "tun 实现 mixed") { copy(tunImplementation = 2) },
        Triple("mtu-1500", "MTU 1500") { copy(mtu = 1500) },
        Triple("mixed-port", "mixed 端口 7890") { copy(mixedPort = 7890) },
        Triple("domain-strategy-explicit", "三个 domain strategy 显式取值") {
            copy(domainStrategyRemote = "prefer_ipv6", domainStrategyDirect = "ipv4_only", domainStrategyServer = "prefer_ipv4")
        },
        Triple("domain-strategy-unset", "三个 domain strategy 都没写过") {
            copy(domainStrategyRemote = null, domainStrategyDirect = null, domainStrategyServer = null)
        },
        Triple("domain-strategy-server-ipv6-only", "节点服务器解析 ipv6_only，IPv6 优先") {
            copy(domainStrategyServer = "ipv6_only", ipv6Mode = IPv6Mode.PREFER)
        },
        Triple("dns-tls-udp", "远程 DNS tls://、直连 DNS udp:// 带端口") {
            copy(remoteDns = "tls://dns.example.net", directDns = "udp://192.0.2.53:5353")
        },
        Triple("dns-h3-quic", "远程 DNS h3://、直连 DNS quic:// IPv6") {
            copy(remoteDns = "h3://dns.example.net/dns-query", directDns = "quic://[2001:db8::53]:853")
        },
        Triple("dns-tcp-local", "远程 DNS tcp://、直连 DNS local") {
            copy(remoteDns = "tcp://192.0.2.54", directDns = "local")
        },
        Triple("dns-multiline", "多行 DNS（注释、空行，只取第一条）") {
            copy(
                remoteDns = "# golden\n\nhttps://dns.example.net/q\nhttps://dns.example.org/q",
                directDns = "  # comment\n192.0.2.53\n192.0.2.54",
            )
        },
        Triple("dns-bare", "裸地址：域名与 [IPv6]:端口") {
            copy(remoteDns = "dns.example.net", directDns = "[2001:db8::53]:53")
        },
        Triple("dns-https-ipv6-port", "https:// 带 IPv6 与端口") {
            copy(remoteDns = "https://[2001:db8::54]:8443/dns-query", directDns = "https://dns.example.org:8443/q?x=1")
        },
        Triple("dns-direct-dhcp", "直连 DNS dhcp://：不支持，应报错") { copy(directDns = "dhcp://auto") },
        Triple("dns-direct-empty", "直连 DNS 为空：应报错") { copy(directDns = "") },
        Triple("dns-remote-empty", "远程 DNS 为空：运行 / 导出报错，测速不用远程 DNS") { copy(remoteDns = "") },
        Triple("dns-bad-port", "DNS 端口越界：应报错") { copy(remoteDns = "https://dns.example.net:99999/dns-query") },
        Triple("dns-unknown-scheme", "DNS 未知 scheme：应报错") { copy(remoteDns = "golden://dns.example.net") },
        Triple("dns-bare-ipv6-unbracketed", "裸 IPv6 不带括号") { copy(directDns = "2001:db8::53") },
    )
    for ((suffix, description, transform) in variants) scenario("settings-$suffix", description) {
        settingsBase()
        settings { transform() }
    }
    // 影响外核生成器的设置（日志级别、IPv6 模式、全局 allowInsecure），用全外核链各采一次
    val externalVariants = listOf<Triple<String, String, GoldenSettings.() -> GoldenSettings>>(
        Triple("loglevel-0", "全外核链，日志级别 0") { copy(logLevel = 0) },
        Triple("loglevel-1", "全外核链，日志级别 1") { copy(logLevel = 1) },
        Triple("loglevel-2", "全外核链，日志级别 2") { copy(logLevel = 2) },
        Triple("loglevel-3", "全外核链，日志级别 3") { copy(logLevel = 3) },
        Triple("loglevel-4", "全外核链，日志级别 4") { copy(logLevel = 4) },
        Triple("ipv6-enable", "全外核链，IPv6 启用") { copy(ipv6Mode = IPv6Mode.ENABLE) },
        Triple("ipv6-prefer", "全外核链，IPv6 优先") { copy(ipv6Mode = IPv6Mode.PREFER) },
        Triple("ipv6-only", "全外核链，仅 IPv6") { copy(ipv6Mode = IPv6Mode.ONLY) },
        Triple("global-insecure", "全外核链，全局 allowInsecure") { copy(globalAllowInsecure = true) },
    )
    for ((suffix, description, transform) in externalVariants) scenario("settings-ext-$suffix", description) {
        allExternalChain()
        settings { transform() }
    }
}

private fun ScenarioTable.groupNameservers() {
    // 分组「节点解析 DNS」：本组域名节点用它解析；system / local / 回环地址会被过滤
    val nameservers = "https://dns.example.org/dns-query\n192.0.2.1\nsystem\nlocal\n127.0.0.1\n[::1]:53\ntls://dns.example.com#h3"
    scenario("group-dns", "分组 DNS：本组域名节点、IP 节点与别组节点") {
        group(1, nameserver = nameservers)
        group(2)
        node(1, vmess("golden-gdns-main").ws())
        node(2, shadowsocks("golden-gdns-other-group"), group = 2)
        node(3, trojan("golden-gdns-ip", server = "203.0.113.80").tls(sni = "trojan.example.com"))
        node(4, vless("golden-gdns-xray").reality())
        rule(1, outbound = 2) { domains = "domain:other.example.org" }
        rule(2, outbound = 3) { domains = "domain:ip.example.org" }
        rule(3, outbound = 4) { domains = "domain:xray.example.org" }
    }
    scenario("group-dns-chain", "分组 DNS：主节点是链，成员跨分组") {
        group(1, nameserver = nameservers)
        group(2)
        node(2, vmess("golden-gdnsc-a"))
        node(3, shadowsocks("golden-gdnsc-b"), group = 2)
        node(4, vless("golden-gdnsc-xray").reality())
        chain(1, 2, 3, 4)
    }
    scenario("group-dns-unparseable", "分组 DNS 有解析不了的地址：跳过它") {
        group(1, nameserver = "dhcp://auto\nhttps://dns.example.org/dns-query\nudp://dns.example.net:99999")
        node(1, vmess("golden-gdnsu-main").ws())
    }
    scenario("group-dns-ip-nodes", "分组 DNS，但本组节点全是 IP：不输出分组 DNS") {
        group(1, nameserver = nameservers)
        node(1, vmess("golden-gdnsip-main", server = "192.0.2.90").ws())
    }
    scenario("group-dns-all-filtered", "分组 DNS 只有被过滤的地址") {
        group(1, nameserver = "system\nlocal\n127.0.0.1\nlocalhost")
        node(1, vmess("golden-gdnsf-main").ws())
    }
    scenario("group-dns-selector", "分组 DNS + 选择器分组") {
        group(1, selector = true, nameserver = nameservers)
        node(1, vmess("golden-gdnss-vmess").ws())
        node(2, vless("golden-gdnss-xray").reality())
        node(3, anytls("golden-gdnss-anytls"))
    }
    scenario("group-dns-domain-strategy", "分组 DNS + 显式 domain strategy") {
        group(1, nameserver = "https://dns.example.org/dns-query\n192.0.2.1")
        node(1, vmess("golden-gdnsds-main").ws())
        settings { copy(domainStrategyServer = "ipv4_only", ipv6Mode = IPv6Mode.ENABLE) }
    }
}
