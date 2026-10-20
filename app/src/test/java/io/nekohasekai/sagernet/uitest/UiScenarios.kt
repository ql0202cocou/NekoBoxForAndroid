package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.net.Uri
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.BackupFragment
import java.io.File
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.ui.AssetsActivity
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.ConfigurationHolder
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileListFragment
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import moe.matsuri.nb4a.TempDatabase
import androidx.appcompat.view.menu.MenuBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/** 一个验收项的判定：[ok] 为真表示满足验收；[detail] 在不满足时说明差在哪 */
class Check(val ok: Boolean, val detail: String = "") {
    override fun toString() = if (ok) "满足" else "不满足：$detail"
}

enum class Change { RECREATE, ROTATE }

enum class Page(val navId: Int) {
    CONFIG(R.id.nav_configuration), GROUPS(R.id.nav_group), ROUTES(R.id.nav_route), SETTINGS(R.id.nav_settings),
}

/**
 * 行为用例（页面重建 / 旋转 / 关闭后回调 / 编辑器会话 / 入场动画）共用的场景。每个场景一个函数，返回 [Check]，
 * 自己不断言：满足验收的场景由各 *Test 断言为真，目前不满足的登记在 [KnownUiGaps]，由 KnownUiGapsTest 断言「仍不满足」。
 * 一个场景必须跑在自己的用例里（Robolectric 同一用例内的资源缓存会串）。
 */
class UiScenarios(private val ui: UiTestRule) {

    // ---------------------------------------------------------------- 公共

    private fun main(): ActivityController<MainActivity> {
        UiFixtures.install()
        return ui.launch<MainActivity>()
    }

    private fun MainActivity.fragment() = supportFragmentManager.findFragmentById(R.id.fragment_holder)

    private fun ActivityController<MainActivity>.go(page: Page) {
        if (page != Page.CONFIG) {
            get().displayFragmentWithId(page.navId)
            UiSettle.settle()
        }
    }

    /** 重建（recreate）或旋转（横屏），随后等界面安静。进程重建见编辑器的场景。前置条件不成立一律抛错，不返回「不满足」，免得已知缺口的探针空转 */
    private fun <A : Activity> ActivityController<A>.change(how: Change) {
        when (how) {
            Change.RECREATE -> recreate()
            Change.ROTATE -> {
                RuntimeEnvironment.setQualifiers("+land")
                configurationChange()
                // configurationChange 重建出来的 Activity 不会自己 attach 到窗口（实测：根视图 0×0、没有页面 Fragment），
                // 补一次 visible()，和 recreate() 之后的状态一致
                if (!get().window.decorView.isAttachedToWindow) visible()
            }
        }
        UiSettle.settle()
    }

