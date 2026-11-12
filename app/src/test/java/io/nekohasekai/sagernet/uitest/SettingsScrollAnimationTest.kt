package io.nekohasekai.sagernet.uitest

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** 设置列表只随实际消费的滚动移动；卡片收放和重新布局不能把可见行推回。 */
class SettingsScrollAnimationTest : UiTestBase() {

    private fun settings(heightDp: Int = 800, middle: Boolean = true): MainActivity {
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h${heightDp}dp-port-notnight-xhdpi")
        val main = ui.launchMain().get()
        main.displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        main.connect()
        assertTrue(main.binding.stats.isExpanded)
        if (!middle) return main
        val list = main.settingsList().listView
        list.scrollBy(0, 100_000)
        UiSettle.settle()
        list.scrollBy(0, -list.computeVerticalScrollOffset() / 2)
        UiSettle.settle()
        assertTrue("前置条件：列表处于滚动范围中间", list.canScrollVertically(-1) && list.canScrollVertically(1))
        return main
    }

    /** 留白全部回收到当前目标值时的列表底部内边距 */
    private fun targetPadding(main: MainActivity, list: RecyclerView) = insetBottom(list) + main.bottomControlsClearance()

    /** 开始一次触摸嵌套滚动并拖动 20px（足以收起卡片），再推进 [frames] 帧 */
    private fun RecyclerView.beginTouchScroll(frames: Int) {
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
        scrollBy(0, 20)
        UiSettle.advanceFrames(frames)
    }

