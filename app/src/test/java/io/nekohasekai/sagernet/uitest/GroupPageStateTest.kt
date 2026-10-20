package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.haveLink
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import kotlin.concurrent.thread

/**
 * U4d d3 分组页 ViewModel：列表、撤销窗口提交的删除、仓库监听都由 [io.nekohasekai.sagernet.ui.GroupViewModel] 持有。
 * - 旋转后不重读库、滚动位置保留（做法：载入之后绕过 Repository 直接改库，旋转后页面仍是载入时的那一份）；
 * - 旋转后仍能收到真正的变更通知，且监听器不重复（旋转不多挂一个，离开页面摘干净）；
 * - 旋转时撤销窗口里待删的分组：旋转后不回来，库里也已删掉；
 * - 选中的分组 id 经 SavedStateHandle 带过进程重建，文件选择器的结果送达新页面实例时仍导出对的那一组。
 * 不截图，所有平台都跑。
 */
class GroupPageStateTest : UiTestBase() {

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    private fun addGroups(count: Int) = ui.offMain {
        repeat(count) { n ->
            SagerDatabase.groupDao.createGroup(
                ProxyGroup(name = "Extra %02d".format(n + 1)).apply { userOrder = SagerDatabase.groupDao.nextOrder() ?: 1 }
            )
        }
    }

