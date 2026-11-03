package io.nekohasekai.sagernet.uitest

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingChild
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.AboutFragment
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.LogcatFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.ui.ToolsFragment
import io.nekohasekai.sagernet.ui.WebviewFragment
import io.nekohasekai.sagernet.widget.ContentListener
import io.nekohasekai.sagernet.widget.ScrollHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

/**
 * U5e E1：主界面托管页面的滚动视图只走 hostMainScrollable（widget/MainContentHost.kt）。
 * - 每个托管页面的滚动视图都登记在 MainActivity 的 contentHost 里，底部留白 = 布局声明的留白 +
 *   系统栏插入区 + Dock / 状态卡片的让位（与改造前 padForSystemBars(bottomExtra = …) 的算法一致）；
 * - 顶部栏抬升跟着滚动走（单页直接绑顶部栏、从祖先找顶部栏、分页宿主的控制器按当前页判断三种）；
 * - 滚动分发器每个视图只有一个，宿主级订阅者与视图级订阅者（含抬升）都收到同一次滚动；
 * - 页面切换时结构变化事件到达，登记的视图集合随当前页变。
 * Dashboard 是登记的例外：WebView 容器不发嵌套滚动，登记为不发滚动事件。
 * 不截图，所有平台都跑。
 */
class MainContentHostTest : UiTestBase() {

    private fun ActivityController<MainActivity>.go(page: Int): Fragment {
        get().displayFragmentWithId(page)
        UiSettle.settle()
        return hostedFragment<Fragment>()
    }

    private fun Fragment.child(id: Int) = view!!.findViewById<View>(id)

    /** 布局文件里声明的底部内边距：新 inflate 一份读，不依赖被测代码 */
    private fun declaredBottom(main: MainActivity, layout: Int, id: Int?): Int {
        val root = LayoutInflater.from(main).inflate(layout, FrameLayout(main), false)
        return (if (id == null) root else root.findViewById(id)).paddingBottom
    }

    private fun assertHosted(main: MainActivity, scroll: View, declaredBottom: Int) {
        val host = main.contentHost
        assertTrue("${scroll.javaClass.simpleName} 没有登记到 contentHost", scroll in host.hostedViews)
        assertTrue("嵌套滚动没开", scroll.isNestedScrollingEnabled)
        assertTrue("${scroll.javaClass.simpleName} 不是 NestedScrollingChild", scroll is NestedScrollingChild)
        val clearance = host.bottomClearance()
        assertTrue("Dock 的让位应大于 0（$clearance）", clearance > 0)
        assertEquals(
            "${scroll.javaClass.simpleName} 的底部留白 = 声明 + 插入区 + 让位",
            declaredBottom + insetBottom(scroll) + clearance, scroll.paddingBottom,
        )
        assertFalse("clipToPadding 应关闭，内容才能滚进留白区", (scroll as android.view.ViewGroup).clipToPadding)
    }

    // ---------------------------------------------------------------- 底部留白与登记

    @Test
    fun configListIsHosted() {
        val main = ui.launchMain(30)
        val config = main.go(Page.CONFIG.navId) as ConfigurationFragment
        val list = config.getCurrentGroupFragment()!!.configurationListView
        // 主界面的节点列表：底部留白与顶部相同
        assertHosted(main.get(), list, list.paddingTop)
    }

    @Test
    fun groupListIsHosted() {
        val main = ui.launchMain(30)
        val group = main.go(Page.GROUPS.navId) as GroupFragment
        assertHosted(main.get(), group.groupListView, declaredBottom(main.get(), R.layout.layout_group, R.id.group_list))
    }

    @Test
    fun routeListIsHosted() {
        val main = ui.launchMain(30)
        val route = main.go(Page.ROUTES.navId) as RouteFragment
        assertHosted(main.get(), route.ruleListView, declaredBottom(main.get(), R.layout.layout_route, R.id.route_list))
    }

    @Test
    fun settingsListIsHosted() {
        val main = ui.launchMain(30)
        val settings = main.go(Page.SETTINGS.navId) as SettingsFragment
        val list = (settings.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment).listView
        assertHosted(main.get(), list, 0)
    }

    @Test
    fun aboutLogcatAndToolsPagesAreHosted() {
        val main = ui.launchMain(30)
        val about = main.go(R.id.nav_about) as AboutFragment
        assertHosted(main.get(), about.child(R.id.about_scroll), declaredBottom(main.get(), R.layout.layout_about, R.id.about_scroll))

        val logcat = main.go(R.id.nav_logcat) as LogcatFragment
        assertHosted(main.get(), logcat.child(R.id.scroolview), declaredBottom(main.get(), R.layout.layout_logcat, R.id.scroolview))

        val tools = main.go(R.id.nav_tools) as ToolsFragment
        val network = tools.childFragmentManager.findFragmentByTag("f0")!!.view!!
        assertHosted(main.get(), network, declaredBottom(main.get(), R.layout.layout_network, null))
    }

