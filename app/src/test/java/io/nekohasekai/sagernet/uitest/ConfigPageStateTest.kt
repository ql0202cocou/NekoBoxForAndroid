package io.nekohasekai.sagernet.uitest

import android.os.Bundle
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileListFragment
import io.nekohasekai.sagernet.ui.ProfileListViewModel
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * U4d d2 配置页的页面级状态：[io.nekohasekai.sagernet.ui.ConfigurationViewModel]（tab 列表、搜索词、入场已播的分组、待写出的导出内容）
 * 与每个分组页的 [ProfileListViewModel]（节点列表、载入标志、仓库监听）。
 * - 旋转：不重读库、同一份列表、滚动位置保留（原先登记在 KnownUiGaps 里）、入场不重播（同上）、搜索词和选中的分组保留；
 * - 进程重建：选中的分组与选中的节点、滚动位置、搜索词恢复，分组 id 来自 Fragment arguments，onResume 不崩；
 * - 监听器：旋转不重复、离开配置页摘干净；
 * - 选择模式下用户翻到的分组随旋转和进程重建保留。
 * 不截图，所有平台都跑。
 */
class ConfigPageStateTest : UiBehaviorBase() {

    // ---------------------------------------------------------------- 公共

    private fun ActivityController<*>.config(): ConfigurationFragment =
        (get() as androidx.fragment.app.FragmentActivity).supportFragmentManager
            .findFragmentById(R.id.fragment_holder) as ConfigurationFragment

    private fun ConfigurationFragment.page(groupId: Long): ProfileListFragment =
        childFragmentManager.findFragmentByTag("f$groupId") as ProfileListFragment

    private fun ConfigurationFragment.currentPage(): ProfileListFragment =
        page(adapter.groupList[groupPager.currentItem].id)

    private fun ProfileListFragment.firstVisible() = (configurationListView.layoutManager as LinearLayoutManager)
        .findFirstVisibleItemPosition()

    private fun ProfileListFragment.names() = profileModel.items.map { it.displayName() }

    private fun profileListeners(): Int = listenerCount(ProfileManager, "listeners")
    private fun groupListeners(): Int = listenerCount(GroupManager, "listeners")

    private fun listenerCount(owner: Any, field: String): Int =
        (owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as List<*>).size

