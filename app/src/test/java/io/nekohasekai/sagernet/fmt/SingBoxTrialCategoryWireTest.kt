package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 试建的类别标识两边一一对应：libcore/outbound_trial.go 的 trialCategory 常量表（每行写成 trialCategory = "<WIRE>"）
// 必须正好是 Kotlin 的 SingBoxTrialCategory 全部枚举名加上「没有结论」的两个。Go 新增或改名一个类别而 Kotlin 没跟上时，
// 那个类别在 Kotlin 侧会落到 OTHER（仍然拦下，只是说不清哪一类），这里先红。字段路径的长度上限两边也必须相同
class SingBoxTrialCategoryWireTest {

    // 单测的工作目录是 app/，从仓库根目录跑时也能找到
    private fun repoFile(path: String): File =
        listOf(File("../$path"), File(path)).firstOrNull { it.isFile } ?: error("找不到 $path（工作目录 ${File(".").absolutePath}）")

    private val goSource by lazy { repoFile("libcore/outbound_trial.go").readText() }

    @Test
    fun `libcore 的类别表等于 Kotlin 的类别加上没有结论的两个`() {
        val goWires = Regex("""trialCategory = "([A-Z0-9_]+)"""").findAll(goSource).map { it.groupValues[1] }.toList()
        assertEquals("Go 的类别表里有重复：$goWires", goWires.size, goWires.toSet().size)
        assertEquals(
            SingBoxTrialCategory.entries.map { it.name }.toSet() + SING_BOX_TRIAL_NO_VERDICT,
            goWires.toSet(),
        )
        // 「没有结论」不能同时是失败类别，否则 reader 会把它当通过
        assertTrue(SingBoxTrialCategory.entries.none { it.name in SING_BOX_TRIAL_NO_VERDICT })
    }

    @Test
    fun `字段路径的长度上限两边相同`() {
        val goMax = Regex("""const trialPathMax = (\d+)""").find(goSource)?.groupValues?.get(1)?.toInt()
            ?: error("outbound_trial.go 里找不到 trialPathMax")
        assertEquals(SING_BOX_TRIAL_PATH_MAX, goMax)
    }
}
