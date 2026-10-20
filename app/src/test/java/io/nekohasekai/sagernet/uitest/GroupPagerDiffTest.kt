package io.nekohasekai.sagernet.uitest

import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileListFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * U4c c6 分组 tab 页（GroupPagerAdapter，按分组 id 差量刷新）：
 * 整表重读（reload）后，新增 / 删除 / 挪动 / 改名只动对应的页，当前选中的 tab 与页跟着分组走，
 * 没变的页还是同一个 Fragment 实例；导入落到新分组时自动切过去；空的 ungrouped 分组的 tab 隐藏与显示。
 * 不截图，所有平台都跑。
 */
class GroupPagerDiffTest : UiTestBase() {

    // ---------------------------------------------------------------- 公共

    private class Setup(val config: ConfigurationFragment, val fixtures: UiFixtures.Result?) {
        val pager get() = config.groupPager
        val tabs get() = config.tabLayout
        val ids get() = config.adapter.groupList.map { it.id }
        val currentId get() = ids[pager.currentItem]
        fun page(id: Long): Fragment? = config.childFragmentManager.findFragmentByTag("f$id")

        /** 切到第 [index] 页；DataStore.selectedGroup 由滚动回调写，Robolectric 里没有真实滚动，这里按回调的做法补上 */
        fun select(index: Int) {
            pager.setCurrentItem(index, false)
            UiSettle.settle()
            val id = ids[index]
            Thread { DataStore.selectedGroup = id }.apply { start() }.join()
        }
    }

    private fun openMain(): ConfigurationFragment {
        val main = ui.launch<MainActivity>()
        return main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
    }

    /** 夹具的两个分组（Personal、订阅）再加 [extra] 个，共 2 + extra 个；当前选中第 [current] 个 */
    private fun open(extra: Int = 0, current: Int = 0): Setup {
        val fixtures = UiFixtures.install()
        repeat(extra) { n -> createGroup("Extra ${n + 1}") }
        val config = openMain()
        val s = Setup(config, fixtures)
        if (current != 0) s.select(current)
        s.assertConsistent()
        return s
    }

    /** 直接写库（不发仓库通知）；[order] 为空时排在最后 */
    private fun createGroup(name: String, order: Long? = null): ProxyGroup = ui.offMain {
        val group = ProxyGroup(name = name).apply { userOrder = order ?: (SagerDatabase.groupDao.nextOrder() ?: 1) }
        group.id = SagerDatabase.groupDao.createGroup(group)
        group
    }

    private fun minOrder(): Long = ui.offMain { SagerDatabase.groupDao.allGroups() }.minOf { it.userOrder }

    private fun dbIds(): List<Long> = ui.offMain { SagerDatabase.groupDao.allGroups() }.map { it.id }

    /** 整表重读并等它落到界面上 */
    private fun Setup.reload() {
        config.adapter.reload()
        UiSettle.settle()
    }

    /** tab 条、ViewPager、选中分组三者一致：tab 文字 = 分组名、选中的 tab = 当前页、当前页的 Fragment 已建出 */
    private fun Setup.assertConsistent() {
        val groups = config.adapter.groupList
        assertEquals("tab 数 = 分组数", groups.size, tabs.tabCount)
        assertEquals(
            "tab 文字 = 分组名",
            groups.map { it.displayName() },
            (0 until tabs.tabCount).map { tabs.getTabAt(it)?.text?.toString() },
        )
        if (groups.isEmpty()) return
        assertTrue("当前页在范围内：${pager.currentItem}", pager.currentItem in groups.indices)
        assertEquals("选中的 tab = 当前页", pager.currentItem, tabs.selectedTabPosition)
        assertNotNull("当前页的 Fragment 已建出", page(currentId))
    }