    /** 在后台线程跑会触发仓库监听回调的操作：回调要切回主线程，所以轮询 settle 直到 worker 结束 */
    private fun <T> viaWorker(block: () -> T): T {
        var result: Result<T>? = null
        val worker = kotlin.concurrent.thread(name = "ui-test-bg") { result = runCatching(block) }
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    private fun openMain(extraProfiles: Int = 0): ActivityController<MainActivity> {
        val fixtures = UiFixtures.install()
        if (extraProfiles > 0) UiFixtures.addProfiles(fixtures.normalGroup, extraProfiles)
        return ui.launch<MainActivity>()
    }

    /** 切到第 [index] 页；DataStore.selectedGroup 由滚动回调写，Robolectric 里没有真实滚动，这里按回调的做法补上 */
    private fun ConfigurationFragment.select(index: Int) {
        groupPager.setCurrentItem(index, false)
        UiSettle.settle()
        val id = adapter.groupList[index].id
        ui.offMain { DataStore.selectedGroup = id }
    }

    private fun saveProcessState(main: ActivityController<*>): Bundle = Bundle().also { main.pause().saveInstanceState(it) }

    private fun ConfigurationFragment.searchView(): SearchView =
        toolbar.menu.findItem(R.id.action_search).actionView as SearchView

    private fun ConfigurationFragment.search(text: String) {
        searchView().onActionViewExpanded()
        searchView().setQuery(text, false)
        UiSettle.settle()
    }

    // ---------------------------------------------------------------- 旋转：不重读库、同一份列表

    @Test
    fun rotationReusesTheListWithoutRereadingTheDatabase() {
        val main = openMain(extraProfiles = 30)
        val page = main.config().currentPage()
        val model = page.profileModel
        val items = model.items
        val names = page.names()
        val tabs = main.config().adapter.groupList
        assertTrue("前置条件：列表载入了", names.size > 30)

        // 绕过 Repository 直接改库（不发通知）：旋转若重读了库，改动就会出现在列表里
        ui.offMain {
            val first = SagerDatabase.proxyDao.getByGroup(model.groupId).first()
            SagerDatabase.proxyDao.deleteById(first.id)
            SagerDatabase.groupDao.createGroup(
                io.nekohasekai.sagernet.database.ProxyGroup(name = "Inserted under the hood")
                    .apply { userOrder = SagerDatabase.groupDao.nextOrder() ?: 1 }
            )
        }
        main.rotate()

        val rotated = main.config().currentPage()
        assertSame("节点页的 ViewModel 是同一个", model, rotated.profileModel)
        assertSame("列表是同一个工作副本", items, rotated.profileModel.items)
        assertEquals("旋转后节点列表仍是载入时的那一份", names, rotated.names())
        assertEquals("适配器条数 = 列表条数", names.size, rotated.adapter!!.itemCount)
        assertSame("tab 列表是同一个工作副本", tabs, main.config().adapter.groupList)
        assertEquals("tab 数没有因为库里多出的分组而变", tabs.size, main.config().tabLayout.tabCount)
    }

    @Test
    fun listenersAreNotDuplicatedByRotationAndRemovedOnLeaving() {
        val main = openMain()
        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        val baseProfile = profileListeners()
        val baseGroup = groupListeners()
        main.get().displayFragmentWithId(Page.CONFIG.navId)
        UiSettle.settle()
        val onConfigProfile = profileListeners()
        val onConfigGroup = groupListeners()
        assertTrue("配置页挂了监听器（在 ViewModel 上）", onConfigProfile > baseProfile && onConfigGroup > baseGroup)

        main.rotate()
        assertEquals("旋转后仓库监听器没有重复也没有漏", onConfigProfile, profileListeners())
        assertEquals(onConfigGroup, groupListeners())

        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        assertEquals("离开配置页后节点监听器摘干净", baseProfile, profileListeners())
        assertEquals("离开配置页后分组监听器摘干净", baseGroup, groupListeners())
    }

    @Test
    fun changeNotificationsStillReachTheListAfterRotationExactlyOnce() {
        val main = openMain()
        main.rotate()
        val page = main.config().currentPage()
        val before = page.names()
        val added = viaWorker {
            kotlinx.coroutines.runBlocking {
                io.nekohasekai.sagernet.database.ProfileRepository.createProfile(
                    page.profileModel.groupId,
                    io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                        name = "Added after rotation"; serverAddress = "192.0.2.99"; serverPort = 1080
                    },
                )
            }
        }
        UiSettle.settle()
        assertEquals(before + added.displayName(), page.names())
    }

    // ---------------------------------------------------------------- 旋转 / 重建：入场不重播、列表位置保留（原先的已知缺口）

    @Test
    fun rotationDoesNotReplayEnterAnimation() {
        val check = scenarios.rotationDoesNotReplayEnterAnimation()
        assertTrue("旋转后配置页不再播入场动画：$check", check.ok)
    }

    @Test
    fun listPositionKeptAfterRecreate() {
        val check = scenarios.configListPositionSurvives(Change.RECREATE)
        assertTrue("重建后列表位置：$check", check.ok)
    }

    @Test
    fun listPositionKeptAfterRotation() {
        val check = scenarios.configListPositionSurvives(Change.ROTATE)
        assertTrue("旋转后列表位置：$check", check.ok)
    }