    @Test
    fun backupPageIsHosted() {
        val main = ui.launchMain(30)
        val tools = main.go(R.id.nav_tools) as ToolsFragment
        // 切到第二页（备份）
        tools.view!!.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.tools_pager).setCurrentItem(1, false)
        UiSettle.settle()
        val backup = tools.childFragmentManager.findFragmentByTag("f1")!!.view!!
        assertHosted(main.get(), backup, declaredBottom(main.get(), R.layout.layout_backup, null))
    }

    @Test
    fun dashboardIsTheDeclaredException() {
        val main = ui.launchMain(30)
        val dashboard = main.go(R.id.nav_traffic) as WebviewFragment
        val container = dashboard.child(R.id.webview_container)
        val host = main.get().contentHost
        assertTrue("Dashboard 的容器也要让开 Dock", container in host.hostedViews)
        assertFalse("WebView 容器不是嵌套滚动的子视图", container is NestedScrollingChild)
        assertEquals(
            "Dashboard 容器的底部留白也 = 声明 + 插入区 + 让位",
            declaredBottom(main.get(), R.layout.layout_webview, R.id.webview_container) +
                insetBottom(container) + host.bottomClearance(),
            container.paddingBottom,
        )
    }

    // ---------------------------------------------------------------- 顶部栏抬升

    private fun shortScreen() = RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h300dp-port-notnight-xhdpi")

    private fun Fragment.appBar() = child(R.id.appbar) as AppBarLayout

    @Test
    fun liftFollowsScrollWithDirectAppBar() {
        shortScreen()
        val main = ui.launchMain(30)
        val about = main.go(R.id.nav_about) as AboutFragment
        val scroll = about.child(R.id.about_scroll)
        val bar = about.appBar()
        assertFalse("前置条件：在顶部时不抬升", bar.isLifted)
        scroll.scrollBy(0, 300)
        UiSettle.settle()
        assertTrue("前置条件：页面可滚动", scroll.canScrollVertically(-1))
        assertTrue("滚离顶部后抬升", bar.isLifted)
        scroll.scrollTo(0, 0)
        UiSettle.settle()
        assertFalse("回到顶部还原", bar.isLifted)
    }

    @Test
    fun liftFollowsScrollWithAncestorAppBar() {
        shortScreen()
        val main = ui.launchMain(30)
        val settings = main.go(Page.SETTINGS.navId) as SettingsFragment
        val list = (settings.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment).listView
        val bar = settings.appBar()
        assertFalse(bar.isLifted)
        list.scrollBy(0, 300)
        UiSettle.settle()
        assertTrue("滚离顶部后抬升", bar.isLifted)
    }

    @Test
    fun liftControllerFollowsCurrentConfigPage() {
        val main = ui.launchMain(30)
        val config = main.go(Page.CONFIG.navId) as ConfigurationFragment
        val list = config.getCurrentGroupFragment()!!.configurationListView
        val bar = config.appBar()
        assertFalse(bar.isLifted)
        list.scrollBy(0, 1500)
        UiSettle.settle()
        assertTrue("当前页滚离顶部后抬升", bar.isLifted)
        list.scrollBy(0, -100000)
        UiSettle.settle()
        assertFalse("回到顶部还原", bar.isLifted)
    }

    @Test
    fun liftControllerFollowsCurrentToolsPage() {
        shortScreen()
        val main = ui.launchMain(30)
        val tools = main.go(R.id.nav_tools) as ToolsFragment
        val bar = tools.appBar()
        val pager = tools.child(R.id.tools_pager) as androidx.viewpager2.widget.ViewPager2
        val network = tools.childFragmentManager.findFragmentByTag("f0")!!.view!!
        assertFalse("前置条件：第一页（网络工具）在顶部", network.canScrollVertically(-1))
        assertFalse(bar.isLifted)

        pager.setCurrentItem(1, false)
        UiSettle.settle()
        val backup = tools.childFragmentManager.findFragmentByTag("f1")!!.view!!
        backup.scrollBy(0, 300)
        UiSettle.settle()
        assertTrue("前置条件：第二页（备份）可滚动", backup.canScrollVertically(-1))
        assertTrue("第二页滚离顶部后抬升", bar.isLifted)

        // 回到在顶部的第一页：按当前页判断，不再抬升
        pager.setCurrentItem(0, false)
        UiSettle.settle()
        assertFalse(bar.isLifted)
        // 非当前页（备份）继续滚动，不影响当前页的判断
        backup.scrollBy(0, 10)
        UiSettle.settle()
        assertFalse(bar.isLifted)
    }

    // ---------------------------------------------------------------- 滚动分发器与托管视图

    private class Counter : ContentListener {
        var scrolled = 0
        override fun onContentScrolled(source: View) {
            scrolled++
        }
    }

    @Test
    fun hubSubscribersAllReceiveTheSameScroll() {
        shortScreen()
        val main = ui.launchMain(30)
        val about = main.go(R.id.nav_about) as AboutFragment
        val scroll = about.child(R.id.about_scroll)

        val hubA = Counter()
        val hubB = Counter()
        val hub = ScrollHub.of(scroll)
        assertSame("每个视图只有一个分发器", hub, ScrollHub.of(scroll))
        hub.addListener(hubA)
        hub.addListener(hubA) // 同一个对象重复订阅无效
        hub.addListener(hubB)

        scroll.scrollBy(0, 100)
        UiSettle.settle()
        assertEquals("重复订阅只收一次", 1, hubA.scrolled)
        assertEquals(1, hubB.scrolled)
        // 抬升是同一个分发器的又一个订阅者，没有被后来的订阅顶掉
        assertTrue(about.appBar().isLifted)

        hub.removeListener(hubB)
        scroll.scrollBy(0, 100)
        UiSettle.settle()
        assertEquals(2, hubA.scrolled)
        assertEquals("摘掉之后不再收到", 1, hubB.scrolled)
    }

    @Test
    fun hostTracksTheHostedViewOfTheCurrentPage() {
        val main = ui.launchMain(30)
        main.go(Page.GROUPS.navId)
        val host = main.get().contentHost
        val oldView = host.hostedViews.single { it is RecyclerView && it.id == R.id.group_list }

        val route = main.go(Page.ROUTES.navId) as RouteFragment
        assertNotNull(route.ruleListView)
        assertTrue("新页面的滚动视图登记了", route.ruleListView in host.hostedViews)
        assertFalse("被替换的页面摘出了托管视图", oldView in host.hostedViews)
    }
}
