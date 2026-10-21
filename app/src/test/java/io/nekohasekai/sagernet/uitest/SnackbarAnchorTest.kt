package io.nekohasekai.sagernet.uitest

import android.view.View
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * Snackbar 显示期间状态卡片滑入 / 滑出：Snackbar 跟着换锚点、挪位置，始终在可见控件（卡片或 Dock）之上，
 * 不盖住卡片，也不被卡片盖住。原先锚点只在创建时决定，卡片后滑入时会盖住 Snackbar 的下半部分。
 * 不截图，所有平台都跑。
 */
class SnackbarAnchorTest : UiTestBase() {

    private fun launchMain(): ActivityController<MainActivity> {
        UiFixtures.install()
        return ui.launch<MainActivity>()
    }

    private fun top(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[1]
    private fun bottom(view: View) = top(view) + view.height

    @Test
    fun snackbarFollowsTheStatusCardWhileShown() {
        val controller = launchMain()
        val main = controller.get()
        val stats = main.binding.stats
        val dock = main.binding.dock

        // 未连接时弹出：锚 Dock
        val snackbar = main.snackbar("message").apply { show() }
        UiSettle.settle()
        assertSame(dock, snackbar.anchorView)
        assertTrue("Snackbar 应在 Dock 之上", bottom(snackbar.view) <= top(dock))
        val bottomAboveDock = bottom(snackbar.view)

        // 显示期间连上：卡片滑入，Snackbar 换到卡片之上（卡片在 delay(100) 之后才展开）
        main.stateChanged(BaseService.State.Connected, null, null)
        UiSettle.settle(advanceMs = 500)
        assertTrue("前置条件：卡片已展开", stats.isExpanded)
        assertSame(stats, snackbar.anchorView)
        assertTrue(
            "Snackbar（底边 ${bottom(snackbar.view)}）被卡片（顶边 ${top(stats)}）盖住",
            bottom(snackbar.view) <= top(stats),
        )

        // 显示期间断开：卡片滑走，Snackbar 落回 Dock 之上原来的位置
        main.stateChanged(BaseService.State.Stopped, null, null)
        UiSettle.settle(advanceMs = 500)
        assertSame(dock, snackbar.anchorView)
        assertTrue("Snackbar 应回到 Dock 之上原来的位置", bottom(snackbar.view) == bottomAboveDock)
    }
}