    @Test
    fun enterAnimationStillPlaysForAGroupThatHasNotPlayedYet() {
        UiFixtures.install()
        ui.enableAnimations()
        val main = ui.launch<MainActivity>()
        val config = main.config()
        val firstGroup = config.adapter.groupList[0].id
        val secondGroup = config.adapter.groupList[1].id
        assertTrue("前置条件：冷启动播过第一个分组", firstGroup in config.model.enterAnimationPlayed)
        assertFalse("前置条件：第二个分组还没播过", secondGroup in config.model.enterAnimationPlayed)

        main.rotate()
        val rotated = main.config()
        assertEquals("旋转保留了已播的记录", setOf(firstGroup), rotated.model.enterAnimationPlayed)
        assertFalse("旋转后第一个分组的列表没有排入场动画", rotated.page(firstGroup).configurationListView.layoutAnimation != null)

        // 旋转之后用户切到还没播过的分组：照常播
        rotated.groupPager.setCurrentItem(1, false)
        UiSettle.settle()
        assertTrue("第二个分组播入场", secondGroup in rotated.model.enterAnimationPlayed)
    }

    @Test
    fun laterListChangesAfterRotationDoNotReplayEnterAnimation() {
        UiFixtures.install()
        ui.enableAnimations()
        val main = ui.launch<MainActivity>()
        val groupId = main.config().adapter.groupList[0].id
        main.rotate()
        // 动画随虚拟时钟走完、取下
        UiSettle.settle(advanceMs = 2000)
        val list = main.config().page(groupId).configurationListView
        assertEquals("前置条件：入场动画已取下", null, list.layoutAnimation)

        // 旋转之后列表又有一次整组刷新（比如测速结果到了）：不是切分组，不该再播
        viaWorker { kotlinx.coroutines.runBlocking { io.nekohasekai.sagernet.database.GroupRepository.postReload(groupId) } }
        assertEquals("整组刷新没有重播入场动画", null, main.config().page(groupId).configurationListView.layoutAnimation)
    }

    // ---------------------------------------------------------------- 旋转：搜索词与选中分组

    @Test
    fun searchQueryAndSelectedGroupSurviveRotation() {
        val main = openMain()
        val config = main.config()
        config.select(1)
        val groupId = config.adapter.groupList[1].id
        config.search("Sub | Hong")
        val page = config.page(groupId)
        assertEquals(listOf("Sub | Hong Kong 01"), page.names())

        main.rotate()

        val rotated = main.config()
        assertEquals("选中的分组保留", 1, rotated.groupPager.currentItem)
        assertEquals(groupId, DataStore.selectedGroup)
        assertEquals("搜索词保留", "Sub | Hong", rotated.model.searchQuery)
        val searchView = rotated.searchView()
        assertFalse("搜索框仍展开", searchView.isIconified)
        assertEquals("Sub | Hong", searchView.query.toString())
        assertEquals("列表仍按搜索词过滤", listOf("Sub | Hong Kong 01"), rotated.page(groupId).names())

        // 清空搜索：列表还原
        searchView.setQuery("", false)
        UiSettle.settle()
        assertEquals("", rotated.model.searchQuery)
        assertEquals(3, rotated.page(groupId).names().size)
    }

    // ---------------------------------------------------------------- 进程重建

    @Test
    fun processRecreationRestoresSelectedGroupSelectedProfileAndPosition() {
        val fixtures = UiFixtures.install()
        UiFixtures.addProfiles(fixtures.normalGroup, 30)
        val main = ui.launch<MainActivity>()
        val config = main.config()
        val list = config.currentPage().configurationListView
        list.scrollBy(0, 1500)
        UiSettle.settle()
        val position = config.currentPage().firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)
        val selectedProxy = ui.offMain { DataStore.selectedProxy }
        val groupId = config.adapter.groupList[0].id

        val state = saveProcessState(main)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        UiSettle.settle(advanceMs = 500)

