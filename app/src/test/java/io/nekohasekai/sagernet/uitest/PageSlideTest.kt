package io.nekohasekai.sagernet.uitest

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.children
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** 打开动画检查中间帧：相邻页面必须始终不透明、边缘相接，快速切换后只留下最终页面。 */
class PageSlideTest : UiTestBase() {

    @After
    fun restoreFrameClock() {
        ShadowChoreographer.setPaused(false)
    }

    private fun enableAnimationFrames(scale: Float = 1f) {
        // 默认绘制时钟会在 idle() 内跑完整段动画；暂停自动推进，逐帧检查真实转场。
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations(scale)
    }

    private fun page(main: MainActivity): View = main.hostedFragment<Fragment>().requireView()

    private fun advanceFrames(count: Int) {
        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun select(main: MainActivity, destination: Page) {
        main.binding.dock.onItemSelected!!.invoke(destination.navId)
        UiSettle.settle()
    }

    private fun assertDockSelected(main: MainActivity, destination: Page) {
        assertEquals("Dock 选中最后的目标", main.getString(destination.title), main.dockSelection())
    }

    private inline fun <reified F : Fragment> assertHosted(main: MainActivity) {
        assertTrue("当前页面应为 ${F::class.simpleName}", main.hostedFragment<Fragment>() is F)
    }

    // 转场结束：容器里只留下最终页面，停在原位且不透明
    private fun assertSettled(main: MainActivity) {
        assertEquals("只留下最终页面", 1, main.binding.fragmentHolder.childCount)
        assertEquals("页面停在原位", 0f, page(main).translationX, 0f)
        assertEquals("页面保持不透明", 1f, page(main).alpha, 0f)
    }

    private fun startGroupSlide(main: MainActivity): ViewGroup {
        val outgoing = page(main)
        select(main, Page.GROUPS)
        advanceFrames(6)
        val incoming = page(main)
        assertTrue("前置条件：当前滑动尚未完成", incoming.translationX > 0f && incoming.translationX < incoming.width)
        // Fragment 转场把旧页面移入 ViewOverlay，不能只检查容器里的新页面。
        return outgoing.parent as ViewGroup
    }

    private fun assertCoveredWhile(main: MainActivity, overlay: ViewGroup, action: () -> Unit) {
        val holder = main.binding.fragmentHolder
        var frames = 0
        val gaps = mutableListOf<String>()
        // 在布局完成的绘制阶段检查；消息队列刚提交的新页面可能尚未测量，不是已显示的画面。
        val check = ViewTreeObserver.OnPreDrawListener {
            frames++
            val intervals = (holder.children + overlay.children)
                .filter { it.visibility == View.VISIBLE && it.alpha > .99f }
                .map { (it.left + it.translationX) to (it.left + it.translationX + it.width) }
                .sortedBy { it.first }
            var covered = 0f
            for ((left, right) in intervals) {
                if (left > covered + 1f) break
                covered = maxOf(covered, right)
            }
            if (covered < holder.width - 1f) gaps += "frame=$frames covered=$covered/${holder.width}"
            true
        }
        holder.viewTreeObserver.addOnPreDrawListener(check)
        try {
            action()
        } finally {
            holder.viewTreeObserver.removeOnPreDrawListener(check)
        }
        assertTrue("必须检查多个滑动中间帧", frames > 10)
        assertTrue("页面与退出覆盖层应连续铺满容器：$gaps", gaps.isEmpty())
        assertEquals("转场结束后覆盖层清空", 0, overlay.childCount)
        assertSettled(main)
    }

    private inline fun <reified F : Fragment> assertInterruptedSlide(destination: Page, scale: Float = 1f) {
        val main = ui.launchMain().get()
        enableAnimationFrames(scale)
        val overlay = startGroupSlide(main)
        assertCoveredWhile(main, overlay) {
            select(main, destination)
            advanceFrames((60 * scale).toInt())
        }
        assertHosted<F>(main)
        assertDockSelected(main, destination)
    }

    @Test
    fun forwardRequestDuringSlideKeepsEveryFrameCovered() = assertInterruptedSlide<RouteFragment>(Page.ROUTES)

    @Test
    fun reverseRequestDuringSlideKeepsEveryFrameCovered() = assertInterruptedSlide<ConfigurationFragment>(Page.CONFIG)

    @Test
    fun slowerSystemAnimationsStillWaitForTheSlideToFinish() = assertInterruptedSlide<RouteFragment>(Page.ROUTES, scale = 2f)

    @Test
    fun repeatedRequestsKeepOnlyTheLastDestination() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        val overlay = startGroupSlide(main)
        val incoming = page(main)
        assertCoveredWhile(main, overlay) {
            for (destination in listOf(Page.ROUTES, Page.SETTINGS, Page.CONFIG)) {
                select(main, destination)
                assertSame("等待期间不替换正在进入的页面", incoming, page(main))
                assertDockSelected(main, destination)
            }
            advanceFrames(60)
        }
        assertHosted<ConfigurationFragment>(main)
    }

