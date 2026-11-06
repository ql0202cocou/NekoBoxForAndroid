package io.nekohasekai.sagernet.uitest

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.descendants
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 经真实触摸分发验证拖选：预览不导航，松手只提交一次，取消后恢复实际页面。 */
class NavDockTouchTest : UiTestBase() {

    private class Gesture(val main: MainActivity) {
        val dock = main.binding.dock
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

        fun send(action: Int, point: Pair<Float, Float>) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                dock.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            UiSettle.settle()
        }

        fun addSecondPointer(page: Page) {
            val (x, y) = center(page)
            val pointers = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    this.x = x + index
                    this.y = y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, pointers, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                dock.dispatchTouchEvent(event)
                event.action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                dock.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            UiSettle.settle()
        }

        fun assertSelected(page: Page) =
            assertEquals(main.getString(page.title), main.dockSelection())

        fun tap(page: Page) {
            send(MotionEvent.ACTION_DOWN, page)
            send(MotionEvent.ACTION_UP, page)
        }
    }

    private fun gesture() = Gesture(ui.launchMain().get())

    @Test
    fun tapLongPressAndAccessibilityClickStillNavigate() {
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
        assertTrue(g.main.hostedFragment<Fragment>() is RouteFragment)
        g.assertSelected(Page.ROUTES)
    }

    @Test
    fun releasingOutsideCancelsAndTheNextTapWorks() {
        val g = gesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        val outside = g.center(Page.SETTINGS).first to -1f
        g.send(MotionEvent.ACTION_MOVE, outside)
        g.assertSelected(Page.CONFIG)
        g.send(MotionEvent.ACTION_UP, outside)
        assertTrue(g.requests.isEmpty())
        assertSame(original, g.main.hostedFragment<Fragment>())
        g.tap(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
    }

    @Test
    fun returningInsideUsesTheReleaseTarget() {
        val g = gesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.dock.width + 1f to g.dock.height / 2f)
        g.assertSelected(Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
        g.assertSelected(Page.GROUPS)
        // 最后的 UP 也要重新命中，不能提交上一个 MOVE 的旧目标。
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
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
    fun verticalSwipeDoesNotBecomeNavigationWhenItTurnsSideways() {
        val g = gesture()
        val (x, y) = g.center(Page.CONFIG)
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, x to y - g.dock.height)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_UP, Page.SETTINGS)
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