    private fun View.textViewsById(id: Int): List<TextView> {
        val found = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.id == id) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return found
    }

    // Dock 里当前选中项的文字（选中项的外层视图 isSelected）
    private fun MainActivity.dockSelection(): String =
        binding.dock.textViewsById(R.id.nav_dock_label).filter { (it.parent as View).isSelected }
            .joinToString { it.text }

    private data class PageState(val fragment: String, val dock: String, val title: String)

    private fun MainActivity.pageState(): PageState {
        val fragment = fragment()
        val key = (fragment as? SettingsFragment)?.sectionKey?.let { "[$it]" } ?: ""
        return PageState(fragment!!.javaClass.simpleName + key, dockSelection(), (fragment as ToolbarFragment).toolbar.title.toString())
    }

    private fun same(what: String, before: Any?, after: Any?) =
        if (before == after) Check(true) else Check(false, "$what：之前 $before，之后 $after")

    // ------------------------------------------------------- 主界面页面重建

    /** 四个一级页面重建 / 旋转后：页面类型、Dock 选中项、标题不变 */
    fun mainPageSurvives(page: Page, how: Change): Check {
        val main = main()
        main.go(page)
        val before = main.get().pageState()
        main.change(how)
        return same("页面状态", before, main.get().pageState())
    }

    /** 配置页：选中的分组（Tab、ViewPager、DataStore）重建 / 旋转后不变 */
    fun configSelectedGroupSurvives(how: Change): Check {
        val main = main()
        val config = main.get().fragment() as ConfigurationFragment
        config.groupPager.currentItem = 1
        UiSettle.settle()
        val before = Triple(DataStore.selectedGroup, config.groupPager.currentItem, config.tabLayout.selectedTabPosition)
        if (before.second != 1) error("前置条件：没能切到第二个分组 $before")
        main.change(how)
        val after = (main.get().fragment() as ConfigurationFragment).let {
            Triple(DataStore.selectedGroup, it.groupPager.currentItem, it.tabLayout.selectedTabPosition)
        }
        return same("（选中分组 id，ViewPager 页，Tab 位置）", before, after)
    }

    private fun ConfigurationFragment.currentList(): RecyclerView =
        (getCurrentGroupFragment() as ProfileListFragment).listViewOrNull!!

    /** 配置页：节点列表滚到中间后重建 / 旋转，第一个可见项不变 */
    fun configListPositionSurvives(how: Change): Check {
        val fixtures = UiFixtures.install()
        UiFixtures.addProfiles(fixtures.normalGroup, 30)
        val main = ui.launch<MainActivity>()
        val list = (main.get().fragment() as ConfigurationFragment).currentList()
        list.scrollBy(0, 1500)
        UiSettle.settle()
        val before = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        if (before <= 0) error("前置条件：列表没有滚动（第一个可见项 $before）")
        main.change(how)
        val after = (main.get().fragment() as ConfigurationFragment).currentList()
        return same("第一个可见项", before, (after.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
    }

    /** 设置分类页重建 / 旋转后仍停在该分类页（页面、Dock 选中「设置」、标题） */
    fun settingsSectionSurvives(how: Change): Check {
        val main = main()
        main.get().displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[0].first))
        UiSettle.settle()
        val before = main.get().pageState()
        main.change(how)
        return same("页面状态", before, main.get().pageState())
    }

    /**
     * 设置一级页滚动后进入分类页，再经返回回到一级页，滚动位置恢复；[rotateInside] 为真时在分类页上先旋转一次。
     * 一级页要能滚动，所以窗口压到 300dp 高。
     */
    fun settingsHubScrollKept(rotateInside: Boolean): Check {
        RuntimeEnvironment.setQualifiers("en-rUS-w411dp-h300dp-port-notnight-xhdpi")
        val main = main()
        main.go(Page.SETTINGS)
        fun hubList() = (main.get().fragment() as SettingsFragment).childFragmentManager
            .findFragmentById(R.id.settings).let { (it as SettingsPreferenceFragment).listView }
        hubList().scrollBy(0, 300)
        UiSettle.settle()
        val before = hubList().computeVerticalScrollOffset()
        if (before <= 0) error("前置条件：设置一级页没有滚动")
        main.get().displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[0].first))
        UiSettle.settle()
        if (rotateInside) main.change(Change.ROTATE)
        main.get().backToSettings()
        UiSettle.settle()
        return same("设置一级页的滚动偏移", before, hubList().computeVerticalScrollOffset())
    }

    // ------------------------------------------------------ 关闭后回调

    /**
     * 页面创建后立刻销毁，再放行后台：不得有未捕获异常（异常由 UiTestRule 收集，主线程上的由 settle 抛出）。
     * 重复几次，后台任务与销毁的先后每次都不一样。
     */
    fun closeBeforeLoad(target: CloseTarget): Check {
        val fixtures = UiFixtures.install()
        repeat(3) {
            val controller: ActivityController<out Activity> = when (target) {
                CloseTarget.MAIN_CONFIG, CloseTarget.MAIN_GROUPS, CloseTarget.MAIN_ROUTES, CloseTarget.MAIN_SETTINGS ->
                    ui.launchUnsettled(MainActivity::class.java).also {
                        val page = when (target) {
                            CloseTarget.MAIN_GROUPS -> Page.GROUPS
                            CloseTarget.MAIN_ROUTES -> Page.ROUTES
                            CloseTarget.MAIN_SETTINGS -> Page.SETTINGS
                            else -> null
                        }
                        if (page != null) it.get().displayFragmentWithId(page.navId)
                    }

                CloseTarget.EDITOR_VMESS -> ui.launchUnsettled(
                    VMessSettingsActivity::class.java, editIntent(VMessSettingsActivity::class.java, fixtures.profiles[0].id)
                )

                CloseTarget.EDITOR_SHADOWSOCKS -> ui.launchUnsettled(
                    ShadowsocksSettingsActivity::class.java,
                    editIntent(ShadowsocksSettingsActivity::class.java, fixtures.profiles[1].id)
                )
            }
            ui.destroyNow(controller)
            UiSettle.settle()
        }
        return Check(true)
    }

    enum class CloseTarget { MAIN_CONFIG, MAIN_GROUPS, MAIN_ROUTES, MAIN_SETTINGS, EDITOR_VMESS, EDITOR_SHADOWSOCKS }

    // ------------------------------------------------------ 页面作用域（U4b b2）

    /** 读私有的 Job 字段（行内协程的句柄）：只为断言「销毁后协程已取消」 */
    private fun Any.jobField(name: String): Job? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Job?

    private fun Job?.running() = this?.isActive == true

    /**
     * 占满 Dispatchers.IO 的并行度：门开着的时候，页面代码提交到 IO 的读库任务只能排队，不会运行，
     * 「读库在途时销毁页面」因此是确定的，不取决于读库和销毁谁先到。
     * 关闭（[close]）后排队的任务照常跑完。门开着时不能 settle（占着的线程在等门闩，settle 会超时），
     * 所以一律用 `IoGate().use { ... }` 包住「触发读库 → 销毁页面」，出了块再 settle。
     * Robolectric 的 ActivityController.pause()/stop()/destroy() 每一步都会把主 looper 空转一遍，
     * 没有这道门时，已读完的结果会在销毁前的空转里被应用——那是正常行为，不是缺陷。
     */
    @OptIn(DelicateCoroutinesApi::class)
    private class IoGate : AutoCloseable {
        private val release = CountDownLatch(1)

        init {
            // kotlinx.coroutines 的 IO 并行度：系统属性，缺省 max(64, CPU 数)
            val slots = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
                ?: maxOf(64, Runtime.getRuntime().availableProcessors())
            val occupied = CountDownLatch(slots)
            repeat(slots) {
                GlobalScope.launch(Dispatchers.IO) {
                    occupied.countDown()
                    release.await()
                }
            }
            try {
                check(occupied.await(10, TimeUnit.SECONDS)) { "IO 调度器没能在 10 s 内被占满" }
                // 自检：门关上后再提交的 IO 任务必须跑不起来，否则下面的「在途」不成立
                val probe = CountDownLatch(1)
                GlobalScope.launch(Dispatchers.IO) { probe.countDown() }
                check(!probe.await(100, TimeUnit.MILLISECONDS)) { "IO 门没有挡住后续的 IO 任务" }
            } catch (t: Throwable) {
                release.countDown()
                throw t
            }
        }

        override fun close() = release.countDown()
    }

    /**
     * 配置页：行内的选中态查询在途时销毁页面——查询随视图作用域取消，销毁之后不再回填这一行。
     * 回填只会把 selectedView 设成 VISIBLE / INVISIBLE，所以先把它设成 GONE 当哨兵。
     * 读库被 [IoGate] 挡在门外，销毁时查询一定还在途。
     */
    fun configRowLoadCancelledOnDestroy(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val list = (main.get().fragment() as ConfigurationFragment).currentList()
        val holders = (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) as ConfigurationHolder }
        if (holders.isEmpty()) error("前置条件：配置页没有可见行")
        IoGate().use {
            holders.forEach {
                it.selectedView.visibility = View.GONE
                it.bind(it.entity)
            }
            if (holders.none { it.jobField("bindJob").running() }) error("前置条件：重绑后没有在途的行内查询")
            ui.destroyNow(main)
        }
        UiSettle.settle()
        val stray = holders.count { it.selectedView.visibility != View.GONE }
        val alive = holders.count { it.jobField("bindJob").running() }
        return if (stray == 0 && alive == 0) Check(true)
        else Check(false, "销毁后仍有 $stray 行被回填、$alive 个行内协程没取消")
    }

    /** 分组页：卡片的节点数查询在途时销毁页面，销毁后不再回填状态文字 */
    fun groupRowCountCancelledOnDestroy(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.go(Page.GROUPS)
        val fragment = main.get().fragment() as GroupFragment
        val list = fragment.groupListView
        val holders = (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) as GroupFragment.GroupHolder }
        if (holders.isEmpty()) error("前置条件：分组页没有可见行")
        val sentinel = "sentinel"
        IoGate().use {
            holders.forEach {
                it.groupStatus.text = sentinel
                it.bind(it.proxyGroup)
            }
            if (holders.none { it.jobField("countJob").running() }) error("前置条件：重绑后没有在途的节点数查询")
            ui.destroyNow(main)
        }
        UiSettle.settle()
        val stray = holders.count { it.groupStatus.text.toString() != sentinel }
        val alive = holders.count { it.jobField("countJob").running() }
        return if (stray == 0 && alive == 0) Check(true)
        else Check(false, "销毁后仍有 $stray 张卡片被回填、$alive 个查询没取消")
    }

    /** 路由页：整表重读在途时销毁页面，销毁后列表不被刷新 */
    fun routeReloadCancelledOnDestroy(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.go(Page.ROUTES)
        val adapter = (main.get().fragment() as RouteFragment).ruleAdapter
        if (adapter.ruleList.isEmpty()) error("前置条件：路由页没有规则")
        IoGate().use {
            adapter.ruleList.clear()
            adapter.reload()
            ui.destroyNow(main)
        }
        UiSettle.settle()
        return if (adapter.ruleList.isEmpty()) Check(true) else Check(false, "销毁后路由列表又被刷新了（${adapter.ruleList.size} 条）")
    }

    /**
     * 配置页的分组 tab 适配器：重读在途时销毁页面，以及页面销毁后再从后台线程触发重读，
     * 都不得改动 tab 列表、不得有未捕获异常（后台线程不碰 View，换列表只在主线程的页面作用域里）
     */
    fun pagerReloadAfterDestroyIsHarmless(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val adapter = (main.get().fragment() as ConfigurationFragment).adapter
        val before = adapter.groupList
        IoGate().use {
            adapter.reload()
            ui.destroyNow(main)
        }
        UiSettle.settle()
        ui.offMain { adapter.reload() }
        UiSettle.settle()
        return if (adapter.groupList === before) Check(true) else Check(false, "销毁后 tab 列表被换掉了")
    }

    /**
     * 没有 proxyGroup 的 ProfileListFragment（不是经 GroupPagerAdapter 建出来）走到 onResume：
     * 不得抛 UninitializedPropertyAccessException，也没有列表可显示
     */
    fun profileListResumesWithoutGroup(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val orphan = ProfileListFragment()
        main.get().supportFragmentManager.beginTransaction()
            .add(R.id.fragment_holder, orphan).commitNow()
        UiSettle.settle()
        return when {
            !orphan.isResumed -> Check(false, "孤立的列表页没有走到 RESUMED")
            orphan.listViewOrNull != null -> Check(false, "没有分组却建出了列表")
            else -> Check(true)
        }
    }

    /**
     * 「删除不可用」：读库后才弹确认框，读库在途时销毁页面就不弹；[destroyFirst] 为假时作对照，必须弹出来
     * （夹具里有失败的节点），免得「没弹」是因为场景根本弹不出来
     */
    fun deleteUnavailableConfirm(destroyFirst: Boolean): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val config = main.get().fragment() as ConfigurationFragment
        val item = config.toolbar.menu.findItem(R.id.action_connection_test_delete_unavailable)
            ?: error("前置条件：菜单里没有「删除不可用」")
        ShadowDialog.reset()
        if (destroyFirst) {
            // 读库挡在门外，销毁时一定还在途
            IoGate().use {
                config.onMenuItemClick(item)
                ui.destroyNow(main)
            }
        } else {
            config.onMenuItemClick(item)
        }
        UiSettle.settle()
        val shown = ShadowDialog.getLatestDialog() != null
        return when {
            destroyFirst && shown -> Check(false, "页面销毁后仍弹出了确认框")
            !destroyFirst && !shown -> Check(false, "对照组：页面还在却没有弹出确认框")
            else -> Check(true)
        }
    }

    /**
     * 点测试后读当前分组在途时销毁页面：不起测试，进程级的「测试中」标记复位，之后还能再测。
     * 读库不挡住的话，读完的结果会在销毁前的空转里真的起测试（对文档保留地址发 TCP 连接）。
     */
    fun groupTestEntryResetsOnDestroy(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val config = main.get().fragment() as ConfigurationFragment
        val item = config.toolbar.menu.findItem(R.id.action_connection_tcp_ping) ?: error("前置条件：菜单里没有「TCP Ping」")
        // 进程级的静态标记在同一沙箱的用例之间共享：开始前清零，结束后也清零，不连累别的用例
        val flag = Class.forName("io.nekohasekai.sagernet.ui.GroupTestRunnerKt").getDeclaredField("runningTest")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean
        flag.set(false)
        try {
            IoGate().use {
                config.onMenuItemClick(item)
                if (!flag.get()) error("前置条件：点测试后没有置位")
                ui.destroyNow(main)
            }
            UiSettle.settle()
            return if (!flag.get()) Check(true) else Check(false, "页面销毁后「测试中」标记仍然置位，之后所有测试都会被静默拒绝")
        } finally {
            flag.set(false)
        }
    }

    // ------------------------------------------------------ 控件与零散读取（U4b b3）

    /** 规则资源页：整表重读在途时销毁页面，销毁后列表不被回填；[destroyFirst] 为假时作对照，必须回填 */
    fun assetsReloadCancelledOnDestroy(destroyFirst: Boolean): Check {
        UiFixtures.install()
        val assets = ui.launch<AssetsActivity>()
        val adapter = assets.get().adapter
        if (adapter.assets.isEmpty()) error("前置条件：规则资源页没有列表项")
        adapter.assets.clear()
        if (destroyFirst) {
            IoGate().use {
                adapter.reloadAssets()
                ui.destroyNow(assets)
            }
        } else {
            adapter.reloadAssets()
        }
        UiSettle.settle()
        val alive = adapter.jobField("reloadJob").running()
        return when {
            destroyFirst && adapter.assets.isNotEmpty() -> Check(false, "销毁后规则资源列表又被回填了（${adapter.assets.size} 项）")
            destroyFirst && alive -> Check(false, "销毁后重读协程没取消")
            !destroyFirst && adapter.assets.isEmpty() -> Check(false, "对照组：页面还在却没有回填")
            else -> Check(true)
        }
    }

    /** 出站摘要查节点名：查库在途时销毁页面，结果不再回填；[destroyFirst] 为假时作对照，必须查到 */
    fun outboundSummaryLoadCancelledOnDestroy(destroyFirst: Boolean): Check {
        val fixtures = UiFixtures.install()
        val host = ui.launch<AssetsActivity>()
        val preference = OutboundPreference(host.get()).apply {
            key = "b3_outbound"
            value = "3"
        }
        EditorCache.profileCacheStore.putLong("b3_outboundLong", fixtures.profiles[0].id)
        fun loaded() = preference.javaClass.getDeclaredField("loadedName")
            .apply { isAccessible = true }.get(preference)
        if (destroyFirst) {
            IoGate().use {
                preference.summary
                ui.destroyNow(host)
            }
        } else {
            preference.summary
        }
        UiSettle.settle()
        return when {
            destroyFirst && loaded() != null -> Check(false, "销毁后查询结果仍被回填")
            !destroyFirst && loaded() == null -> Check(false, "对照组：页面还在却没有查到节点名")
            else -> Check(true)
        }
    }

    /** 应用列表摘要等待包缓存：等待在途时销毁页面，等待随之取消并复位标志（否则之后永远不再等待） */
    fun appListWaitCancelledOnDestroy(): Check {
        UiFixtures.install()
        val host = ui.launch<AssetsActivity>()
        // 包缓存在应用启动时就已加载完：临时置回未加载（进程内单例，结束后还原），摘要才会进入等待
        val loadedField = PackageCache.javaClass.getDeclaredField("isLoaded").apply { isAccessible = true }
        val wasLoaded = loadedField.getBoolean(PackageCache)
        loadedField.setBoolean(PackageCache, false)
        try {
            val preference = AppListPreference(host.get())
            fun waiting() = preference.javaClass.getDeclaredField("waitingForCache")
                .apply { isAccessible = true }.getBoolean(preference)
            IoGate().use {
                preference.summary
                if (!waiting()) error("前置条件：取摘要后没有进入等待")
                ui.destroyNow(host)
            }
            UiSettle.settle()
            return if (!waiting()) Check(true) else Check(false, "销毁后等待标志仍然置位")
        } finally {
            loadedField.setBoolean(PackageCache, wasLoaded)
        }
    }

    /** 状态卡片的连通性测试：测试在途时销毁页面，协程随之取消 */
    fun statsTestCancelledOnDestroy(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val stats = main.get().binding.stats
        IoGate().use {
            stats.testConnection()
            if (!stats.jobField("testJob").running()) error("前置条件：点测试后没有在途的协程")
            ui.destroyNow(main)
        }
        UiSettle.settle()
        return if (!stats.jobField("testJob").running()) Check(true) else Check(false, "销毁后测试协程没取消")
    }

    // ------------------------------------------------------ 编辑器重建与会话

    private fun editIntent(type: Class<*>, profileId: Long) =
        Intent(ApplicationProvider.getApplicationContext(), type).putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, profileId)

    private fun ssEditor(): Pair<ActivityController<ShadowsocksSettingsActivity>, Intent> {
        val fixtures = UiFixtures.install()
        val intent = editIntent(ShadowsocksSettingsActivity::class.java, fixtures.profiles[1].id)
        return ui.launch(ShadowsocksSettingsActivity::class.java, intent) to intent
    }

    private fun EditorActivity.serverAddressPreference() =
        child!!.findPreference<EditTextPreference>(Key.SERVER_ADDRESS)!!

    /** 改一个字段后重建 / 旋转：字段值与未保存标记都还在 */
    fun editorFieldEditSurvives(how: Change): Check {
        val (editor, _) = ssEditor()
        editor.get().serverAddressPreference().text = EDITED_ADDRESS
        UiSettle.settle()
        if (!EditorCache.dirty) error("前置条件：改字段后没有置未保存标记")
        editor.change(how)
        return same(
            "（服务器地址，未保存标记）", EDITED_ADDRESS to true,
            editor.get().serverAddressPreference().text to EditorCache.dirty,
        )
    }

    private fun saveProcessState(editor: ActivityController<out Activity>): Bundle {
        val state = Bundle()
        editor.pause().saveInstanceState(state)
        return state
    }

    /**
     * 模拟进程重建（SS 编辑器）：存实例状态 → 销毁 → 内存里的编辑缓存清空 → 用实例状态新建。
     * 按 EditorSession 规则：缓存丢了（CACHE_LOST）不是被接管，编辑器照旧从库里的实体重新初始化，
     * 重新认领缓存后仍是会话属主（还能保存）；未保存的编辑随进程一起丢是设计内的，字段回到实体的值。
     */
    fun editorProcessRecreationReinitializes(): Check {
        val (editor, intent) = ssEditor()
        editor.get().serverAddressPreference().text = EDITED_ADDRESS
        UiSettle.settle()
        val state = saveProcessState(editor)
        ui.destroyNow(editor)
        TempDatabase.profileCacheDao.reset()
        val restored = ui.launchRestored(ShadowsocksSettingsActivity::class.java, intent, state)
        UiSettle.settle()
        if (restored.get().isFinishing) return Check(false, "进程重建后编辑器自己关掉了")
        val session = checkEditorSession(state.getLong(STATE_EDITOR_SESSION))
        if (session != EditorSessionState.OWNER) return Check(false, "重新初始化后没有重新认领缓存：$session")
        return same("服务器地址（回到实体的值）", ORIGINAL_ADDRESS, restored.get().serverAddressPreference().text)
    }

    /** 进程重建时缓存已被别的顶层编辑器认领（TAKEN_OVER）：编辑器提示并关闭，不碰别人的缓存 */
    fun editorProcessRecreationTakenOver(): Check {
        val (editor, intent) = ssEditor()
        val state = saveProcessState(editor)
        ui.destroyNow(editor)
        claimEditorSession() // 别的编辑器接管
        val foreign = EditorCache.profileCacheStore.getLong(Key.EDITOR_SESSION)
        val restored = ui.launchRestored(ShadowsocksSettingsActivity::class.java, intent, state)
        UiSettle.settle()
        if (!restored.get().isFinishing) return Check(false, "缓存被接管后编辑器没有关闭")
        return same("缓存里的会话令牌", foreign, EditorCache.profileCacheStore.getLong(Key.EDITOR_SESSION))
    }

    /**
     * 子编辑器（ConfigEditActivity，自定义出站 JSON）在前台时进程重建：父编辑器重建后，子编辑器之前已保存、
     * 还没随父编辑器写库的内容仍在缓存里，子编辑器重建后显示的也是它。
     */
    fun childEditorProcessRecreationKeepsSavedContent(): Check {
        val (parent, parentIntent) = ssEditor()
        // 子编辑器之前保存过的内容（ConfigEditActivity 保存即写进缓存的这个键）
        EditorCache.serverCustomOutbound = SAVED_JSON
        val childIntent = Intent(ApplicationProvider.getApplicationContext(), ConfigEditActivity::class.java)
            .putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
        val child = ui.launch(ConfigEditActivity::class.java, childIntent)
        val parentState = saveProcessState(parent)
        val childState = saveProcessState(child)
        ui.destroyNow(child)
        ui.destroyNow(parent)
        TempDatabase.profileCacheDao.reset()
        ui.launchRestored(ShadowsocksSettingsActivity::class.java, parentIntent, parentState)
        val restoredChild = ui.launchRestored(ConfigEditActivity::class.java, childIntent, childState)
        UiSettle.settle()
        val cached = EditorCache.serverCustomOutbound
        val shown = restoredChild.get().binding.editor.text.toString()
        return if (cached == SAVED_JSON && shown == SAVED_JSON) Check(true)
        else Check(false, "缓存里是 ${cached?.let { "\"$it\"" }}，子编辑器显示 \"$shown\"，应为 \"$SAVED_JSON\"")
    }

    // ------------------------------------------------------ 编辑器旋转不重读库（U4d d5）
    //
    // 做法：编辑器载入之后，在库里把对应的行改掉（或删掉），再旋转。旋转若重读了库，改动就会出现在编辑器里；
    // 状态留在 ViewModel 里的话，旋转后看到的仍是载入时的那一份（节点用对象同一性断言）。

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    /** 等后台把缓存写好，期间不空转主 looper：Fragment 的提交排在主 looper 上，此时还没发生 */
    private fun awaitCache(what: String, ok: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!ok()) {
            if (System.nanoTime() > deadline) error("前置条件：等缓存里的$what 超时")
            Thread.sleep(1)
        }
    }

    private fun EditorActivity.moveItemVisible(): Boolean {
        val menu = MenuBuilder(this)
        onCreateOptionsMenu(menu)
        return menu.findItem(R.id.action_move).isVisible
    }

    /** 节点编辑器：正在编辑的节点载入一次，旋转后还是同一个对象，不重新读库 */
    fun profileEditorRotationKeepsLoadedEntity(): Check {
        val (editor, _) = ssEditor()
        val before = editor.get().proxyEntity ?: error("前置条件：节点还没载入")
        val name = before.displayName()
        bg {
            val entity = ProfileRepository.getProfile(before.id)!!
            entity.requireBean().name = "Renamed elsewhere"
            ProfileRepository.updateProfile(entity)
        }
        editor.change(Change.ROTATE)
        val after = editor.get().proxyEntity
        return if (after === before) Check(true)
        else Check(false, "旋转后节点重新读了库：之前「$name」，之后「${after?.displayName()}」")
    }

    /** 节点编辑器：「移动」菜单项的可见性算一次，旋转不重算（算了的话，库里分组删掉后菜单会变） */
    fun profileEditorRotationKeepsMoveMenu(): Check {
        val fixtures = UiFixtures.install()
        val second = bg { GroupRepository.createGroup(ProxyGroup(name = "Second group")) }
        val editor = ui.launch(
            ShadowsocksSettingsActivity::class.java,
            editIntent(ShadowsocksSettingsActivity::class.java, fixtures.profiles[1].id),
        )
        if (!editor.get().moveItemVisible()) error("前置条件：有两个普通分组时菜单里没有「移动」")
        bg { GroupRepository.deleteGroup(second.id) }
        editor.change(Change.ROTATE)
        return if (editor.get().moveItemVisible()) Check(true) else Check(false, "旋转后「移动」菜单项重新查了库，变成隐藏")
    }

    /**
     * 分组编辑器：初始化写完缓存、Fragment 还没提交时旋转（上一个实例在提交前被销毁）：新实例只重新提交 Fragment，
     * 不再读一遍库——库里把分组改名后，缓存里仍是第一次读到的名字。
     */
    fun groupEditorRotationBeforeCommitDoesNotReload(): Check {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), GroupSettingsActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, fixtures.normalGroup.id)
        val editor = ui.launchCreatedOnly(GroupSettingsActivity::class.java, intent)
        awaitCache("分组名") { EditorCache.groupName == UiFixtures.NORMAL_GROUP }
        if (editor.get().child != null) error("前置条件：旋转前 Fragment 已经提交了")
        bg { GroupRepository.updateGroup(fixtures.normalGroup.apply { name = "Renamed elsewhere" }) }
        editor.change(Change.ROTATE)
        if (editor.get().child == null) return Check(false, "旋转后没有提交偏好 Fragment")
        return same("缓存里的分组名", UiFixtures.NORMAL_GROUP, EditorCache.groupName)
    }

    /** 路由编辑器：同分组编辑器，初始化写完缓存、Fragment 还没提交时旋转，不重读库 */
    fun routeEditorRotationBeforeCommitDoesNotReload(): Check {
        UiFixtures.install()
        val rule = bg { RuleRepository.getRules().first() }
        val intent = Intent(ApplicationProvider.getApplicationContext(), RouteSettingsActivity::class.java)
            .putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
        val editor = ui.launchCreatedOnly(RouteSettingsActivity::class.java, intent)
        awaitCache("规则名") { EditorCache.routeName == rule.name }
        if (editor.get().child != null) error("前置条件：旋转前 Fragment 已经提交了")
        bg { RuleRepository.updateRule(rule.copy(name = "Renamed elsewhere")) }
        editor.change(Change.ROTATE)
        if (editor.get().child == null) return Check(false, "旋转后没有提交偏好 Fragment")
        return same("缓存里的规则名", rule.name, EditorCache.routeName)
    }

    private fun chainEditor(): ActivityController<ChainSettingsActivity> {
        val fixtures = UiFixtures.install()
        val chain = bg {
            ProfileRepository.createProfile(
                fixtures.normalGroup.id,
                ChainBean().apply {
                    name = "Chain A"
                    proxies = listOf(fixtures.profiles[0].id)
                },
            )
        }
        return ui.launch(ChainSettingsActivity::class.java, editIntent(ChainSettingsActivity::class.java, chain.id))
    }

    /** 链式代理编辑器：成员列表载入一次，旋转后是同一批对象，不按 id 重读库；适配器里的条数不变 */
    fun chainEditorRotationKeepsMembers(): Check {
        val editor = chainEditor()
        val before = editor.get().proxyList.toList()
        if (before.size != 1) error("前置条件：成员列表没有载入 $before")
        bg {
            val member = ProfileRepository.getProfile(before[0].id)!!
            member.requireBean().name = "Renamed elsewhere"
            ProfileRepository.updateProfile(member)
        }
        editor.change(Change.ROTATE)
        val after = editor.get().proxyList
        if (after.size != 1 || after[0] !== before[0]) {
            return Check(false, "旋转后成员列表重新读了库：之前 ${before.map { it.displayName() }}，之后 ${after.map { it.displayName() }}")
        }
        return same("适配器条数", 2, editor.get().configurationAdapter.itemCount)
    }

    /** 链式代理编辑器：待处理的「替换第几个成员」随进程重建带回（SavedStateHandle），不会变成追加 */
    fun chainEditorReplacingSurvivesProcessRecreation(): Check {
        val editor = chainEditor()
        editor.get().replacing = 1
        val intent = editor.get().intent
        val state = saveProcessState(editor)
        ui.destroyNow(editor)
        TempDatabase.profileCacheDao.reset()
        val restored = ui.launchRestored(ChainSettingsActivity::class.java, intent, state)
        return same("replacing", 1, restored.get().replacing)
    }

    // ------------------------------------------------------ 入场动画（M5）

    /** 配置页节点列表此刻有没有在播（或排好了）入场的 layoutAnimation；入场动画要在虚拟时钟不动时才看得到 */
    private fun MainActivity.listEntering(): Boolean =
        (fragment() as ConfigurationFragment).currentList().layoutAnimation != null

    /** 冷启动：配置页节点卡片播错位入场 */
    fun coldStartPlaysEnterAnimation(): Check {
        UiFixtures.install()
        ui.enableAnimations()
        val main = ui.launch<MainActivity>()
        return if (main.get().listEntering()) Check(true) else Check(false, "冷启动没有播入场动画")
    }

    /** 旋转（视图重建）不再重播入场动画 */
    fun rotationDoesNotReplayEnterAnimation(): Check {
        UiFixtures.install()
        ui.enableAnimations()
        val main = ui.launch<MainActivity>()
        if (!main.get().listEntering()) error("前置条件：冷启动没有播入场动画")
        main.change(Change.ROTATE)
        return if (!main.get().listEntering()) Check(true) else Check(false, "旋转后又播了一次入场动画")
    }

    // ------------------------------------------------------ 进程级写入不碰已销毁的页面（U4b b4）

    /**
     * 备份导入：点「导入」之后页面销毁，恢复仍要写完，且全程不碰 View。
     * 服务状态置为「在跑」，恢复协程必然卡在「等服务停稳」，销毁页面时它一定还在途；销毁之后放行。
     * 监听器执行（点击）之后把三个勾选框全取消：恢复用的是点击时在主线程读到的值，不再回头读 CheckBox。
     * 判定：路由规则被备份内容（空规则表）整表替换，即写库完成；未捕获异常由 UiTestRule 收集。
     */
    fun backupImportOutlivesPage(): Check {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val fragment = BackupFragment()
        main.get().supportFragmentManager.beginTransaction().add(R.id.fragment_holder, fragment).commitNow()
        UiSettle.settle()
        val before = ui.offMain { SagerDatabase.rulesDao.allRules().size }
        if (before == 0) error("前置条件：夹具里没有路由规则")
        val backup = File(app.cacheDir, "b4_backup.json").apply { writeText("{\"version\":1,\"rules\":[]}") }
        ServiceRegistry.state = BaseService.State.Connected
        try {
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) { fragment.startImport(Uri.fromFile(backup)) }
            UiSettle.settle()
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog ?: error("前置条件：没有弹出导入确认框")
            val boxes = listOf(R.id.backup_configurations, R.id.backup_rules, R.id.backup_settings)
                .map { dialog.findViewById<CheckBox>(it) ?: error("前置条件：确认框里没有勾选项") }
            if (!boxes[1].isChecked) error("前置条件：规则勾选项默认应勾选")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            // AlertController 把按钮点击转成消息投给主 looper：空转一次，确认框的监听器（读勾选项）才真正执行
            shadowOf(Looper.getMainLooper()).idle()
            boxes.forEach { it.isChecked = false }
            UiSettle.settle()
            val waiting = ui.offMain { SagerDatabase.rulesDao.allRules().size }
            if (waiting != before) error("前置条件：恢复没有卡在等服务停稳（规则已变成 $waiting 条）")
            ui.destroyNow(main)
            ServiceRegistry.state = BaseService.State.Idle
            UiSettle.settle()
            drainRestart()
        } finally {
            ServiceRegistry.state = BaseService.State.Idle
            backup.delete()
        }
        val after = ui.offMain { SagerDatabase.rulesDao.allRules().size }
        return if (after == 0) Check(true) else Check(false, "页面销毁后备份没有导入完（规则表还有 $after 条）")
    }

    // triggerFullRestart 在 appScope 里 delay(500) 之后才去连 :bg：等它走完，并把它置的进程级标志还原，不连累别的用例
    private fun drainRestart() {
        Thread.sleep(800)
        UiSettle.settle()
        SagerConnection.restartingApp = false
    }

    /**
     * 配置页的导入链跑在 appScope，写库之后页面已销毁：节点照常写入，选中的分组照常记下（下次建视图按它选 tab），
     * 但不去动旧视图的 pager。先选中订阅分组，使导入目标（第一个普通分组）与当前分组不同，切 tab 的分支才会被走到。
     */
    fun importAfterPageDestroyedLeavesViewAlone(): Check {
        val fixtures = UiFixtures.install()
        DataStore.selectedGroup = fixtures.subscriptionGroup.id
        val main = ui.launch<MainActivity>()
        val fragment = main.get().fragment() as ConfigurationFragment
        val pager = fragment.groupPager
        val pagerBefore = pager.currentItem
        val count = { ui.offMain { SagerDatabase.proxyDao.countByGroup(fixtures.normalGroup.id) } }
        val before = count()
        ui.destroyNow(main)
        runOnDefaultDispatcher {
            fragment.import(listOf(SOCKSBean().apply {
                name = "Imported after destroy"
                serverAddress = "192.0.2.99"
                serverPort = 1080
            }))
        }
        UiSettle.settle()
        return when {
            count() != before + 1 -> Check(false, "页面销毁后导入的节点没有写库")
            DataStore.selectedGroup != fixtures.normalGroup.id -> Check(false, "导入目标分组没有记进 DataStore.selectedGroup")
            pager.currentItem != pagerBefore -> Check(false, "页面销毁后旧视图的 pager 被切换了（$pagerBefore -> ${pager.currentItem}）")
            else -> Check(true)
        }
    }

    /** 规则资源页：撤销窗口到期删 ca.pem 之后的「需要重启」提示，页面已销毁时直接丢弃，文件照常删掉 */
    fun assetsCommitAfterPageDestroyed(): Check {
        UiFixtures.install()
        val assets = ui.launch<AssetsActivity>()
        val adapter = assets.get().adapter
        val ca = File(app.assetsDir.apply { mkdirs() }, "ca.pem").apply { writeText("x") }
        ui.destroyNow(assets)
        adapter.commit(listOf(0 to ca))
        UiSettle.settle()
        val deleted = !ca.exists()
        ca.delete()
        return if (deleted) Check(true) else Check(false, "页面销毁后 ca.pem 没有删掉")
    }

    companion object {
        const val ORIGINAL_ADDRESS = "192.0.2.20"
        const val EDITED_ADDRESS = "192.0.2.99"
        const val SAVED_JSON = "{\"saved\":true}"
    }
}
