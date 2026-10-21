package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldRecord
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Clash 订阅的 XHTTP（mihomo v1.19.32 adapter/outbound/vless.go）：只有 VLESS 的 network: xhttp 导入 host / path / mode，
// 带 download-settings 的整个节点拒绝，其它 xhttp-opts 键记有损；VMess 仍拒绝，Trojan 仍按 TCP
class ClashXhttpTest {

    private val vless = "name: l, type: vless, server: l.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000c"
    private val vmess = "name: v, type: vmess, server: v.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000b"
    private val trojan = "name: t, type: trojan, server: t.example.com, port: 443, password: pw-placeholder"

    private fun parsed(body: String) = parseClash(loadClashYaml("proxies:\n  - {$body}"))
    private fun node(body: String) = parsed(body).nodes.single()

    private fun imported(body: String): Pair<VMessBean, Map<String, ClashFieldRecord>> {
        val result = parsed(body)
        val entry = result.nodes.single() as? ClashNodeResult.Imported ?: error("not imported: $body: ${result.nodes.single()}")
        return result.beans.single() as VMessBean to entry.fields.associateBy { it.path }
    }

    private fun failed(body: String) = node(body) as? ClashNodeResult.Failed ?: error("not rejected: $body")

    // ---- 导入

    @Test
    fun `VLESS xhttp 导入 host path mode`() {
        val (bean, fields) = imported("$vless, network: xhttp, xhttp-opts: {host: cdn.example.com, path: /xh, mode: stream-up}")
        assertEquals("xhttp", bean.type)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("/xh", bean.path)
        assertEquals("stream-up", bean.xhttpMode)
        assertEquals("", bean.xhttpExtra)
        assertEquals(ClashFieldResult.KEPT, fields.getValue("network").result)
        for (key in listOf("host", "path", "mode")) assertEquals(key, ClashFieldResult.KEPT, fields.getValue("xhttp-opts.$key").result)
        // mode 按枚举路径显示，host / path 不显示
        assertEquals("stream-up", fields.getValue("xhttp-opts.mode").shownValue)
        assertNull(fields.getValue("xhttp-opts.host").shownValue)
        assertNull(fields.getValue("xhttp-opts.path").shownValue)
        assertTrue(fields.values.none { it.lossy })
    }

    @Test
    fun `缺省值：没有 xhttp-opts 或键为空时 path 是斜杠，mode 是 auto，host 为空`() {
        for (body in listOf(
            "$vless, network: xhttp",
            "$vless, network: xhttp, xhttp-opts: {}",
            "$vless, network: xhttp, xhttp-opts: {path: \"\", host: \"\", mode: \"\"}",
        )) {
            val (bean) = imported(body)
            assertEquals(body, "xhttp", bean.type)
            assertEquals(body, "/", bean.path)
            assertEquals(body, "", bean.host)
            assertEquals(body, "auto", bean.xhttpMode)
        }
    }

    @Test
    fun `键按 mihomo 的读法找：大小写不敏感，下划线当作短横线`() {
        val (bean, fields) = imported("$vless, network: xhttp, Xhttp_Opts: {Path: /p, HOST: h.example.com, Mode: Packet-Up}")
        assertEquals("/p", bean.path)
        assertEquals("h.example.com", bean.host)
        assertEquals("packet-up", bean.xhttpMode)
        // 写法不同的 mode 规范成小写：转换而不是原样保留；键名如实显示，值按规范化后的路径 xhttp-opts.mode 的枚举规则显示
        assertEquals(ClashFieldResult.CONVERTED, fields.getValue("Xhttp_Opts.Mode").result)
        assertEquals("Packet-Up", fields.getValue("Xhttp_Opts.Mode").shownValue)
        assertEquals(ClashFieldResult.KEPT, fields.getValue("Xhttp_Opts.Path").result)
    }

    @Test
    fun `TLS 与 REALITY 下 host 只是 Host 头，不补成 SNI`() {
        val (tls) = imported("$vless, tls: true, network: xhttp, xhttp-opts: {host: cdn.example.com, path: /x}")
        assertEquals("cdn.example.com", tls.host)
        assertTrue("host 不当 SNI", tls.sni.isNullOrBlank())
        // 写了 servername 就用它
        val (named) = imported("$vless, tls: true, servername: sni.example.com, network: xhttp, xhttp-opts: {host: cdn.example.com}")
        assertEquals("sni.example.com", named.sni)
        // 对照：ws 的 Host 头仍补成 SNI（既有行为）
        val (ws) = imported("$vless, tls: true, network: ws, ws-opts: {path: /x, headers: {Host: cdn.example.com}}")
        assertEquals("cdn.example.com", ws.sni)
    }

