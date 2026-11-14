package io.nekohasekai.sagernet.uitest

import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ui.AboutFragment
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * 状态卡片收放与列表留白分离：
 * - 卡片展开时目标留白包含卡片高度与间隔，收起后在安全位置回收；
 * - 每种状态下列表的最后一项都能完整滚到可见控件（展开时是卡片，否则是 Dock）上方；
 * - 卡片保持动画中间帧，列表留白到位后更新；动画关闭时直接更新。
 * 滚动期间的冻结与接近底部的延迟回收另见 SettingsScrollAnimationTest。
 * 不截图，所有平台都跑。
 */
class StatsClearanceTest : UiTestBase() {

    private val ActivityController<MainActivity>.stats get() = get().binding.stats

    private fun ActivityController<MainActivity>.connect(advanceMs: Long = 500) = get().connect(advanceMs)

    /** 打开关于页，返回它的滚动视图 */
    private fun ActivityController<MainActivity>.aboutScroll(): NestedScrollView {
        get().displayFragmentWithId(R.id.nav_about)
        UiSettle.settle()
        return hostedFragment<AboutFragment>().requireView().findViewById(R.id.about_scroll)
    }

    /** 在宿主处理完卡片进度回调之后再调 [block] */
    private fun ActivityController<MainActivity>.afterEachClearanceChange(block: () -> Unit) {
        val original = stats.onClearanceChanged
        stats.onClearanceChanged = { settled ->
            original?.invoke(settled)
            block()
        }
    }

    private fun ActivityController<MainActivity>.list(): RecyclerView {
        val config = hostedFragment<ConfigurationFragment>()
        return config.getCurrentGroupFragment()!!.configurationListView
    }

    private fun dockClearance(main: MainActivity) = main.resources.getDimensionPixelSize(R.dimen.nav_dock_clearance)
    private fun gap(main: MainActivity) = main.resources.getDimensionPixelSize(R.dimen.stats_bar_gap)

    /** 列表底部内边距里除了页面自己声明的那一份（= 顶部内边距）和系统栏插入区之外的部分 */
    private fun clearanceIn(list: RecyclerView) = list.paddingBottom - list.paddingTop - insetBottom(list)

    private fun expectedClearance(main: MainActivity, expanded: Boolean) =
        dockClearance(main) + if (expanded) main.binding.stats.height + gap(main) else 0

    @Test
    fun hiddenCardLeavesOnlyTheDockClearance() {
        val main = ui.launchMain(30)
        val list = main.list()
        assertEquals(0f, main.stats.clearanceFraction, 0f)
        assertEquals(expectedClearance(main.get(), false), main.get().bottomControlsClearance())
        assertEquals(main.get().bottomControlsClearance(), clearanceIn(list))
        val gap = gapAboveControls(main.get(), list)
        assertTrue("未连接时最后一项离 Dock 不该多出一张卡片的空白（$gap）", gap < main.stats.height)
    }

    @Test
    fun shownCardAddsItsOwnClearance() {
        val main = ui.launchMain(30)
        main.connect()
        assertTrue(main.stats.isExpanded)
        assertEquals(1f, main.stats.clearanceFraction, 0f)
        assertEquals(expectedClearance(main.get(), true), main.get().bottomControlsClearance())
        val list = main.list()
        assertEquals("列表底部留白 = 声明 + 插入区 + Dock + 卡片", main.get().bottomControlsClearance(), clearanceIn(list))
        gapAboveControls(main.get(), list)
    }

    @Test
    fun collapsedCardGivesItsClearanceBack() {
        val main = ui.launchMain(30)
        main.connect()
        val list = main.list()
        val behavior = main.stats.behavior
        // 列表滚动把卡片收起 / 拉出走的是 Behavior 的 slideDown / slideUp
        behavior.slideDown(main.stats, true)
        UiSettle.settle(advanceMs = 500)
        assertFalse(main.stats.isExpanded)
        assertTrue("已连接（允许出现）只是被收起", main.stats.allowShow)
        assertEquals(0f, main.stats.clearanceFraction, 0f)
        assertEquals("卡片被收起后不再多留空白", expectedClearance(main.get(), false), clearanceIn(list))
        val gap = gapAboveControls(main.get(), list)
        assertTrue("收起后最后一项离 Dock 不该多出一张卡片的空白（$gap）", gap < main.stats.height)

        behavior.slideUp(main.stats, true)
        UiSettle.settle(advanceMs = 500)
        assertTrue(main.stats.isExpanded)
        assertEquals(expectedClearance(main.get(), true), clearanceIn(list))
        gapAboveControls(main.get(), list)
    }

