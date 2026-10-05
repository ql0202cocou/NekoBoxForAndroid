package io.nekohasekai.sagernet.golden

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.golden.GoldenAddressCorpus.Companion.quoted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// JVM 黄金测试：基线（app/src/test/resources/golden/）的每个场景按 input.json 重建输入，三种模式各走一次
// 「采集快照 → 纯构建 → 运行计划 → 组装 / 导出」（GoldenJvmScenario），按采集入口的格式写成一棵产物树，
// 用 GoldenCompareTree 与基线做全量比较。只有 RELAXATIONS 名单上的场景、只在名单列出的位置放宽。
// 诊断与警告基线里没有，按 app/src/test/resources/golden-jvm/expected-diagnostics.json 逐条断言
class GoldenJvmBuildTest {

    companion object {
        @ClassRule
        @JvmField
        val temp = TemporaryFolder()

        // 全部场景 × 三种模式只跑一次，本类的测试共用
        private val tree: GoldenJvmTree by lazy { GoldenJvmTree.build(GoldenBaseline.load(), temp.newFolder()) }

        /**
         * 按名单放宽的比较。只对列出的场景与模式的 result.json，只放宽 error 里随平台变化的位置；这些场景的其余产物
         * （status、error 的类与消息、dynamic）照常比较。名单上的每个场景每种模式都必须真的用到放宽，不再需要时删掉。
         */
        private val RELAXATIONS = listOf(
            GoldenJvmRelaxation(
                scenarioId = "mihomo-anytls-bad-certificate",
                modes = ConfigBuildMode.entries,
                reason = "错误证书的 cause 链随平台的 X.509 实现而变：Android 的 Conscrypt 是 CertificateException 包两层 " +
                    "OpenSSLX509CertificateFactory\$ParsingException 再包 RuntimeException（共 5 个 cause），JDK 只有 " +
                    "CertificateException 包 IOException（共 3 个），CertificateException 的消息也不同。两个平台一致的最大部分是" +
                    "顶层异常的类与消息、第一个 cause（MihomoConfig 抛的 IllegalArgumentException，类与消息）与第二个 cause 的类，" +
                    "这些照常比较；放宽的只有 cause 的个数、第二个 cause 的消息与第三个起的 cause",
                allowed = listOf(
                    // 个数不同只放宽这一种消息；同一位置的其它差异（例如不是数组）照常报
                    Regex("""\$\.error\.causes""") to { message: String -> message.startsWith("数组长度不同") },
                    Regex("""\$\.error\.causes\[1]\.message""") to { _: String -> true },
                    Regex("""\$\.error\.causes\[([2-9]|[1-9][0-9]+)](\..+)?""") to { _: String -> true },
                ),
            ),
        )
    }

    @Test
    fun `全部场景三种模式的产物与基线一致`() {
        val baseline = GoldenBaseline.load()
        // 基线目录里的场景一个都不能少：与采集时 manifest 记的场景数核对
        val manifest = JsonParser.parseString(baseline.root.resolve("manifest.json").readText()).asJsonObject
        assertEquals("基线的场景数与 manifest.json 不一致", manifest["scenarios"].asInt, baseline.scenarioIds.size)
        assertEquals(baseline.scenarioIds.size * ConfigBuildMode.entries.size, tree.outputs.size)

        val report = GoldenCompareTree.compare(baseline.root, tree.root, GoldenCompareScope.ALL)
        val (relaxed, remaining) = report.diffs.partition { diff -> RELAXATIONS.any { it.allows(diff) } }
        val unused = RELAXATIONS.flatMap { r -> r.modes.map { r to it } }
            .filter { (r, mode) -> relaxed.none { it.file == r.file(mode) } }
            .map { (r, mode) -> "${r.file(mode)}：名单上的放宽没有用到，平台差异已不存在时从 RELAXATIONS 删掉" }
        val failure = GoldenTreeReport(report.expectedRoot, report.actualRoot, report.manifest, remaining, report.warnings)
        assertTrue(
            failure.render(maxPerFile = 50) + unused.joinToString("\n"),
            remaining.isEmpty() && report.warnings.isEmpty() && unused.isEmpty(),
        )
    }

    @Test
    fun `交给回退实现的地址输入正好是已知名单`() {
        val known = GoldenAddressCorpus.KNOWN_OUTSIDE.keys
        val extra = tree.outsideCorpus - known
        val gone = known - tree.outsideCorpus
        assertTrue(
            "语料之外的输入与 GoldenAddressCorpus.KNOWN_OUTSIDE 不一致。多出的 ${extra.map { it.quoted() }}：要么补进语料" +
                "（重新采集 address/corpus.json），要么核对 StrictNumericAddress 在它上的结果后加进名单；" +
                "名单上没再查到的 ${gone.map { it.quoted() }}：从名单删掉",
            extra.isEmpty() && gone.isEmpty(),
        )
    }