    @Test
    fun `network 不是 xhttp 时 xhttp-opts 不起作用`() {
        val (bean, fields) = imported("$vless, network: ws, ws-opts: {path: /ws}, xhttp-opts: {path: /xh, mode: stream-up}")
        assertEquals("ws", bean.type)
        assertEquals("/ws", bean.path)
        assertEquals("auto", bean.xhttpMode)
        assertEquals(ClashFieldReason.INACTIVE, fields.getValue("xhttp-opts").reason)
        assertFalse(fields.getValue("xhttp-opts").lossy)
        // 不读，所以 download-settings 与坏 mode 也不拒绝
        imported("$vless, xhttp-opts: {download-settings: {server: x.example.com}, mode: bogus}")
    }

    // ---- 有损记录

    @Test
    fun `host path mode 之外的 xhttp-opts 键记为有损，不翻译成 extra`() {
        val keys = listOf(
            "headers: {X-Token: secret-value-zq71}", "no-grpc-header: true", "x-padding-bytes: 100-500", "x-padding-obfs-mode: true",
            "x-padding-key: k", "x-padding-header: h", "x-padding-placement: header", "x-padding-method: tokenish",
            "uplink-http-method: PUT", "sc-max-each-post-bytes: 1000000", "sc-min-posts-interval-ms: 30", "uplink-chunk-size: 4096",
            "session-placement: path", "session-key: s", "session-table: abc", "session-length: 16", "seq-placement: path",
            "seq-key: q", "uplink-data-placement: body", "uplink-data-key: d", "reuse-settings: {max-concurrency: 16-32}",
        )
        val (bean, fields) = imported("$vless, network: xhttp, xhttp-opts: {path: /xh, ${keys.joinToString(", ")}}")
        assertEquals("", bean.xhttpExtra)
        for (entry in keys) {
            val key = entry.substringBefore(":")
            val record = fields.getValue("xhttp-opts.$key")
            assertEquals(key, ClashFieldResult.IGNORED, record.result)
            assertEquals(key, ClashFieldReason.NOT_READ, record.reason)
            assertTrue(key, record.lossy)
            assertNull(key, record.shownValue)
        }
        // 没见过的键名拿不准也算有损（不当成「mihomo 不认识」）
        val (_, unknown) = imported("$vless, network: xhttp, xhttp-opts: {some-future-option: 1}")
        assertTrue(unknown.getValue("xhttp-opts.some-future-option").lossy)
        // 空值与 false 同没写一样
        val (_, empty) = imported("$vless, network: xhttp, xhttp-opts: {no-grpc-header: false, x-padding-key: \"\"}")
        assertFalse(empty.getValue("xhttp-opts.no-grpc-header").lossy)
        assertFalse(empty.getValue("xhttp-opts.x-padding-key").lossy)
    }

    @Test
    fun `诊断文本不带 header 的名字与值`() {
        val result = parsed("$vless, network: xhttp, xhttp-opts: {headers: {X-Token-zq71: secret-value-zq72}}")
        val text = (result.nodes.single() as ClashNodeResult.Imported).fields.joinToString("\n") { "${it.path} ${it.shownValue}" }
        assertFalse(text, text.contains("zq71"))
        assertFalse(text, text.contains("zq72"))
    }

    // ---- 拒绝

    @Test
    fun `download-settings 整个节点拒绝，空对象也一样，值与键内容不进诊断`() {
        for (body in listOf(
            "$vless, network: xhttp, xhttp-opts: {path: /x, download-settings: {server: secret-dl.example.com, port: 8443}}",
            "$vless, network: xhttp, xhttp-opts: {download-settings: {}}",
            "$vless, network: xhttp, xhttp-opts: {Download_Settings: {server: x.example.com}}",
            "$vless, network: xhttp, Xhttp-Opts: {download-settings: {server: x.example.com}}",
        )) {
            val f = failed(body)
            assertEquals(body, ClashNodeFailure.DOWNLOAD_SETTINGS_NOT_SUPPORTED, f.failure)
            assertTrue(body, f.path!!.lowercase().replace('_', '-').endsWith("xhttp-opts.download-settings"))
            assertNull(body, f.shownValue)
            assertFalse(body, f.description.contains("secret-dl"))
        }
    }

