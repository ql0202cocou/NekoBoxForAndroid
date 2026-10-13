package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CLASH_KEY_NOT_SHOWN
import io.nekohasekai.sagernet.fmt.CLASH_VALUE_NOT_SHOWN
import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldRecord
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashFieldResult.CONVERTED
import io.nekohasekai.sagernet.fmt.ClashFieldResult.IGNORED
import io.nekohasekai.sagernet.fmt.ClashFieldResult.KEPT
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 字段记录：每个非空键一个结果，按最终导入的值判断
class ClashFieldRecordTest {

    private val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val pbk = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
    private val uuid = "00000000-0000-0000-0000-00000000000a"

    private fun parsed(node: String) = parseClash(loadClashYaml("proxies:\n  - $node"))

    private fun imported(node: String): Pair<AbstractBean, Map<String, ClashFieldRecord>> {
        val result = parsed(node)
        val entry = result.nodes.single() as ClashNodeResult.Imported
        return result.beans.single() to entry.fields.associateBy { it.path }
    }

    private fun fields(node: String) = imported(node).second

    private fun assertField(
        fields: Map<String, ClashFieldRecord>, path: String, result: ClashFieldResult,
        reason: ClashFieldReason? = null,
    ) {
        val field = fields[path] ?: error("no record for $path in ${fields.keys}")
        assertEquals(path, result, field.result)
        if (reason != null) assertEquals(path, reason, field.reason)
    }

    private fun assertNoLoss(fields: Map<String, ClashFieldRecord>) {
        assertEquals(emptyList<ClashFieldRecord>(), fields.values.filter { it.lossy })
    }

    // ---------- 每种类型全部可转换 ----------

