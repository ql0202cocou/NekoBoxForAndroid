package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.CLASH_VALUE_NOT_SHOWN
import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashImportSummary
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.CERT_FINGERPRINT_INVALID
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.CERT_PIN_NOT_SUPPORTED
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.EXTRA_ENCRYPTION
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.OBFS_NOT_SUPPORTED
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.REALITY_PUBLIC_KEY_MISSING
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.REALITY_WITHOUT_TLS
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.SECURITY_SETTING_NOT_READ
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.TLS_NOT_SUPPORTED
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.TLS_VARIANT
import io.nekohasekai.sagernet.fmt.ClashNodeFailure.VLESS_ENCRYPTION
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 少一道安全校验或导入后永远连不上的节点整个拒绝；边界上照常导入的同样钉住
class ClashRejectionTest {

    private val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val pbk = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
    private val vmess = "name: v, type: vmess, server: v.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000b"
    private val vless = "name: l, type: vless, server: l.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000c"
    private val trojan = "name: t, type: trojan, server: t.example.com, port: 443, password: pw-placeholder"

    private fun parsed(body: String) = parseClash(loadClashYaml("proxies:\n  - {$body}"))
    private fun node(body: String) = parsed(body).nodes.single()

    private fun assertRejected(body: String, failure: ClashNodeFailure, path: String) {
        val node = node(body) as? ClashNodeResult.Failed ?: error("not rejected: $body")
        assertEquals(failure, node.failure)
        assertEquals(path, node.path)
    }

    private fun assertImported(body: String) = node(body) as? ClashNodeResult.Imported ?: error("not imported: $body")

    private fun ClashNodeResult.Imported.reason(path: String) = fields.single { it.path == path }.reason

    @Test
    fun `fingerprint 不是 SHA-256 摘要且 TLS 打开时拒绝`() {
        assertRejected("$trojan, fingerprint: chrome-secret-zq30", CERT_FINGERPRINT_INVALID, "fingerprint")
        assertRejected("$vmess, tls: true, fingerprint: chrome", CERT_FINGERPRINT_INVALID, "fingerprint")
        assertRejected("$vless, reality-opts: {public-key: $pbk}, fingerprint: chrome", CERT_FINGERPRINT_INVALID, "fingerprint")
        for (other in listOf(
            "name: a, type: anytls, server: a.example.com, port: 443, password: pw-placeholder",
            "name: h, type: hysteria, server: h.example.com, port: 443, auth-str: pw-placeholder",
            "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-placeholder",
            "name: t, type: tuic, server: t.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000d",
        )) assertRejected("$other, fingerprint: chrome", CERT_FINGERPRINT_INVALID, "fingerprint")
        // 原因里没有值
        val failed = node("$trojan, fingerprint: chrome-secret-zq30") as ClashNodeResult.Failed
        assertFalse(failed.description.contains("zq30"))
    }

    @Test
    fun `fingerprint 的边界：合法摘要、空值、TLS 关着时照常导入`() {
        assertImported("$trojan, fingerprint: \"$fp\"")
        assertImported("$trojan, fingerprint: \"\"")
        assertImported("$vmess, tls: false, fingerprint: chrome")
    }

    @Test
    fun `fingerprint 按 mihomo 的读法找键：写法不同的摘要解析器读不到，同样拒绝`() {
        assertRejected("$vmess, tls: true, Fingerprint: \"$fp\"", SECURITY_SETTING_NOT_READ, "Fingerprint")
        assertRejected("$vmess, tls: true, FINGERPRINT: chrome", CERT_FINGERPRINT_INVALID, "FINGERPRINT")
        assertRejected(
            "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-placeholder, Fingerprint: \"$fp\"",
            SECURITY_SETTING_NOT_READ, "Fingerprint",
        )
        // 原样的键在时 mihomo 用它
        assertImported("$vmess, tls: true, fingerprint: \"$fp\", Fingerprint: chrome")
    }