    private fun open(extraGroups: Int = 0): Pair<ActivityController<MainActivity>, GroupFragment> {
        UiFixtures.install()
        addGroups(extraGroups)
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.GROUPS.navId)
        UiSettle.settle()
        return main to main.groupFragment()
    }

    private fun ActivityController<MainActivity>.groupFragment() =
        get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment

    private fun GroupFragment.ids() = groupAdapter.groupList.map { it.id }
    private fun GroupFragment.names() = groupAdapter.groupList.map { it.name }
    private fun dbIds(): List<Long> = ui.offMain { SagerDatabase.groupDao.allGroups() }.map { it.id }

    // ------------------------------------------------------------- 旋转：不重读库、滚动位置

    @Test
    fun rotationKeepsScrollPositionAndDoesNotRereadDatabase() {
        val (main, fragment) = open(extraGroups = 16)
        val list = fragment.groupListView
        list.scrollBy(0, 700)
        UiSettle.settle()
        val position = list.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)
        val ids = fragment.ids()
        val names = fragment.names()

        // 绕过 Repository 直接改库（不发通知）：改名一个、再插入一个。旋转若重读了库，改动就会出现在列表里
        ui.offMain {
            val first = SagerDatabase.groupDao.getById(ids.first())!!.apply { name = "Renamed under the hood" }
            SagerDatabase.groupDao.updateGroup(first)
            SagerDatabase.groupDao.createGroup(
                ProxyGroup(name = "Inserted under the hood").apply { userOrder = SagerDatabase.groupDao.nextOrder() ?: 1 }
            )
        }
        main.rotate()

        val rotated = main.groupFragment()
        assertEquals("旋转后分组列表仍是载入时的那一份", ids, rotated.ids())
        assertEquals("旋转后分组名仍是载入时的", names, rotated.names())
        assertEquals("旋转后滚动位置保留", position, rotated.groupListView.firstVisible())
    }

    @Test
    fun rotationKeepsScrollPositionWhenDatabaseUnchanged() {
        val (main, fragment) = open(extraGroups = 16)
        val list = fragment.groupListView
        list.scrollBy(0, 700)
        UiSettle.settle()
        val position = list.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)
        val ids = fragment.ids()

        main.rotate()

        val rotated = main.groupFragment()
        assertEquals(ids, rotated.ids())
        assertEquals("库没变时旋转也保持滚动位置（列表是现成的，不是等重读完再恢复）", position, rotated.groupListView.firstVisible())
    }

    @Test
    fun processRecreationRestoresScrollPositionOnceListArrives() {
        val (main, fragment) = open(extraGroups = 16)
        fragment.groupListView.scrollBy(0, 700)
        UiSettle.settle()
        val position = fragment.groupListView.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)

        val state = android.os.Bundle()
        main.pause().saveInstanceState(state)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)

        // 新 ViewModel 的列表是异步读回来的：布局状态等到列表非空再恢复（PREVENT_WHEN_EMPTY），不在空列表上丢掉
        assertEquals(position, restored.groupFragment().groupListView.firstVisible())
    }

    // ------------------------------------------------------------- 监听器订阅

    @Test
    fun changeNotificationsStillArriveAfterRotationAndListenerIsNotDuplicated() {
        val (main, fragment) = open()
        // 先去设置页量一下基数（配置页自己也挂监听器），再回分组页
        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        val base = ListenerTable.GROUP.count()
        main.get().displayFragmentWithId(Page.GROUPS.navId)
        UiSettle.settle()
        assertEquals("分组页挂了一个监听器（在 ViewModel 上）", base + 1, ListenerTable.GROUP.count())

        main.rotate()
        val rotated = main.groupFragment()
        assertEquals("旋转后监听器没有重复也没有漏", base + 1, ListenerTable.GROUP.count())

        val target = rotated.groupAdapter.groupList.first()
        val renamed = ui.offMain { SagerDatabase.groupDao.getById(target.id)!! }.apply { name = "Renamed after rotation" }
        val changes = ArrayList<Int>()
        rotated.groupAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changes += positionStart
            }
        })
        viaWorker { runBlocking { GroupRepository.updateGroup(renamed) } }
        assertEquals("旋转后真正的变更通知照常到达", "Renamed after rotation", rotated.groupAdapter.groupList.first().name)
        assertEquals("只发一次变更（监听器没有重复）", listOf(0), changes)

        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        assertEquals("离开分组页后监听器摘干净", base, ListenerTable.GROUP.count())
    }

    // 在后台线程跑会触发仓库监听回调的操作：回调要切回主线程，主线程不能阻塞等它，轮询 settle 直到 worker 结束
    private fun <T> viaWorker(block: () -> T): T {
        var result: Result<T>? = null
        val worker = thread(name = "ui-test-bg") { result = runCatching(block) }
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    // ------------------------------------------------------------- 旋转时撤销窗口里待删的分组

    @Test
    fun swipedGroupStaysGoneAfterRotationAndIsDeleted() {
        val (main, fragment) = open(extraGroups = 2)
        val index = fragment.ids().size - 1
        val group = fragment.groupAdapter.groupList[index]
        fragment.groupAdapter.remove(index)
        fragment.undoManager.remove(index to group)
        UiSettle.settle()

        main.rotate()

        val rotated = main.groupFragment()
        assertFalse("旋转后滑掉的分组不回来", group.id in rotated.ids())
        assertFalse("旋转时撤销窗口提交了删除", group.id in dbIds())
        assertEquals(dbIds(), rotated.ids())
        assertEquals(rotated.ids().size, rotated.groupAdapter.itemCount)
    }

    // ------------------------------------------------------------- selectedGroupId

    @Test
    fun selectedGroupIdSurvivesRotation() {
        val (main, fragment) = open()
        val chosen = fragment.groupAdapter.groupList.last()
        fragment.groupModel.selectedGroupId = chosen.id

        main.rotate()

        assertEquals(chosen.id, main.groupFragment().groupModel.selectedGroupId)
    }

    @Test
    fun selectedGroupIdSurvivesProcessRecreation() {
        val (main, fragment) = open()
        val chosen = fragment.groupAdapter.groupList.last()
        fragment.groupModel.selectedGroupId = chosen.id

        val state = android.os.Bundle()
        main.pause().saveInstanceState(state)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)
        val back = restored.groupFragment()

        assertEquals("进程重建后选中的分组 id 仍在（ViewModel 是新的，只能来自 SavedStateHandle）",
            chosen.id, back.groupModel.selectedGroupId)
    }

    /**
     * 端到端：点分组 B 的「更多」菜单 → 导出到文件 → 选择器开着时进程死亡 → 新进程里选择器的结果送达新页面实例。
     * 写出的文件必须是 B 的链接，而不是别的分组的（新实例里点菜单的那次点击已不存在）。
     */
    @Test
    fun exportPickerResultAfterProcessRecreationExportsTheChosenGroup() {
        val fixtures = UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.GROUPS.navId)
        UiSettle.settle()
        val fragment = main.groupFragment()

        // 选订阅分组（不是列表第一个），用真实的点击路径设置 selectedGroupId，再点「导出到文件」
        val target = fixtures.subscriptionGroup
        val holder = fragment.groupListView.let { list ->
            (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) as GroupFragment.GroupHolder }
        }.first { it.proxyGroup.id == target.id }
        holder.optionsButton.performClick()
        UiSettle.settle()
        assertEquals("前置条件：点「更多」记下了分组", target.id, fragment.groupModel.selectedGroupId)
        holder.onMenuItemClick(MenuBuilder(main.get()).add(0, R.id.action_export_file, 0, ""))
        val launched = shadowOf(main.get()).nextStartedActivityForResult
            ?: error("前置条件：没有拉起文件选择器")

        val expected = bg { ProfileRepository.getProfilesByGroup(target.id) }
            .filter { it.haveLink() }.map { it.toStdLink() }
        assertTrue("前置条件：目标分组有可导出的节点", expected.isNotEmpty())
        val others = bg { ProfileRepository.getProfilesByGroup(fixtures.normalGroup.id) }
            .filter { it.haveLink() }.map { it.toStdLink() }
        assertTrue("前置条件：另一个分组也有节点，导错了能看出来", others.isNotEmpty())

        val state = android.os.Bundle()
        main.pause().saveInstanceState(state)
        ui.destroyNow(main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)

        val file = File.createTempFile("export-", ".txt").apply { deleteOnExit() }
        val result = Intent().setData(Uri.fromFile(file))
        restored.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, result)
        UiSettle.settle()

        assertEquals(expected.joinToString("\n"), file.readText())
        assertNotEquals(others.joinToString("\n"), file.readText())
    }
}
