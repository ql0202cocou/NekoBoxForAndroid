package io.nekohasekai.sagernet.uitest

import android.view.View
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.bg.BaseService
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snackbar 显示期间状态卡片滑入 / 滑出：Snackbar 跟着换锚点、挪位置，始终在可见控件（卡片或 Dock）之上，
 * 不盖住卡片，也不被卡片盖住。原先锚点只在创建时决定，卡片后滑入时会盖住 Snackbar 的下半部分。
 * 不截图，所有平台都跑。
 */
class SnackbarAnchorTest : UiTestBase() {

    private fun bottom(view: View) = top(view) + view.height

    @Test
    fun snackbarFollowsTheStatusCardWhileShown() {
        val controller = ui.launchMain()
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
        main.connect()
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

    @Test
    fun snackbarStaysAboveTheDockWhileTheCardSlidesBehindIt() {
        val main = ui.launchMain().get()
        val stats = main.binding.stats
        val dock = main.binding.dock
        main.connect()
        // 两段滑动加起来超过默认显示时长，不让它中途自己消失
        val snackbar = main.snackbar("message").apply { duration = Snackbar.LENGTH_INDEFINITE }.apply { show() }
        UiSettle.settle()
        assertSame("前置条件：卡片展开时锚卡片", stats, snackbar.anchorView)

        // 卡片收起 / 拉出时有一段在 Dock 顶边以下（从 Dock 后面进出屏幕），这段里 Snackbar 不能跟下去盖住 Dock
        ui.enableAnimationFrames()
        fun slideAndWatch(slide: () -> Unit) {
            slide()
            var cardBehindDock = 0
            repeat(40) {
                UiSettle.advanceFrames(1)
                if (top(stats) in (top(dock) + 1) until main.binding.coordinator.height) cardBehindDock++
                assertTrue(
                    "Snackbar（底边 ${bottom(snackbar.view)}）越过了 Dock（顶边 ${top(dock)}），卡片顶边 ${top(stats)}",
                    bottom(snackbar.view) <= top(dock),
                )
            }
            assertTrue("前置条件：采到卡片位于 Dock 顶边以下的中间帧", cardBehindDock > 0)
        }
        slideAndWatch { stats.behavior.slideDown(stats, true) }
        assertSame(dock, snackbar.anchorView)
        slideAndWatch { stats.behavior.slideUp(stats, true) }
        assertSame(stats, snackbar.anchorView)
        assertTrue("到位后 Snackbar 在卡片之上", bottom(snackbar.view) <= top(stats))
    }

    @Test
    fun snackbarStillEnteringFollowsTheCardToo() {
        val main = ui.launchMain().get()
        val stats = main.binding.stats
        val dock = main.binding.dock
        main.connect()

        // 出错断开时提示和卡片收起几乎同时发生：Snackbar 的入场动画还没播完，卡片已经开始滑走
        ui.enableAnimationFrames()
        val snackbar = main.snackbar("message").apply { duration = Snackbar.LENGTH_INDEFINITE }.apply { show() }
        UiSettle.advanceFrames(2)
        assertTrue("前置条件：Snackbar 已挂上窗口", snackbar.view.isAttachedToWindow)
        assertSame("前置条件：弹出时卡片展开，锚卡片", stats, snackbar.anchorView)
        stats.behavior.slideDown(stats, true)
        repeat(40) {
            UiSettle.advanceFrames(1)
            assertTrue(
                "Snackbar（底边 ${bottom(snackbar.view)}）越过了 Dock（顶边 ${top(dock)}），卡片顶边 ${top(stats)}",
                bottom(snackbar.view) <= top(dock),
            )
        }
        assertSame(dock, snackbar.anchorView)
    }
}
