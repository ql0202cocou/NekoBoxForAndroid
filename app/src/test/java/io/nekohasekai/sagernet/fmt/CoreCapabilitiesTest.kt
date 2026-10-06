package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProxyEntity
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
        // BUG-001 的 sing-box 取值名单（SingBoxValueSets.kt）按同一个 vendored sing-box 核实
        assertEquals(goVersion.substringBefore("-neko-"), SING_BOX_VALUE_SETS_VERSION)
    }

    // 取值名单抄自这些上游模块：libcore/sing-box/go.mod 里的版本一变就要回去逐项复核
    @Test
    fun `sing-box 取值名单依赖的模块版本与 go mod 一致`() {
        val goMod = repoFile("libcore/sing-box/go.mod").readLines()
        for ((module, version) in SING_BOX_VALUE_SET_MODULES) {
            val pinned = goMod.mapNotNull { Regex("""^\s*${Regex.escape(module)} (\S+)""").find(it)?.groupValues?.get(1) }
            assertEquals("go.mod 里 $module 应恰好一行", listOf(version), pinned)
        }
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
            val conflicts = when (cell) {
                is CapabilityCell.Unsupported -> listOf(cell.conflict)
                is CapabilityCell.SupportedValues -> listOfNotNull(cell.outside, cell.rejected?.conflict)
                else -> emptyList()
            }
            for (conflict in conflicts) {
                assertTrue("$requirement × $core → $conflict", conflict.core == null || conflict.core == core)
            }
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
            is CapabilityCell.SupportedValues -> {
                used += cell.outside
                cell.rejected?.let { used += it.conflict }
            }

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

    // 编辑器下拉框里能选到的 uTLS 指纹（空表示不用）：sing-box 与 Xray 都认；mihomo 除 randomized 外都认，randomized
    // 在 mihomo 上握手时常失败，是冲突（K1b 待决定项 D）
    @Test
    fun `编辑器下拉框的 uTLS 指纹 sing-box 与 Xray 都认，mihomo 只拒绝 randomized`() {
        val entries = stringArray("utls_fingerprint_entry")
        assertEquals(
            listOf("", "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"),
            entries,
        )
        for (name in entries.filter { it.isNotEmpty() }) {
            assertTrue(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
            assertEquals(name, name != "randomized", MIHOMO_UTLS_FINGERPRINTS.accepts(name))
            assertEquals(name, name == "randomized", MIHOMO_UTLS_FINGERPRINTS_BROKEN.accepts(name))
        }
    }

    // 编辑器下拉框里的取值都在 sing-box 的名单里（BUG-001 的预检不会拒绝编辑器能选到的值）
    @Test
    fun `编辑器下拉框的 sing-box 枚举取值都在名单里`() {
        assertEquals(SING_BOX_SHADOWSOCKS_METHODS, stringArray("ss_enc_method_value").toSet())
        assertEquals(listOf("", "obfs-local", "v2ray-plugin"), stringArray("box_shadowsocks_plugins"))
        assertEquals(SING_BOX_SHADOWSOCKS_PLUGINS, stringArray("box_shadowsocks_plugins").filter { it.isNotEmpty() }.toSet())
        for (security in stringArray("vmess_encryption_value")) assertTrue(security, security in SING_BOX_VMESS_SECURITIES)
        for (cc in stringArray("tuic_congestion_controller_value")) assertTrue(cc, cc in SING_BOX_TUIC_CONGESTION_CONTROLS)
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
        for (name in listOf("chrome_pq", "chrome_psk_shuffle")) {
            assertTrue(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
        }
        // sing-box 认；mihomo 认识但握手必败，记在单独的名单里（K1b M1 L2-MF-*）；Xray 不认
        for (name in listOf("chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle")) {
            assertTrue(name, SING_BOX_UTLS_FINGERPRINTS.accepts(name))
            assertFalse(name, MIHOMO_UTLS_FINGERPRINTS.accepts(name))
            assertTrue(name, MIHOMO_UTLS_FINGERPRINTS_BROKEN.accepts(name))
            assertFalse(name, XRAY_UTLS_FINGERPRINTS.accepts(name))
        }
        assertFalse(SING_BOX_VLESS_FLOWS.accepts("xtls-rprx-vision-udp443"))
        assertTrue(XRAY_VLESS_FLOWS.accepts("xtls-rprx-vision-udp443"))
        assertTrue(XRAY_WS_EARLY_DATA_HEADERS.accepts("sec-websocket-protocol"))
        assertFalse(XRAY_WS_EARLY_DATA_HEADERS.accepts("X-Early-Data"))
    }

    // K1b 待决定项 D：mihomo 认识、但握手必败的 4 个名字是单独的冲突，先于「名单外」命中；sing-box 照常接受
    @Test
    fun `mihomo 握手必败的指纹是单独的冲突，sing-box 仍接受`() {
        val broken = listOf("chrome_psk", "chrome_pq_psk", "chrome_padding_psk_shuffle", "randomized")
        assertEquals(broken.toSet(), MIHOMO_UTLS_FINGERPRINTS_BROKEN.names)
        assertFalse(MIHOMO_UTLS_FINGERPRINTS_BROKEN.ignoreCase)
        // 与能完成握手的名单不相交，合起来正是 mihomo 认识的 19 个名字（K1b M1 §3.3）
        assertEquals(emptySet<String>(), MIHOMO_UTLS_FINGERPRINTS.names intersect MIHOMO_UTLS_FINGERPRINTS_BROKEN.names)
        assertEquals(19, (MIHOMO_UTLS_FINGERPRINTS.names + MIHOMO_UTLS_FINGERPRINTS_BROKEN.names).size)
        val cell = CAPABILITY_TABLE.getValue(Requirement.UTLS_FINGERPRINT).getValue(DialCore.MIHOMO)
            as CapabilityCell.SupportedValues
        assertEquals(CoreConflict.MIHOMO_UTLS_HANDSHAKE_FAILS, cell.rejected?.conflict)
        assertTrue(cell.rejected?.names === MIHOMO_UTLS_FINGERPRINTS_BROKEN)
        for (name in broken) {
            val requirements = coreRequirements(
                ProxyEntity.TYPE_ANYTLS,
                CoreTestNodes.anytls { utlsFingerprint = name }, false,
            )
            assertEquals(
                name, listOf(Conflict(DialCore.MIHOMO, CoreConflict.MIHOMO_UTLS_HANDSHAKE_FAILS, name)),
                coreConflicts(DialCore.MIHOMO, requirements),
            )
            assertEquals(name, emptyList<Conflict>(), coreConflicts(DialCore.SING_BOX, requirements))
        }
        // 大小写不同的写法 mihomo 不认识，仍是原来的「名单外」冲突
        val upper = coreRequirements(
            ProxyEntity.TYPE_ANYTLS, CoreTestNodes.anytls { utlsFingerprint = "Randomized" }, false,
        )
        assertEquals(
            listOf(Conflict(DialCore.MIHOMO, CoreConflict.MIHOMO_UTLS_FINGERPRINT, "Randomized")),
            coreConflicts(DialCore.MIHOMO, upper),
        )
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
                KnownDifference.REALITY_SERVER_MLKEM to DifferenceDecision.DECIDED,
                KnownDifference.REALITY_FINGERPRINT_MLKEM to DifferenceDecision.DECIDED,
                KnownDifference.UTLS_DEFAULT to DifferenceDecision.DECIDED,
                KnownDifference.PACKET_ENCODING_NONE to DifferenceDecision.DECIDED,
                KnownDifference.VMESS_SECURITY_AUTO to DifferenceDecision.DECIDED,
                KnownDifference.VMESS_SECURITY_NONE_ZERO to DifferenceDecision.DECIDED,
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
                KnownDifference.SING_BOX_UTLS_RANDOMIZED_SEED to DifferenceDecision.OPEN,
                KnownDifference.CHAIN_MUX to DifferenceDecision.BY_PRINCIPLE,
                KnownDifference.XRAY_MUX_UDP443 to DifferenceDecision.DECIDED,
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
                (if (cell.names.ignoreCase) "，不区分大小写" else "，区分大小写") +
                (cell.rejected?.let { "；${it.names.names.sorted().joinToString("、")} 报 `${it.conflict}`" } ?: "") +
                "；名单外 `${cell.outside}`（${cell.since}）"

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
