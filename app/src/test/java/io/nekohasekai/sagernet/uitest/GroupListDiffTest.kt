package io.nekohasekai.sagernet.uitest

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import kotlin.concurrent.thread

/**
 * U4c c3 分组列表（ListAdapter + DiffUtil）：订阅进度只走 payload 刷新受影响的那张卡；拖动排序落库且
 * 拖动期间不被整表重读拽回；groupAdd 的延迟窗口里整表重读不再多出一行；整表重读无变化时不发任何通知；
 * 删除 / 撤销 / 清空各自正确。不截图，所有平台都跑。
 */
class GroupListDiffTest : UiTestBase() {

    @After
    fun resetUpdaterState() {
        // GroupUpdater 的运行状态是进程内静态的，别漏给后面的用例
        GroupUpdater.updating.clear()
        GroupUpdater.progress.clear()
    }

    // ---------------------------------------------------------------- 公共

    private fun open(extraGroups: Int = 0): Pair<ActivityController<MainActivity>, GroupFragment> {
        UiFixtures.install()
        repeat(extraGroups) { n ->
            ui.offMain {
                SagerDatabase.groupDao.createGroup(
                    ProxyGroup(name = "Extra ${n + 1}").apply { userOrder = SagerDatabase.groupDao.nextOrder() ?: 1 }
                )
            }
        }
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.GROUPS.navId)
        UiSettle.settle()
        val fragment = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
        return main to fragment
    }

    private fun GroupFragment.ids() = groupAdapter.groupList.map { it.id }

    private fun dbIds(): List<Long> = ui.offMain { SagerDatabase.groupDao.allGroups() }.map { it.id }

    /** 屏幕上各行（按自上而下）绑定的分组 id */
    private fun GroupFragment.visibleIds(): List<Long> {
        val list = groupListView
        return (0 until list.childCount).map { list.getChildAt(it) }
            .sortedBy { it.top }
            .map { (list.getChildViewHolder(it) as GroupFragment.GroupHolder).proxyGroup.id }
    }

    private fun GroupFragment.holders(): List<GroupFragment.GroupHolder> {
        val list = groupListView
        return (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) as GroupFragment.GroupHolder }
    }

    /** 记下适配器收到的全部通知 */
    private class Events : RecyclerView.AdapterDataObserver() {
        val changed = ArrayList<Triple<Int, Int, Any?>>()
        var inserted = 0
        var removed = 0
        var moved = 0
        var reset = 0
        override fun onChanged() { reset++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { changed += Triple(positionStart, itemCount, payload) }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { inserted += itemCount }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { removed += itemCount }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { moved += itemCount }
        val total get() = changed.size + inserted + removed + moved + reset
    }

    /**
     * 在后台线程跑会触发仓库监听回调的操作：回调要切回主线程，主线程不能阻塞等它，
     * 所以轮询 settle 直到 worker 结束
     */
    private fun <T> viaWorker(block: () -> T): T {
        var result: Result<T>? = null
        val worker = thread(name = "ui-test-bg") { result = runCatching(block) }
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    // ---------------------------------------------------------------- 订阅进度

    @Test
    fun progressUpdatesOnlyTheAffectedCard() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        val target = adapter.groupList.first { it.type == GroupType.SUBSCRIPTION }
        val targetIndex = adapter.groupList.indexOf(target)
        assertTrue("前置条件：至少两张可见卡片", fragment.holders().size >= 2)
        // 哨兵：整卡重绑会立刻把名字改回分组名；只刷进度条时哨兵留着
        fragment.holders().forEach { it.groupName.text = "sentinel" }
        val events = Events().also { adapter.registerAdapterDataObserver(it) }

        GroupUpdater.updating.add(target.id)
        GroupUpdater.progress[target.id] = GroupUpdater.Progress(10).apply { progress = 3 }
        viaWorker { runBlocking { GroupManager.postProgress(target.id) } }

        assertEquals("只该有一条通知", 1, events.total)
        val (position, count, payload) = events.changed.single()
        assertEquals(targetIndex, position)
        assertEquals(1, count)
        assertEquals("progress", payload)
        assertTrue("所有卡片都不该被整卡重绑", fragment.holders().all { it.groupName.text.toString() == "sentinel" })
        val bar = fragment.holders().first { it.proxyGroup.id == target.id }.subscriptionUpdateProgress
        assertEquals(View.VISIBLE, bar.visibility)
        assertFalse(bar.isIndeterminate)
        assertEquals(10, bar.max)
        assertEquals(3, bar.progress)

        // 再前进一步：仍然只有进度条变
        GroupUpdater.progress[target.id]!!.progress = 7
        viaWorker { runBlocking { GroupManager.postProgress(target.id) } }
        assertEquals(2, events.total)
        assertEquals(7, fragment.holders().first { it.proxyGroup.id == target.id }.subscriptionUpdateProgress.progress)
        assertTrue(fragment.holders().all { it.groupName.text.toString() == "sentinel" })

        // 更新结束后的整卡刷新（groupUpdated(groupId)）会收回进度条并重绑
        GroupUpdater.updating.remove(target.id)
        GroupUpdater.progress.remove(target.id)
        viaWorker { runBlocking { GroupManager.postReload(target.id) } }
        val rebound = fragment.holders().first { it.proxyGroup.id == target.id }
        assertEquals(View.INVISIBLE, rebound.subscriptionUpdateProgress.visibility)
        assertEquals(target.displayName(), rebound.groupName.text.toString())
    }

    // ---------------------------------------------------------------- 差量

    @Test
    fun reloadWithSameDataDispatchesNothing() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        val before = fragment.ids()
        val events = Events().also { adapter.registerAdapterDataObserver(it) }

        adapter.reload()
        UiSettle.settle()

        assertEquals("库里读出的是新实例，内容相同就不该有任何通知", 0, events.total)
        assertEquals(before, fragment.ids())
    }

    @Test
    fun editedGroupRebindsOnlyItsOwnRow() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        val target = adapter.groupList.first()
        val events = Events().also { adapter.registerAdapterDataObserver(it) }

        val renamed = ui.offMain { SagerDatabase.groupDao.getById(target.id)!! }.apply { name = "Renamed" }
        viaWorker { runBlocking { GroupRepository.updateGroup(renamed) } }

        assertEquals(1, events.total)
        val (position, count, payload) = events.changed.single()
        assertEquals(0, position)
        assertEquals(1, count)
        assertNull("整卡重绑，不带 payload", payload)
        assertEquals("Renamed", fragment.holders().first { it.proxyGroup.id == target.id }.groupName.text.toString())
    }

    @Test
    fun invisibleFieldChangeDoesNotRebindButActionsSeeLatest() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        val sub = adapter.groupList.first { it.type == GroupType.SUBSCRIPTION }
        val events = Events().also { adapter.registerAdapterDataObserver(it) }

        // 订阅链接卡片上不显示：差量认为没变，不重绑；但二维码 / 立即更新取用的必须是新链接
        val edited = ui.offMain { SagerDatabase.groupDao.getById(sub.id)!! }.apply {
            subscription!!.link = "https://example.com/edited"
        }
        viaWorker { runBlocking { GroupRepository.updateGroup(edited) } }

        assertEquals(0, events.total)
        val holder = fragment.holders().first { it.proxyGroup.id == sub.id }
        assertEquals("https://example.com/edited", holder.proxyGroup.subscription!!.link)
    }

    @Test
    fun clearGroupRefreshesNodeCount() {
        val (_, fragment) = open()
        val normal = fragment.groupAdapter.groupList.first { it.name == UiFixtures.NORMAL_GROUP }
        val before = fragment.holders().first { it.proxyGroup.id == normal.id }.groupStatus.text.toString()
        assertTrue("前置条件：分组里有节点（$before）", before != app.getString(R.string.group_status_empty))

        viaWorker { runBlocking { GroupRepository.clearGroup(normal.id) } }

        val holder = fragment.holders().first { it.proxyGroup.id == normal.id }
        assertEquals(app.getString(R.string.group_status_empty), holder.groupStatus.text.toString())
        assertEquals(0L, ui.offMain { runBlocking { ProfileRepository.countProfilesByGroup(normal.id) } })
    }

    // ---------------------------------------------------------------- 拖动排序

    @Test
    fun dragReorderPersistsAndSurvivesReload() {
        val (_, fragment) = open(extraGroups = 1)
        val adapter = fragment.groupAdapter
        val before = fragment.ids()
        assertTrue("前置条件：至少三个分组", before.size >= 3)

        // 与 ItemTouchHelper 回调一致：onMove → adapter.move，clearView → adapter.commitMove
        assertTrue(adapter.move(0, 2))
        UiSettle.settle()
        val dragged = listOf(before[1], before[2], before[0]) + before.drop(3)
        assertEquals(dragged, fragment.ids())
        assertEquals("屏幕上的行与工作副本一致", dragged, fragment.visibleIds())
        assertEquals("拖动期间不落库", before, dbIds())

        adapter.commitMove()
        UiSettle.settle()
        assertEquals("落下后库里是同一顺序", dragged, dbIds())

        // 重读读出同样的顺序：差量无变化，列表不动
        val events = Events().also { adapter.registerAdapterDataObserver(it) }
        adapter.reload()
        UiSettle.settle()
        assertEquals(dragged, fragment.ids())
        assertEquals(dragged, fragment.visibleIds())
        assertEquals(0, events.total)
    }

    @Test
    fun reloadDuringDragWaitsForDrop() {
        val (_, fragment) = open(extraGroups = 1)
        val adapter = fragment.groupAdapter
        val before = fragment.ids()
        assertTrue(adapter.move(0, 1))
        UiSettle.settle()
        val dragged = fragment.ids()
        assertTrue(before != dragged)

        // 拖动中来了一次整表重读（库里还是旧顺序）：不能把正在拖的列表拽回去
        adapter.reload()
        UiSettle.settle()
        assertEquals(dragged, fragment.ids())

        adapter.commitMove()
        UiSettle.settle()
        assertEquals(dragged, dbIds())
        assertEquals("落库后补读一次，列表与库一致", dragged, fragment.ids())
        assertEquals(dragged, fragment.visibleIds())
    }

    // ---------------------------------------------------------------- 新建分组

    @Test
    fun groupAddWithConcurrentReloadDoesNotDuplicate() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        // 直接写库、不经监听：下面手动触发 groupAdd，才能把重读塞进它的延迟窗口
        val created = ui.offMain {
            val group = ProxyGroup(name = "Fresh").apply { userOrder = SagerDatabase.groupDao.nextOrder() ?: 1 }
            group.id = SagerDatabase.groupDao.createGroup(group)
            group
        }
        // 主线程协程上的 delay 走 looper 的虚拟时钟，可以推进
        CoroutineScope(Dispatchers.Main).launch { fragment.groupModel.groupAdd(created) }
        UiSettle.settle()
        assertFalse("前置条件：延迟期间新分组还没进列表", created.id in fragment.ids())

        adapter.reload()
        UiSettle.settle()
        assertEquals("重读已读进新分组", 1, fragment.ids().count { it == created.id })

        UiSettle.settle(advanceMs = 400)
        val ids = fragment.ids()
        assertEquals("延迟结束后不能再追加一行", 1, ids.count { it == created.id })
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(ids.size, adapter.itemCount)
        assertEquals(ids, fragment.visibleIds())
        assertEquals(dbIds(), ids)
    }

    @Test
    fun groupAddAppendsWhenNoReloadHappened() {
        val (_, fragment) = open()
        val adapter = fragment.groupAdapter
        val before = fragment.ids()

        // 经真实的仓库监听回调：延迟是 worker 协程上的真实时间，viaWorker 轮询到它结束
        val created = viaWorker { runBlocking { GroupRepository.createGroup(ProxyGroup(name = "Added")) } }

        assertEquals(before + created.id, fragment.ids())
        assertEquals(fragment.ids(), fragment.visibleIds())
        assertEquals(fragment.ids().size, adapter.itemCount)
    }

    // ---------------------------------------------------------------- 删除与撤销

    private fun View.findUndoAction(): View? {
        if (id == com.google.android.material.R.id.snackbar_action) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findUndoAction()?.let { return it }
        return null
    }

    private fun swipe(fragment: GroupFragment, index: Int): ProxyGroup {
        val group = fragment.groupAdapter.groupList[index]
        fragment.groupAdapter.remove(index)
        fragment.undoManager.remove(index to group)
        return group
    }

    @Test
    fun swipeDeleteThenUndoRestoresPosition() {
        val (main, fragment) = open(extraGroups = 1)
        val before = fragment.ids()
        val events = Events().also { fragment.groupAdapter.registerAdapterDataObserver(it) }

        val deleted = swipe(fragment, 1)
        UiSettle.settle()
        assertEquals(before - deleted.id, fragment.ids())
        assertEquals(fragment.ids(), fragment.visibleIds())
        assertEquals("撤销窗口里还没落库", before, dbIds())

        val action = main.get().window.decorView.findUndoAction() ?: error("前置条件：没有撤销按钮")
        action.performClick()
        UiSettle.settle()

        assertEquals("撤销按原位置插回", before, fragment.ids())
        assertEquals(before, fragment.visibleIds())
        assertEquals(before, dbIds())
        assertEquals(1, events.removed)
        assertEquals(1, events.inserted)
        assertEquals("滑动与撤销都是精确通知，没有整表重置", 0, events.reset)

        // 窗口随撤销关闭，之后什么都不会再被删
        UiSettle.settle(advanceMs = 10_000)
        assertEquals(before, dbIds())
        assertEquals(before, fragment.ids())
    }

    @Test
    fun swipeDeleteCommitsAndListStaysConsistent() {
        val (_, fragment) = open(extraGroups = 1)
        val before = fragment.ids()

        val deleted = swipe(fragment, 1)
        UiSettle.settle()
        // 窗口到期 / 别处改列表都会提交：这里直接提交
        fragment.undoManager.flush()
        UiSettle.settle()

        assertEquals(before - deleted.id, dbIds())
        assertEquals(before - deleted.id, fragment.ids())
        assertEquals(fragment.ids(), fragment.visibleIds())
        assertEquals(fragment.ids().size, fragment.groupAdapter.itemCount)
    }
}
