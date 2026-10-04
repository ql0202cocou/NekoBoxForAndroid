package io.nekohasekai.sagernet.golden

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// 对照基线的入口，由 buildScript/golden/compare.sh 调用：
// NEKO_GOLDEN_ACTUAL   新采集的目录；不设就跳过
// NEKO_GOLDEN_EXPECTED 基线目录，默认 app/src/test/resources/golden/；目录不存在就跳过
// NEKO_GOLDEN_REPORT   报告写到这个文件，脚本据此确认测试确实执行、环境变量确实传到了测试进程
class GoldenCompareBaselineTest {

    @Test
    fun `新采集与基线结构一致`() {
        val actualEnv = System.getenv("NEKO_GOLDEN_ACTUAL")
        assumeTrue("没设 NEKO_GOLDEN_ACTUAL", !actualEnv.isNullOrEmpty())
        val expected = System.getenv("NEKO_GOLDEN_EXPECTED")?.takeIf { it.isNotEmpty() }?.let(::File)
            ?: defaultBaseline()
        assumeTrue("基线目录不存在：$expected", expected.isDirectory)

        val report = GoldenCompareTree.compare(expected, File(actualEnv!!))
        val text = report.render()
        System.getenv("NEKO_GOLDEN_REPORT")?.takeIf { it.isNotEmpty() }?.let { File(it).writeText(text) }
        println(text)
        assertTrue(text, report.same)
    }

    // Gradle 跑单测时工作目录是 app/；从仓库根目录运行（如 IDE）时再找 app/ 下
    private fun defaultBaseline(): File {
        val candidates = listOf(File("src/test/resources/golden"), File("app/src/test/resources/golden"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }
}
