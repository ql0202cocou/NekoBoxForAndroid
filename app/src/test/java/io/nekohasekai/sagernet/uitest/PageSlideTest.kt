package io.nekohasekai.sagernet.uitest

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.children
import androidx.core.view.descendants
import io.nekohasekai.sagernet.R
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** 打开动画检查中间帧：相邻页面必须始终不透明、边缘相接，快速切换后只留下最终页面。 */
class PageSlideTest : UiTestBase() {

    @After
    fun restoreFrameClock() {
        ShadowChoreographer.setPaused(false)
    }

    private fun enableAnimationFrames() {
        // 默认绘制时钟会在 idle() 内跑完整段动画；暂停自动推进，逐帧检查真实转场。
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations()
    }

    private fun page(main: MainActivity): View =
        main.supportFragmentManager.findFragmentById(R.id.fragment_holder)!!.requireView()

    private fun advanceFrames(count: Int) {
        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun select(main: MainActivity, destination: Int) {
        main.binding.dock.onItemSelected!!.invoke(destination)
        main.supportFragmentManager.executePendingTransactions()
        UiSettle.settle()
    }

    private fun assertDockSelected(main: MainActivity, destination: Int) {
        val title = main.getText(when (destination) {
            R.id.nav_configuration -> R.string.menu_configuration
            R.id.nav_group -> R.string.menu_group
            R.id.nav_route -> R.string.menu_route
            else -> R.string.settings
        })
        assertTrue("Dock 选中最后的目标 $title", main.binding.dock.descendants.any {
            it.contentDescription == title && it.isSelected
        })
    }

    private fun startGroupSlide(main: MainActivity): ViewGroup {
        val outgoing = page(main)
        select(main, R.id.nav_group)
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
        assertEquals("只留下最终页面", 1, holder.childCount)
        assertEquals(0f, page(main).translationX, 0f)
    }

    private fun assertInterruptedSlide(destination: Int, scale: Float = 1f) {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
        val overlay = startGroupSlide(main)
        assertCoveredWhile(main, overlay) {
            select(main, destination)
            advanceFrames((60 * scale).toInt())
        }
        val expected = if (destination == R.id.nav_route) RouteFragment::class.java else ConfigurationFragment::class.java
        assertEquals(expected, main.supportFragmentManager.findFragmentById(R.id.fragment_holder)!!.javaClass)
        assertDockSelected(main, destination)
    }

    @Test
    fun forwardRequestDuringSlideKeepsEveryFrameCovered() = assertInterruptedSlide(R.id.nav_route)

    @Test
    fun reverseRequestDuringSlideKeepsEveryFrameCovered() = assertInterruptedSlide(R.id.nav_configuration)

    @Test
    fun slowerSystemAnimationsStillWaitForTheSlideToFinish() = assertInterruptedSlide(R.id.nav_route, scale = 2f)

    @Test
    fun repeatedRequestsKeepOnlyTheLastDestination() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        val overlay = startGroupSlide(main)
        val incoming = page(main)
        assertCoveredWhile(main, overlay) {
            for (destination in listOf(R.id.nav_route, R.id.nav_settings, R.id.nav_configuration)) {
                select(main, destination)
                assertSame("等待期间不替换正在进入的页面", incoming, page(main))
                assertDockSelected(main, destination)
            }
            advanceFrames(60)
        }
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment)
    }

    @Test
    fun selectingTheIncomingPageCancelsTheQueuedDestination() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        val overlay = startGroupSlide(main)
        val incoming = page(main)
        assertCoveredWhile(main, overlay) {
            select(main, R.id.nav_route)
            select(main, R.id.nav_group)
            advanceFrames(60)
        }
        assertSame("点回进入中的页面保留当前实例", incoming, page(main))
        assertDockSelected(main, R.id.nav_group)
    }

    @Test
    fun rotationKeepsTheLastQueuedDestination() {
        val controller = ui.launchMain()
        enableAnimationFrames()
        startGroupSlide(controller.get())
        select(controller.get(), R.id.nav_route)
        RuntimeEnvironment.setQualifiers("+land")
        controller.configurationChange()
        if (!controller.get().window.decorView.isAttachedToWindow) controller.visible()
        UiSettle.settle()
        advanceFrames(60)
        val main = controller.get()
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is RouteFragment)
        assertDockSelected(main, R.id.nav_route)
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
        val main = restored.get()
        assertEquals(section, (main.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment).sectionKey)
        assertDockSelected(main, R.id.nav_settings)
    }

    @Test
    fun navigationAfterSavingStateWaitsUntilResume() {
        val controller = ui.launchMain()
        val main = controller.get()
        controller.pause().saveInstanceState(Bundle()).stop()
        select(main, R.id.nav_route)
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment)
        controller.start().resume()
        UiSettle.settle()
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is RouteFragment)
    }

    private fun assertSlide(main: MainActivity, destination: Int, forward: Boolean) {
        val previous = page(main)
        main.binding.dock.onItemSelected!!.invoke(destination)
        UiSettle.settle()
        val next = page(main)
        var movingFrames = 0
        repeat(24) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            assertEquals("旧页保持不透明", 1f, previous.alpha, 0f)
            assertEquals("新页保持不透明", 1f, next.alpha, 0f)
            assertEquals("新页不缩放", 1f, next.scaleX, 0f)
            if (next.translationX != 0f) {
                movingFrames++
                if (forward) {
                    assertTrue("旧页向左退出", previous.translationX <= 0f)
                    assertTrue("新页从右侧进入", next.translationX > 0f)
                    assertEquals("两页边缘相接", previous.translationX + previous.width, next.translationX, 1f)
                } else {
                    assertTrue("旧页向右退出", previous.translationX >= 0f)
                    assertTrue("新页从左侧进入", next.translationX < 0f)
                    assertEquals("两页边缘相接", next.translationX + next.width, previous.translationX, 1f)
                }
            }
        }
        assertTrue("必须实际经过多个滑动中间帧", movingFrames > 2)
        assertEquals("新页停在原位", 0f, next.translationX, 0f)
        assertEquals("转场结束只保留当前页面", 1, main.binding.fragmentHolder.childCount)
    }

    @Test
    fun primaryPagesSlideInBothDirectionsWithoutFadingOrGaps() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        assertSlide(main, R.id.nav_group, true)
        assertSlide(main, R.id.nav_route, true)
        assertSlide(main, R.id.nav_settings, true)
        assertSlide(main, R.id.nav_configuration, false)
    }

    @Test
    fun rapidSelectionReturnsToTheLastRequestedPage() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        main.binding.dock.onItemSelected!!.invoke(R.id.nav_group)
        main.binding.dock.onItemSelected!!.invoke(R.id.nav_configuration)
        UiSettle.settle()
        repeat(40) {
            advanceFrames(1)
            assertEquals("同一帧点回原页无需再滑动", 0f, page(main).translationX, 0f)
            assertEquals("同一帧点回原页不淡变", 1f, page(main).alpha, 0f)
        }
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder)
            is ConfigurationFragment)
        assertEquals(1, main.binding.fragmentHolder.childCount)
        assertEquals(0f, page(main).translationX, 0f)
    }

    @Test
    fun changingDestinationDuringSlideLeavesOnlyTheFinalPage() {
        val main = ui.launchMain().get()
        enableAnimationFrames()
        for (destination in listOf(R.id.nav_group, R.id.nav_settings, R.id.nav_route)) {
            main.binding.dock.onItemSelected!!.invoke(destination)
            UiSettle.settle()
            advanceFrames(4)
            assertTrue("切换下一页时上一个滑动仍在进行", page(main).translationX != 0f)
            assertEquals(1f, page(main).alpha, 0f)
        }
        main.binding.dock.onItemSelected!!.invoke(R.id.nav_configuration)
        UiSettle.settle()
        advanceFrames(40)
        assertTrue(main.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment)
        assertEquals(1, main.binding.fragmentHolder.childCount)
        assertEquals(0f, page(main).translationX, 0f)
        assertEquals(1f, page(main).alpha, 0f)
    }

    @Test
    fun rtlMirrorsTheSlideDirection() {
        val main = ui.launchMain().get()
        main.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        UiSettle.settle()
        enableAnimationFrames()
        assertSlide(main, R.id.nav_settings, false)
        assertSlide(main, R.id.nav_configuration, true)
    }

    @Test
    fun disabledAnimationsLeaveNoOffsetOrOutgoingPage() {
        val main = ui.launchMain().get()
        for (destination in listOf(R.id.nav_settings, R.id.nav_route, R.id.nav_group, R.id.nav_configuration)) {
            main.binding.dock.onItemSelected!!.invoke(destination)
            UiSettle.settle()
            assertEquals(1, main.binding.fragmentHolder.childCount)
            assertEquals(0f, page(main).translationX, 0f)
            assertEquals(1f, page(main).alpha, 0f)
        }
    }
}
