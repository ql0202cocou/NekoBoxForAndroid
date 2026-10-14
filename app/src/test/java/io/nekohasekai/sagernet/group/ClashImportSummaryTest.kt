package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SilencedLogs
import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldRecord
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.ClashImportException
import io.nekohasekai.sagernet.fmt.ClashImportSummary
import io.nekohasekai.sagernet.fmt.ClashNodeFailure
import io.nekohasekai.sagernet.fmt.ClashNodeResult
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ClashImportSummaryTest {

    // parseRaw 会写日志：Logs 开着就走 JNI（Libcore），JVM 上没有
    @get:Rule
    val logs = SilencedLogs()

    // 代替 Context.getString：资源名加参数，便于断言结构
    private fun getString(id: Int, arg: Any): String = when (id) {
        R.string.group_diff_more -> "[more $arg]"
        R.string.group_added -> "[added]\n$arg"
        R.string.group_changed -> "[changed]\n$arg"
        R.string.group_deleted -> "[deleted]\n$arg"
        R.string.group_duplicate -> "[duplicate]\n$arg"
        else -> error("unexpected string resource $id")
    }

    private fun summaryOf(yaml: String) = ClashImportSummary.of(parseClash(loadClashYaml(yaml)).nodes)

    private val lossyField = ClashFieldRecord("smux.max-connections", ClashFieldResult.IGNORED, ClashFieldReason.NOT_READ)
    private val noEffectField = ClashFieldRecord("udp", ClashFieldResult.IGNORED, ClashFieldReason.NO_UDP_SWITCH)
    private val keptField = ClashFieldRecord("server", ClashFieldResult.KEPT)

    @Test
    fun `四类计数与分组`() {
        val summary = ClashImportSummary.of(
            listOf(
                ClashNodeResult.Imported(0, "vmess", "a", listOf(keptField, noEffectField)),
                ClashNodeResult.Imported(1, "vmess", "b", listOf(keptField, lossyField, lossyField)),
                ClashNodeResult.Imported(2, "vless", "c", listOf(lossyField)),
                ClashNodeResult.UnknownType(3, "ssr", "d"),
                ClashNodeResult.UnknownType(4, "ssr", "e"),
                ClashNodeResult.UnknownType(5, "snell", null),
                ClashNodeResult.Failed(6, "vmess", "g", ClashNodeFailure.UNSUPPORTED_TRANSPORT, "network", "kcp"),
                ClashNodeResult.Failed(7, "(key not shown)", null, ClashNodeFailure.ENTRY_NOT_MAP),
            )
        )
        assertEquals(8, summary.total)
        assertEquals(1, summary.complete)
        assertEquals(2, summary.lossy)
        assertEquals(3, summary.unknownType)
        assertEquals(2, summary.failed)
        assertEquals(mapOf("ssr" to 2, "snell" to 1), summary.unknownTypes)
        assertEquals(
            mapOf(
                "vmess: unsupported transport at network = kcp" to 1,
                "(key not shown): entry is not a map" to 1,
            ),
            summary.failures,
        )
        // 同一节点的同一字段只计一次
        assertEquals(
            mapOf(
                "vmess.smux.max-connections: not imported" to 1,
                "vless.smux.max-connections: not imported" to 1,
            ),
            summary.lossyFields,
        )
        assertEquals(mapOf("vmess.udp: UDP use follows the protocol here" to 1), summary.noEffectFields)
        assertFalse(summary.isEmpty)

        val log = summary.logText()
        assertTrue(log.startsWith("Clash import: 8 entries, 1 complete, 2 lossy, 3 unsupported type, 2 failed"))
        assertTrue(log.contains("unsupported types: ssr (2); snell (1)"))
        assertTrue(log.contains("lossy fields: vless.smux.max-connections: not imported (1); vmess.smux.max-connections: not imported (1)"))
        assertTrue(log.contains("no-effect fields: vmess.udp: UDP use follows the protocol here (1)"))
    }

    @Test
    fun `没有可报告内容时日志为空`() {
        val complete = ClashImportSummary.of(listOf(ClashNodeResult.Imported(0, "ss", "a", listOf(keptField))))
        assertTrue(complete.isEmpty)
        assertEquals("", complete.logText())
        // 只有无影响的忽略也不提示
        val noEffectOnly = ClashImportSummary.of(listOf(ClashNodeResult.Imported(0, "ss", "a", listOf(noEffectField))))
        assertTrue(noEffectOnly.isEmpty)
        assertEquals("", noEffectOnly.logText())
        assertEquals(1, noEffectOnly.noEffectFields.size)
    }

    @Test
    fun `日志每组最多 20 项，其余折叠`() {
        val nodes = (0 until 25).map { ClashNodeResult.UnknownType(it, "type$it", "n$it") }
        val log = ClashImportSummary.of(nodes).logText()
        // 计数相同的按名字排序：前 20 个是 type0…type4 与 type10…type24
        for (i in (0..4) + (10..24)) assertTrue("type$i", log.contains("type$i (1)"))
        for (i in 5..9) assertFalse("type$i", log.contains("type$i (1)"))
        assertTrue(log.endsWith("; and 5 more"))
    }

    @Test
    fun `不是字符串的键不进日志`() {
        val entry = LinkedHashMap<Any?, Any?>(
            loadClashYaml("{name: node-zq30, type: vmess, server: v.example.com, port: 443, uuid: 00000000-0000-0000-0000-000000000001}")
        )
        entry[listOf("zq31", "zq32")] = "x"
        val summary = ClashImportSummary.of(parseClash(mapOf("proxies" to listOf(entry))).nodes)
        assertEquals(1, summary.failed)
        val log = summary.logText()
        assertFalse(log, log.contains("zq31"))
        assertTrue(log, log.contains("invalid value at (key not shown)"))
    }

    @Test
    fun `日志不含凭据、地址与节点名`() {
        val summary = summaryOf(
            """
            proxies:
              - {name: node-zq01, type: vless, server: host-zq02.example.com, port: 443, uuid: 00000000-0000-0000-0000-00000000zq03, tls: true, servername: sni-zq04.example.com, network: ws, ws-opts: {path: /path-zq05, headers: {Host: cdn-zq06.example.com, X-Token: token-zq07}}, reality-opts: {public-key: jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0, short-id: 0a0b, support-x25519mlkem768: true}, dialer-proxy: chain-zq08, packet-encoding: "packet zq09"}
              - {name: node-zq10, type: ss, server: 192.0.2.77, port: 8388, cipher: aes-128-gcm, password: pw-zq11, plugin: shadow-tls, plugin-opts: {password: pw-zq12}}
              - {name: node-zq13, type: snell, server: 198.51.100.77, port: 443, psk: psk-zq14}
              - {name: node-zq15, type: tuic, server: 203.0.113.77, port: 443, token: token-zq16}
            """.trimIndent()
        )
        assertEquals(1, summary.lossy)
        assertEquals(1, summary.unknownType)
        assertEquals(2, summary.failed)
        val log = summary.logText()
        val secrets = listOf(
            "zq02", "zq03", "zq04", "zq05", "zq06", "zq07", "jNXHt1yR", "0a0b", "zq08", "zq09", "192.0.2.77",
            "pw-zq11", "pw-zq12", "198.51.100.77", "zq14", "203.0.113.77", "zq16",
        )
        for (secret in secrets) {
            assertFalse("log leaks $secret: $log", log.contains(secret))
        }
        for (name in listOf("node-zq01", "node-zq10", "node-zq13", "node-zq15")) {
            assertFalse("log leaks $name", log.contains(name))
        }
        // 字段路径与枚举键的合法值照常显示
        assertTrue(log.contains("vless.ws-opts.headers.X-Token: not imported"))
        assertTrue(log.contains("vless.dialer-proxy: not imported"))
        assertTrue(log.contains("ss: unsupported shadowsocks plugin at plugin = shadow-tls"))
    }

    @Test
    fun `订阅更新的 Diff 对话框只列四类节点名单`() {
        assertEquals(
            "[added]\na\n\n[deleted]\nb\n\n[duplicate]\nc (1)",
            groupUpdateDialogText(listOf("a"), emptyMap(), listOf("b"), listOf("c (1)"), ::getString),
        )
        // 改名节点显示 旧名 => 新名，没改名的只显示名字
        assertEquals(
            "[changed]\nold => new\nsame",
            groupUpdateDialogText(emptyList(), linkedMapOf("old" to "new", "same" to "same"), emptyList(), emptyList(), ::getString),
        )
        assertEquals("", groupUpdateDialogText(emptyList(), emptyMap(), emptyList(), emptyList(), ::getString))
        // 超出 GROUP_DIFF_LINES 的折成一行剩余条数
        val added = (0 until GROUP_DIFF_LINES + 3).map { "a$it" }
        val lines = groupUpdateDialogText(added, emptyMap(), emptyList(), emptyList(), ::getString).lines()
        assertEquals("a${GROUP_DIFF_LINES - 1}", lines[GROUP_DIFF_LINES])
        assertEquals("[more 3]", lines[GROUP_DIFF_LINES + 1])
    }

    @Test
    fun `全部节点被跳过时 parseRaw 抛出带汇总的异常，消息不含值`() {
        val e = assertThrows(ClashImportException::class.java) {
            runBlocking {
                RawUpdater.parseRaw(
                    "proxies:\n" +
                            "  - {name: node-zq20, type: snell, server: snell-zq21.example.com, port: 443, psk: psk-zq22}\n" +
                            "  - {name: node-zq23, type: vmess, server: vmess-zq24.example.com, port: 443, uuid: uuid-zq25, network: kcp}\n" +
                            "  - {name: node-zq26, type: socks5, server: 192.0.2.88, port: port-zq27}",
                )
            }
        }
        assertEquals(
            "No proxies imported from the Clash subscription: 3 entries, 1 unsupported type, 2 failed to parse",
            e.message,
        )
        assertEquals(1, e.summary.unknownType)
        assertEquals(2, e.summary.failed)
        for (secret in listOf("zq21", "zq22", "zq24", "zq25", "192.0.2.88", "zq27")) {
            assertFalse(e.summary.logText().contains(secret))
        }
    }

    @Test
    fun `proxies 为空列表时同样抛出，汇总为空`() {
        val e = assertThrows(ClashImportException::class.java) {
            runBlocking { RawUpdater.parseRaw("proxies: []") }
        }
        assertEquals(0, e.summary.total)
        assertTrue(e.summary.isEmpty)
    }
}