    @Test
    fun `socks5 全部可转换`() {
        val (bean, fields) = imported(
            "{name: s, type: socks5, server: 192.0.2.10, port: 1080, username: user-a, password: pw-a, udp: true}"
        )
        assertNoLoss(fields)
        bean as SOCKSBean
        assertEquals("192.0.2.10", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("user-a", bean.username)
        assertEquals("pw-a", bean.password)
        assertField(fields, "udp", IGNORED, ClashFieldReason.NO_UDP_SWITCH)
    }

    @Test
    fun `http 全部可转换`() {
        val (bean, fields) = imported(
            "{name: h, type: http, server: proxy.example.com, port: 443, username: user-b, password: pw-b, tls: true, sni: sni.example.com, skip-cert-verify: false}"
        )
        assertNoLoss(fields)
        bean as HttpBean
        assertEquals("tls", bean.security)
        assertEquals("sni.example.com", bean.sni)
        assertEquals(false, bean.allowInsecure)
    }

    @Test
    fun `ss 全部可转换（obfs 与 v2ray-plugin）`() {
        val (obfs, obfsFields) = imported(
            "{name: a, type: ss, server: ss.example.com, port: 8388, cipher: 2022-blake3-aes-128-gcm, password: pw-c, udp: true, plugin: obfs, plugin-opts: {mode: tls, host: obfs.example.com}}"
        )
        assertNoLoss(obfsFields)
        assertEquals("obfs-local;obfs=tls;obfs-host=obfs.example.com", (obfs as ShadowsocksBean).plugin)
        assertField(obfsFields, "plugin", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        assertField(obfsFields, "plugin-opts.mode", KEPT)

        val (v2ray, v2rayFields) = imported(
            "{name: b, type: ss, server: ss.example.com, port: 8388, cipher: aes-256-gcm, password: pw-d, plugin: v2ray-plugin, plugin-opts: {mode: websocket, tls: true, host: ws.example.com, path: /ws, mux: true}}"
        )
        assertNoLoss(v2rayFields)
        assertEquals(
            "v2ray-plugin;mode=websocket;tls;host=ws.example.com;path=/ws;mux=8", (v2ray as ShadowsocksBean).plugin
        )
        assertField(v2rayFields, "plugin-opts.mux", CONVERTED)
    }

    @Test
    fun `vmess 全部可转换`() {
        val (bean, fields) = imported(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, alterId: 0, cipher: auto, udp: true, tls: true, servername: sni.example.com, skip-cert-verify: false, client-fingerprint: chrome, alpn: [http/1.1], network: ws, ws-opts: {path: /ws, headers: {Host: cdn.example.com}, max-early-data: 2048, early-data-header-name: Sec-WebSocket-Protocol}}"
        )
        assertNoLoss(fields)
        bean as VMessBean
        assertEquals("ws", bean.type)
        assertEquals("/ws", bean.path)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("sni.example.com", bean.sni)
        assertEquals("http/1.1", bean.alpn)
        assertEquals(2048, bean.wsMaxEarlyData)
        assertEquals("chrome", bean.utlsFingerprint)
        assertField(fields, "ws-opts.headers.Host", KEPT)
        // mihomo 的 ws 把 ALPN 写死成 http/1.1，与它相同
        assertField(fields, "alpn", IGNORED, ClashFieldReason.MATCHES_RESULT)
    }

    @Test
    fun `vless 全部可转换（REALITY 与 vision）`() {
        val (bean, fields) = imported(
            "{name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, flow: xtls-rprx-vision, tls: true, servername: www.example.com, client-fingerprint: chrome, packet-encoding: xudp, encryption: none, reality-opts: {public-key: $pbk, short-id: 0123abcd, support-x25519mlkem768: false}, network: tcp}"
        )
        assertNoLoss(fields)
        bean as VMessBean
        assertTrue(bean.isVLESS)
        assertEquals("xtls-rprx-vision", bean.encryption)
        assertEquals(pbk, bean.realityPubKey)
        assertEquals("0123abcd", bean.realityShortId)
        assertEquals(2, bean.packetEncoding)
        assertField(fields, "encryption", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(fields, "reality-opts.support-x25519mlkem768", IGNORED, ClashFieldReason.MATCHES_RESULT)
    }

    @Test
    fun `trojan 全部可转换`() {
        val (bean, fields) = imported(
            "{name: t, type: trojan, server: t.example.com, port: 443, password: pw-e, sni: sni.example.com, skip-cert-verify: true, alpn: [h2], network: grpc, grpc-opts: {grpc-service-name: svc}, fingerprint: \"$fp\"}"
        )
        assertNoLoss(fields)
        bean as TrojanBean
        assertEquals("grpc", bean.type)
        assertEquals("svc", bean.path)
        assertEquals(fp, bean.certificateFingerprint)
        assertField(fields, "sni", KEPT)
    }

    @Test
    fun `anytls 全部可转换`() {
        val (bean, fields) = imported(
            "{name: a, type: anytls, server: a.example.com, port: 443, password: pw-f, sni: sni.example.com, skip-cert-verify: false, client-fingerprint: chrome, alpn: [h2, http/1.1], ech-opts: {enable: true, config: ZWNoLWNvbmZpZw==}}"
        )
        assertNoLoss(fields)
        bean as AnyTLSBean
        assertEquals("h2\nhttp/1.1", bean.alpn)
        assertEquals(true, bean.enableECH)
    }

    @Test
    fun `hysteria 与 hysteria2 全部可转换`() {
        val (v1, v1Fields) = imported(
            "{name: h1, type: hysteria, server: h.example.com, port: 443, ports: 1000-2000, up: 30 Mbps, down: 100, auth-str: pw-g, obfs: obfs-g, protocol: udp, sni: sni.example.com, alpn: [h3], recv-window-conn: 1000}"
        )
        assertNoLoss(v1Fields)
        v1 as HysteriaBean
        assertEquals("1000-2000", v1.serverPorts)
        assertEquals(30, v1.uploadMbps)
        assertField(v1Fields, "port", IGNORED, ClashFieldReason.INACTIVE)

        val (v2, v2Fields) = imported(
            "{name: h2, type: hysteria2, server: h.example.com, port: 443, password: pw-h, obfs: salamander, obfs-password: op-h, sni: sni.example.com, up: 50, down: 200 Mbps, alpn: [h3]}"
        )
        assertNoLoss(v2Fields)
        v2 as HysteriaBean
        assertEquals("op-h", v2.obfuscation)
        assertEquals(200, v2.downloadMbps)
        assertField(v2Fields, "obfs", CONVERTED)
        assertField(v2Fields, "alpn", IGNORED, ClashFieldReason.MATCHES_RESULT)
    }

    @Test
    fun `hy2 是 hysteria2 的别名，不计损失`() {
        val (bean, fields) = imported("{name: h, type: hy2, server: h.example.com, port: 443, password: pw-i}")
        assertNoLoss(fields)
        assertEquals(2, (bean as HysteriaBean).protocolVersion)
        assertField(fields, "type", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        assertEquals("hy2", fields.getValue("type").shownValue)
    }

    @Test
    fun `tuic 全部可转换`() {
        val (bean, fields) = imported(
            "{name: t, type: tuic, server: t.example.com, port: 443, uuid: $uuid, password: pw-j, sni: sni.example.com, alpn: [h3], congestion-controller: bbr, udp-relay-mode: native, heartbeat-interval: 10000, reduce-rtt: true, ip: 203.0.113.7}"
        )
        assertNoLoss(fields)
        bean as TuicBean
        assertEquals(10, bean.heartbeatInterval)
        assertEquals("203.0.113.7", bean.serverAddress)
        assertField(fields, "heartbeat-interval", CONVERTED, ClashFieldReason.UNIT_CONVERTED)
        assertField(fields, "ip", CONVERTED, ClashFieldReason.DIAL_ADDRESS)
    }

    @Test
    fun `wireguard 全部可转换`() {
        val (bean, fields) = imported(
            "{name: w, type: wireguard, server: 198.51.100.20, port: 51820, ip: 198.51.100.2/32, ipv6: \"2001:db8::2/128\", private-key: pk-placeholder, public-key: pub-placeholder, pre-shared-key: psk-placeholder, reserved: [1, 2, 3], mtu: 1280, persistent-keepalive: 25, allowed-ips: [192.0.2.0/24, \"2001:db8::/32\"], udp: true, workers: 2}"
        )
        assertNoLoss(fields)
        bean as WireGuardBean
        assertEquals("198.51.100.2/32\n2001:db8::2/128", bean.localAddress)
        assertEquals("1,2,3", bean.reserved)
        assertEquals("192.0.2.0/24,2001:db8::/32", bean.peerAllowedIps)
        assertField(fields, "workers", IGNORED, ClashFieldReason.NO_WIREGUARD_WORKERS)
    }

    // ---------- 剩余顶层 / 嵌套字段 ----------

    @Test
    fun `没导入的顶层与嵌套字段按路径记有损`() {
        val fields = fields(
            "{name: v, type: vless, server: v.example.com, port: 443, uuid: $uuid, tls: true, dialer-proxy: other, network: ws, ws-opts: {path: /ws, headers: {Host: cdn.example.com, User-Agent: ua-placeholder}}, reality-opts: {public-key: $pbk, support-x25519mlkem768: true}, smux: {enabled: true, max-connections: 4, protocol: smux}}"
        )
        assertField(fields, "dialer-proxy", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "ws-opts.headers.User-Agent", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "reality-opts.support-x25519mlkem768", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "smux.max-connections", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "smux.protocol", KEPT)
        assertEquals(
            setOf("dialer-proxy", "ws-opts.headers.User-Agent", "reality-opts.support-x25519mlkem768", "smux.max-connections"),
            fields.values.filter { it.lossy }.map { it.path }.toSet(),
        )
    }

    @Test
    fun `http-opts 的多个 path 只导入第一个`() {
        val (bean, fields) = imported(
            "{name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid, network: http, http-opts: {path: [/a, /b], method: GET, headers: {Host: [h1.example.com, h2.example.com], Connection: [keep-alive]}}}"
        )
        assertEquals("/a", (bean as VMessBean).path)
        assertField(fields, "http-opts.path", IGNORED, ClashFieldReason.FIRST_ITEM_ONLY)
        assertField(fields, "http-opts.method", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(fields, "http-opts.headers.Host", CONVERTED, ClashFieldReason.LIST_JOINED)
        assertField(fields, "http-opts.headers.Connection", IGNORED, ClashFieldReason.NOT_READ)
    }

    @Test
    fun `mihomo 不认识的键与只对别的 network 起作用的键无影响`() {
        val fields = fields(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, flow: xtls-rprx-vision, foo-bar: x, network: grpc, ws-opts: {max-early-data: 2048}}"
        )
        assertField(fields, "flow", IGNORED, ClashFieldReason.UNKNOWN_KEY)
        assertField(fields, "foo-bar", IGNORED, ClashFieldReason.UNKNOWN_KEY)
        // 别的 network 的 opts 没有写进构建会用到的 host / path
        assertField(fields, "ws-opts", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(fields)
    }

    @Test
    fun `无影响名单里的键单列且不计损失`() {
        val fields = fields(
            "{name: s, type: socks5, server: 192.0.2.10, port: 1080, udp: true, tfo: true, mptcp: true, interface-name: eth0, routing-mark: 255, ip-version: ipv6}"
        )
        for ((key, reason) in CLASH_NO_EFFECT_KEYS) assertField(fields, key, IGNORED, reason)
        assertNoLoss(fields)
        assertEquals("ipv6", fields.getValue("ip-version").shownValue)
    }

    // ---------- 未知值 ----------

    @Test
    fun `未知取值：network 拒绝，packet-encoding 与 smux protocol 记有损`() {
        val failed = parsed("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, network: kcp}")
            .nodes.single() as ClashNodeResult.Failed
        assertEquals(ClashNodeFailure.UNSUPPORTED_TRANSPORT, failed.failure)
        assertEquals(ClashFieldResult.REJECTED, failed.field!!.result)
        assertEquals("network", failed.field!!.path)

        val (bean, fields) = imported(
            "{name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, packet-encoding: foo, smux: {enabled: true, protocol: foo}}"
        )
        // vless 默认 xudp，未知值被归 0
        assertEquals(0, (bean as VMessBean).packetEncoding)
        assertEquals(0, bean.muxType)
        assertField(fields, "packet-encoding", IGNORED, ClashFieldReason.UNKNOWN_VALUE)
        assertField(fields, "smux.protocol", IGNORED, ClashFieldReason.UNKNOWN_VALUE)
        assertEquals("foo", fields.getValue("packet-encoding").shownValue)
    }

    @Test
    fun `packet-addr 与 xudp 布尔键按 mihomo 的实际结果判断`() {
        assertField(
            fields("{name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, xudp: true}"),
            "xudp", IGNORED, ClashFieldReason.MATCHES_RESULT,
        )
        assertField(
            fields("{name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, packet-addr: true}"),
            "packet-addr", IGNORED, ClashFieldReason.NOT_READ,
        )
        assertField(
            fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, xudp: true}"),
            "xudp", IGNORED, ClashFieldReason.NOT_READ,
        )
    }

    // ---------- 读了但丢弃 / 改写 ----------

    @Test
    fun `读了但值被丢弃或改写的记有损`() {
        val tuic = fields("{name: t, type: tuic, server: t.example.com, port: 443, uuid: $uuid, heartbeat-interval: 1500}")
        assertField(tuic, "heartbeat-interval", IGNORED, ClashFieldReason.ROUNDED)

        val hysteria = fields("{name: h, type: hysteria, server: h.example.com, port: 443, up: 1 Gbps, down: 100 MBps, auth-str: pw-k}")
        assertField(hysteria, "up", IGNORED, ClashFieldReason.RATE_UNIT_DROPPED)
        assertField(hysteria, "down", IGNORED, ClashFieldReason.RATE_UNIT_DROPPED)

        val anytls = fields("{name: a, type: anytls, server: a.example.com, port: 443, password: pw-l, certificate: cert-placeholder, private-key: key-placeholder}")
        assertField(anytls, "certificate", IGNORED, ClashFieldReason.MTLS_CLIENT_CERT)
        assertField(anytls, "private-key", IGNORED, ClashFieldReason.MTLS_CLIENT_CERT)

        val ss = fields("{name: s, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, password: pw-m, plugin: v2ray-plugin, plugin-opts: {mode: websocket, mux: false}, udp-over-tcp: true}")
        assertField(ss, "plugin-opts.mux", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(ss, "udp-over-tcp", KEPT)

        val wireguard = fields("{name: w, type: wireguard, server: 198.51.100.20, port: 51820, private-key: pk-placeholder, allowed-ips: bad-placeholder, remote-dns-resolve: true, dns: [192.0.2.53]}")
        assertField(wireguard, "allowed-ips", IGNORED, ClashFieldReason.INVALID_DROPPED)
        assertField(wireguard, "remote-dns-resolve", IGNORED, ClashFieldReason.NOT_SUPPORTED)
        assertField(wireguard, "dns", IGNORED, ClashFieldReason.NOT_READ)
    }

    @Test
    fun `按最终值判断：后写的键改写了前面的`() {
        val sni = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, servername: a.example.com, sni: b.example.com}")
        assertField(sni, "servername", IGNORED, ClashFieldReason.OVERRIDDEN)
        // mihomo 不读 vmess 的 sni，用的是 servername
        assertField(sni, "sni", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)

        // 别的 network 的 opts 不套用（见 `只套用当前 network 的 opts`），不会改写当前 network 的值
        val grpc = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, network: grpc, grpc-opts: {grpc-service-name: svc}, ws-opts: {path: /ws}}")
        assertField(grpc, "grpc-opts.grpc-service-name", KEPT)
        assertField(grpc, "ws-opts", IGNORED, ClashFieldReason.INACTIVE)
    }

    // ---------- 等价转换不计损失 ----------

    @Test
    fun `等价转换不计损失`() {
        val ss = fields("{name: s, type: ss, server: s.example.com, port: 1, cipher: dummy, password: pw-n}")
        assertField(ss, "cipher", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        assertNoLoss(ss)

        val vless = fields("{name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, tls: false, reality-opts: {public-key: $pbk}, flow: xtls-rprx-vision-udp443, sni: l.example.com, alpn: [h2, h3]}")
        assertField(vless, "tls", CONVERTED, ClashFieldReason.REALITY_IMPLIES_TLS)
        assertField(vless, "flow", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        // mihomo 不读 vless 的 sni，但它与 mihomo 实际用的 SNI（server）相同
        assertField(vless, "sni", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(vless, "alpn", CONVERTED, ClashFieldReason.LIST_JOINED)
        assertNoLoss(vless)

        val vmess = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, servername: sni.example.com}")
        assertField(vmess, "servername", KEPT)
        assertNoLoss(vmess)
    }

    @Test
    fun `TLS 关着时不是 SHA-256 摘要的 fingerprint 无影响`() {
        // TLS 打开时整个节点被拒，见 ClashRejectionTest
        assertField(
            fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, fingerprint: chrome}"),
            "fingerprint", IGNORED, ClashFieldReason.INACTIVE,
        )
    }

    // ---------- F：httpupgrade 与键顺序 ----------

    @Test
    fun `HTTP upgrade 两种键顺序都记成等价转换`() {
        for (node in listOf(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, network: ws, ws-opts: {v2ray-http-upgrade: true, path: /up}}",
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, ws-opts: {v2ray-http-upgrade: true, path: /up}, network: ws}",
        )) {
            val (bean, fields) = imported(node)
            assertEquals("httpupgrade", (bean as VMessBean).type)
            assertField(fields, "ws-opts.v2ray-http-upgrade", CONVERTED)
            assertField(fields, "ws-opts.path", KEPT)
        }
    }

    // ---------- D：值回显 ----------

    @Test
    fun `枚举键的值不匹配正则时不显示`() {
        val bad = "\"bad value\""
        fun shown(node: String, path: String) = fields(node).getValue(path).shownValue
        fun failedShown(node: String) = (parsed(node).nodes.single() as ClashNodeResult.Failed).shownValue
        val vmess = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid"
        val vless = "name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid"
        val tuic = "name: t, type: tuic, server: t.example.com, port: 443, uuid: $uuid"

        assertEquals(CLASH_VALUE_NOT_SHOWN, (parsed("{name: x, type: $bad}").nodes.single() as ClashNodeResult.UnknownType).type)
        assertEquals(CLASH_VALUE_NOT_SHOWN, failedShown("{$vmess, network: $bad}"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{name: s, type: ss, server: s.example.com, port: 1, cipher: $bad}", "cipher"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, failedShown("{name: s, type: ss, server: s.example.com, port: 1, cipher: none, plugin: $bad}"))
        assertEquals(
            CLASH_VALUE_NOT_SHOWN,
            shown("{name: s, type: ss, server: s.example.com, port: 1, cipher: none, plugin: obfs, plugin-opts: {mode: $bad}}", "plugin-opts.mode"),
        )
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vless, packet-encoding: $bad}", "packet-encoding"))
        assertEquals(
            CLASH_VALUE_NOT_SHOWN,
            shown("{name: h, type: hysteria, server: h.example.com, port: 443, protocol: $bad}", "protocol"),
        )
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vmess, smux: {enabled: true, protocol: $bad}}", "smux.protocol"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vless, flow: $bad}", "flow"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vmess, client-fingerprint: $bad}", "client-fingerprint"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$tuic, udp-relay-mode: $bad}", "udp-relay-mode"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$tuic, congestion-controller: $bad}", "congestion-controller"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vmess, ip-version: $bad}", "ip-version"))
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vmess, alpn: [h2, $bad]}", "alpn"))
        // 64 个字符以内的合法值原样显示；超长的不显示
        assertEquals("h2,h3", shown("{$vmess, alpn: [h2, h3]}", "alpn"))
        // flow 超过 16 个字节又不以 vision 开头时节点被拒，值同样按规则处理
        assertEquals(CLASH_VALUE_NOT_SHOWN, failedShown("{$vless, flow: ${"x".repeat(65)}}"))
    }

    @Test
    fun `非枚举键不带值，用户写的头名不匹配正则时不显示`() {
        val fields = fields(
            "{name: v, type: vmess, server: secret.example.com, port: 443, uuid: $uuid, network: ws, ws-opts: {headers: {\"X Secret Header\": value-placeholder, X-Plain: v}}}"
        )
        assertTrue(fields.values.filter { it.path != "type" && it.path != "network" }.all { it.shownValue == null })
        assertField(fields, "ws-opts.headers.$CLASH_KEY_NOT_SHOWN", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "ws-opts.headers.X-Plain", IGNORED, ClashFieldReason.NOT_READ)
        assertFalse(fields.keys.any { it.contains("Secret") })
    }

    @Test
    fun `空值与 null 值与没写一样`() {
        val fields = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, global-padding: false, name-cert-verify: \"\", tfo: ~}")
        assertField(fields, "global-padding", IGNORED, ClashFieldReason.DEFAULT_VALUE)
        assertField(fields, "name-cert-verify", IGNORED, ClashFieldReason.DEFAULT_VALUE)
        assertNull(fields["tfo"])
        assertNoLoss(fields)
    }

    // ---------- 与 mihomo 的实际行为对照 ----------

    @Test
    fun `hysteria 的 auth 非空时总是生效，auth-str 无影响`() {
        val h = "name: h, type: hysteria, server: h.example.com, port: 443"
        // 先 auth 后 auth-str：两边都用 auth（依据 mihomo adapter/outbound/hysteria.go NewHysteria：Auth 非空就用它，
        // AuthString 只在 Auth 为空时用；与键的先后无关）
        val (firstBean, authFirst) = imported("{$h, auth: YWJj, auth-str: pw-p}")
        assertEquals(HysteriaBean.TYPE_BASE64, (firstBean as HysteriaBean).authPayloadType)
        assertEquals("YWJj", firstBean.authPayload)
        assertField(authFirst, "auth", KEPT)
        assertField(authFirst, "auth-str", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(authFirst)
        // 先 auth-str 后 auth：两边都用 auth
        val (bean, strFirst) = imported("{$h, auth-str: pw-p, auth: YWJj}")
        assertEquals(HysteriaBean.TYPE_BASE64, (bean as HysteriaBean).authPayloadType)
        assertField(strFirst, "auth", KEPT)
        assertField(strFirst, "auth-str", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(strFirst)
        // auth 为空时 mihomo 用 auth-str
        val emptyAuth = fields("{$h, auth: \"\", auth-str: pw-p}")
        assertField(emptyAuth, "auth-str", KEPT)
        assertField(emptyAuth, "auth", IGNORED, ClashFieldReason.DEFAULT_VALUE)
    }

    @Test
    fun `alpn 按 network 判断：mihomo 写死 ALPN 的传输上与写死值不同记有损`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, servername: v.example.com"
        assertField(fields("{$v, network: ws, alpn: [h2, http/1.1]}"), "alpn", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(fields("{$v, network: ws, alpn: [http/1.1]}"), "alpn", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(fields("{$v, network: grpc, alpn: [h2]}"), "alpn", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(fields("{$v, network: h2, alpn: [http/1.1]}"), "alpn", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        // tcp 两边都用节点写的值
        assertField(fields("{$v, network: tcp, alpn: [h2]}"), "alpn", CONVERTED, ClashFieldReason.LIST_JOINED)
        // TLS 关着时 alpn 不起作用
        assertField(
            fields("{name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid, network: ws, alpn: [h2]}"),
            "alpn", IGNORED, ClashFieldReason.INACTIVE,
        )
        // trojan 只有 grpc 写死，ws 用节点写的值
        val t = "name: t, type: trojan, server: t.example.com, port: 443, password: pw-q"
        assertField(fields("{$t, network: grpc, alpn: [h2, http/1.1]}"), "alpn", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(fields("{$t, network: ws, alpn: [h2]}"), "alpn", CONVERTED, ClashFieldReason.LIST_JOINED)
    }

    @Test
    fun `h2-opts 的 host 列表全部交给构建，被改写时记有损`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, servername: v.example.com, network: h2"
        val (bean, fields) = imported("{$v, h2-opts: {host: [a.example.com, b.example.com], path: /h2}}")
        assertEquals("a.example.com\nb.example.com", (bean as VMessBean).host)
        assertField(fields, "network", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        assertField(fields, "h2-opts.host", CONVERTED, ClashFieldReason.LIST_JOINED)
        assertField(fields, "h2-opts.path", KEPT)
        assertNoLoss(fields)
        // 写在后面的 http-opts 不是当前 network 的：mihomo 不读，解析器也不套用，h2-opts 的 host 不会被改写
        val other = fields("{$v, h2-opts: {host: [a.example.com]}, http-opts: {headers: {Host: [c.example.com]}}}")
        assertField(other, "h2-opts.host", CONVERTED, ClashFieldReason.LIST_JOINED)
        assertField(other, "http-opts", IGNORED, ClashFieldReason.INACTIVE)
    }

    @Test
    fun `只套用当前 network 的 opts，其它 network 的不再改写 host path SNI`() {
        // 依据 mihomo adapter/outbound/vmess.go StreamConnContext、vless.go、trojan.go：每个 network 只读自己的 *-opts
        // tcp 加 TLS：ws-opts 的 Host 不再被 applyClashFixups 当成 SNI（mihomo 用 server）
        val (tcpBean, tcp) = imported(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, network: tcp, ws-opts: {path: /ws, headers: {Host: cdn.example.com}}}"
        )
        tcpBean as VMessBean
        assertEquals("", tcpBean.sni)
        assertEquals("", tcpBean.host)
        assertEquals("", tcpBean.path)
        assertField(tcp, "ws-opts", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(tcp)
        // trojan 不写 network：h2-opts 不是 trojan 的选项
        val (trojan, trojanFields) = imported(
            "{name: t, type: trojan, server: t.example.com, port: 443, password: pw-r, h2-opts: {host: [cdn.example.com]}}"
        )
        assertEquals("", (trojan as TrojanBean).sni)
        assertField(trojanFields, "h2-opts", IGNORED, ClashFieldReason.INACTIVE)
        // network 在 opts 之后写也一样；ws 与 grpc 的 opts 互不影响
        val (ws, wsFields) = imported(
            "{name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid, grpc-opts: {grpc-service-name: svc}, ws-opts: {path: /ws}, network: ws}"
        )
        assertEquals("/ws", (ws as VMessBean).path)
        assertField(wsFields, "ws-opts.path", KEPT)
        assertField(wsFields, "grpc-opts", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(wsFields)
        // 写了 servername，tcp 又不用 host / path：仍无影响
        val quiet = fields(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, servername: v.example.com, ws-opts: {path: /ws, headers: {Host: cdn.example.com}}}"
        )
        assertField(quiet, "ws-opts", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(quiet)
    }

    @Test
    fun `host 补出的 SNI 与 mihomo 不同时记有损`() {
        // trojan 的 ws：mihomo 的 SNI 用 server，不看 Host 头
        val trojan = "name: t, type: trojan, server: t.example.com, port: 443, password: pw-s, network: ws"
        assertField(
            fields("{$trojan, ws-opts: {headers: {Host: cdn.example.com}}}"),
            "ws-opts.headers.Host", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
        assertField(fields("{$trojan, ws-opts: {headers: {Host: t.example.com}}}"), "ws-opts.headers.Host", KEPT)
        // vmess 的 ws：mihomo 同样拿 Host 头当 SNI
        assertField(
            fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, network: ws, ws-opts: {headers: {Host: cdn.example.com}}}"),
            "ws-opts.headers.Host", KEPT,
        )
        // vmess 的 h2：mihomo 只认 servername，否则用 server
        val h2 = fields(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, network: h2, h2-opts: {host: [cdn.example.com], path: /h2}}"
        )
        assertField(h2, "h2-opts.host", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(h2, "h2-opts.path", KEPT)
    }

    @Test
    fun `mihomo 不读的 SNI 键按两边实际用的 SNI 判断`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true"
        assertField(fields("{$v, sni: other.example.com}"), "sni", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(fields("{$v, sni: v.example.com}"), "sni", IGNORED, ClashFieldReason.MATCHES_RESULT)
        // 被后写的 servername 改写：结果与不写相同
        val overridden = fields("{$v, sni: other.example.com, servername: a.example.com}")
        assertField(overridden, "sni", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(overridden, "servername", KEPT)
        assertField(
            fields("{name: t, type: trojan, server: t.example.com, port: 443, password: pw-t, servername: other.example.com}"),
            "servername", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
    }

    @Test
    fun `随节点而定的无影响与语义不同`() {
        // 没打开的 smux 整个无影响
        val smux = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, smux: {enabled: false, max-connections: 4}}")
        assertField(smux, "smux", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(smux)
        // ss 的 client-fingerprint 只用于被拒的插件
        assertField(
            fields("{name: s, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, password: pw-u, client-fingerprint: chrome}"),
            "client-fingerprint", IGNORED, ClashFieldReason.INACTIVE,
        )
        // hysteria2 只写 obfs-password：mihomo 不混淆（NewHysteria2 只在 Obfs 非空时用口令），本应用同样不混淆
        val (plain, onlyPassword) = imported(
            "{name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-v, obfs-password: op-v}"
        )
        assertField(onlyPassword, "obfs-password", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(onlyPassword)
        assertEquals("", (plain as HysteriaBean).obfuscation)
    }

    @Test
    fun `anytls 的 ech-opts 只写 config 不写 enable 时不开 ECH`() {
        // 依据 mihomo adapter/outbound/ech.go ECHOptions.Parse：!o.Enable 就返回 nil，config 不起作用
        val a = "name: a, type: anytls, server: a.example.com, port: 443, password: pw-w"
        val (onlyConfig, onlyConfigFields) = imported("{$a, ech-opts: {config: ZWNoLWNvbmZpZw==}}")
        onlyConfig as AnyTLSBean
        assertEquals(false, onlyConfig.enableECH)
        assertEquals("", onlyConfig.echConfig)
        assertField(onlyConfigFields, "ech-opts.config", IGNORED, ClashFieldReason.INACTIVE)
        assertNoLoss(onlyConfigFields)
        val (disabled, disabledFields) = imported("{$a, ech-opts: {enable: false, config: ZWNoLWNvbmZpZw==}}")
        assertEquals("", (disabled as AnyTLSBean).echConfig)
        assertField(disabledFields, "ech-opts.config", IGNORED, ClashFieldReason.INACTIVE)
        // enable 打开：config 照常保存
        val (enabled, enabledFields) = imported("{$a, ech-opts: {enable: true, config: ZWNoLWNvbmZpZw==}}")
        assertEquals(true, (enabled as AnyTLSBean).enableECH)
        assertEquals("ZWNoLWNvbmZpZw==", enabled.echConfig)
        assertField(enabledFields, "ech-opts.config", KEPT)
        // 整数 1 在 mihomo 里也是真，解析器不认：enable 记「未导入」
        val (one, oneFields) = imported("{$a, ech-opts: {enable: 1, config: ZWNoLWNvbmZpZw==}}")
        assertEquals(false, (one as AnyTLSBean).enableECH)
        assertField(oneFields, "ech-opts.enable", IGNORED, ClashFieldReason.NOT_READ)
        assertField(oneFields, "ech-opts.config", IGNORED, ClashFieldReason.NOT_READ)
    }

    @Test
    fun `顶层的 global-client-fingerprint 不再读取，只在没写指纹的 REALITY 节点上报告`() {
        // 依据 mihomo config/config.go parseGeneral：该配置已移除，只打一条错误日志
        val reality = "name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid, reality-opts: {public-key: $pbk}"
        val plain = "name: p, type: vless, server: p.example.com, port: 443, uuid: $uuid, tls: true"
        val own = "name: o, type: vless, server: o.example.com, port: 443, uuid: $uuid, client-fingerprint: firefox, reality-opts: {public-key: $pbk}"
        val result = parseClash(
            loadClashYaml("global-client-fingerprint: safari\nproxies:\n  - {$reality}\n  - {$plain}\n  - {$own}")
        )
        // 不再把全局值补给 REALITY 节点：用默认的 chrome
        assertEquals(listOf("chrome", "", "firefox"), result.beans.map { (it as VMessBean).utlsFingerprint })
        val records = result.nodes.map { (it as ClashNodeResult.Imported).fields.associateBy { f -> f.path } }
        assertField(records[0], "global-client-fingerprint", IGNORED, ClashFieldReason.REMOVED_IN_MIHOMO)
        assertTrue(records[0].getValue("global-client-fingerprint").lossy)
        assertNull(records[1]["global-client-fingerprint"])
        assertNull(records[2]["global-client-fingerprint"])
        // 没写全局值时没有这条记录
        val without = parsed("{$reality}")
        assertNull((without.nodes.single() as ClashNodeResult.Imported).fields.find { it.path == "global-client-fingerprint" })
        assertEquals("chrome", (without.beans.single() as VMessBean).utlsFingerprint)
    }

    @Test
    fun `ss 的 udp-over-tcp 版本按 mihomo 的规则写进节点`() {
        // 依据 mihomo adapter/outbound/shadowsocks.go NewShadowSocks：UDPOverTCPVersion 为 0（没写）时用 uot.LegacyVersion（1），
        // 1 与 2 照用，其它值报错；ListenPacketContext 按版本选 uot.NewConn（1）或 NewLazyConn（2）
        val s = "name: s, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, password: pw-z"
        val (absent, absentFields) = imported("{$s, udp-over-tcp: true}")
        assertEquals(true, (absent as ShadowsocksBean).sUoT)
        assertEquals(1, absent.sUoTVersion)
        assertField(absentFields, "udp-over-tcp", KEPT)
        assertNoLoss(absentFields)
        val (two, twoFields) = imported("{$s, udp-over-tcp: true, udp-over-tcp-version: 2}")
        assertEquals(2, (two as ShadowsocksBean).sUoTVersion)
        assertField(twoFields, "udp-over-tcp-version", KEPT)
        val (one, oneFields) = imported("{$s, udp-over-tcp: true, udp-over-tcp-version: 1}")
        assertEquals(1, (one as ShadowsocksBean).sUoTVersion)
        assertField(oneFields, "udp-over-tcp-version", KEPT)
        // 0 与不写同义
        val (zero, zeroFields) = imported("{$s, udp-over-tcp: true, udp-over-tcp-version: 0}")
        assertEquals(1, (zero as ShadowsocksBean).sUoTVersion)
        assertField(zeroFields, "udp-over-tcp-version", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        // 键的先后无关
        assertEquals(2, (imported("{$s, udp-over-tcp-version: 2, udp-over-tcp: true}").first as ShadowsocksBean).sUoTVersion)
        // UoT 没开：版本不起作用，留默认值 2
        val (off, offFields) = imported("{$s, udp-over-tcp-version: 1}")
        assertEquals(false, (off as ShadowsocksBean).sUoT)
        assertEquals(2, off.sUoTVersion)
        assertField(offFields, "udp-over-tcp-version", IGNORED, ClashFieldReason.INACTIVE)
        // 不是 1、2、0：mihomo 创建节点时报 unknown udp over tcp protocol version，与 UoT 开没开无关；
        // 拒绝按 mihomo 的读法找键（大小写不敏感、_ 当作 -），写法不同的键同样生效
        for (node in listOf(
            "{$s, udp-over-tcp: true, udp-over-tcp-version: 3}", "{$s, udp-over-tcp-version: 3}", "{$s, udp-over-tcp-version: abc}",
            "{$s, Udp-Over-Tcp-Version: 3}", "{$s, udp_over_tcp_version: 3}",
        )) {
            val failed = parsed(node).nodes.single() as ClashNodeResult.Failed
            assertEquals(node, ClashNodeFailure.INVALID_VALUE, failed.failure)
            assertTrue(node, failed.path!!.equals("udp-over-tcp-version", ignoreCase = true) || failed.path == "udp_over_tcp_version")
            assertNull(failed.shownValue)
        }
        // 整数 1 在 mihomo 里也是真，解析器不认：UoT 没有被读进来
        val (int, intFields) = imported("{$s, udp-over-tcp: 1}")
        assertEquals(false, (int as ShadowsocksBean).sUoT)
        assertField(intFields, "udp-over-tcp", IGNORED, ClashFieldReason.NOT_READ)
    }

    @Test
    fun `trojan 的 network 只认 ws 与 grpc，其它值按 mihomo 当作 TCP 导入`() {
        // 依据 mihomo adapter/outbound/trojan.go StreamConnContext / dialContext：switch Network 只有 ws 与 grpc，default 是 tcp
        for (network in listOf("h2", "http", "xhttp", "kcp", "quic", "WS")) {
            val (bean, trojan) = imported(
                "{name: t, type: trojan, server: t.example.com, port: 443, password: pw-x, network: $network, ws-opts: {path: /ws}}"
            )
            assertEquals(network, "tcp", (bean as TrojanBean).type)
            assertEquals(network, "", bean.path)
            assertField(trojan, "network", IGNORED, ClashFieldReason.UNKNOWN_VALUE)
            assertEquals(network, trojan.getValue("network").shownValue)
            assertTrue(trojan.getValue("network").lossy)
            // 不是 ws，ws-opts 不起作用
            assertField(trojan, "ws-opts", IGNORED, ClashFieldReason.INACTIVE)
        }
        // ws 与 grpc 照旧
        val (ws, wsFields) = imported(
            "{name: t, type: trojan, server: t.example.com, port: 443, password: pw-x, network: ws, ws-opts: {path: /ws}}"
        )
        assertEquals("ws", (ws as TrojanBean).type)
        assertField(wsFields, "network", KEPT)
    }

    @Test
    fun `vmess 与 vless 的 h2 http 只导入对得上的 TLS 组合`() {
        // 依据 mihomo adapter/outbound/vmess.go StreamConnContext：http 是 TLS（可选）之上的 HTTP/1.1 伪装头，
        // h2 是 HTTP/2（tls 关着时是明文 h2c）。本应用的 http 传输带 TLS 时是 h2、不带 TLS 时是伪装头
        for (type in listOf("vmess", "vless")) {
            val base = "name: v, type: $type, server: v.example.com, port: 443, uuid: $uuid"
            val (h2, h2Fields) = imported("{$base, tls: true, network: h2, h2-opts: {host: [a.example.com], path: /h2}}")
            assertEquals("http", (h2 as VMessBean).type)
            assertField(h2Fields, "network", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
            val (http, httpFields) = imported("{$base, network: http, http-opts: {path: [/a], headers: {Host: [b.example.com]}}}")
            assertEquals("http", (http as VMessBean).type)
            assertEquals("/a", http.path)
            assertField(httpFields, "network", KEPT)
            // h2 不带 TLS（h2c）、http 带 TLS（TLS 里的 HTTP/1.1 伪装头）：没有对应的表示，整个节点被拒
            for (node in listOf("{$base, network: h2}", "{$base, tls: true, network: http}")) {
                val failed = parsed(node).nodes.single() as ClashNodeResult.Failed
                assertEquals(node, ClashNodeFailure.TRANSPORT_TLS_MISMATCH, failed.failure)
                assertEquals("network", failed.path)
                assertEquals("transport does not match the TLS setting at network = ${if (node.endsWith("h2}")) "h2" else "http"}", failed.description)
            }
            // REALITY 也会打开 TLS
            val reality = parsed("{$base, network: http, reality-opts: {public-key: $pbk}}").nodes.single()
            assertEquals(ClashNodeFailure.TRANSPORT_TLS_MISMATCH, (reality as ClashNodeResult.Failed).failure)
        }
    }

    @Test
    fun `结果与 mihomo 相同的 TUIC 取值不计损失`() {
        val fields = fields(
            "{name: t, type: tuic, server: t.example.com, port: 443, uuid: $uuid, fast-open: true, udp-relay-mode: foo, heartbeat-interval: -1}"
        )
        assertField(fields, "fast-open", IGNORED, ClashFieldReason.INACTIVE)
        assertField(fields, "udp-relay-mode", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertField(fields, "heartbeat-interval", IGNORED, ClashFieldReason.MATCHES_RESULT)
        assertNoLoss(fields)
    }

    @Test
    fun `同名异义：reserved 字符串`() {
        assertField(
            fields("{name: w, type: wireguard, server: 198.51.100.20, port: 51820, private-key: pk-placeholder, reserved: AQID}"),
            "reserved", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
    }

    @Test
    fun `packet-encoding 的 packet 按 mihomo 当作 packetaddr`() {
        // 依据 mihomo adapter/outbound/vmess.go NewVmess 与 vless.go NewVless：case "packetaddr", "packet"
        for (type in listOf("vmess", "vless")) {
            val (bean, packet) = imported("{name: n, type: $type, server: n.example.com, port: 443, uuid: $uuid, packet-encoding: packet}")
            assertEquals(1, (bean as VMessBean).packetEncoding)
            assertField(packet, "packet-encoding", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
            assertEquals("packet", packet.getValue("packet-encoding").shownValue)
            assertNoLoss(packet)
        }
    }

    @Test
    fun `vless 的 flow 按 mihomo 的判断：以 vision 开头才是 vision`() {
        // 依据 mihomo adapter/outbound/vless.go NewVless：flow 至少 16 个字节时取前 16 个字节，必须恰好是 xtls-rprx-vision
        val l = "name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid"
        val (vision, visionFields) = imported("{$l, flow: xtls-rprx-vision-udp443}")
        assertEquals("xtls-rprx-vision", (vision as VMessBean).encryption)
        assertField(visionFields, "flow", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        // 不足 16 个字节的值被 mihomo 静默忽略，不是 vision
        val (short, shortFields) = imported("{$l, flow: none}")
        assertEquals("", (short as VMessBean).encryption ?: "")
        assertField(shortFields, "flow", IGNORED, ClashFieldReason.UNKNOWN_VALUE)
        // 只是包含 vision：mihomo 报 unsupported xtls flow type，节点被拒（见 ClashRejectionTest）
        val failed = parsed("{$l, flow: foo-xtls-rprx-vision}").nodes.single() as ClashNodeResult.Failed
        assertEquals(ClashNodeFailure.INVALID_VALUE, failed.failure)
        assertEquals("flow", failed.path)
    }

    @Test
    fun `vmess 的 cipher 按 mihomo 转小写`() {
        // 依据 mihomo adapter/outbound/vmess.go NewVmess：security := strings.ToLower(option.Cipher)
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid"
        val (upper, upperFields) = imported("{$v, cipher: AES-128-GCM}")
        assertEquals("aes-128-gcm", (upper as VMessBean).encryption)
        assertField(upperFields, "cipher", CONVERTED, ClashFieldReason.EQUIVALENT_VALUE)
        assertNoLoss(upperFields)
        val (lower, lowerFields) = imported("{$v, cipher: auto}")
        assertEquals("auto", (lower as VMessBean).encryption)
        assertField(lowerFields, "cipher", KEPT)
    }

    // ---------- inactive 判断按 mihomo 的读法 ----------

    // 依据：mihomo 的结构解码器（common/structure.Decoder.decodeStructFromMap）找键时先找原样，再不区分大小写并把 _ 当作 -；
    // 布尔按 WeaklyTypedInput（true 或非 0 整数）。解析器只读原样的键，没读到的键 mihomo 却读到并用上时，要记有损

    @Test
    fun `smux 的开关键写法不同：mihomo 读到而解析器没读到，记有损而不是无影响`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid"
        // adapter/parser.go：smux 子选项交给结构解码器，Enabled 与 enabled 同义
        val (bean, upper) = imported("{$v, smux: {Enabled: true, max-streams: 8}}")
        assertEquals(false, (bean as VMessBean).enableMux)
        assertField(upper, "smux.Enabled", IGNORED, ClashFieldReason.NOT_READ)
        assertField(upper, "smux.max-streams", IGNORED, ClashFieldReason.NOT_READ)
        assertTrue(upper.getValue("smux.Enabled").lossy)
        assertTrue(upper.getValue("smux.max-streams").lossy)
        // 整数 1 在 mihomo 里也是真
        assertField(fields("{$v, smux: {enabled: 1, padding: true}}"), "smux.padding", IGNORED, ClashFieldReason.NOT_READ)
        // 写法不同的 false：mihomo 不套 sing-mux，整个对象无影响
        assertField(fields("{$v, smux: {Enabled: false, max-streams: 8}}"), "smux", IGNORED, ClashFieldReason.INACTIVE)
        // 顶层的 smux 键 ParseProxy 只按原样取：SMUX 在 mihomo 里不起作用
        assertField(fields("{$v, SMUX: {enabled: true, max-streams: 8}}"), "SMUX", IGNORED, ClashFieldReason.INACTIVE)
        // 原样的键仍按已导入记
        val (exact, kept) = imported("{$v, smux: {enabled: true, max-streams: 8}}")
        assertEquals(true, (exact as VMessBean).enableMux)
        assertField(kept, "smux.max-streams", KEPT)
        // brutal-opts 的 enabled 同样按 mihomo 的读法
        assertField(
            fields("{$v, smux: {enabled: true, brutal-opts: {Enabled: true, up: 100}}}"), "smux.brutal-opts", IGNORED,
            ClashFieldReason.NOT_READ,
        )
        assertField(
            fields("{$v, smux: {enabled: true, brutal-opts: {Enabled: false, up: 100}}}"), "smux.brutal-opts", IGNORED,
            ClashFieldReason.INACTIVE,
        )
    }

    @Test
    fun `ech-opts 的 enable 按 mihomo 的读法判断`() {
        val tls = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true"
        assertField(fields("{$tls, ech-opts: {Enable: true, config: ZWNo}}"), "ech-opts", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$tls, ECH-Opts: {enable: 1}}"), "ECH-Opts", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$tls, ech-opts: {Enable: false, config: ZWNo}}"), "ech-opts", IGNORED, ClashFieldReason.INACTIVE)
        // 其它类型的 ech-opts 判断相同
        val hysteria = "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-z"
        assertField(fields("{$hysteria, ech-opts: {Enable: true}}"), "ech-opts", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$hysteria, ech-opts: {Enable: false}}"), "ech-opts", IGNORED, ClashFieldReason.INACTIVE)
        val tuic = "name: t, type: tuic, server: t.example.com, port: 443, uuid: $uuid"
        assertField(fields("{$tuic, ech-opts: {Enable: true}}"), "ech-opts", IGNORED, ClashFieldReason.NOT_READ)
        val anytls = "name: a, type: anytls, server: a.example.com, port: 443, password: pw-z"
        assertField(fields("{$anytls, ech-opts: {Enable: true, query-server-name: q.example.com}}"), "ech-opts.Enable", IGNORED, ClashFieldReason.NOT_READ)
        assertField(
            fields("{$anytls, ech-opts: {Enable: false, query-server-name: q.example.com}}"),
            "ech-opts.query-server-name", IGNORED, ClashFieldReason.INACTIVE,
        )
    }

    @Test
    fun `network 与 ws-opts 开关键写法不同时按 mihomo 的读法判断`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid"
        // mihomo 读到 Network: ws 并用 ws-opts，解析器没读到 network
        val (bean, fields) = imported("{$v, Network: ws, ws-opts: {path: /ws}}")
        assertEquals("tcp", (bean as VMessBean).type)
        assertField(fields, "Network", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields, "ws-opts", IGNORED, ClashFieldReason.NOT_READ)
        // v2ray-http-upgrade-fast-open 只在 v2ray-http-upgrade 打开时 mihomo 才用
        val ws = "$v, network: ws"
        assertField(
            fields("{$ws, ws-opts: {V2ray-Http-Upgrade: true, v2ray-http-upgrade-fast-open: true}}"),
            "ws-opts.v2ray-http-upgrade-fast-open", IGNORED, ClashFieldReason.NOT_READ,
        )
        assertField(
            fields("{$ws, ws-opts: {V2ray-Http-Upgrade: false, v2ray-http-upgrade-fast-open: true}}"),
            "ws-opts.v2ray-http-upgrade-fast-open", IGNORED, ClashFieldReason.INACTIVE,
        )
    }

    @Test
    fun `没有公钥的 reality-opts 与未生效的 TLS 变体，写法不同时仍是无影响`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true"
        assertField(fields("{$v, Reality-Opts: {public-key: \"\"}}"), "Reality-Opts", IGNORED, ClashFieldReason.INACTIVE)
        // 缺 public-key 键：mihomo 的 RealityOptions.PublicKey 没有 omitempty，has unset fields，节点解码失败，不是「无影响」
        assertField(fields("{$v, Reality-Opts: {short-id: ab}}"), "Reality-Opts", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertTrue(fields("{$v, reality-opts: {short-id: ab}}").getValue("reality-opts").lossy)
        assertField(fields("{$v, reality-opts: {public-key: \"\", short-id: ab}}"), "reality-opts", IGNORED, ClashFieldReason.INACTIVE)
        assertField(fields("{$v, Shadow-TLS-Opts: {version: 0}}"), "Shadow-TLS-Opts", IGNORED, ClashFieldReason.INACTIVE)
    }

    @Test
    fun `ss 的 plugin 与 udp-over-tcp 写法不同时按 mihomo 的读法判断`() {
        val s = "name: s, type: ss, server: s.example.com, port: 1, cipher: aes-128-gcm, password: pw-z"
        // NewShadowSocks：Plugin 经结构解码器读到，obfs 插件会用 plugin-opts
        assertField(fields("{$s, Plugin: obfs, plugin-opts: {mode: tls}}"), "plugin-opts", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$s, plugin-opts: {mode: tls}}"), "plugin-opts", IGNORED, ClashFieldReason.INACTIVE)
        // udp-over-tcp-version 只在 udp-over-tcp 打开时用
        assertField(
            fields("{$s, UDP-over-TCP: true, udp-over-tcp-version: 2}"), "udp-over-tcp-version", IGNORED,
            ClashFieldReason.NOT_READ,
        )
        assertField(
            fields("{$s, UDP-over-TCP: false, udp-over-tcp-version: 2}"), "udp-over-tcp-version", IGNORED,
            ClashFieldReason.INACTIVE,
        )
        // client-fingerprint 在 shadow-tls / restls / jls 插件里 mihomo 会用（NewShadowSocks 传给这些插件）；插件写成解析器
        // 读不到的键名（Plugin）时节点照常导入，指纹是 mihomo 用上而本应用没导入的，不能记无影响；别的插件才无影响
        for (plugin in listOf("shadow-tls", "restls", "jls")) {
            assertField(
                fields("{$s, Plugin: $plugin, client-fingerprint: chrome}"), "client-fingerprint", IGNORED,
                ClashFieldReason.NOT_READ,
            )
        }
        assertField(
            fields("{$s, Plugin: obfs, client-fingerprint: chrome}"), "client-fingerprint", IGNORED, ClashFieldReason.INACTIVE,
        )
        // v2ray-plugin 的 tls 子键用的解码器不把 _ 当作 -：skip_cert_verify 在 mihomo 里不是 skip-cert-verify
        val plugin = "$s, plugin: v2ray-plugin, plugin-opts: {mode: websocket, tls: true"
        assertField(fields("{$plugin, skip-cert-verify: true}}"), "plugin-opts.skip-cert-verify", IGNORED, ClashFieldReason.NOT_READ)
        assertField(
            fields("{$s, plugin: v2ray-plugin, plugin-opts: {mode: websocket, skip-cert-verify: true}}"),
            "plugin-opts.skip-cert-verify", IGNORED, ClashFieldReason.INACTIVE,
        )
    }

    @Test
    fun `wireguard 的 dns 与 hysteria 的 ports 按 mihomo 的读法判断`() {
        val w = "name: w, type: wireguard, server: 198.51.100.20, port: 51820, private-key: pk-placeholder"
        assertField(fields("{$w, remote_dns_resolve: true, dns: [192.0.2.53]}"), "dns", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$w, Remote-DNS-Resolve: 1, dns: [192.0.2.53]}"), "dns", IGNORED, ClashFieldReason.NOT_READ)
        assertField(fields("{$w, Remote-DNS-Resolve: 0, dns: [192.0.2.53]}"), "dns", IGNORED, ClashFieldReason.INACTIVE)
        // Ports 在 mihomo 里生效，port 退为无影响
        val h = fields("{name: h, type: hysteria, server: h.example.com, port: 443, Ports: 1000-2000, auth-str: pw-z}")
        assertField(h, "port", IGNORED, ClashFieldReason.INACTIVE)
        assertField(h, "Ports", IGNORED, ClashFieldReason.NOT_READ)
    }

    // ---------- 失败路径与兜底 ----------

    @Test
    fun `不是字符串的键：失败路径只显示占位串，不带键的原文`() {
        for (base in listOf(
            "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid",
            "name: t, type: trojan, server: t.example.com, port: 443, password: pw-y",
            "name: a, type: anytls, server: a.example.com, port: 443, password: pw-y",
            "name: u, type: tuic, server: u.example.com, port: 443, uuid: $uuid",
        )) for (key in listOf<Any>(listOf("zq61", "zq62"), mapOf("zq63" to "zq64"), 6160, true)) {
            val entry = LinkedHashMap<Any?, Any?>(loadClashYaml("{$base}"))
            entry[key] = "x"
            val failed = parseClash(mapOf("proxies" to listOf(entry))).nodes.single() as ClashNodeResult.Failed
            assertEquals(ClashNodeFailure.INVALID_VALUE, failed.failure)
            assertEquals(CLASH_KEY_NOT_SHOWN, failed.path)
            assertEquals("invalid value at $CLASH_KEY_NOT_SHOWN", failed.description)
        }
    }

    @Test
    fun `显式标签的非字符串标量键：字段路径只显示占位串`() {
        // 本应用的 YAML 把未加标签的数字 / 布尔读成字符串，加了 !!int 之类的显式标签才会得到非字符串键；
        // 它们的 toString 能通过显示正则，但非字符串键一律不显示
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid"
        for (key in listOf("!!int 6171", "!!float 6172.5", "!!bool true")) {
            val fields = fields("{$v, network: ws, ws-opts: {headers: {$key: x, X-Plain: v}}}")
            assertField(fields, "ws-opts.headers.$CLASH_KEY_NOT_SHOWN", IGNORED, ClashFieldReason.NOT_READ)
            assertField(fields, "ws-opts.headers.X-Plain", IGNORED, ClashFieldReason.NOT_READ)
            assertFalse(key, fields.keys.any { it.contains("617") || it.contains("true") })
        }
    }

    @Test
    fun `失败原因只在枚举键上带值`() {
        val v = "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid"
        val smux = parsed("{$v, smux: {enabled: true, max-streams: secret-zq65}}").nodes.single() as ClashNodeResult.Failed
        assertEquals(ClashNodeFailure.INVALID_VALUE, smux.failure)
        assertEquals("smux.max-streams", smux.path)
        assertNull(smux.shownValue)
        assertFalse(smux.description.contains("zq65"))
        val bad = parsed("{$v, client-fingerprint: [chrome, \"bad zq66\"]}").nodes.single() as ClashNodeResult.Failed
        assertEquals("client-fingerprint", bad.path)
        assertEquals(CLASH_VALUE_NOT_SHOWN, bad.shownValue)
        val good = parsed("{$v, client-fingerprint: [chrome, firefox]}").nodes.single() as ClashNodeResult.Failed
        assertEquals("chrome,firefox", good.shownValue)
    }

    @Test
    fun `字段记录本身出错时只记一条未分类`() {
        val broken = object : LinkedHashMap<String, Any?>() {
            override fun get(key: String): Any? = throw IllegalStateException("zq67")
        }.apply {
            put("name", "v")
            put("type", "vmess")
        }
        val records = classifyClashFields("vmess", broken, VMessBean())
        assertEquals(listOf(ClashFieldRecord("*", IGNORED, ClashFieldReason.NOT_CLASSIFIED)), records)
        assertTrue(records.single().lossy)
    }
}
