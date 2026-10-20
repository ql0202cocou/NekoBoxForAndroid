package io.nekohasekai.sagernet.uitest

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * 主界面四页与两个编辑器：创建后立刻销毁、再放行后台，不得有任何未捕获异常
 * （U4b「关闭后回调不碰 View」的验收）。异常由 [UiTestRule] 收集，主线程上的由 settle 抛出。
 * 不截图，所有平台都跑。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CloseBeforeLoadTest(private val target: UiScenarios.CloseTarget) : UiBehaviorBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun targets(): List<Array<Any>> = UiScenarios.CloseTarget.entries.map { arrayOf(it) }
    }

    @Test
    fun closeRightAfterCreate() {
        val check = scenarios.closeBeforeLoad(target)
        org.junit.Assert.assertTrue("$target：$check", check.ok)
    }
}
