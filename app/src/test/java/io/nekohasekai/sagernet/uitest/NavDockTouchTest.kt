package io.nekohasekai.sagernet.uitest

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.descendants
import androidx.fragment.app.Fragment
import androidx.test.core.view.MotionEventBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.uitest.UiSettle.advanceFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 经真实触摸分发验证拖选：预览不导航，松手只提交一次，取消后恢复实际页面。 */
class NavDockTouchTest : UiTestBase() {

    private class Gesture(
        val main: MainActivity,
        private val throughWindow: Boolean = false,
        private val settleAfterEvent: Boolean = true,
    ) {
        val dock = main.binding.dock
        val pill: View get() = (dock.getChildAt(0) as ViewGroup).getChildAt(0)
        val requests = mutableListOf<Int>()
        private var downTime = 0L

        init {
            val navigate = dock.onItemSelected!!
            dock.onItemSelected = { id ->
                requests += id
                navigate(id)
            }
        }

        fun item(page: Page): View = dock.descendants.single {
            it.contentDescription == main.getText(page.title)
        }

        fun center(page: Page): Pair<Float, Float> {
            val view = item(page)
            val rect = Rect()
            view.getDrawingRect(rect)
            dock.offsetDescendantRectToMyCoords(view, rect)
            return rect.exactCenterX() to rect.exactCenterY()
        }

        fun send(action: Int, page: Page) = send(action, center(page))

        private fun dispatch(event: MotionEvent) {
            if (!throughWindow) {
                dock.dispatchTouchEvent(event)
                return
            }
            val location = IntArray(2)
            dock.getLocationInWindow(location)
            val windowEvent = MotionEvent.obtain(event)
            windowEvent.offsetLocation(location[0].toFloat(), location[1].toFloat())
            main.dispatchTouchEvent(windowEvent)
        }

        fun send(action: Int, point: Pair<Float, Float>) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            dispatch(event)
            if (settleAfterEvent) UiSettle.settle()
        }

        fun addSecondPointer(page: Page) {
            val (x, y) = center(page)
            val event = MotionEventBuilder.newBuilder()
                .setDownTime(downTime).setEventTime(SystemClock.uptimeMillis())
                .setAction(MotionEvent.ACTION_POINTER_DOWN).setActionIndex(1)
                .setPointer(x, y).setPointer(x + 1, y)
                .setSource(InputDevice.SOURCE_TOUCHSCREEN)
                .build()
            dispatch(event)
            event.action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            dispatch(event)
            UiSettle.settle()
        }

        fun assertSelected(page: Page) = assertDockSelected(main, page)

        fun tap(point: Pair<Float, Float>) {
            send(MotionEvent.ACTION_DOWN, point)
            send(MotionEvent.ACTION_UP, point)
        }

        fun tap(page: Page) = tap(center(page))

        fun drag(from: Page, to: Page) {
            send(MotionEvent.ACTION_DOWN, from)
            send(MotionEvent.ACTION_MOVE, to)
            send(MotionEvent.ACTION_UP, to)
        }

