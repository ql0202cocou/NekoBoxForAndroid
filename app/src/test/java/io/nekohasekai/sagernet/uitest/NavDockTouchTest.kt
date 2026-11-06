package io.nekohasekai.sagernet.uitest

import android.graphics.Rect
import android.os.Looper
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
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import kotlin.math.abs

/** 经真实触摸分发验证拖选：预览不导航，松手只提交一次，取消后恢复实际页面。 */
class NavDockTouchTest : UiTestBase() {

    @After
    fun restoreFrameClock() {
        // 取消后的弹簧回调要在本用例内清理，不能留到 Robolectric 重置时钟之后。
        ui.finishActivities()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        ShadowChoreographer.setPaused(false)
    }

    private class Gesture(
        val main: MainActivity,
        private val throughWindow: Boolean = false,
        private val settleAfterEvent: Boolean = true,
    ) {
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

        private fun dispatch(event: MotionEvent) {
            if (!throughWindow) {
                dock.dispatchTouchEvent(event)
                return
            }
            val location = IntArray(2)
            dock.getLocationInWindow(location)
            val windowEvent = MotionEvent.obtain(event)
            try {
                windowEvent.offsetLocation(location[0].toFloat(), location[1].toFloat())
                main.dispatchTouchEvent(windowEvent)
            } finally {
                windowEvent.recycle()
            }
        }

        fun send(action: Int, point: Pair<Float, Float>) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, point.first, point.second, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                dispatch(event)
            } finally {
                event.recycle()
            }
            if (settleAfterEvent) UiSettle.settle()
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
                dispatch(event)
                event.action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                dispatch(event)
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