        val back = restored.config()
        val page = back.currentPage()
        assertEquals("选中的分组", groupId, DataStore.selectedGroup)
        assertEquals(0, back.groupPager.currentItem)
        assertEquals("进程重建后选中的节点仍是它", selectedProxy, ui.offMain { DataStore.selectedProxy })
        assertTrue("列表载入了", page.profileModel.items.size > 30)
        assertTrue("页面走到 RESUMED", page.isResumed)
        assertEquals("滚动位置恢复，没有被首次载入的定位盖掉", position, page.firstVisible())
        val selectedRow = page.profileModel.items.indexOfFirst { it.id == selectedProxy }
        assertTrue(selectedRow >= 0)
    }

    @Test
    fun processRecreationRestoresSelectedProfileMarkAndOtherGroup() {
        val fixtures = UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.config().select(1)
        val state = saveProcessState(main)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        UiSettle.settle(advanceMs = 500)

        val back = restored.config()
        assertEquals("进程重建后回到第二个分组", 1, back.groupPager.currentItem)
        assertEquals(fixtures.subscriptionGroup.id, DataStore.selectedGroup)
        assertEquals(back.groupPager.currentItem, back.tabLayout.selectedTabPosition)
        val page = back.currentPage()
        assertEquals(fixtures.subscriptionGroup.id, page.profileModel.groupId)
        assertEquals("分组 id 来自 Fragment arguments", fixtures.subscriptionGroup.id,
            page.requireArguments().getLong(ProfileListViewModel.ARG_GROUP_ID))
        assertEquals(3, page.profileModel.items.size)
        assertTrue(page.isResumed)
    }

    @Test
    fun everyRestoredPageGetsItsGroupFromArguments() {
        val main = openMain()
        val state = saveProcessState(main)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        val config = restored.config()
        val pages = config.childFragmentManager.fragments.filterIsInstance<ProfileListFragment>()
        assertTrue("恢复出了分组页", pages.isNotEmpty())
        for (page in pages) {
            assertTrue("分组页都有 arguments 里的分组 id", page.requireArguments().getLong(ProfileListViewModel.ARG_GROUP_ID) > 0)
            assertNotNull(page.listViewOrNull)
        }
    }

    @Test
    fun searchQueryRestoredAfterProcessRecreation() {
        val main = openMain()
        val config = main.config()
        config.search("Tokyo")
        assertEquals(listOf("Tokyo 01 VMess"), config.currentPage().names())

        val state = saveProcessState(main)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        UiSettle.settle(advanceMs = 500)

        val back = restored.config()
        assertEquals("搜索词恢复", "Tokyo", back.searchView().query.toString())
        assertFalse(back.searchView().isIconified)
        assertEquals("列表按搜索词过滤", listOf("Tokyo 01 VMess"), back.currentPage().names())
    }

    // ---------------------------------------------------------------- 待写出的导出内容

    @Test
    fun pendingExportConfigSurvivesRotationButNotProcessRecreation() {
        val main = openMain()
        main.config().pendingExportConfig = "{\"exported\":true}"

        main.rotate()
        assertEquals("旋转后选择器的结果送到新页面实例时内容还在", "{\"exported\":true}", main.config().pendingExportConfig)

        val state = saveProcessState(main)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        assertEquals("进程重建会丢（配置里有凭据，不进 Bundle）", "", restored.config().pendingExportConfig)
    }

    // ---------------------------------------------------------------- 选择模式

    @Test
    fun selectModeRemembersTheBrowsedGroupAcrossRotationAndProcessRecreation() {
        UiFixtures.install()
        val select = ui.launch<ProfileSelectActivity>()
        val config = select.config()
        val second = config.adapter.groupList[1].id
        config.groupPager.setCurrentItem(1, false)
        UiSettle.settle()
        assertEquals("前置条件：记下了翻到的分组", second, config.model.selectedGroupId)

        select.rotate()
        assertEquals("旋转后仍在第二个分组", 1, select.config().groupPager.currentItem)

        val state = saveProcessState(select)
        ui.destroyNow(select)
        val restored = ui.launchRestored(ProfileSelectActivity::class.java, null, state)
        UiSettle.settle(advanceMs = 500)
        assertEquals("进程重建后仍在第二个分组", 1, restored.config().groupPager.currentItem)
        assertEquals(second, restored.config().model.selectedGroupId)
    }
}
