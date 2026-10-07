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
        assertField(ss, "udp-over-tcp", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)

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

        val grpc = fields("{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, network: grpc, grpc-opts: {grpc-service-name: svc}, ws-opts: {path: /ws}}")
        assertField(grpc, "grpc-opts.grpc-service-name", IGNORED, ClashFieldReason.OVERRIDDEN)
        assertField(grpc, "ws-opts.path", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
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
        assertEquals(CLASH_VALUE_NOT_SHOWN, shown("{$vless, flow: ${"x".repeat(65)}}", "flow"))
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
        // 先 auth 后 auth-str：解析器用了 auth-str，mihomo 用 auth
        val authFirst = fields("{$h, auth: YWJj, auth-str: pw-p}")
        assertField(authFirst, "auth", IGNORED, ClashFieldReason.OVERRIDDEN)
        assertField(authFirst, "auth-str", IGNORED, ClashFieldReason.INACTIVE)
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
        // 写在后面的 http-opts 改写了 host：h2-opts 的被改写，http-opts 的 mihomo 不读却生效了
        val overridden = fields("{$v, h2-opts: {host: [a.example.com]}, http-opts: {headers: {Host: [c.example.com]}}}")
        assertField(overridden, "h2-opts.host", IGNORED, ClashFieldReason.OVERRIDDEN)
        assertField(overridden, "http-opts.headers.Host", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
    }

    @Test
    fun `别的 network 的 opts 写进了最终 host 或 path 时按子键记有损`() {
        // tcp 加 TLS：ws-opts 的 Host 被 applyClashFixups 当成 SNI，mihomo 用 server
        val (tcpBean, tcp) = imported(
            "{name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid, tls: true, network: tcp, ws-opts: {path: /ws, headers: {Host: cdn.example.com}}}"
        )
        assertEquals("cdn.example.com", (tcpBean as VMessBean).sni)
        assertField(tcp, "ws-opts.headers.Host", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        // tcp 不用 path
        assertField(tcp, "ws-opts.path", IGNORED, ClashFieldReason.INACTIVE)
        // trojan 不写 network：h2-opts 的 host 同样变成 SNI
        assertField(
            fields("{name: t, type: trojan, server: t.example.com, port: 443, password: pw-r, h2-opts: {host: [cdn.example.com]}}"),
            "h2-opts.host", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
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
        // hysteria2 只写 obfs-password：mihomo 不混淆，本应用开 salamander
        assertField(
            fields("{name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-v, obfs-password: op-v}"),
            "obfs-password", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
        // hysteria2 写了 salamander 却没有混淆密码：mihomo 报 missing obfs password，本应用导入成不混淆的节点
        for (password in listOf("", ", obfs-password: \"\"")) {
            val (bean, noPassword) = imported("{name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-v, obfs: salamander$password}")
            assertField(noPassword, "obfs", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
            assertTrue(noPassword.getValue("obfs").lossy)
            assertEquals("", (bean as HysteriaBean).obfuscation)
        }
        // anytls 的 ech-opts：只写 config 时本应用开 ECH、mihomo 不开；enable 关着时 config 无影响
        val a = "name: a, type: anytls, server: a.example.com, port: 443, password: pw-w"
        assertField(fields("{$a, ech-opts: {config: ZWNoLWNvbmZpZw==}}"), "ech-opts.config", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertField(
            fields("{$a, ech-opts: {enable: false, config: ZWNoLWNvbmZpZw==}}"),
            "ech-opts.config", IGNORED, ClashFieldReason.INACTIVE,
        )
        // h2 不带 TLS：本应用是 HTTP/1.1 伪装头，mihomo 是 h2c
        assertField(
            fields("{name: v, type: vmess, server: v.example.com, port: 80, uuid: $uuid, network: h2, h2-opts: {path: /h2}}"),
            "network", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
        // trojan 的 h2 / http：mihomo 当裸 TCP，本应用套 HTTP 传输
        for (network in listOf("h2", "http")) {
            assertField(
                fields("{name: t, type: trojan, server: t.example.com, port: 443, password: pw-x, network: $network}"),
                "network", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
            )
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
    fun `同名异义：reserved 字符串、只包含 vision 的 flow、packet`() {
        assertField(
            fields("{name: w, type: wireguard, server: 198.51.100.20, port: 51820, private-key: pk-placeholder, reserved: AQID}"),
            "reserved", IGNORED, ClashFieldReason.SEMANTICS_DIFFER,
        )
        val l = "name: l, type: vless, server: l.example.com, port: 443, uuid: $uuid"
        assertField(fields("{$l, flow: foo-xtls-rprx-vision}"), "flow", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        val packet = fields("{$l, packet-encoding: packet}")
        assertField(packet, "packet-encoding", IGNORED, ClashFieldReason.SEMANTICS_DIFFER)
        assertEquals("packet", packet.getValue("packet-encoding").shownValue)
    }

    // ---------- 失败路径与兜底 ----------

    @Test
    fun `不是字符串的键：失败路径只显示占位串，不带键的原文`() {
        for (base in listOf(
            "name: v, type: vmess, server: v.example.com, port: 443, uuid: $uuid",
            "name: t, type: trojan, server: t.example.com, port: 443, password: pw-y",
            "name: a, type: anytls, server: a.example.com, port: 443, password: pw-y",
            "name: u, type: tuic, server: u.example.com, port: 443, uuid: $uuid",
        )) for (key in listOf<Any>(listOf("zq61", "zq62"), mapOf("zq63" to "zq64"))) {
            val entry = LinkedHashMap<Any?, Any?>(loadClashYaml("{$base}"))
            entry[key] = "x"
            val failed = parseClash(mapOf("proxies" to listOf(entry))).nodes.single() as ClashNodeResult.Failed
            assertEquals(ClashNodeFailure.INVALID_VALUE, failed.failure)
            assertEquals(CLASH_KEY_NOT_SHOWN, failed.path)
            assertEquals("invalid value at $CLASH_KEY_NOT_SHOWN", failed.description)
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