    @Test
    fun bottomExitReleaseCommitsAndTheNextDragWorks() {
        val g = windowGesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.assertSelected(Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        g.assertSelected(Page.SETTINGS)
        assertTrue("越界仍只预览，松手才导航", g.requests.isEmpty())
        assertSame(original, g.main.hostedFragment<Fragment>())
        g.send(MotionEvent.ACTION_UP, g.gestureLine(Page.GROUPS))
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.assertSelected(Page.ROUTES)
        assertEquals(listOf(Page.GROUPS.navId, Page.ROUTES.navId), g.requests)
        assertTrue(g.main.hostedFragment<Fragment>() is RouteFragment)
    }

    @Test
    fun bottomExitAndReentryFollowTheCurrentFingerUntilRelease() {
        val g = windowGesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        repeat(3) {
            g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
            g.assertSelected(Page.SETTINGS)
            g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.GROUPS))
            g.assertSelected(Page.GROUPS)
            g.send(MotionEvent.ACTION_MOVE, Page.GROUPS)
            g.assertSelected(Page.GROUPS)
            assertTrue("滑出、滑回期间都不导航", g.requests.isEmpty())
        }
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
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations()
        val pill = (g.dock.getChildAt(0) as ViewGroup).getChildAt(0)
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        repeat(2) {
            for (page in listOf(Page.SETTINGS, Page.GROUPS, Page.CONFIG, Page.ROUTES)) {
                g.send(MotionEvent.ACTION_MOVE, g.gestureLine(page))
                g.assertSelected(page)
                assertTrue("手指留在手势区域来回拖动时不提前切页", g.requests.isEmpty())
                assertSame(original, g.main.hostedFragment<Fragment>())
                repeat(45) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                assertEquals("胶囊跟随横向目标，不跳回当前页", g.item(page).left.toFloat(), pill.translationX, 1f)
            }
        }
        g.send(MotionEvent.ACTION_UP, g.gestureLine(Page.GROUPS))
        repeat(90) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.GROUPS.navId), g.requests)
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        repeat(90) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        g.assertSelected(Page.ROUTES)
        assertTrue(g.main.hostedFragment<Fragment>() is RouteFragment)
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
        val top = g.center(Page.GROUPS).first to 1f
        g.send(MotionEvent.ACTION_DOWN, top)
        g.send(MotionEvent.ACTION_UP, top)
        g.assertSelected(Page.GROUPS)
        val bottom = g.center(Page.ROUTES).first to g.dock.height - 1f
        g.send(MotionEvent.ACTION_DOWN, bottom)
        g.send(MotionEvent.ACTION_UP, bottom)
        g.assertSelected(Page.ROUTES)
        val end = g.dock.width - 1f to g.dock.height / 2f
        g.send(MotionEvent.ACTION_DOWN, end)
        g.send(MotionEvent.ACTION_UP, end)
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
        g.main.window.decorView.dispatchWindowFocusChanged(false)
        g.main.window.decorView.dispatchWindowFocusChanged(true)
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        g.assertSelected(Page.CONFIG)
        assertTrue("恢复焦点不能提交失焦前的按压", g.requests.isEmpty())
        g.tap(Page.ROUTES)
        assertEquals(listOf(Page.ROUTES.navId), g.requests)
    }

    @Test
    fun rapidTapsAndBottomReentryDuringTransitionKeepTheLastPage() {
        val g = windowGesture(settleAfterEvent = false)
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations()
        g.tap(Page.GROUPS)
        UiSettle.settle()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        assertTrue("前置条件：页面还在转场", g.main.hostedFragment<Fragment>().requireView().translationX != 0f)
        // 连续事件之间不等待消息队列清空，覆盖转场中的点击、滑出、滑回及再次点击。
        g.tap(Page.SETTINGS)
        g.send(MotionEvent.ACTION_DOWN, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
        g.tap(Page.CONFIG)
        UiSettle.settle()
        repeat(90) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        g.assertSelected(Page.CONFIG)
        assertEquals(listOf(Page.GROUPS, Page.SETTINGS, Page.ROUTES, Page.CONFIG).map { it.navId }, g.requests)
        assertTrue(g.main.hostedFragment<Fragment>() is ConfigurationFragment)
        assertEquals(1, g.main.binding.fragmentHolder.childCount)
        val pill = (g.dock.getChildAt(0) as ViewGroup).getChildAt(0)
        assertEquals(g.item(Page.CONFIG).left.toFloat(), pill.translationX, 0.1f)
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
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        g.send(MotionEvent.ACTION_MOVE, Page.ROUTES)
        g.send(MotionEvent.ACTION_UP, Page.ROUTES)
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
        g.main.window.decorView.dispatchWindowFocusChanged(false)
        g.main.window.decorView.dispatchWindowFocusChanged(true)
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
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations()
        val pill = (g.dock.getChildAt(0) as ViewGroup).getChildAt(0)
        g.tap(Page.SETTINGS)
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val before = pill.translationX
        assertTrue("前置条件：已提交的选中动画仍在移动", abs(before - g.item(Page.SETTINGS).left) > 1f)
        g.send(MotionEvent.ACTION_DOWN, Page.GROUPS)
        assertEquals("正常的新按压不让高亮突然跳到终点", before, pill.translationX, 0f)
        g.send(MotionEvent.ACTION_UP, Page.GROUPS)
        repeat(90) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        g.assertSelected(Page.GROUPS)
        assertEquals(listOf(Page.SETTINGS.navId, Page.GROUPS.navId), g.requests)
    }

    @Test
    fun interruptedBottomExitRestoresTheVisibleHighlightImmediately() {
        val g = windowGesture()
        ShadowChoreographer.setPaused(true)
        ui.enableAnimations()
        val pill = (g.dock.getChildAt(0) as ViewGroup).getChildAt(0)
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        g.send(MotionEvent.ACTION_MOVE, g.gestureLine(Page.SETTINGS))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        assertTrue("前置条件：滑出后胶囊仍在预选项一侧", abs(pill.translationX - g.item(Page.CONFIG).left) > 1f)
        g.main.window.decorView.dispatchWindowFocusChanged(false)
        g.main.window.decorView.dispatchWindowFocusChanged(true)
        g.assertSelected(Page.CONFIG)
        assertEquals("恢复焦点前高亮已回到实际页面", g.item(Page.CONFIG).left.toFloat(), pill.translationX, 0f)
        for (page in listOf(Page.CONFIG, Page.GROUPS, Page.ROUTES, Page.SETTINGS)) {
            val item = g.item(page)
            assertEquals("图标填充与实际页面一致", if (page == Page.CONFIG) 1f else 0f,
                item.findViewById<View>(R.id.nav_dock_icon_fill).alpha, 0f)
            assertEquals("没有残留缩放", 1f, item.findViewById<View>(R.id.nav_dock_icon).scaleX, 0f)
        }
        assertTrue(g.requests.isEmpty())
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
        assertTrue(g.main.hostedFragment<Fragment>() is RouteFragment)
        g.assertSelected(Page.ROUTES)
    }

    @Test
    fun releasingAboveTheDockCommitsAndTheNextTapWorks() {
        val g = gesture()
        val original = g.main.hostedFragment<Fragment>()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        val outside = g.center(Page.SETTINGS).first to -1f
        g.send(MotionEvent.ACTION_MOVE, outside)
        g.assertSelected(Page.SETTINGS)
        assertTrue(g.requests.isEmpty())
        assertSame(original, g.main.hostedFragment<Fragment>())
        g.send(MotionEvent.ACTION_UP, outside)
        g.assertSelected(Page.SETTINGS)
        g.tap(Page.GROUPS)
        assertEquals(listOf(Page.SETTINGS.navId, Page.GROUPS.navId), g.requests)
    }

    @Test
    fun returningInsideUsesTheReleaseTarget() {
        val g = gesture()
        g.send(MotionEvent.ACTION_DOWN, Page.CONFIG)
        g.send(MotionEvent.ACTION_MOVE, Page.SETTINGS)
        g.send(MotionEvent.ACTION_MOVE, g.dock.width + 1f to g.dock.height / 2f)
        g.assertSelected(Page.SETTINGS)
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