    @Test
    fun `http 的 fingerprint：不合法拒绝，合法的读进来记有损，TLS 关着时无影响`() {
        val http = "name: h, type: http, server: h.example.com, port: 443"
        assertRejected("$http, tls: true, fingerprint: chrome", CERT_FINGERPRINT_INVALID, "fingerprint")
        val result = parsed("$http, tls: true, fingerprint: \"$fp\"")
        // 构建时由选核的 PROTOCOL_CERTIFICATE_PIN 明确拒绝，不再悄悄丢
        assertEquals(fp, (result.beans.single() as HttpBean).certificateFingerprint)
        val pinned = result.nodes.single() as ClashNodeResult.Imported
        assertEquals(ClashFieldReason.NOT_SUPPORTED, pinned.reason("fingerprint"))
        assertTrue(pinned.lossy)
        val plain = parsed("$http, tls: false, fingerprint: \"$fp\"")
        assertEquals("", (plain.beans.single() as HttpBean).certificateFingerprint)
        assertEquals(ClashFieldReason.INACTIVE, (plain.nodes.single() as ClashNodeResult.Imported).reason("fingerprint"))
    }

    @Test
    fun `hysteria 与 tuic 的合法证书摘要照常导入，记有损`() {
        for (other in listOf(
            "name: h, type: hysteria, server: h.example.com, port: 443, auth-str: pw-placeholder",
            "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-placeholder",
            "name: t, type: tuic, server: t.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000000d",
        )) {
            val imported = assertImported("$other, fingerprint: \"$fp\"")
            assertEquals(ClashFieldReason.NOT_SUPPORTED, imported.reason("fingerprint"))
            assertTrue(imported.lossy)
        }
        // anytls 能做证书固定
        val anytls = assertImported("name: a, type: anytls, server: a.example.com, port: 443, password: pw-placeholder, fingerprint: \"$fp\"")
        assertEquals(null, anytls.reason("fingerprint"))
    }

    @Test
    fun `TLS 被丢：mihomo 读法下打开 TLS 而导入成明文的拒绝`() {
        assertRejected("$vless, TLS: true", SECURITY_SETTING_NOT_READ, "TLS")
        assertRejected("$vless, tls: 1", SECURITY_SETTING_NOT_READ, "tls")
        assertRejected("name: h, type: http, server: h.example.com, port: 443, tls: 1", SECURITY_SETTING_NOT_READ, "tls")
        // 原样的 tls: false 在时 mihomo 用它
        assertImported("$vless, tls: false, TLS: true")
        assertImported("$vless, tls: 0")
    }

    @Test
    fun `socks5 打开 TLS 时拒绝`() {
        val socks = "name: s, type: socks5, server: s.example.com, port: 1080"
        assertRejected("$socks, tls: true", TLS_NOT_SUPPORTED, "tls")
        assertRejected("$socks, TLS: true", TLS_NOT_SUPPORTED, "TLS")
        assertRejected("$socks, tls: 1", TLS_NOT_SUPPORTED, "tls")
        assertImported("$socks, tls: false, fingerprint: \"$fp\"")
    }

    @Test
    fun `ss 的 v2ray-plugin 打开 TLS 且写了证书固定时拒绝`() {
        val ss = "name: s, type: ss, server: s.example.com, port: 8388, cipher: aes-128-gcm, password: pw-placeholder, plugin: v2ray-plugin"
        assertRejected(
            "$ss, plugin-opts: {mode: websocket, tls: true, fingerprint: \"$fp\"}",
            CERT_PIN_NOT_SUPPORTED, "plugin-opts.fingerprint",
        )
        assertRejected(
            "$ss, plugin-opts: {mode: websocket, TLS: true, Fingerprint: \"$fp\"}",
            CERT_PIN_NOT_SUPPORTED, "plugin-opts.Fingerprint",
        )
        assertImported("$ss, plugin-opts: {mode: websocket, tls: false, fingerprint: \"$fp\"}")
        assertImported("$ss, plugin-opts: {mode: websocket, tls: true, fingerprint: \"\"}")
    }

