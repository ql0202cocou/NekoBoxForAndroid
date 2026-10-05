package io.nekohasekai.sagernet.golden

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ControllerExpectation
import io.nekohasekai.sagernet.fmt.ExternalCoreInvariants
import io.nekohasekai.sagernet.fmt.InvariantReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

// 基线里全部 Xray / mihomo 合并配置都满足外核「哑管道」不变量（ExternalCoreInvariants，plan.md K1）：每个场景的
// run、test 两种模式的 ext-* 配置，加 export.txt 里导出的外核段。基线是回归钉，重新采集时新增的键会被一起接受；
// 这里的白名单与取值约束是独立的一层，重采基线也带不进新键。
// mihomo 的测速控制器按 result.json 的记录断言：测速且开了控制器的配置必须有，且端口与 secret 对得上；
// 运行与导出不许有。
class GoldenExternalInvariantsTest {

    // 命中表里显式列出的例外的配置：key 是「场景 / 模式 配置文件或导出段」，值是例外。ECH 的 DNS 例外（plan.md K1）：
    // mihomo 只开 ECH 没有内联配置时自己查 DNS。这一项只有 mihomo-anytls-ech-enable 一个场景；
    // 新增场景命中例外时测试失败，要先核对它是有意的，再把它写进名单
    private val expectedExceptions: Map<String, List<String>> = mapOf(
        "mihomo-anytls-ech-enable / run ext-0.mihomo-plugin.yaml" to listOf(ExternalCoreInvariants.ECH_DNS_EXCEPTION),
        "mihomo-anytls-ech-enable / test ext-0.mihomo-plugin.yaml" to listOf(ExternalCoreInvariants.ECH_DNS_EXCEPTION),
        "mihomo-anytls-ech-enable / export#1" to listOf(ExternalCoreInvariants.ECH_DNS_EXCEPTION),
    )

    private val failures = ArrayList<String>()
    private val exceptions = LinkedHashMap<String, List<String>>()
    private var xrayRunTest = 0
    private var mihomoRunTest = 0
    private var xrayExport = 0
    private var mihomoExport = 0
    private var mihomoWithController = 0

    private fun record(where: String, report: InvariantReport) {
        for (violation in report.violations) failures += "$where：$violation"
        if (report.exceptions.isNotEmpty()) exceptions[where] = report.exceptions
    }

    @Test
    fun `基线里全部 Xray 与 mihomo 合并配置满足哑管道不变量`() {
        val baseline = GoldenBaseline.load()
        for (id in baseline.scenarioIds) {
            for (mode in GoldenBoxMode.entries) {
                val modeDir = File(baseline.root, "scenarios/$id/${mode.dir}")
                for (group in baseline.externalGroups(id, mode)) {
                    val where = "$id / $mode"
                    val text = File(modeDir, group.file).readText()
                    when (group.pluginId) {
                        "xray-plugin" -> {
                            xrayRunTest++
                            // Xray 没有测速控制器
                            check(group.controller == null) { "$where ${group.file}：Xray 配置记录了控制器" }
                            record("$where ${group.file}", ExternalCoreInvariants.xray(text))
                        }

                        "mihomo-plugin" -> {
                            mihomoRunTest++
                            val controller = group.controller
                            val expectation = if (controller == null) {
                                ControllerExpectation.Absent
                            } else {
                                mihomoWithController++
                                ControllerExpectation.Present(controller.first, controller.second)
                            }
                            record("$where ${group.file}", ExternalCoreInvariants.mihomo(text, expectation))
                        }
                    }
                }
            }
            val export = File(baseline.root, "scenarios/$id/export/export.txt")
            if (export.isFile) {
                // 导出：第 0 段是 sing-box 配置，其后每段一份外核配置，没有测速控制器
                export.readText().split("\n\n").drop(1).forEachIndexed { n, segment ->
                    val where = "$id / export#${n + 1}"
                    when (detect(segment)) {
                        "xray" -> {
                            xrayExport++
                            record(where, ExternalCoreInvariants.xray(segment))
                        }

                        "mihomo" -> {
                            mihomoExport++
                            record(where, ExternalCoreInvariants.mihomo(segment, ControllerExpectation.Absent))
                        }
                    }
                }
            }
        }
        println(
            "哑管道不变量：Xray run / test $xrayRunTest 份、export $xrayExport 份；" +
                "mihomo run / test $mihomoRunTest 份（其中带测速控制器 $mihomoWithController 份）、export $mihomoExport 份",
        )
        assertTrue(failures.take(40).joinToString("\n", "违反不变量 ${failures.size} 处：\n"), failures.isEmpty())
        assertTrue("基线里应有 Xray 配置", xrayRunTest > 0 && xrayExport > 0)
        assertTrue("基线里应有 mihomo 配置", mihomoRunTest > 0 && mihomoExport > 0)
        assertTrue("基线里应有带测速控制器的 mihomo 配置", mihomoWithController > 0)
        // 例外要一个不多一个不少，名单里的也要真的命中
        assertEquals("命中例外的配置与名单不一致", expectedExceptions, exceptions)
    }

    // 导出的一段属于哪个合并核心：Xray 是带 routing 与 inbounds 的 JSON，mihomo 是带 listeners 的 YAML；其余核心不检查
    private fun detect(segment: String): String? {
        val json = runCatching { JsonParser.parseString(segment) }.getOrNull()
        if (json != null && json.isJsonObject) {
            val obj = json.asJsonObject
            return if (obj.has("routing") && obj.has("inbounds")) "xray" else null
        }
        val yaml = runCatching { Yaml().load<Any?>(segment) as? Map<*, *> }.getOrNull()
        return if (yaml?.containsKey("listeners") == true) "mihomo" else null
    }
}