    @Test
    fun `每个场景每种模式的诊断与警告与预期一致`() {
        val baseline = GoldenBaseline.load()
        val expected = GoldenJvmExpectations.load()
        val unknown = expected.keys.map { it.first }.filter { it !in baseline.scenarioIds }.distinct()
        assertTrue("预期里有基线没有的场景：$unknown", unknown.isEmpty())

        val mismatches = ArrayList<String>()
        for ((key, output) in tree.outputs) {
            val (id, mode) = key
            val want = expected[id to mode] ?: GoldenJvmExpectation.NONE
            if (output.diagnostics != want.diagnostics) {
                mismatches += "$id/${mode.dir} 诊断\n    预期：${want.diagnostics}\n    实际：${output.diagnostics}"
            }
            if (output.warnings != want.warnings) {
                mismatches += "$id/${mode.dir} 警告\n    预期：${want.warnings}\n    实际：${output.warnings}"
            }
        }
        assertTrue("诊断或警告与预期不一致 ${mismatches.size} 处：\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}

/** 一个场景（只限 modes 里的模式）在 result.json 里允许的差异：路径匹配且消息满足条件。 */
class GoldenJvmRelaxation(
    val scenarioId: String,
    val modes: List<ConfigBuildMode>,
    val reason: String,
    private val allowed: List<Pair<Regex, (String) -> Boolean>>,
) {
    fun file(mode: ConfigBuildMode) = "scenarios/$scenarioId/${mode.dir}/result.json"

    fun allows(diff: GoldenDiff): Boolean = modes.any { diff.file == file(it) } &&
        allowed.any { (path, message) -> path.matches(diff.path) && message(diff.message) }
}

/**
 * JVM 上的产物树：目录结构同采集入口（manifest.json 只记来源），outputs 按（场景，模式）；outsideCorpus 是全部场景
 * 交给回退实现（StrictNumericAddress）的地址输入。
 */
class GoldenJvmTree(
    val root: File,
    val outputs: Map<Pair<String, ConfigBuildMode>, GoldenJvmOutput>,
    val outsideCorpus: Set<String>,
) {

    companion object {
        fun build(baseline: GoldenBaseline, dir: File): GoldenJvmTree {
            val root = File(dir, "tree").apply { mkdirs() }
            val tempRoot = File(dir, "cache").apply { mkdirs() }
            val corpus = GoldenAddressCorpus.of(baseline)
            val outputs = LinkedHashMap<Pair<String, ConfigBuildMode>, GoldenJvmOutput>()
            for (id in baseline.scenarioIds) {
                val input = baseline.input(id)
                val scenarioDir = File(root, "scenarios/$id").apply { mkdirs() }
                File(input.dir, "input.json").copyTo(File(scenarioDir, "input.json"))
                val scenario = GoldenJvmScenario(input, corpus, tempRoot)
                for (mode in ConfigBuildMode.entries) {
                    val output = scenario.run(mode)
                    GoldenJvmScenario.write(File(scenarioDir, mode.dir), output)
                    outputs[id to mode] = output
                }
            }
            baseline.root.resolve("address").copyRecursively(File(root, "address"))
            File(root, "manifest.json").writeText("{\"formatVersion\": 3, \"commit\": \"jvm-golden-test\"}\n")
            return GoldenJvmTree(root, outputs, corpus.outsideCorpus.toSet())
        }
    }
}

/** 一个场景一种模式的诊断与警告预期。 */
class GoldenJvmExpectation(val diagnostics: List<ConfigBuildDiagnostic>, val warnings: List<String>) {
    companion object {
        val NONE = GoldenJvmExpectation(emptyList(), emptyList())
    }
}

/**
 * 诊断与警告的预期（golden-jvm/expected-diagnostics.json，在基线目录之外：基线目录会被采集脚本整体替换）。
 * 只列有诊断或警告的场景与模式，其余都断言为空；每个场景的 note 写明依据。
 */
object GoldenJvmExpectations {

    private const val RESOURCE = "golden-jvm/expected-diagnostics.json"

    fun load(): Map<Pair<String, ConfigBuildMode>, GoldenJvmExpectation> {
        val text = GoldenJvmExpectations::class.java.classLoader!!.getResourceAsStream(RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("测试资源里没有 $RESOURCE")
        val root = JsonParser.parseString(text).asJsonObject
        check(root["formatVersion"].asInt == 1) { "$RESOURCE 的 formatVersion 不是 1" }
        val result = LinkedHashMap<Pair<String, ConfigBuildMode>, GoldenJvmExpectation>()
        for ((id, value) in root.getAsJsonObject("scenarios").entrySet()) {
            val scenario = value.asJsonObject
            check(scenario["note"]?.asString?.isNotBlank() == true) { "$RESOURCE：$id 没有写依据（note）" }
            for ((key, modeValue) in scenario.entrySet()) {
                if (key == "note") continue
                val mode = ConfigBuildMode.entries.singleOrNull { it.dir == key }
                    ?: error("$RESOURCE：$id 下不认识的键 $key")
                val entry = modeValue.asJsonObject
                result[id to mode] = GoldenJvmExpectation(
                    entry.getAsJsonArray("diagnostics").map { diagnostic(it.asJsonObject, "$id/$key") },
                    entry.getAsJsonArray("warnings").map { it.asString },
                )
            }
        }
        return result
    }

    private fun diagnostic(json: JsonObject, where: String): ConfigBuildDiagnostic {
        fun long(key: String) = json[key]?.asLong ?: error("$RESOURCE $where：诊断缺少 $key")
        fun string(key: String) = json[key]?.asString ?: error("$RESOURCE $where：诊断缺少 $key")
        return when (val type = string("type")) {
            "ProfileSkipped" -> ConfigBuildDiagnostic.ProfileSkipped(
                long("profileId"), string("profileName"), string("reason"),
                json["reasonNamesProfile"]?.asBoolean ?: error("$RESOURCE $where：诊断缺少 reasonNamesProfile"),
            )

            "RuleAppsNotInstalled" -> ConfigBuildDiagnostic.RuleAppsNotInstalled(long("ruleId"), string("ruleName"))
            "RuleOutboundMissing" -> ConfigBuildDiagnostic.RuleOutboundMissing(
                long("ruleId"), string("ruleName"), long("outboundId"),
            )

            "RuleNeedsVpn" -> ConfigBuildDiagnostic.RuleNeedsVpn(long("ruleId"), string("ruleName"))
            else -> error("$RESOURCE $where：不认识的诊断类型 $type")
        }
    }
}