    @Test
    fun `reality-opts 不是 map 或公钥没被解析器读到时拒绝`() {
        // vless 没写 tls：没有可用公钥的 reality-opts 让解析器打开了 TLS，mihomo 却是明文
        assertRejected("$vless, reality-opts: {short-id: 0123}", REALITY_PUBLIC_KEY_MISSING, "reality-opts")
        assertRejected("$vless, reality-opts: {public-key: \"\"}", REALITY_PUBLIC_KEY_MISSING, "reality-opts")
        // mihomo 把 reality_opts、Public-Key 当作 reality-opts、public-key，本应用的解析器不认：REALITY 丢了
        assertRejected("$vless, tls: true, reality_opts: {public-key: $pbk}", REALITY_PUBLIC_KEY_MISSING, "reality_opts.public-key")
        assertRejected("$vless, tls: true, reality-opts: {Public-Key: $pbk}", REALITY_PUBLIC_KEY_MISSING, "reality-opts.Public-Key")
        assertRejected("$vless, tls: true, reality-opts: not-a-map", REALITY_PUBLIC_KEY_MISSING, "reality-opts")
    }

    @Test
    fun `reality-opts 的边界：mihomo 不启用 REALITY 的照常导入成普通 TLS`() {
        assertImported("$vless, reality-opts: {}")
        assertImported("$vless, reality-opts: {public-key: $pbk, short-id: 0123}")
        for (opts in listOf("{public-key: \"\"}", "{short-id: 0123}", "{public-key: ~}")) {
            val result = parsed("$vless, tls: true, reality-opts: $opts")
            val bean = result.beans.single() as VMessBean
            assertEquals("tls", bean.security)
            assertEquals("", bean.realityPubKey)
            assertEquals(ClashFieldReason.INACTIVE, (result.nodes.single() as ClashNodeResult.Imported).reason("reality-opts"))
        }
        // trojan 恒为 TLS
        assertImported("$trojan, reality-opts: {short-id: 0123}")
    }

    @Test
    fun `带公钥的 REALITY 被后写的 tls false 关掉时拒绝`() {
        assertRejected("$vless, reality-opts: {public-key: $pbk}, tls: false", REALITY_WITHOUT_TLS, "tls")
        assertRejected("$vmess, reality-opts: {public-key: $pbk}, tls: false", REALITY_WITHOUT_TLS, "tls")
        // tls: false 写在前面时 REALITY 照常打开 TLS
        val before = parsed("$vless, tls: false, reality-opts: {public-key: $pbk}")
        assertEquals("tls", (before.beans.single() as VMessBean).security)
        // trojan 没有 tls 键，恒为 TLS
        assertImported("$trojan, reality-opts: {public-key: $pbk}, tls: false")
    }

    @Test
    fun `VLESS encryption 不是空串或 none 时拒绝`() {
        assertRejected("$vless, encryption: mlkem768x25519plus.native.0rtt.placeholder", VLESS_ENCRYPTION, "encryption")
        val none = assertImported("$vless, encryption: none")
        assertEquals(ClashFieldReason.MATCHES_RESULT, none.fields.single { it.path == "encryption" }.reason)
        assertImported("$vless, encryption: \"\"")
        // vmess 没有这个键
        assertImported("$vmess, encryption: mlkem768x25519plus.native.0rtt.placeholder")
    }

    @Test
    fun `生效的 TLS 层变体拒绝`() {
        assertRejected("$vmess, tls: true, shadow-tls-opts: {password: pw-placeholder, version: 3}", TLS_VARIANT, "shadow-tls-opts")
        assertRejected("$vless, tls: true, restls-opts: {version-hint: tls13}", TLS_VARIANT, "restls-opts")
        assertRejected("$trojan, jls-opts: {username: user-placeholder}", TLS_VARIANT, "jls-opts")
        assertRejected("$vmess, tls: true, tlsmirror-opts: {primary-key: key-placeholder}", TLS_VARIANT, "tlsmirror-opts")
    }

    @Test
    fun `TLS 层变体的边界：空对象与不生效的照常导入`() {
        val empty = assertImported(
            "$vmess, tls: true, shadow-tls-opts: {version: 0}, restls-opts: {password: \"\"}, jls-opts: {}"
        )
        assertTrue(empty.fields.none { it.lossy })
        // vless 没有 tlsmirror-opts
        assertImported("$vless, tls: true, tlsmirror-opts: {primary-key: key-placeholder}")
    }