    @Test
    fun `mode 不认识的节点拒绝，值只经枚举路径与规则显示`() {
        val f = failed("$vless, network: xhttp, xhttp-opts: {mode: fast-mode}")
        assertEquals(ClashNodeFailure.INVALID_VALUE, f.failure)
        assertEquals("xhttp-opts.mode", f.path)
        assertEquals("fast-mode", f.shownValue)
        // 形态不合格的值不显示
        val odd = failed("$vless, network: xhttp, xhttp-opts: {mode: \"has space & secret\"}")
        assertEquals(ClashNodeFailure.INVALID_VALUE, odd.failure)
        assertEquals("(value not shown)", odd.shownValue)
        assertFalse(odd.description.contains("secret"))
        // 写法不同的键：路径如实，值不显示（路径不在枚举里）
        val spelled = failed("$vless, network: xhttp, xhttp-opts: {Mode: bogus}")
        assertEquals("xhttp-opts.Mode", spelled.path)
        assertNull(spelled.shownValue)
    }

    @Test
    fun `xhttp-opts 或其取值的形状不对时拒绝`() {
        assertEquals(ClashNodeFailure.INVALID_VALUE, failed("$vless, network: xhttp, xhttp-opts: just-a-string").failure)
        assertEquals(ClashNodeFailure.INVALID_VALUE, failed("$vless, network: xhttp, xhttp-opts: [a, b]").failure)
        val list = failed("$vless, network: xhttp, xhttp-opts: {path: [/a, /b]}")
        assertEquals(ClashNodeFailure.INVALID_VALUE, list.failure)
        assertEquals("xhttp-opts.path", list.path)
        assertNull(list.shownValue)
        assertEquals(ClashNodeFailure.INVALID_VALUE, failed("$vless, network: xhttp, xhttp-opts: {host: {a: b}}").failure)
    }

    @Test
    fun `VMess 的 xhttp 仍然拒绝，按不支持的传输报 network`() {
        val f = failed("$vmess, network: xhttp, xhttp-opts: {path: /xh}")
        assertEquals(ClashNodeFailure.UNSUPPORTED_TRANSPORT, f.failure)
        assertEquals("network", f.path)
        assertEquals("xhttp", f.shownValue)
    }

    @Test
    fun `Trojan 的 network xhttp 与 mihomo 一样按 TCP 导入`() {
        val result = parsed("$trojan, network: xhttp, xhttp-opts: {path: /xh}")
        val bean = result.beans.single() as TrojanBean
        assertEquals("tcp", bean.type)
        assertEquals("", bean.path)
        val fields = (result.nodes.single() as ClashNodeResult.Imported).fields.associateBy { it.path }
        assertEquals(ClashFieldReason.UNKNOWN_VALUE, fields.getValue("network").reason)
        assertEquals(ClashFieldReason.INACTIVE, fields.getValue("xhttp-opts").reason)
    }

    @Test
    fun `其它 network 的拒绝不受影响`() {
        val f = failed("$vless, network: kcp")
        assertEquals(ClashNodeFailure.UNSUPPORTED_TRANSPORT, f.failure)
        // network 区分大小写，与 mihomo 一致
        assertEquals(ClashNodeFailure.UNSUPPORTED_TRANSPORT, failed("$vless, network: XHTTP").failure)
    }

    @Test
    fun `批量里坏节点只跳过它自己`() {
        val yaml = """
            proxies:
              - {$vless, network: xhttp, xhttp-opts: {path: /ok}}
              - {$vless, network: xhttp, xhttp-opts: {download-settings: {server: x.example.com}}}
              - {$vless, network: xhttp, xhttp-opts: {mode: bogus}}
              - {$vmess, network: xhttp}
        """.trimIndent()
        val result = parseClash(loadClashYaml(yaml))
        assertEquals(1, result.beans.size)
        assertEquals(
            listOf(
                ClashNodeFailure.DOWNLOAD_SETTINGS_NOT_SUPPORTED, ClashNodeFailure.INVALID_VALUE, ClashNodeFailure.UNSUPPORTED_TRANSPORT,
            ),
            result.nodes.filterIsInstance<ClashNodeResult.Failed>().map { it.failure },
        )
    }
}
