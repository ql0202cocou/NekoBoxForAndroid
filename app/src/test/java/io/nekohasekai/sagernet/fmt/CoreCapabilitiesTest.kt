package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 能力表本身：结构完整、核心版本与仓库里的固定版本一致、名单与编辑器对得上、已知差异清单钉住
class CoreCapabilitiesTest {

    // Gradle 跑单测时工作目录是 app/，IDE 里可能是仓库根目录；找不到就失败，不跳过
    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.isFile } ?: error("找不到 $path（工作目录 ${File(".").absolutePath}）")

    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile } ?: error("找不到 app/$path")

    // ---- 核心版本：升级核心不改表，这里就失败

    private fun pinnedInPluginsSh(name: String): String {
        val pattern = Regex("^$name=\"?([^\"]*)\"?$")
        val matches = repoFile("buildScript/lib/plugins.sh").readLines().mapNotNull { pattern.find(it)?.groupValues?.get(1) }
        assertEquals("plugins.sh 里 $name 应恰好一行", 1, matches.size)
        return matches.single()
    }

    @Test
    fun `声明的核心版本等于仓库固定的版本`() {
        assertEquals(pinnedInPluginsSh("XRAY_VERSION"), DialCore.XRAY.version)
        assertEquals(pinnedInPluginsSh("MIHOMO_VERSION"), DialCore.MIHOMO.version)
        // vendored sing-box 的版本声明是 1.14.2-neko-1，能力表只记上游版本
        val goVersion = Regex("""var Version = "([^"]+)"""")
            .find(repoFile("libcore/sing-box/constant/version.go").readText())!!.groupValues[1]
        assertTrue(goVersion, Regex("""\d+\.\d+\.\d+-neko-\d+""").matches(goVersion))
        assertEquals(goVersion.substringBefore("-neko-"), DialCore.SING_BOX.version)
    }

    @Test
    fun `README 内置核心表的版本与能力表一致`() {
        val lines = repoFile("README.md").readLines()
        fun readmeVersion(link: String): String {
            val line = lines.single { it.startsWith("| [$link](") }
            return Regex("""v\d+(\.\d+)+""").find(line.split('|')[2])!!.value
        }
        assertEquals("v" + DialCore.SING_BOX.version, readmeVersion("sing-box"))
        assertEquals(DialCore.XRAY.version, readmeVersion("Xray-core"))
        assertEquals(DialCore.MIHOMO.version, readmeVersion("mihomo"))
    }

    @Test
    fun `每条声明的起始版本不晚于声明的核心版本`() {
        for ((requirement, cells) in CAPABILITY_TABLE) for ((core, cell) in cells) {
            val since = when (cell) {
                is CapabilityCell.Supported -> cell.since
                is CapabilityCell.SupportedValues -> cell.since
                is CapabilityCell.Unsupported -> cell.since
                CapabilityCell.NotApplicable -> continue
            }
            assertTrue("$requirement × $core: $since", CoreVersion.compare(since.version, core.version) <= 0)
        }
        for (rule in COMBINATION_RULES) {
            assertTrue(rule.description, CoreVersion.compare(rule.since.version, rule.core.version) <= 0)
        }
    }

    @Test
    fun `版本号比较`() {
        assertEquals(listOf(26, 3, 27), CoreVersion.parse("v26.3.27"))
        assertEquals(listOf(1, 14, 2), CoreVersion.parse("1.14.2-neko-1"))
        assertTrue(CoreVersion.compare("v1.19.30", "v1.19.31") < 0)
        assertTrue(CoreVersion.compare("1.14.2", "v1.14.2") == 0)
        assertTrue(CoreVersion.compare("v26.10.1", "v26.3.27") > 0)
    }

    // ---- 表的结构

    @Test
    fun `每一行给三个核心各一格`() {
        assertEquals(Requirement.entries.toSet(), CAPABILITY_TABLE.keys)
        for ((requirement, cells) in CAPABILITY_TABLE) {
            assertEquals("$requirement", DialCore.entries.toSet(), cells.keys)
        }
    }

    @Test
    fun `不支持的格子与组合规则只引用属于该核心的冲突`() {
        for ((requirement, cells) in CAPABILITY_TABLE) for ((core, cell) in cells) {
            val conflict = when (cell) {
                is CapabilityCell.Unsupported -> cell.conflict
                is CapabilityCell.SupportedValues -> cell.outside
                else -> null
            } ?: continue
            assertTrue("$requirement × $core → $conflict", conflict.core == null || conflict.core == core)
        }
        for (rule in COMBINATION_RULES) assertEquals(rule.description, rule.core, rule.conflict.core)
    }

    // 协议行已报冲突的核心，其余行写 NotApplicable：只有 mihomo 有（它只承载 AnyTLS），sing-box 与 Xray 每行都有声明
    @Test
    fun `NotApplicable 只出现在承载不了协议的核心上`() {
        val anyTlsRows = setOf(
            Requirement.SECURITY_TLS, Requirement.CERTIFICATE_PIN, Requirement.CUSTOM_CA,
            Requirement.ALLOW_INSECURE, Requirement.UTLS_FINGERPRINT, Requirement.ECH_INLINE, Requirement.ECH_AUTO_QUERY,
        )
        for ((requirement, cells) in CAPABILITY_TABLE) {
            assertFalse("$requirement × sing-box", cells.getValue(DialCore.SING_BOX) == CapabilityCell.NotApplicable)
            assertFalse("$requirement × Xray", cells.getValue(DialCore.XRAY) == CapabilityCell.NotApplicable)
            assertEquals(
                "$requirement × mihomo", !requirement.isProtocol && requirement !in anyTlsRows,
                cells.getValue(DialCore.MIHOMO) == CapabilityCell.NotApplicable,
            )
        }
    }

    @Test
    fun `候选核心正是协议行声明支持的核心`() {
        val rows = mapOf(
            CoreProtocol.VMESS to Requirement.PROTOCOL_VMESS,
            CoreProtocol.VLESS to Requirement.PROTOCOL_VLESS,
            CoreProtocol.TROJAN to Requirement.PROTOCOL_TROJAN,
            CoreProtocol.ANYTLS to Requirement.PROTOCOL_ANYTLS,
        )
        for ((protocol, row) in rows) {
            val supported = CAPABILITY_TABLE.getValue(row).filterValues { it is CapabilityCell.Supported }.keys
            assertEquals("$protocol", supported, candidateCores(protocol).toSet())
        }
        assertEquals(emptyList<DialCore>(), candidateCores(CoreProtocol.OTHER))
    }

    @Test
    fun `每个冲突标识都有出处`() {
        val used = HashSet<CoreConflict>()
        for (cells in CAPABILITY_TABLE.values) for (cell in cells.values) when (cell) {
            is CapabilityCell.Unsupported -> used += cell.conflict
            is CapabilityCell.SupportedValues -> used += cell.outside
            else -> Unit
        }
        COMBINATION_RULES.forEach { used += it.conflict }
        // 选核本身产生的（CoreSelection.decideCore）
        used += listOf(
            CoreConflict.CORE_VALUE_UNKNOWN, CoreConflict.CORE_NOT_SELECTABLE,
            CoreConflict.PROTOCOL_CERTIFICATE_PIN, CoreConflict.PROTOCOL_MLDSA65_VERIFY,
        )
        assertEquals(CoreConflict.entries.toSet(), used)
        for (conflict in CoreConflict.entries) {
            assertTrue("$conflict", conflict.fields.isNotEmpty() && conflict.reason.isNotBlank())
            // 原因是给用户看的英文
            assertTrue("$conflict", conflict.reason.all { it.code < 128 })
        }
    }

    // 三种不支持都用到了，分得清
    @Test
    fun `三种不支持各有实例`() {
        val kinds = CAPABILITY_TABLE.values.flatMap { it.values }.filterIsInstance<CapabilityCell.Unsupported>()
            .map { it.conflict.kind }.toSet()
        assertTrue(kinds.containsAll(listOf(GapKind.CORE, GapKind.GENERATOR, GapKind.SEMANTICS)))
        assertEquals(GapKind.GENERATOR, CoreConflict.XRAY_ECH_AUTO_QUERY.kind)
        assertEquals(GapKind.SEMANTICS, CoreConflict.XRAY_WS_EARLY_DATA_PATH.kind)
        assertEquals(GapKind.CORE, CoreConflict.XRAY_PACKETADDR.kind)
    }

    // ---- 名单

    private fun stringArray(name: String): List<String> {
        val xml = appFile("src/main/res/values/arrays.xml").readText()
        val body = Regex("""<string-array name="$name"[^>]*>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("arrays.xml 里没有 $name")
        return Regex("""<item>(.*?)</item>|<item\s*/>""").findAll(body).map { it.groupValues[1] }.toList()
    }

    // 编辑器下拉框里能选到的 uTLS 指纹（空表示不用），三个核心都认
    @Test
    fun `编辑器下拉框的 uTLS 指纹三个核心都认`() {
        val entries = stringArray("utls_fingerprint_entry")
        assertEquals(
            listOf("", "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"),
            entries,
        )
        for (name in entries.filter { it.isNotEmpty() }) {
            assertTrue(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
        }
    }

    @Test
    fun `编辑器的 VLESS flow 两个核心都认`() {
        val flows = stringArray("xtls_flow_value")
        for (flow in flows.filter { it.isNotEmpty() && it != "auto" }) {
            assertTrue(flow, SING_BOX_VLESS_FLOWS.accepts(flow))
            assertTrue(flow, XRAY_VLESS_FLOWS.accepts(flow))
        }
    }

    @Test
    fun `名单之间的已知差别`() {
        // 只有 Xray 认的
        for (name in listOf("randomizednoalpn", "hellochrome_131", "unsafe", "Chrome", "FIREFOX")) {
            assertTrue(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
        }
        // 只有 mihomo 认的（none 表示 Go 标准 TLS）
        for (name in listOf("chrome120", "firefox120", "safari16", "none")) {
            assertTrue(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
        }
        // sing-box 与 mihomo 认、Xray 不认的
        for (name in listOf("chrome_psk", "chrome_pq", "chrome_padding_psk_shuffle")) {
            assertTrue(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
        }
        assertFalse(SING_BOX_VLESS_FLOWS.accepts("xtls-rprx-vision-udp443"))
        assertTrue(XRAY_VLESS_FLOWS.accepts("xtls-rprx-vision-udp443"))
        assertTrue(XRAY_WS_EARLY_DATA_HEADERS.accepts("sec-websocket-protocol"))
        assertFalse(XRAY_WS_EARLY_DATA_HEADERS.accepts("X-Early-Data"))
    }

    // ---- 字段

    @Test
    fun `冲突字段的 key 对应编辑器的字段`() {
        val keys = listOf("standard_v2ray_preferences.xml", "anytls_preferences.xml").flatMap { name ->
            Regex("""app:key="([^"]+)"""").findAll(appFile("src/main/res/xml/$name").readText()).map { it.groupValues[1] }
        }.toSet()
        for (field in ProfileField.entries) when (field) {
            ProfileField.GLOBAL_ALLOW_INSECURE -> assertEquals(Key.GLOBAL_ALLOW_INSECURE, field.key)
            // 自定义出站 JSON 由菜单进入单独的编辑页，key 是 bean 字段名
            ProfileField.CUSTOM_OUTBOUND_JSON -> assertEquals("customOutboundJson", field.key)
            else -> assertTrue("$field", field.key in keys)
        }
    }

    // ---- 已知差异清单

    @Test
    fun `已知差异清单`() {
        assertEquals(
            mapOf(
                KnownDifference.REALITY_ALPN to DifferenceDecision.DECIDED,
                KnownDifference.REALITY_ALLOW_INSECURE_CERTIFICATES to DifferenceDecision.DECIDED,
                KnownDifference.XRAY_REALITY_IGNORES_PIN_ECH to DifferenceDecision.DECIDED,
                KnownDifference.UTLS_DEFAULT to DifferenceDecision.DECIDED,
                KnownDifference.PACKET_ENCODING_NONE to DifferenceDecision.DECIDED,
                KnownDifference.VMESS_SECURITY_AUTO to DifferenceDecision.OPEN,
                KnownDifference.VMESS_SECURITY_NONE_ZERO to DifferenceDecision.OPEN,
                KnownDifference.V2RAY_ID_MAPPING to DifferenceDecision.OPEN,
                KnownDifference.MUX_COOL_PADDING to DifferenceDecision.DECIDED,
                KnownDifference.VISION_MUX to DifferenceDecision.DECIDED,
                KnownDifference.CERTIFICATES_TRUST to DifferenceDecision.DECIDED,
                KnownDifference.CUSTOM_OUTBOUND_JSON_EXTERNAL to DifferenceDecision.DECIDED,
                KnownDifference.ANYTLS_CERTIFICATES to DifferenceDecision.DECIDED,
                KnownDifference.ECH_AUTO_QUERY_RESOLVER to DifferenceDecision.OPEN,
                KnownDifference.WS_EARLY_DATA_SIZE to DifferenceDecision.BY_PRINCIPLE,
                KnownDifference.VMESS_ALTER_ID to DifferenceDecision.OPEN,
                KnownDifference.HTTP_HEADER_CAMOUFLAGE to DifferenceDecision.OPEN,
                KnownDifference.XUDP_UNDER_VISION to DifferenceDecision.BY_PRINCIPLE,
                KnownDifference.UTLS_IMPLEMENTATION to DifferenceDecision.BY_PRINCIPLE,
                KnownDifference.CHAIN_MUX to DifferenceDecision.BY_PRINCIPLE,
                KnownDifference.XRAY_MUX_UDP443 to DifferenceDecision.OPEN,
            ),
            KnownDifference.entries.associateWith { it.decision },
        )
        for (difference in KnownDifference.entries) {
            assertTrue("$difference", difference.fields.isNotEmpty())
            assertTrue("$difference", difference.behaviors.size >= 2)
            assertTrue("$difference", difference.rationale.isNotBlank())
        }
    }

    // Xray 对 REALITY 节点不算要求的几行，正是已知差异 XRAY_REALITY_IGNORES_PIN_ECH 说的字段
    @Test
    fun `Xray 在 REALITY 下忽略的行与已知差异一致`() {
        assertEquals(
            setOf(Requirement.CERTIFICATE_PIN, Requirement.ECH_INLINE, Requirement.ECH_AUTO_QUERY),
            XRAY_REALITY_IGNORED,
        )
        val fields = XRAY_REALITY_IGNORED.flatMap { it.fields }.toSet()
        assertEquals(KnownDifference.XRAY_REALITY_IGNORES_PIN_ECH.fields.toSet(), fields)
    }

    @Test
    fun `AnyTLS 带 certificates 的偏好例外`() {
        // 维护者 2026-10-06 定：留在 mihomo。以后改动要同时更新选核测试与对照表
        assertTrue(ANYTLS_CERTIFICATES_PREFER_MIHOMO)
    }

    // 把能力表、组合规则、冲突与已知差异渲染成 Markdown（build/reports/core-selection/capability-table.md），
    // 报告与评审直接用代码里的声明，不另抄一份
    @Test
    fun `能力表导出为 Markdown`() {
        fun cell(cell: CapabilityCell): String = when (cell) {
            is CapabilityCell.Supported -> "✔" + (cell.note?.let { "：$it" } ?: "") + "（${cell.since}）"
            is CapabilityCell.SupportedValues -> "✔ 限名单 ${cell.names.names.size} 个" +
                (if (cell.names.ignoreCase) "，不区分大小写" else "，区分大小写") + "；名单外 `${cell.outside}`（${cell.since}）"

            is CapabilityCell.Unsupported -> "✘ ${cell.conflict.kind} `${cell.conflict}`（${cell.since}）"
            CapabilityCell.NotApplicable -> "—"
        }
        val md = StringBuilder()
        md.appendLine("## 能力表")
        md.appendLine()
        md.appendLine("| 要求 | sing-box ${DialCore.SING_BOX.version} | Xray ${DialCore.XRAY.version} | mihomo ${DialCore.MIHOMO.version} |")
        md.appendLine("| --- | --- | --- | --- |")
        for ((requirement, cells) in CAPABILITY_TABLE) {
            md.appendLine(
                "| ${requirement.label} | ${cell(cells.getValue(DialCore.SING_BOX))} | ${cell(cells.getValue(DialCore.XRAY))} | " +
                    "${cell(cells.getValue(DialCore.MIHOMO))} |",
            )
        }
        md.appendLine()
        md.appendLine("Xray 对 REALITY 节点不算要求的行：" + XRAY_REALITY_IGNORED.joinToString("、") { it.label })
        md.appendLine()
        md.appendLine("## 组合规则")
        md.appendLine()
        md.appendLine("| 核心 | 组合 | 冲突 | 起始版本 |")
        md.appendLine("| --- | --- | --- | --- |")
        for (rule in COMBINATION_RULES) {
            md.appendLine("| ${rule.core.displayName} | ${rule.description} | `${rule.conflict}` | ${rule.since} |")
        }
        md.appendLine()
        md.appendLine("## 冲突标识")
        md.appendLine()
        md.appendLine("| 标识 | 核心 | 性质 | 字段 | 原因（英文，给用户） |")
        md.appendLine("| --- | --- | --- | --- | --- |")
        for (conflict in CoreConflict.entries) {
            md.appendLine(
                "| `$conflict` | ${conflict.core?.displayName ?: "—"} | ${conflict.kind} | " +
                    "${conflict.fields.joinToString(", ") { it.key }} | ${conflict.reason} |",
            )
        }
        md.appendLine()
        md.appendLine("## 已知差异")
        md.appendLine()
        md.appendLine("| 标识 | 字段 | 各核心的行为 | 为什么不拦 | 归类 |")
        md.appendLine("| --- | --- | --- | --- | --- |")
        for (difference in KnownDifference.entries) {
            md.appendLine(
                "| `$difference` | ${difference.fields.joinToString(", ") { it.key }} | " +
                    "${difference.behaviors.entries.joinToString("；") { "${it.key.displayName}：${it.value}" }} | " +
                    "${difference.rationale} | ${difference.decision} |",
            )
        }
        val dir = File("build/reports/core-selection").apply { mkdirs() }
        File(dir, "capability-table.md").writeText(md.toString())
        assertTrue(md.lines().size > Requirement.entries.size + CoreConflict.entries.size + KnownDifference.entries.size)
    }

    @Test
    fun `冲突说明`() {
        val conflict = Conflict(DialCore.SING_BOX, CoreConflict.SING_BOX_UTLS_FINGERPRINT, "netscape")
        assertEquals("sing-box: sing-box does not know this uTLS fingerprint (netscape)", conflict.message())
        assertEquals(listOf(ProfileField.UTLS_FINGERPRINT), conflict.fields)
        assertEquals("This app does not support the transport", Conflict(null, CoreConflict.TRANSPORT_UNKNOWN).message())
    }
}