    @Test
    fun selectingTheIncomingPageCancelsTheQueuedDestination() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        val overlay = startGroupSlide(main)
        val incoming = page(main)
        assertCoveredWhile(main, overlay) {
            select(main, Page.ROUTES)
            select(main, Page.GROUPS)
            advanceFrames(60)
        }
        assertSame("点回进入中的页面保留当前实例", incoming, page(main))
        assertDockSelected(main, Page.GROUPS)
    }

    @Test
    fun rotationKeepsTheLastQueuedDestination() {
        val controller = ui.launchMain()
        enableAnimationFrames()
        startGroupSlide(controller.get())
        select(controller.get(), Page.ROUTES)
        controller.rotate()
        advanceFrames(60)
        val main = controller.get()
        assertHosted<RouteFragment>(main)
        assertDockSelected(main, Page.ROUTES)
    }

    @Test
    fun processRecreationKeepsQueuedSettingsSectionArguments() {
        val controller = ui.launchMain()
        enableAnimationFrames()
        startGroupSlide(controller.get())
        val section = SettingsPreferenceFragment.sections[0].first
        controller.get().displayFragment(SettingsFragment.section(section))
        val restored = ui.recreateProcess(controller)
        advanceFrames(60)
        assertEquals(section, restored.hostedFragment<SettingsFragment>().sectionKey)
        assertDockSelected(restored.get(), Page.SETTINGS)
    }

    @Test
    fun navigationAfterSavingStateWaitsUntilResume() {
        val controller = ui.launchMain()
        val main = controller.get()
        ui.saveInstanceState(controller)
        controller.stop()
        select(main, Page.ROUTES)
        assertHosted<ConfigurationFragment>(main)
        controller.start().resume()
        UiSettle.settle()
        assertHosted<RouteFragment>(main)
    }

    private fun assertSlide(main: MainActivity, destination: Page, forward: Boolean) {
        val previous = page(main)
        select(main, destination)
        val next = page(main)
        // 前进时新页从右侧进入（正偏移）、旧页向左退出；后退相反。
        val direction = if (forward) 1 else -1
        var movingFrames = 0
        repeat(24) {
            advanceFrames(1)
            assertEquals("旧页保持不透明", 1f, previous.alpha, 0f)
            assertEquals("新页保持不透明", 1f, next.alpha, 0f)
            assertEquals("新页不缩放", 1f, next.scaleX, 0f)
            if (next.translationX != 0f) {
                movingFrames++
                assertTrue("旧页向反方向退出", previous.translationX * direction <= 0f)
                assertTrue("新页从行进方向进入", next.translationX * direction > 0f)
                assertEquals("两页边缘相接", previous.translationX + direction * previous.width, next.translationX, 1f)
            }
        }
        assertTrue("必须实际经过多个滑动中间帧", movingFrames > 2)
        assertSettled(main)
    }

    @Test
    fun primaryPagesSlideInBothDirectionsWithoutFadingOrGaps() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        assertSlide(main, Page.GROUPS, true)
        assertSlide(main, Page.ROUTES, true)
        assertSlide(main, Page.SETTINGS, true)
        assertSlide(main, Page.CONFIG, false)
    }

    @Test
    fun rapidSelectionReturnsToTheLastRequestedPage() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        // 两次点击落在同一条消息里，中间不能 settle
        main.binding.dock.onItemSelected!!.invoke(Page.GROUPS.navId)
        main.binding.dock.onItemSelected!!.invoke(Page.CONFIG.navId)
        UiSettle.settle()
        repeat(40) {
            advanceFrames(1)
            assertEquals("同一帧点回原页无需再滑动", 0f, page(main).translationX, 0f)
            assertEquals("同一帧点回原页不淡变", 1f, page(main).alpha, 0f)
        }
        assertHosted<ConfigurationFragment>(main)
        assertEquals(1, main.binding.fragmentHolder.childCount)
    }

    @Test
    fun rtlMirrorsTheSlideDirection() {
        val main = ui.launchMain().get()
        main.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        UiSettle.settle()
        enableAnimationFrames()
        assertSlide(main, Page.SETTINGS, false)
        assertSlide(main, Page.CONFIG, true)
    }

    @Test
    fun disabledAnimationsLeaveNoOffsetOrOutgoingPage() {
        val main = ui.launchMain().get()
        for (destination in listOf(Page.SETTINGS, Page.ROUTES, Page.GROUPS, Page.CONFIG)) {
            select(main, destination)
            assertSettled(main)
        }
    }
}