        fun loseAndRegainFocus() {
            main.window.decorView.dispatchWindowFocusChanged(false)
            main.window.decorView.dispatchWindowFocusChanged(true)
        }
    }

    private fun gesture() = Gesture(ui.launchMain().get())

    private fun windowGesture(settleAfterEvent: Boolean = true): Gesture {
        val main = ui.launchMain().get()
        // 底部保留真实窗口内的手势线区域，事件经 Activity 和父容器分发给 Dock。
        val bottom = (24 * main.resources.displayMetrics.density).toInt()
        val content = main.findViewById<View>(android.R.id.content)
        // 页面转场会重新申请插入区，必须在窗口入口持续提供，而不是只分发一次。
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, bottom))
                .build()
        }
        ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder().build())
        UiSettle.settle()
        return Gesture(main, throughWindow = true, settleAfterEvent = settleAfterEvent)
    }

    private fun Gesture.gestureLine(page: Page): Pair<Float, Float> {
        val y = dock.height + 12 * main.resources.displayMetrics.density
        assertTrue("前置条件：手势线在 Dock 下方、窗口内部", dock.top + y < main.binding.coordinator.height)
        return center(page).first to y
    }

    private fun assertExitReleaseCommits(exit: Gesture.(Page) -> Pair<Float, Float>) {
        val g = windowGesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.assertSelected(Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.exit(Page.SETTINGS))
        g.assertSelected(Page.SETTINGS)
        assertTrue("越界仍只预览，松手才导航", g.requests.isEmpty())
        assertSame(original, g.main.hostedFragment<Fragment>())
        g.send(MotionEvent.ACTION_UP, g.exit(Page.GROUPS))
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
        g.drag(Page.GROUPS, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.GROUPS.navId, Page.ROUTES.navId), g.requests)
        assertHosted<RouteFragment>(g.main)
    }

    @Test
    fun bottomExitReleaseCommitsAndTheNextDragWorks() = assertExitReleaseCommits { gestureLine(it) }

    @Test
    fun releasingAboveTheDockCommitsAndTheNextDragWorks() = assertExitReleaseCommits { center(it).first to -1f }

    @Test
    fun bottomExitAndReentryFollowTheCurrentFingerUntilRelease() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.dock.width + 1f to g.dock.height / 2f)
        g.assertSelected(Page.SETTINGS)
        repeat(3) {
            g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
            g.assertSelected(Page.SETTINGS)
            g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.GROUPS))
            g.assertSelected(Page.GROUPS)
            g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
            g.assertSelected(Page.GROUPS)
            assertTrue("滑出、滑回期间都不导航", g.requests.isEmpty())
        }
        // 最后的 UP 也要重新命中，不能提交上一个 MOVE 的旧目标。
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun downwardExitBeforeDragCanResumeOnReentry() {
        val g = windowGesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.CONFIG))
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        g.assertSelected(Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
        g.assertSelected(Page.GROUPS)
        assertTrue("滑回前后都只预览", g.requests.isEmpty())
        assertSame(original, g.main.hostedFragment<Fragment>())
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun draggingAlongTheGestureLineKeepsFollowingWithoutReentry() {
        val g = windowGesture()
        val original = g.main.hostedFragment<Fragment>()
        ui.enableAnimationFrames()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        repeat(2) {
            for (page in listOf(Page.SETTINGS, Page.GROUPS, Page.CONFIG, Page.ROUTES)) {
                g.send(MotionEvent.ACTION_MOVE, g.gestureLine(page))
                g.assertSelected(page)
                assertTrue("手指留在手势区域来回拖动时不提前切页", g.requests.isEmpty())
                assertSame(original, g.main.hostedFragment<Fragment>())
                advanceFrames(45)
                assertEquals("胶囊跟随横向目标，不跳回当前页", g.item(page).left.toFloat(), g.pill.translationX, 1f)
            }
        }
        g.send(MotionEvent.ACTION_UP, g.gestureLine(Page.GROUPS))
        advanceFrames(90)
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
        g.drag(Page.GROUPS, Page.ROUTES)
        advanceFrames(90)
        g.assertSelected(Page.ROUTES)
        assertHosted<RouteFragment>(g.main)
        assertEquals(listOf(Page.GROUPS.navId, Page.ROUTES.navId), g.requests)
    }

    @Test
    fun diagonalMotionInsideAnItemDoesNotDisableLaterDrag() {
        val g = windowGesture()
        val (x, y) = g.center(Page.GROUPS)
        val slop = ViewConfiguration.get(g.main).scaledTouchSlop
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        // 起手在按钮内略偏向下，后续才明确横向拖动。
        g.send(MotionEvent.ACTION_MOVE, x + slop + 1f to y + slop + 2f)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.assertSelected(Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun tapsInTheDockPaddingStillReachTheAdjacentItem() {
        val g = windowGesture()
        g.tap(g.center(Page.GROUPS).first to 1f)
        g.assertSelected(Page.GROUPS)
        g.tap(g.center(Page.ROUTES).first to g.dock.height - 1f)
        g.assertSelected(Page.ROUTES)
        g.tap(g.dock.width - 1f to g.dock.height / 2f)
        g.assertSelected(Page.SETTINGS)
        assertEquals(listOf(Page.GROUPS.navId, Page.ROUTES.navId, Page.SETTINGS.navId), g.requests)
    }

    @Test
    fun secondFingerBeforeDragCancelsThePendingTap() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.addSecondPointer(Page.GROUPS)
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        g.assertSelected(Page.CONFIG)
        assertTrue("第二指取消整个触摸，不能落回子按钮的点击", g.requests.isEmpty())
        g.tap(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun focusLossBeforeDragCancelsThePendingTap() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.loseAndRegainFocus()
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        g.assertSelected(Page.CONFIG)
        assertTrue("恢复焦点不能提交失焦前的按压", g.requests.isEmpty())
        g.tap(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun rapidTapsAndBottomReentryDuringTransitionKeepTheLastPage() {
        val g = windowGesture(settleAfterEvent = false)
        ui.enableAnimationFrames()
        g.tap(Page.GROUPS)
        UiSettle.settle()
        advanceFrames(6)
        assertTrue("前置条件：页面还在转场", g.main.hostedFragment<Fragment>().requireView().translationX != 0f)
        // 连续事件之间不等待消息队列清空，覆盖转场中的点击、滑出、滑回及再次点击。
        g.tap(Page.SETTINGS)
        g.send(MotionEvent.ACTION_DOWN, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.tap(Page.CONFIG)
        UiSettle.settle()
        advanceFrames(90)
        g.assertSelected(Page.CONFIG)
        assertEquals(listOf(Page.GROUPS, Page.SETTINGS, Page.ROUTES, Page.CONFIG).map { it.navId }, g.requests)
        assertHosted<ConfigurationFragment>(g.main)
        assertEquals(1, g.main.binding.fragmentHolder.childCount)
        assertEquals(g.item(Page.CONFIG).left.toFloat(), g.pill.translationX, 0.1f)
    }

    @Test
    fun systemCancelAtBottomDiscardsTheRestOfTheGesture() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        g.send(MotionEvent.ACTION_CANCEL, g.gestureLine(Page.SETTINGS))
        g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.CONFIG)
        assertTrue(g.requests.isEmpty())
        g.drag(Page.GROUPS, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun lostWindowFocusDiscardsTheGestureEvenWithoutCancel() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        // 系统手势接管窗口后，旧手势不能在应用恢复焦点时继续提交。
        g.loseAndRegainFocus()
        g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.CONFIG)
        assertTrue(g.requests.isEmpty())
        g.tap(Page.GROUPS)
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
    }

    @Test
    fun newDownDoesNotKeepAnUnfinishedDragPreview() {
        val g = gesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        // 输入流中断后重新按下：旧预选不能覆盖这次正常点击的选中项。
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.assertSelected(Page.CONFIG)
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
    }

    @Test
    fun nextPressDoesNotSnapAnAlreadyCommittedHighlightAnimation() {
        val g = windowGesture()
        ui.enableAnimationFrames()
        g.tap(Page.SETTINGS)
        advanceFrames(8)
        val before = g.pill.translationX
        assertTrue("前置条件：已提交的选中动画仍在移动", abs(before - g.item(Page.SETTINGS).left) > 1f)
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        assertEquals("正常的新按压不让高亮突然跳到终点", before, g.pill.translationX, 0f)
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        advanceFrames(90)
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.SETTINGS.navId, Page.GROUPS.navId), g.requests)
    }

    @Test
    fun interruptedBottomExitRestoresTheVisibleHighlightImmediately() {
        val g = windowGesture()
        ui.enableAnimationFrames()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        advanceFrames(8)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        advanceFrames(2)
        assertTrue("前置条件：滑出后胶囊仍在预选项一侧", abs(g.pill.translationX - g.item(Page.CONFIG).left) > 1f)
        g.loseAndRegainFocus()
        g.assertSelected(Page.CONFIG)
        assertEquals("恢复焦点前高亮已回到实际页面", g.item(Page.CONFIG).left.toFloat(), g.pill.translationX, 0f)
        for (page in listOf(Page.CONFIG, Page.GROUPS, Page.ROUTES, Page.SETTINGS)) {
            val item = g.item(page)
            assertEquals("图标填充与实际页面一致", if (page == Page.CONFIG) 1f else 0f,
                item.findViewById<View>(R.id.nav_dock_icon_fill).alpha, 0f)
            assertEquals("没有残留缩放", 1f, item.findViewById<View>(R.id.nav_dock_icon).scaleX, 0f)
        }
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun focusLossAfterCancelSnapsTheReturningSpring() {
        val g = windowGesture()
        ui.enableAnimationFrames()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        advanceFrames(12)
        g.send(MotionEvent.ACTION_CANCEL, Page.SETTINGS)
        advanceFrames(2)
        val target = g.item(Page.CONFIG)
        assertTrue("前置条件：胶囊正在弹回", abs(g.pill.translationX - target.left) > 1f)
        // 系统手势的真实顺序是先 CANCEL 再失焦：失焦时不再有活动触摸，仍要立即落位。
        g.main.window.decorView.dispatchWindowFocusChanged(false)
        assertEquals(target.left.toFloat(), g.pill.translationX, 0f)
        assertEquals(1f, target.findViewById<View>(R.id.nav_dock_icon_fill).alpha, 0f)
    }

    @Test
    fun rowRelayoutDoesNotInterruptTheRunningSpring() {
        val g = windowGesture()
        ui.enableAnimationFrames()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        advanceFrames(8)
        val before = g.pill.translationX
        assertTrue("前置条件：弹簧仍在移动", abs(before - g.item(Page.SETTINGS).left) > 1f)
        val row = g.item(Page.SETTINGS).parent as View
        row.forceLayout()
        row.measure(
            View.MeasureSpec.makeMeasureSpec(row.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(row.height, View.MeasureSpec.EXACTLY),
        )
        row.layout(row.left, row.top, row.right, row.bottom)
        assertEquals("位置没变的布局回调不打断弹簧", before, g.pill.translationX, 0f)
    }

    @Test
    fun tapLongPressKeyboardAndAccessibilityClickStillNavigate() {
        val g = gesture()
        g.tap(Page.GROUPS)
        g.assertSelected(Page.GROUPS)
        g.send(MotionEvent.ACTION_DOWN, Page.ROUTES)
        UiSettle.settle(advanceMs = ViewConfiguration.getLongPressTimeout().toLong() + 1)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertTrue(g.item(Page.SETTINGS).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        UiSettle.settle()
        g.assertSelected(Page.SETTINGS)
        assertEquals(listOf(Page.GROUPS.navId, Page.ROUTES.navId, Page.SETTINGS.navId), g.requests)
        val config = g.item(Page.CONFIG)
        assertTrue(config.requestFocusFromTouch())
        g.main.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        g.main.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        UiSettle.settle()
        g.assertSelected(Page.CONFIG)
        assertEquals(Page.CONFIG.navId, g.requests.last())
    }

    @Test
    fun heldDragPreviewsBothDirectionsAndOnlyCommitsOnRelease() {
        val g = gesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        UiSettle.settle(advanceMs = ViewConfiguration.getLongPressTimeout().toLong() + 1)
        for (page in listOf(Page.SETTINGS, Page.GROUPS, Page.ROUTES)) {
            g.send(MotionEvent.ACTION_MOVE, page)
            g.assertSelected(page)
            assertTrue("拖动期间不导航", g.requests.isEmpty())
            assertSame(original, g.main.hostedFragment<Fragment>())
        }
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
        assertHosted<RouteFragment>(g.main)
        g.assertSelected(Page.ROUTES)
    }

    @Test
    fun systemCancellationAndSecondFingerDiscardThePreview() {
        val g = gesture()
        for (secondFinger in listOf(false, true)) {
            g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
            g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
            if (secondFinger) {
                g.addSecondPointer(Page.SETTINGS)
                g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
                g.send(MotionEvent.ACTION_UP, Page.ROUTES)
            } else {
                g.send(MotionEvent.ACTION_CANCEL, Page.SETTINGS)
            }
            assertTrue(g.requests.isEmpty())
            g.assertSelected(Page.CONFIG)
            assertTrue("取消后没有残留的按压态", g.dock.descendants.none { it.isPressed })
        }
        g.tap(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun verticalExitReleaseDoesNotNavigate() {
        val g = windowGesture()
        val (x, y) = g.center(Page.CONFIG)
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, x to y - g.dock.height)
        g.send(MotionEvent.ACTION_UP, x to y - g.dock.height)
        assertTrue(g.requests.isEmpty())
        g.assertSelected(Page.CONFIG)
    }

    @Test
    fun rtlDragHitsTheVisuallyCorrectItem() {
        val g = gesture()
        g.main.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        UiSettle.settle()
        assertTrue("RTL 下配置在设置右侧", g.center(Page.CONFIG).first > g.center(Page.SETTINGS).first)
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.assertSelected(Page.SETTINGS)
        g.send(MotionEvent.ACTION_UP, Page.SETTINGS)
        assertEquals(listOf(Page.SETTINGS.navId), g.requests)
    }
}