    /** 在后台线程跑会触发仓库监听回调的操作；回调要切回主线程，所以轮询 settle 直到 worker 结束 */
    private fun <T> viaWorker(block: () -> T): T {
        var result: Result<T>? = null
        val worker = thread(name = "ui-test-bg") { result = runCatching(block) }
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    private fun bean(name: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.1"
        serverPort = 1080
        protocol = SOCKSBean.PROTOCOL_SOCKS5
    }

    /** 记下适配器收到的全部通知 */
    private class Events : RecyclerView.AdapterDataObserver() {
        val changed = ArrayList<Pair<Int, Int>>()
        var inserted = 0
        var removed = 0
        var moved = 0
        var reset = 0
        override fun onChanged() { reset++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { changed += positionStart to itemCount }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { inserted += itemCount }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { removed += itemCount }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { moved += itemCount }
        val total get() = changed.size + inserted + removed + moved + reset
    }

    private fun Setup.watch() = Events().also { config.adapter.registerAdapterDataObserver(it) }

    // ---------------------------------------------------------------- 差量：只发该发的通知

    @Test
    fun reloadDispatchesOnlyTheNeededNotifications() {
        val s = open(extra = 1, current = 1)
        val events = s.watch()

        s.reload()
        assertEquals("库没变：整表重读不发任何通知", 0, events.total)

        createGroup("Tail")
        s.reload()
        assertEquals("新增一个：只发一次插入", 1, events.inserted)
        assertEquals(0, events.reset)
        assertEquals(1, events.total)

        ui.offMain { SagerDatabase.groupDao.deleteById(s.ids.last()) }
        s.reload()
        assertEquals("删除一个：只发一次移除", 1, events.removed)
        assertEquals(0, events.reset)
        assertEquals(2, events.total)

        ui.offMain {
            val group = SagerDatabase.groupDao.getById(s.ids.first())!!.apply { name = "Renamed" }
            SagerDatabase.groupDao.updateGroup(group)
        }
        s.reload()
        assertEquals("改名：只通知那一页变化", listOf(0 to 1), events.changed)
        assertEquals(0, events.reset)
    }

    // ---------------------------------------------------------------- 整表重读：新增

    @Test
    fun insertBeforeCurrentKeepsCurrentGroupAndFragments() {
        val s = open(current = 1)
        val personal = s.fixtures!!.normalGroup
        val subscription = s.fixtures.subscriptionGroup
        val personalPage = s.page(personal.id)
        val subscriptionPage = s.page(subscription.id)
        assertNotNull(subscriptionPage)
        assertNotNull(personalPage)

        val front = createGroup("Front", order = minOrder() - 1)
        s.reload()

        assertEquals(listOf(front.id, personal.id, subscription.id), s.ids)
        assertEquals("当前仍是订阅分组，页码随它后移", subscription.id, s.currentId)
        assertEquals(2, s.pager.currentItem)
        assertEquals(subscription.id, DataStore.selectedGroup)
        assertSame("没变的页是同一个 Fragment 实例", subscriptionPage, s.page(subscription.id))
        assertSame(personalPage, s.page(personal.id))
        s.assertConsistent()
    }

    @Test
    fun insertAfterCurrentKeepsCurrentGroup() {
        val s = open()
        val personalPage = s.page(s.fixtures!!.normalGroup.id)
        val tail = createGroup("Tail")
        s.reload()

        assertEquals(s.ids.last(), tail.id)
        assertEquals(s.fixtures.normalGroup.id, s.currentId)
        assertEquals(0, s.pager.currentItem)
        assertSame(personalPage, s.page(s.fixtures.normalGroup.id))
        s.assertConsistent()
    }

    // ---------------------------------------------------------------- 整表重读：删除

    @Test
    fun removeBeforeCurrentKeepsCurrentGroup() {
        val s = open(extra = 2, current = 2)
        val current = s.currentId
        val currentPage = s.page(current)
        val removed = s.ids.first()
        ui.offMain { SagerDatabase.groupDao.deleteById(removed) }
        s.reload()

        assertEquals(dbIds(), s.ids)
        assertEquals(current, s.currentId)
        assertEquals(1, s.pager.currentItem)
        assertSame(currentPage, s.page(current))
        assertEquals(null, s.page(removed))
        s.assertConsistent()
    }

    @Test
    fun removeAfterCurrentKeepsCurrentGroup() {
        val s = open(extra = 2, current = 1)
        val current = s.currentId
        val currentPage = s.page(current)
        ui.offMain { SagerDatabase.groupDao.deleteById(s.ids.last()) }
        s.reload()

        assertEquals(dbIds(), s.ids)
        assertEquals(current, s.currentId)
        assertSame(currentPage, s.page(current))
        s.assertConsistent()
    }

    @Test
    fun removeCurrentLandsOnAValidPage() {
        val s = open(extra = 2, current = 2)
        val gone = s.currentId
        ui.offMain { SagerDatabase.groupDao.deleteById(gone) }
        s.reload()

        assertEquals(dbIds(), s.ids)
        assertTrue(gone !in s.ids)
        // 选中项指向的分组没了：落在某个还在的分组上，tab 与页一致
        s.assertConsistent()
    }

    // ---------------------------------------------------------------- 整表重读：挪动与改名

    @Test
    fun reorderKeepsCurrentGroupAndFragmentIdentity() {
        val s = open(extra = 2, current = 2)
        val current = s.currentId
        val pages = s.ids.associateWith { s.page(it) }
        val before = ui.offMain { SagerDatabase.groupDao.allGroups() }
        // 首尾互换顺序
        ui.offMain {
            SagerDatabase.groupDao.updateUserOrder(before.first().id, before.last().userOrder)
            SagerDatabase.groupDao.updateUserOrder(before.last().id, before.first().userOrder)
        }
        s.reload()

        assertEquals(dbIds(), s.ids)
        assertEquals("顺序真的变了", listOf(before.last().id, before[1].id, before[2].id, before.first().id), s.ids)
        assertEquals("当前仍是同一个分组", current, s.currentId)
        assertEquals(s.ids.indexOf(current), s.pager.currentItem)
        for ((id, page) in pages) {
            assertSame("分组 $id 的页是同一个实例", page, s.page(id))
        }
        s.assertConsistent()
    }

    @Test
    fun renameUpdatesTabTextOnly() {
        val s = open(current = 1)
        val current = s.currentId
        val currentPage = s.page(current)
        ui.offMain {
            val group = SagerDatabase.groupDao.getById(s.ids.first())!!.apply { name = "Renamed" }
            SagerDatabase.groupDao.updateGroup(group)
        }
        s.reload()

        assertEquals("Renamed", s.tabs.getTabAt(0)?.text?.toString())
        assertEquals(current, s.currentId)
        assertSame(currentPage, s.page(current))
        s.assertConsistent()
    }

    // ---------------------------------------------------------------- 仓库通知路径（增删）

    @Test
    fun addAndRemoveGroupThroughRepositoryKeepTabsAndPage() {
        val s = open(current = 1)
        val current = s.currentId
        val currentPage = s.page(current)

        val added = viaWorker { runBlocking { GroupRepository.createGroup(ProxyGroup(name = "Added")) } }
        assertEquals(dbIds(), s.ids)
        assertEquals("新增分组后选中它", added.id, s.currentId)
        s.assertConsistent()

        // 先回到原来的页，再删掉新增的那个：当前页不动
        s.select(s.ids.indexOf(current))
        viaWorker { runBlocking { GroupRepository.deleteGroup(added.id) } }
        assertEquals(dbIds(), s.ids)
        assertEquals(current, s.currentId)
        assertSame(currentPage, s.page(current))
        s.assertConsistent()
    }

    // ---------------------------------------------------------------- 导入切到新分组

    @Test
    fun importIntoNewGroupAtCurrentIndexSwitchesToIt() {
        val s = open()
        val personal = s.fixtures!!.normalGroup
        assertEquals(0, s.pager.currentItem)
        val personalPage = s.page(personal.id)

        // 新分组直接写库、排在最前（页码正好等于当前页码）；第一个节点落进去时 onAdd 发现页里没有它，选中并整表重读
        val fresh = createGroup("Fresh", order = minOrder() - 1)
        viaWorker { runBlocking { ProfileRepository.createProfile(fresh.id, bean("first")) } }

        assertEquals(listOf(fresh.id, personal.id, s.fixtures.subscriptionGroup.id), s.ids)
        assertEquals("自动切到新分组", fresh.id, s.currentId)
        assertEquals(0, s.pager.currentItem)
        assertEquals(fresh.id, DataStore.selectedGroup)
        assertNotNull(s.page(fresh.id))
        assertSame("原来的页还是同一个实例", personalPage, s.page(personal.id))
        s.assertConsistent()
    }

    @Test
    fun importIntoNewGroupAtTheEndSwitchesToIt() {
        val s = open()
        val fresh = createGroup("Fresh")
        viaWorker { runBlocking { ProfileRepository.createProfile(fresh.id, bean("first")) } }

        assertEquals(fresh.id, s.ids.last())
        assertEquals(fresh.id, s.currentId)
        assertEquals(fresh.id, DataStore.selectedGroup)
        s.assertConsistent()
    }

    @Test
    fun importFromSubscriptionPageSwitchesToBasicGroup() {
        val s = open(current = 1)
        val personal = s.fixtures!!.normalGroup
        assertEquals("前置条件：停在订阅分组", s.fixtures.subscriptionGroup.id, s.currentId)

        viaWorker { runBlocking { s.config.import(listOf(bean("imported"))) } }

        assertEquals("导入落在第一个普通分组，并切过去", personal.id, s.currentId)
        assertEquals(personal.id, DataStore.selectedGroup)
        assertTrue(ui.offMain { SagerDatabase.proxyDao.getByGroup(personal.id) }.any { it.displayName() == "imported" })
        s.assertConsistent()
    }

    // ---------------------------------------------------------------- 空的 ungrouped 分组：tab 隐藏与显示

    @Test
    fun emptyUngroupedTabHidesAndShows() {
        // 全新安装：库里没有分组，打开配置页会建出 ungrouped，空的不进 tab 列表
        val config = openMain()
        val s = Setup(config, null)
        assertEquals(0, s.ids.size)
        assertEquals("单个分组时不显示 tab 条", View.GONE, s.tabs.visibility)

        // 第一个节点落进 ungrouped：它进列表，仍只有一个分组，tab 条仍隐藏
        val ungrouped = ui.offMain { SagerDatabase.groupDao.allGroups() }.single()
        assertTrue(ungrouped.ungrouped)
        viaWorker { runBlocking { ProfileRepository.createProfile(ungrouped.id, bean("first")) } }
        assertEquals(listOf(ungrouped.id), s.ids)
        assertEquals(View.GONE, s.tabs.visibility)
        s.assertConsistent()

        // 新建一个普通分组：两个分组，tab 条出现
        val added = viaWorker { runBlocking { GroupRepository.createGroup(ProxyGroup(name = "Added")) } }
        assertEquals(listOf(ungrouped.id, added.id), s.ids)
        assertEquals(View.VISIBLE, s.tabs.visibility)
        s.assertConsistent()

        // ungrouped 的节点删光：它退出列表，只剩一个分组，tab 条再次隐藏
        val profile = ui.offMain { SagerDatabase.proxyDao.getByGroup(ungrouped.id) }.single()
        viaWorker { runBlocking { ProfileRepository.deleteProfile(ungrouped.id, profile.id) } }
        assertEquals(listOf(added.id), s.ids)
        assertEquals(View.GONE, s.tabs.visibility)
        assertEquals(added.id, s.currentId)
        s.assertConsistent()
    }
}