    /** 按真实 onScrolled 的消费量对照逐帧行坐标，正常到边界后的未消费量不计作移动。 */
    private class Rows(private val list: RecyclerView) : AutoCloseable {
        private fun positions(): Map<Int, Int> = (0 until list.childCount).associate {
            val child = list.getChildAt(it)
            list.getChildAdapterPosition(child) to child.top
        }
        private var previous = positions()
        private var consumed = 0
        private var frames = 0
        private val errors = mutableListOf<String>()
        private val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { consumed += dy }
        }
        private val draw = ViewTreeObserver.OnPreDrawListener {
            val current = positions()
            for ((position, top) in current) {
                val old = previous[position] ?: continue
                if (top != old - consumed) errors += "行 $position：$old → $top，实际滚动 $consumed"
            }
            previous = current
            consumed = 0
            frames++
            true
        }
        init {
            list.addOnScrollListener(listener)
            list.viewTreeObserver.addOnPreDrawListener(draw)
        }
        override fun close() {
            list.removeOnScrollListener(listener)
            list.viewTreeObserver.removeOnPreDrawListener(draw)
            assertTrue("必须观察绘制帧", frames > 0)
            assertTrue("可见行不能额外移动：${errors.take(8)}", errors.isEmpty())
        }
    }

    private fun reverse(heightDp: Int, animations: Boolean = true) {
        val main = settings(heightDp)
        val list = main.settingsList().listView
        if (animations) ui.enableAnimationFrames()
        val padding = list.paddingBottom
        val height = list.height
        Rows(list).use {
            assertTrue(list.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH))
            for (delta in listOf(20, -20, 20)) {
                list.scrollBy(0, delta)
                UiSettle.advanceFrames(8)
                assertEquals("手势期间留白不变", padding, list.paddingBottom)
                assertEquals("列表可用高度不变", height, list.height)
            }
            list.stopNestedScroll(ViewCompat.TYPE_TOUCH)
            UiSettle.advanceFrames(45)
        }
        assertFalse(main.binding.stats.isExpanded)
    }

    @Test fun reverseOnShortScrollableRange() = reverse(800)
    @Test fun reverseOnLongerScrollableRange() = reverse(700)
    @Test fun reverseWithAnimationsDisabled() = reverse(800, animations = false)

    @Test
    fun touchDragAndCancelKeepRowsStable() {
        val main = settings()
        val list = main.settingsList().listView
        ui.enableAnimationFrames()
        val padding = list.paddingBottom
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, list.width / 2f, y, 0)
            try { list.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        Rows(list).use {
            send(MotionEvent.ACTION_DOWN, 300f)
            send(MotionEvent.ACTION_MOVE, 240f)
            UiSettle.advanceFrames(8)
            send(MotionEvent.ACTION_MOVE, 280f)
            UiSettle.advanceFrames(8)
            assertEquals("真实触摸反向仍冻结留白", padding, list.paddingBottom)
            send(MotionEvent.ACTION_CANCEL, 280f)
            UiSettle.advanceFrames(50)
        }
    }

    @Test
    fun shortListOverscrollKeepsTheViewportHeight() {
        val main = settings(1000, middle = false)
        val list = main.settingsList().listView
        val height = list.height
        val padding = list.paddingBottom
        assertFalse("前置条件：短列表无需滚动", list.canScrollVertically(1))
        ui.enableAnimationFrames()
        Rows(list).use {
            list.beginTouchScroll(frames = 45)
            assertEquals(padding, list.paddingBottom)
            list.stopNestedScroll(ViewCompat.TYPE_TOUCH)
            UiSettle.advanceFrames(3)
            assertEquals("短列表回收留白也不改变容器高度", height, list.height)
            assertEquals(targetPadding(main, list), list.paddingBottom)
        }
    }

    @Test
    fun reversingBeforeTheFirstFrameEndsAtTheVisibleState() {
        val main = settings()
        val stats = main.binding.stats
        ui.enableAnimationFrames()
        repeat(3) {
            stats.behavior.slideDown(stats, true)
            stats.behavior.slideUp(stats, true)
        }
        UiSettle.advanceFrames(50)
        assertEquals(0f, stats.translationY, 0f)
        assertEquals(1f, stats.clearanceFraction, 0f)
        assertEquals(View.VISIBLE, stats.visibility)
        assertTrue(stats.isExpanded)
    }

    @Test
    fun reattachingAListDoesNotKeepAnAbandonedGestureFrozen() {
        val main = settings()
        val list = main.settingsList().listView
        ui.enableAnimationFrames()
        list.beginTouchScroll(frames = 45)
        val parent = list.parent as ViewGroup
        val index = parent.indexOfChild(list)
        parent.removeView(list)
        parent.addView(list, index)
        UiSettle.advanceFrames(3)
        list.scrollBy(0, -100_000)
        UiSettle.advanceFrames(3)
        assertEquals("重挂后不残留旧手势的冻结状态", targetPadding(main, list), list.paddingBottom)
    }

    @Test
    fun touchToFlingKeepsPaddingUntilBothHaveStopped() {
        val main = settings(500)
        val list = main.settingsList().listView
        ui.enableAnimationFrames()
        val padding = list.paddingBottom
        Rows(list).use {
            list.beginTouchScroll(frames = 8)
            assertTrue("前置条件：惯性滚动开始", list.fling(0, 900))
            list.stopNestedScroll(ViewCompat.TYPE_TOUCH)
            var settlingFrames = 0
            repeat(90) {
                if (list.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                    assertEquals("触摸结束后惯性仍冻结留白", padding, list.paddingBottom)
                    settlingFrames++
                }
                UiSettle.advanceFrames(1)
            }
            assertTrue("必须经过惯性中间帧", settlingFrames > 2)
            assertEquals(RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
        }
    }

    @Test
    fun programmaticScrollReleasesRetainedPaddingWhenIdle() {
        val main = settings()
        val list = main.settingsList().listView
        ui.enableAnimationFrames()
        Rows(list).use {
            list.smoothScrollBy(0, -100_000)
            assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
            assertFalse("前置条件：程序滚动不发嵌套滚动", list.hasNestedScrollingParent(ViewCompat.TYPE_NON_TOUCH))
            main.binding.stats.behavior.slideDown(main.binding.stats, false)
            UiSettle.advanceFrames(160)
            assertEquals(RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
            assertFalse(list.canScrollVertically(-1))
            assertEquals("无嵌套结束通知时也应释放", targetPadding(main, list), list.paddingBottom)
        }
    }

    @Test
    fun programmaticScrollThatCannotMoveStillAppliesTheNewPadding() {
        val main = settings()
        val list = main.settingsList().listView
        val stats = main.binding.stats
        stats.behavior.slideDown(stats, false)
        list.scrollBy(0, 100_000)
        UiSettle.settle()
        assertFalse("前置条件：已在底部", list.canScrollVertically(1))
        // 到底后的程序滚动没有位移，也就没有 onScrolled；卡片恰在这一帧到位
        list.smoothScrollBy(0, 50)
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
        stats.behavior.slideUp(stats, false)
        UiSettle.settle()
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
        assertEquals("回到空闲后应让开展开的卡片", targetPadding(main, list), list.paddingBottom)
    }

    @Test
    fun idleNearBottomRetainsOnlyNecessaryPaddingThenReleasesItAtTop() {
        val main = settings()
        val list = main.settingsList().listView
        val shownPadding = list.paddingBottom
        ui.enableAnimationFrames()
        Rows(list).use {
            main.binding.stats.behavior.slideDown(main.binding.stats, true)
            UiSettle.advanceFrames(50)
        }
        val hiddenPadding = targetPadding(main, list)
        assertTrue("接近底部暂留必要空间", list.paddingBottom > hiddenPadding)
        assertTrue("安全的部分已回收", list.paddingBottom < shownPadding)
        Rows(list).use {
            list.scrollBy(0, -100_000)
            UiSettle.advanceFrames(3)
        }
        assertEquals("回到安全位置后释放剩余留白", hiddenPadding, list.paddingBottom)
    }

    @Test
    fun relayoutAndReverseDoNotSnapTheCardToItsHiddenEndpoint() {
        val main = settings()
        val stats = main.binding.stats
        ui.enableAnimationFrames()
        stats.behavior.slideDown(stats, true)
        UiSettle.advanceFrames(5)
        val before = stats.translationY
        val endpoint = stats.height + (stats.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        assertTrue("前置条件：处于动画中间", before > 0 && before < endpoint)
        stats.behavior.onLayoutChild(main.binding.coordinator, stats, View.LAYOUT_DIRECTION_LTR)
        assertEquals("重布局保持当前动画位置", before, stats.translationY, 0f)
        stats.behavior.slideUp(stats, true)
        assertEquals("反向从当前动画位置继续", before, stats.translationY, 0f)
        UiSettle.advanceFrames(50)
        assertEquals(0f, stats.translationY, 0f)
        assertEquals(View.VISIBLE, stats.visibility)
    }

    @Test
    fun lastSettingCanStillScrollAboveTheCardAndDock() {
        val main = settings()
        val list = main.settingsList().listView
        fun above(control: View) {
            list.scrollBy(0, 100_000)
            UiSettle.settle()
            val manager = list.layoutManager as LinearLayoutManager
            val last = manager.findViewByPosition(list.adapter!!.itemCount - 1)!!
            assertTrue("最后一项必须完整露出", top(last) + last.height <= top(control))
        }
        above(main.binding.stats)
        main.binding.stats.behavior.slideDown(main.binding.stats, false)
        UiSettle.settle()
        above(main.binding.dock)
    }
}