    @Test
    fun disconnectingReleasesTheClearance() {
        val main = ui.launchMain(30)
        main.connect()
        val list = main.list()
        assertEquals(expectedClearance(main.get(), true), clearanceIn(list))
        main.get().stateChanged(BaseService.State.Stopped, null, null)
        UiSettle.settle(advanceMs = 500)
        assertFalse(main.stats.allowShow)
        assertEquals(0f, main.stats.clearanceFraction, 0f)
        assertEquals(expectedClearance(main.get(), false), clearanceIn(list))
        gapAboveControls(main.get(), list)
    }

    @Test
    fun otherPagesFollowTheCardToo() {
        val main = ui.launchMain(30)
        val scroll = main.aboutScroll()
        val base = scroll.paddingBottom - insetBottom(scroll) - dockClearance(main.get())
        main.connect()
        assertEquals(base + insetBottom(scroll) + expectedClearance(main.get(), true), scroll.paddingBottom)
        main.stats.behavior.slideDown(main.stats, true)
        UiSettle.settle(advanceMs = 500)
        assertEquals(base + insetBottom(scroll) + expectedClearance(main.get(), false), scroll.paddingBottom)
    }

    @Test
    fun nestedScrollViewNearBottomRetainsItsPositionUntilSafeToReclaim() {
        val main = ui.launchMain(30)
        val scroll = main.aboutScroll()
        val base = scroll.paddingBottom - insetBottom(scroll) - dockClearance(main.get())
        main.connect()
        scroll.scrollBy(0, 100_000)
        UiSettle.settle()
        scroll.scrollBy(0, -20)
        val offset = scroll.scrollY
        assertTrue("前置条件：关于页已滚离顶部", offset > 0)
        ui.enableAnimationFrames()
        main.stats.behavior.slideDown(main.stats, true)
        UiSettle.advanceFrames(50)
        assertEquals("回收留白不能把 NestedScrollView 推回", offset, scroll.scrollY)
        val hidden = base + insetBottom(scroll) + expectedClearance(main.get(), false)
        assertTrue("末端保留必要空间", scroll.paddingBottom > hidden)
        scroll.scrollTo(0, 0)
        UiSettle.advanceFrames(3)
        assertEquals("回到安全位置后释放", hidden, scroll.paddingBottom)
    }

    @Test
    fun cardGlidesWhilePaddingUpdatesAfterItSettles() {
        ui.enableAnimations()
        val main = ui.launchMain(30)
        val list = main.list()
        val seen = ArrayList<Int>()
        val progress = ArrayList<Float>()
        // 同时观察卡片进度与列表留白，不能把平滑进度等同于安全的列表布局。
        main.afterEachClearanceChange {
            seen += clearanceIn(list)
            progress += main.stats.clearanceFraction
        }
        main.connect(advanceMs = 1000)
        assertEquals("最终到位", expectedClearance(main.get(), true), clearanceIn(list))
        val dock = expectedClearance(main.get(), false)
        assertTrue("卡片保留动画中间帧", progress.any { it > 0f && it < 1f })
        assertEquals("卡片单调展开", progress.sorted(), progress)
        assertEquals("留白只在到位后更新", listOf(dock, expectedClearance(main.get(), true)), seen.distinct())

        seen.clear()
        progress.clear()
        main.stats.behavior.slideDown(main.stats, true)
        UiSettle.settle(advanceMs = 1000)
        assertEquals(dock, clearanceIn(list))
        assertTrue("收起也保留动画中间帧", progress.any { it > 0f && it < 1f })
        assertEquals("卡片单调收起", progress.sortedDescending(), progress)
        assertEquals("安全位置的留白在到位后回收", listOf(expectedClearance(main.get(), true), dock), seen.distinct())
    }

    @Test
    fun clearanceJumpsToTheEndWhenAnimationsAreOff() {
        // 规则默认把动画时长缩放设为 0
        val main = ui.launchMain(30)
        val list = main.list()
        val seen = ArrayList<Int>()
        main.afterEachClearanceChange { seen += clearanceIn(list) }
        main.connect()
        assertEquals("一步到位，没有中间帧", listOf(expectedClearance(main.get(), true)), seen)
    }
}
