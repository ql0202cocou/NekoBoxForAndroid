package io.nekohasekai.sagernet.uitest

import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ui.AboutFragment
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.widget.safeDrawingTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * U5e E2：状态卡片的出现 / 消失 / 被滚动收起时，主界面列表的底部留白跟着卡片平滑变化，而不是突跳：
 * - 留白里卡片的那一份 = （卡片高度 + 间隔）× [io.nekohasekai.sagernet.widget.StatsBar.clearanceFraction]，
 *   展开为 1，未连接 / 被滚动收起为 0（原先卡片被收起后列表底部仍多留一块约 100dp 的空白）；
 * - 每种状态下列表的最后一项都能完整滚到可见控件（展开时是卡片，否则是 Dock）上方；
 * - 带动画时留白逐帧过渡、单调变化、最终到位；动画关闭时一步到终态。
 * 不截图，所有平台都跑。
 */
class StatsClearanceTest : UiTestBase() {

    private fun launchMain(): ActivityController<MainActivity> {
        val fixtures = UiFixtures.install()
        UiFixtures.addProfiles(fixtures.normalGroup, 30)
        return ui.launch<MainActivity>()
    }

    private val ActivityController<MainActivity>.stats get() = get().binding.stats

    private fun ActivityController<MainActivity>.connect(advanceMs: Long = 500) {
        get().stateChanged(BaseService.State.Connected, null, null)
        // 状态卡片在 delay(100) 之后才展开
        UiSettle.settle(advanceMs = advanceMs)
    }

    private fun ActivityController<MainActivity>.list(): RecyclerView {
        val config = get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
        return config.getCurrentGroupFragment()!!.configurationListView
    }

    private fun dockClearance(main: MainActivity) = main.resources.getDimensionPixelSize(R.dimen.nav_dock_clearance)
    private fun gap(main: MainActivity) = main.resources.getDimensionPixelSize(R.dimen.stats_bar_gap)

    private fun insetBottom(view: View) =
        ViewCompat.getRootWindowInsets(view)?.getInsets(safeDrawingTypes)?.bottom ?: 0

    private fun top(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[1]

    /** 列表底部内边距里除了页面自己声明的那一份（= 顶部内边距）和系统栏插入区之外的部分 */
    private fun clearanceIn(list: RecyclerView) = list.paddingBottom - list.paddingTop - insetBottom(list)

    private fun expectedClearance(main: MainActivity, expanded: Boolean) =
        dockClearance(main) + if (expanded) main.binding.stats.height + gap(main) else 0

    /** 列表滚到底后，最后一项的底边在可见控件顶边之上；返回控件顶边与最后一项底边的距离（px） */
    private fun gapAboveControls(main: MainActivity, list: RecyclerView): Int {
        list.scrollBy(0, 1_000_000)
        UiSettle.settle()
        val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        assertEquals("前置条件：滚到了最后一项", list.adapter!!.itemCount - 1, manager.findLastVisibleItemPosition())
        val last = list.getChildAt(list.childCount - 1)
        val lastBottom = top(last) + last.height
        val stats = main.binding.stats
        val controlsTop = if (stats.isExpanded) top(stats) else top(main.binding.dock)
        assertTrue("最后一项（底边 $lastBottom）被控件（顶边 $controlsTop）盖住", lastBottom <= controlsTop)
        return controlsTop - lastBottom
    }

    @Test
    fun hiddenCardLeavesOnlyTheDockClearance() {
        val main = launchMain()
        val list = main.list()
        assertEquals(0f, main.stats.clearanceFraction, 0f)
        assertEquals(expectedClearance(main.get(), false), main.get().bottomControlsClearance())
        assertEquals(main.get().bottomControlsClearance(), clearanceIn(list))
        val gap = gapAboveControls(main.get(), list)
        assertTrue("未连接时最后一项离 Dock 不该多出一张卡片的空白（$gap）", gap < main.stats.height)
    }

    @Test
    fun shownCardAddsItsOwnClearance() {
        val main = launchMain()
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
        val main = launchMain()
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
        val main = launchMain()
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
        val main = launchMain()
        main.get().displayFragmentWithId(R.id.nav_about)
        UiSettle.settle()
        val about = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as AboutFragment
        val scroll = about.view!!.findViewById<View>(R.id.about_scroll)
        val base = scroll.paddingBottom - insetBottom(scroll) - dockClearance(main.get())
        main.connect()
        assertEquals(base + insetBottom(scroll) + expectedClearance(main.get(), true), scroll.paddingBottom)
        main.stats.behavior.slideDown(main.stats, true)
        UiSettle.settle(advanceMs = 500)
        assertEquals(base + insetBottom(scroll) + expectedClearance(main.get(), false), scroll.paddingBottom)
    }

    @Test
    fun clearanceGlidesWhenAnimationsAreOn() {
        ui.enableAnimations()
        val main = launchMain()
        val list = main.list()
        val seen = ArrayList<Int>()
        main.get().binding.stats.onClearanceChanged.let { original ->
            // 宿主已接了回调：在它之后再记录每一帧的列表底部留白
            main.get().binding.stats.onClearanceChanged = {
                original?.invoke()
                seen += clearanceIn(list)
            }
        }
        main.connect(advanceMs = 1000)
        assertEquals("最终到位", expectedClearance(main.get(), true), clearanceIn(list))
        val dock = expectedClearance(main.get(), false)
        assertTrue("至少有一个中间帧（$seen）", seen.any { it > dock && it < expectedClearance(main.get(), true) })
        assertEquals("单调不减", seen.sorted(), seen)

        seen.clear()
        main.stats.behavior.slideDown(main.stats, true)
        UiSettle.settle(advanceMs = 1000)
        assertEquals(dock, clearanceIn(list))
        assertTrue("收起也有中间帧（$seen）", seen.any { it > dock && it < expectedClearance(main.get(), true) })
        assertEquals("单调不增", seen.sortedDescending(), seen)
    }

    @Test
    fun clearanceJumpsToTheEndWhenAnimationsAreOff() {
        // 规则默认把动画时长缩放设为 0
        val main = launchMain()
        val list = main.list()
        val seen = ArrayList<Int>()
        val original = main.stats.onClearanceChanged
        main.stats.onClearanceChanged = {
            original?.invoke()
            seen += clearanceIn(list)
        }
        main.connect()
        assertEquals("一步到位，没有中间帧", listOf(expectedClearance(main.get(), true)), seen)
    }
}