    @Test
    fun `TLS 层变体是否生效按 mihomo 的读法判断`() {
        // 子键不区分大小写；字符串非空即生效；version 不是 0 即生效
        assertRejected("$vmess, tls: true, shadow-tls-opts: {Password: pw-placeholder}", TLS_VARIANT, "shadow-tls-opts")
        assertRejected("$vmess, tls: true, shadow-tls-opts: {version: 2}", TLS_VARIANT, "shadow-tls-opts")
        assertRejected("$vless, tls: true, restls-opts: {password: false}", TLS_VARIANT, "restls-opts")
        assertRejected("$vless, tls: true, Shadow-TLS-Opts: {password: pw-placeholder}", TLS_VARIANT, "Shadow-TLS-Opts")
        // anytls 同样支持这几种
        assertRejected(
            "name: a, type: anytls, server: a.example.com, port: 443, password: pw-placeholder, shadow-tls-opts: {password: pw-placeholder}",
            TLS_VARIANT, "shadow-tls-opts",
        )
    }

    @Test
    fun `trojan 的 ss-opts 打开时拒绝，enabled false 照常导入`() {
        assertRejected("$trojan, ss-opts: {enabled: true, password: pw-placeholder}", EXTRA_ENCRYPTION, "ss-opts")
        assertRejected("$trojan, ss-opts: {Enabled: true, password: pw-placeholder}", EXTRA_ENCRYPTION, "ss-opts")
        // mihomo 把整数 1 当作 true
        assertRejected("$trojan, ss-opts: {enabled: 1, password: pw-placeholder}", EXTRA_ENCRYPTION, "ss-opts")
        val disabled = assertImported("$trojan, ss-opts: {enabled: false, password: pw-placeholder}")
        assertEquals(ClashFieldReason.INACTIVE, disabled.reason("ss-opts"))
    }

    @Test
    fun `hysteria2 的 obfs 不是 salamander 时拒绝，类型名可以显示`() {
        val hy2 = "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-placeholder"
        assertRejected("$hy2, obfs: gecko, obfs-password: op-placeholder", OBFS_NOT_SUPPORTED, "obfs")
        assertEquals("gecko", (node("$hy2, obfs: gecko, obfs-password: op-placeholder") as ClashNodeResult.Failed).shownValue)
        val bad = node("name: h, type: hy2, server: h.example.com, port: 443, password: pw-placeholder, obfs: \"bad zq70\"")
        assertEquals(CLASH_VALUE_NOT_SHOWN, (bad as ClashNodeResult.Failed).shownValue)
        val salamander = assertImported("$hy2, obfs: salamander, obfs-password: op-placeholder")
        assertEquals("salamander", salamander.fields.single { it.path == "obfs" }.shownValue)
        // hysteria v1 的 obfs 是口令：照常导入，值不显示
        val v1 = assertImported("name: h, type: hysteria, server: h.example.com, port: 443, auth-str: pw-placeholder, obfs: secret-zq71")
        val obfs = v1.fields.single { it.path == "obfs" }
        assertEquals(ClashFieldResult.KEPT, obfs.result)
        assertNull(obfs.shownValue)
    }

    @Test
    fun `hysteria2 的 obfs 只显示已知的混淆类型名，误写进来的口令不显示`() {
        val hy2 = "name: h, type: hysteria2, server: h.example.com, port: 443, password: pw-placeholder"
        val gecko = node("$hy2, obfs: gecko, obfs-password: op-placeholder") as ClashNodeResult.Failed
        assertTrue(gecko.description, gecko.description.contains("gecko"))
        // 与 hysteria v1 混淆，把口令写进了 obfs：照样拒绝，值不进描述、日志与界面文本
        for (type in listOf("hysteria2", "hy2")) {
            val nodes = parsed("name: h, type: $type, server: h.example.com, port: 443, password: pw-placeholder, obfs: zq-secret-01").nodes
            val failed = nodes.single() as ClashNodeResult.Failed
            assertEquals(OBFS_NOT_SUPPORTED, failed.failure)
            assertEquals(CLASH_VALUE_NOT_SHOWN, failed.shownValue)
            val summary = ClashImportSummary.of(nodes)
            for (text in listOf(failed.description, summary.logText(), summary.uiText { _, arg -> arg.toString() })) {
                assertFalse(text, text.contains("zq-secret-01"))
                assertTrue(text, text.contains(CLASH_VALUE_NOT_SHOWN))
            }
        }
    }
}
