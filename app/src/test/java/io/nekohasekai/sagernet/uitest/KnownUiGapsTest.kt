package io.nekohasekai.sagernet.uitest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * [KnownUiGaps] 里每一项都必须「仍然不满足验收」：某一项修好了，这里就失败并要求把它从名单里删掉
 * （再把场景挂到普通用例里）。每项一个用例，各自有全新的 Robolectric 环境。不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class KnownUiGapsTest(private val gap: KnownUiGap) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun gaps(): List<Array<Any>> = KnownUiGaps.all.map { arrayOf(it) }
    }

    @Test
    fun gapIsStillOpen() {
        val check = gap.probe(scenarios)
        println("GAP ${gap.id} -> $check")
        assertFalse(
            "已知缺口「${gap.id}」现在满足验收了（${gap.acceptance}）：把它从 KnownUiGaps 删掉，" +
                "并把场景改挂到对应的 *Test 里。出处：${gap.source}",
            check.ok,
        )
    }

    @Test
    fun entryIsDocumented() {
        assertTrue("${gap.id} 没写出处", gap.source.contains("open-issues.md") || gap.source.contains("实测"))
        assertTrue("${gap.id} 没写验收内容", gap.acceptance.isNotBlank())
    }
}
