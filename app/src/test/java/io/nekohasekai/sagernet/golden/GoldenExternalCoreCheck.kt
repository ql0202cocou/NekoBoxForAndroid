package io.nekohasekai.sagernet.golden

import io.nekohasekai.sagernet.fmt.assemble
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files

/** 给一个基线用例生成外核配置原文。cacheFile(prefix, ext) 同生产代码，分到的文件按动态路径比较。 */
fun interface GoldenExternalGenerator {
    fun generate(case: GoldenExternalCase, cacheFile: (String, String) -> File): String
}

/**
 * 外核配置的 JVM 黄金测试入口：对某个 pluginId 在基线里的每份配置（一组跳实例一份）重新生成，与基线原文做结构比较。
 * 每个核心的测试类只需一行：`GoldenExternalCoreCheck.assertMatchesBaseline("<pluginId>")`。
 */
object GoldenExternalCoreCheck {

    /** 生产路径：整个计划经共用的组装入口 assemble 生成，取这一组的配置；设置显式传入，不读 DataStore。 */
    val PRODUCTION = GoldenExternalGenerator { case, cacheFile ->
        val process = case.plan.assemble(cacheFile, case.controller, case.settings)[case.groupIndex]
        check(process.group.pluginId == case.pluginId) { "组装入口给出的是 ${process.group.pluginId}" }
        process.config
    }

    // 失败消息最多列出的用例数，与每个用例最多列出的差异数
    private const val MAX_CASES = 5
    private const val MAX_DIFFS = 10

    /** 没有基线时跳过；有差异、生成抛异常或基线里没有这个 pluginId 的用例时失败。 */
    fun assertMatchesBaseline(pluginId: String, generator: GoldenExternalGenerator = PRODUCTION) {
        val cases = GoldenBaseline.assumeAvailable().externalCases(pluginId)
        report(pluginId, cases, generator)?.let(::fail)
    }

    /** 逐个比较，全部一致时返回 null，否则返回失败消息。 */
    fun report(pluginId: String, cases: List<GoldenExternalCase>, generator: GoldenExternalGenerator): String? {
        // 基线里每个外核都有用例，一个都没读到说明读取或 pluginId 出了问题
        if (cases.isEmpty()) return "$pluginId：基线里读到 0 个用例"
        val failures = ArrayList<Pair<GoldenExternalCase, List<String>>>()
        val tempDir = Files.createTempDirectory("golden-ext").toFile()
        try {
            for (case in cases) {
                compareCase(case, generator, tempDir).takeIf { it.isNotEmpty() }?.let { failures += case to it }
            }
        } finally {
            tempDir.deleteRecursively()
        }
        if (failures.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("$pluginId：${cases.size} 个用例中 ${failures.size} 个与基线不一致")
        if (failures.size > MAX_CASES) sb.append("（只列前 $MAX_CASES 个）")
        for ((case, lines) in failures.take(MAX_CASES)) {
            sb.append("\n[$case]")
            lines.take(MAX_DIFFS).forEach { sb.append("\n  ").append(it) }
            if (lines.size > MAX_DIFFS) sb.append("\n  ……共 ${lines.size} 处差异")
        }
        return sb.toString()
    }

    // 一个用例的差异，一致时为空
    private fun compareCase(case: GoldenExternalCase, generator: GoldenExternalGenerator, tempDir: File): List<String> {
        // 本次分到的临时文件：路径与采集时不同，两侧各自按动态路径换成占位符
        val allocated = ArrayList<String>()
        val cacheFile = { prefix: String, ext: String ->
            File.createTempFile(prefix + "_", ".$ext", tempDir).also { allocated += it.absolutePath }
        }
        val actual = try {
            generator.generate(case, cacheFile)
        } catch (e: Exception) {
            return listOf("生成时抛出 ${e.javaClass.name}：${e.message}")
        }
        // 端口用的就是基线里记录的值，两侧相同，不需要替换
        return GoldenCompare.compareDocuments(
            expected = case.expected,
            actual = actual,
            format = case.format,
            expectedDynamic = GoldenDynamic(paths = case.tempFiles),
            actualDynamic = GoldenDynamic(paths = allocated),
            name = case.file,
        ).diffs.map { it.toString() }
    }
}
