package io.nekohasekai.sagernet.golden

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ExternalCoreInvariants
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 基线里全部 Xray 合并配置都满足外核「哑管道」不变量（ExternalCoreInvariants）：每个场景的
// run、test 两种模式的 ext-* 配置，加 export.txt 里导出的外核段。基线是回归钉，重新采集时新增的键会被一起接受；
// 这里的白名单与取值约束是独立的一层，重采基线也带不进新键。
class GoldenExternalInvariantsTest {

    private val failures = ArrayList<String>()
    private var xrayRunTest = 0
    private var xrayExport = 0

    @Test
    fun `基线里全部 Xray 合并配置满足哑管道不变量`() {
        val baseline = GoldenBaseline.load()
        for (id in baseline.scenarioIds) {
            for (mode in GoldenBoxMode.entries) {
                val modeDir = File(baseline.root, "scenarios/$id/${mode.dir}")
                for (group in baseline.externalGroups(id, mode)) {
                    if (group.pluginId != "xray-plugin") continue
                    val where = "$id / $mode"
                    val text = File(modeDir, group.file).readText()
                    xrayRunTest++
                    check(group.controller == null) { "$where ${group.file}：Xray 配置记录了控制器" }
                    val report = ExternalCoreInvariants.xray(text)
                    for (violation in report.violations) failures += "$where ${group.file}：$violation"
                    check(report.exceptions.isEmpty()) { "$where ${group.file}：Xray 不应有例外" }
                }
            }
            val export = File(baseline.root, "scenarios/$id/export/export.txt")
            if (export.isFile) {
                // 导出：第 0 段是 sing-box 配置，其后每段一份外核配置
                export.readText().split("\n\n").drop(1).forEachIndexed { n, segment ->
                    if (!isXray(segment)) return@forEachIndexed
                    xrayExport++
                    val report = ExternalCoreInvariants.xray(segment)
                    for (violation in report.violations) failures += "$id / export#${n + 1}：$violation"
                }
            }
        }
        println("哑管道不变量：Xray run / test $xrayRunTest 份、export $xrayExport 份")
        assertTrue(failures.take(40).joinToString("\n", "违反不变量 ${failures.size} 处：\n"), failures.isEmpty())
        assertTrue("基线里应有 Xray 配置", xrayRunTest > 0 && xrayExport > 0)
    }

    private fun isXray(segment: String): Boolean {
        val json = runCatching { JsonParser.parseString(segment) }.getOrNull()
        if (json == null || !json.isJsonObject) return false
        val obj = json.asJsonObject
        return obj.has("routing") && obj.has("inbounds")
    }
}
