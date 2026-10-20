package io.nekohasekai.sagernet.uitest

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEditableFields
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.ConfigurationHolder
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileListFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import kotlin.concurrent.thread

/**
 * U4c c5 节点列表（ListAdapter + DiffUtil）：测速结果只产生那一行的 payload 变更（M6，就地重绑、状态行淡入）；
 * 搜索过滤再清空还原列表、不重播入场动画（M5），过滤中禁用拖动，无匹配时的空状态；拖动排序落库、拖动中不被整组重读
 * 拽回；删除与撤销；三种排序方式；整组重读不再把滚动位置拽回选中项，只有首次载入才定位；空分组载入完成也显示空状态；
 * 流量实时更新不发通知；选中项切换仍走定点通知；差量认为没变的行，动作取到的仍是最新实例。不截图，所有平台都跑。
 */
class ConfigListDiffTest : UiTestBase() {

    // ---------------------------------------------------------------- 公共

    private class Setup(
        val main: ActivityController<*>,
        val config: ConfigurationFragment,
        val page: ProfileListFragment,
        val fixtures: UiFixtures.Result,
    ) {
        val adapter get() = page.adapter!!
        val list get() = page.configurationListView
    }

    private fun open(extra: Int = 0, animations: Boolean = false, selectIndex: Int? = null): Setup {
        val fixtures = UiFixtures.install()
        if (extra > 0) UiFixtures.addProfiles(fixtures.normalGroup, extra)
        if (selectIndex != null) {
            val id = ui.offMain { SagerDatabase.proxyDao.getByGroup(fixtures.normalGroup.id) }[selectIndex].id
            ui.offMain { DataStore.selectedProxy = id }
        }
        if (animations) ui.enableAnimations()
        val main = ui.launch<MainActivity>()
        val config = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
        val page = config.getCurrentGroupFragment()!!
        return Setup(main, config, page, fixtures)
    }

    private fun Setup.ids() = adapter.items.map { it.id }

    private fun dbIds(groupId: Long): List<Long> = ui.offMain { SagerDatabase.proxyDao.getByGroup(groupId) }.map { it.id }

    /** 屏幕上各行（按自上而下）绑定的节点 id */
    private fun Setup.visibleIds(): List<Long> =
        holders().sortedBy { it.view.top }.map { it.entity.id }

    /** 屏幕上的行是工作副本里连续的一段，且顺序相同（数据与视图一致） */
    private fun Setup.assertScreenMatchesList() {
        val visible = visibleIds()
        assertTrue("屏幕上没有行", visible.isNotEmpty())
        val ids = ids()
        val start = ids.indexOf(visible.first())
        assertTrue("屏幕第一行不在列表里", start >= 0)
        assertEquals(ids.subList(start, minOf(ids.size, start + visible.size)), visible)
    }

    private fun Setup.holders(): List<ConfigurationHolder> =
        (0 until list.childCount).map { list.getChildViewHolder(list.getChildAt(it)) as ConfigurationHolder }

    private fun Setup.holderOf(id: Long) = list.findViewHolderForItemId(id) as ConfigurationHolder?

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

    private fun Setup.watch() = Events().also { adapter.registerAdapterDataObserver(it) }

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

    /** 写测速结果并整组刷新，与 GroupTestRunner 收尾的两步一致（批量写不发逐行更新） */
    private fun postTestResults(groupId: Long, vararg results: Triple<Long, Int, Int>) = viaWorker {
        runBlocking {
            for ((id, status, ping) in results) {
                SagerDatabase.proxyDao.updateStatus(id, status, ping, if (status == 3) "timeout" else null)
            }
            GroupRepository.postReload(groupId)
        }
    }

    private fun setOrder(groupId: Long, order: Int) = viaWorker {
        runBlocking { GroupRepository.updateSortOrder(groupId, order) }
    }

    private fun View.findUndoAction(): View? {
        if (id == com.google.android.material.R.id.snackbar_action) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findUndoAction()?.let { return it }
        return null
    }

    private fun Setup.undoAction(): View {
        UiSettle.settle()
        return main.get().window.decorView.findUndoAction() ?: error("前置条件：没有撤销按钮")
    }

    // ---------------------------------------------------------------- M6：测速结果就地重绑

    @Test
    fun testResultUpdatesOnlyTheAffectedRowsInPlace() {
        // 动画开着：整卡交叉淡变会换出另一个 holder，这里要求同一个 holder 就地重绑
        val s = open(animations = true)
        val p = s.fixtures.profiles
        val before = s.ids()
        val target = p[3] // Local SOCKS5，未测
        val other = p[4] // Seoul，未测
        val untouched = p[6]
        val holder = s.holderOf(target.id)!!
        val untouchedHolder = s.holderOf(untouched.id)!!
        // 哨兵：整卡重绑会把名字改回节点名；没被通知的行哨兵留着
        s.holders().forEach { it.profileName.text = "sentinel" }
        val events = s.watch()

        postTestResults(s.fixtures.normalGroup.id, Triple(target.id, 1, 77), Triple(other.id, 3, 0))

        assertEquals("顺序不变", before, s.ids())
        assertEquals("没有插入 / 删除 / 移动 / 整表重置", 0, events.inserted + events.removed + events.moved + events.reset)
        // 相邻的两行合并成一个范围事件
        assertEquals("只有两行变化", 2, events.changed.sumOf { it.second })
        val changedRows = events.changed.flatMap { (start, count, _) -> (start until start + count).toList() }.toSet()
        assertEquals(setOf(before.indexOf(target.id), before.indexOf(other.id)), changedRows)
        assertTrue("变更都带 payload（就地重绑，ItemAnimator 不做整卡交叉淡变）", events.changed.all { it.third == "updated" })
        // 同一个 holder 就地重绑
        assertSame(holder, s.holderOf(target.id))
        assertEquals(target.displayName(), holder.profileName.text.toString())
        assertEquals("sentinel", untouchedHolder.profileName.text.toString())
        assertEquals(s.list.resources.getString(R.string.available, 77), holder.profileStatus.text.toString())
    }

    @Test
    fun reloadWithSameDataDispatchesNothing() {
        val s = open()
        val before = s.ids()
        val events = s.watch()

        viaWorker { runBlocking { GroupRepository.postReload(s.fixtures.normalGroup.id) } }

        assertEquals("库里读出的是新实例，卡片字段相同就不该有任何通知", 0, events.total)
        assertEquals(before, s.ids())
    }

    @Test
    fun invisibleFieldChangeDoesNotRebindButActionsSeeLatest() {
        val s = open()
        val ss = s.fixtures.profiles[1]
        val holder = s.holderOf(ss.id)!!
        val events = s.watch()

        // 密码卡片上不显示：差量认为没变、不重绑；但分享 / 导出取用的必须是新内容
        val edited = ui.offMain { SagerDatabase.proxyDao.getById(ss.id)!! }.apply { ssBean!!.password = "edited-password" }
        ui.offMain { SagerDatabase.proxyDao.updateEditableFields(ProxyEditableFields(edited)) }
        viaWorker { runBlocking { GroupRepository.postReload(s.fixtures.normalGroup.id) } }

        assertEquals(0, events.total)
        assertEquals("edited-password", holder.entity.ssBean!!.password)
    }

    @Test
    fun selectionChangeStillRebindsBothRows() {
        val s = open()
        val p = s.fixtures.profiles
        val old = p[0]
        val next = p[2]
        assertEquals(View.VISIBLE, s.holderOf(old.id)!!.selectedView.visibility)
        val events = s.watch()

        // 选中项切换（MainActivity.cbSelectorUpdate / SwitchActivity）：内容没变，差量看不出，
        // 靠 onUpdated 的定点通知把两行的选中态重画
        ui.offMain { DataStore.selectedProxy = next.id }
        viaWorker { runBlocking { ProfileRepository.postUpdate(old.id); ProfileRepository.postUpdate(next.id) } }

        assertEquals(2, events.changed.size)
        assertTrue(events.changed.all { it.third == "updated" })
        assertEquals(0, events.inserted + events.removed + events.moved + events.reset)
        UiSettle.settle()
        assertEquals(View.INVISIBLE, s.holderOf(old.id)!!.selectedView.visibility)
        assertEquals(View.VISIBLE, s.holderOf(next.id)!!.selectedView.visibility)
    }

    private fun ConfigurationHolder.card() = view as MaterialCardView

    /** 选中的卡片：底色叠 primary 8%、描边 primary 40%、1dp 线宽；未选中的是默认底色与默认描边 */
    @Test
    fun selectedCardHasFillAndStrokeAndMovesWithSelection() {
        val s = open()
        val p = s.fixtures.profiles
        val primary = s.page.requireContext().getColorAttr(androidx.appcompat.R.attr.colorPrimary)
        val selected = s.holderOf(p[0].id)!!.card()
        val other = s.holderOf(p[2].id)!!.card()
        val defaultFill = other.cardBackgroundColor.defaultColor
        val defaultStroke = other.strokeColor

        assertEquals(MaterialColors.layer(defaultFill, primary, 0.08f), selected.cardBackgroundColor.defaultColor)
        assertEquals(ColorUtils.setAlphaComponent(primary, 102), selected.strokeColor)
        assertTrue("选中卡片的描边宽度", selected.strokeWidth > 0)
        assertNotEquals("选中卡片的底色应不同于默认", defaultFill, selected.cardBackgroundColor.defaultColor)
        assertNotEquals("选中卡片的描边应不同于默认", defaultStroke, selected.strokeColor)

        // 选中项切换：新卡片带样式，旧卡片回到默认（与从没选过的卡片一致）
        ui.offMain { DataStore.selectedProxy = p[2].id }
        viaWorker { runBlocking { ProfileRepository.postUpdate(p[0].id); ProfileRepository.postUpdate(p[2].id) } }
        UiSettle.settle()
        assertEquals(defaultFill, s.holderOf(p[0].id)!!.card().cardBackgroundColor.defaultColor)
        assertEquals(defaultStroke, s.holderOf(p[0].id)!!.card().strokeColor)
        assertEquals(MaterialColors.layer(defaultFill, primary, 0.08f), s.holderOf(p[2].id)!!.card().cardBackgroundColor.defaultColor)
        assertEquals(ColorUtils.setAlphaComponent(primary, 102), s.holderOf(p[2].id)!!.card().strokeColor)
    }

    @Test
    fun liveTrafficRedrawsRowWithoutNotifications() {
        val s = open()
        val target = s.fixtures.profiles[3] // 没有流量的节点
        val holder = s.holderOf(target.id)!!
        assertEquals("", holder.profileStatus.text.toString())
        val events = s.watch()

        viaWorker { runBlocking { ProfileRepository.postUpdate(TrafficData(target.id, 2048, 4096)) } }

        assertEquals("流量只重画该行的流量段，不发任何适配器通知", 0, events.total)
        // 流量显示在上面那一行，测速结果那一格仍空着
        assertEquals(View.VISIBLE, holder.trafficText.visibility)
        assertTrue(holder.trafficText.text.isNotEmpty())
        assertEquals("", holder.profileStatus.text.toString())
        assertEquals(2048L, s.adapter.configurationList[target.id]!!.tx)
    }

    @Test
    fun testResultKeepsCardHeightOfNodeWithTraffic() {
        val s = open()
        val target = s.fixtures.profiles[4] // Seoul：有流量、未测
        val holder = s.holderOf(target.id)!!
        val before = holder.itemView.height
        assertTrue(before > 0)
        val traffic = holder.trafficText.text.toString()

        postTestResults(s.fixtures.normalGroup.id, Triple(target.id, 1, 77))
        UiSettle.settle()

        assertSame(holder, s.holderOf(target.id))
        assertEquals(s.list.resources.getString(R.string.available, 77), holder.profileStatus.text.toString())
        assertEquals("流量留在原来那一行", traffic, holder.trafficText.text.toString())
        assertEquals("测速结果出现不改变卡片高度", before, holder.itemView.height)
    }

    // ---------------------------------------------------------------- 搜索过滤（M5：不重播入场动画）

    @Test
    fun filterThenClearRestoresListAndDoesNotReplayEnterAnimation() {
        val s = open(animations = true)
        val all = s.ids()
        assertNotNull("前置条件：冷启动播入场动画", s.list.layoutAnimation)
        // 模拟入场动画播完（它的结束回调只在真正绘制时触发）
        s.list.layoutAnimation = null
        val events = s.watch()

        s.adapter.filter("tokyo")
        UiSettle.settle()
        assertEquals(listOf(s.fixtures.profiles[0].id), s.ids())
        assertTrue(s.adapter.isFiltered)
        assertNull("过滤不重播入场动画", s.list.layoutAnimation)
        assertEquals(0, events.reset)

        s.adapter.filter("")
        UiSettle.settle()
        assertEquals("清空后还原成完整列表，顺序不变", all, s.ids())
        assertFalse(s.adapter.isFiltered)
        assertNull("清空搜索也不重播入场动画", s.list.layoutAnimation)
        assertEquals("差量更新，没有整表重置", 0, events.reset)
        assertEquals(s.ids().toSet().size, s.ids().size)
    }

    @Test
    fun filterMatchesNameTypeAndAddress() {
        val s = open()
        val p = s.fixtures.profiles
        s.adapter.filter("VMESS")
        UiSettle.settle()
        assertEquals("按类型名匹配（Tokyo 与长名字都是 VMess）", listOf(p[0].id, p[5].id), s.ids())
        s.adapter.filter("192.0.2.20")
        UiSettle.settle()
        assertEquals("按地址匹配", listOf(p[1].id), s.ids())
        s.adapter.filter("")
        UiSettle.settle()
        assertEquals(p.take(7).map { it.id }, s.ids())
    }

    @Test
    fun noMatchShowsEmptyStateAndClearHidesIt() {
        val s = open()
        val empty = s.page.requireView().findViewById<View>(R.id.profile_list_empty)
        assertEquals("前置条件：有节点时不显示占位", View.GONE, empty.visibility)

        s.adapter.filter("no-such-node")
        UiSettle.settle()
        assertEquals(0, s.adapter.itemCount)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(
            s.list.resources.getString(R.string.profile_list_no_match),
            s.page.requireView().findViewById<TextView>(R.id.empty_title).text.toString(),
        )

        s.adapter.filter("")
        UiSettle.settle()
        assertEquals(View.GONE, empty.visibility)
        assertEquals(7, s.adapter.itemCount)
    }

    @Test
    fun emptyGroupShowsEmptyStateAfterLoadWithoutAnyListEvent() {
        val s = open()
        // 新建一个空分组并切过去：它的第一次载入是「空列表 → 空列表」，差量不发任何通知，
        // 载入完成与空状态靠 onListSubmitted 补上
        val created = viaWorker { runBlocking { GroupRepository.createGroup(ProxyGroup(name = "Empty")) } }
        val index = s.config.adapter.groupList.indexOfFirst { it.id == created.id }
        assertTrue("前置条件：新分组进了 tab 列表", index >= 0)
        s.config.groupPager.setCurrentItem(index, false)
        UiSettle.settle()
        val page = s.config.childFragmentManager.findFragmentByTag("f${created.id}") as ProfileListFragment
        assertTrue(page.isResumed)
        assertEquals(0, page.adapter!!.itemCount)
        val empty = page.requireView().findViewById<View>(R.id.profile_list_empty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(
            page.getString(R.string.profile_list_empty),
            page.requireView().findViewById<TextView>(R.id.empty_title).text.toString(),
        )
    }

    @Test
    fun dragIsDisabledWhileFiltering() {
        val s = open()
        ServiceRegistry.state = BaseService.State.Stopped
        val helper = s.page.itemTouchHelper!!
        val callback = ItemTouchHelper::class.java.getDeclaredField("mCallback")
            .apply { isAccessible = true }.get(helper) as ItemTouchHelper.Callback
        val holder = s.holders().first()

        assertFalse(s.adapter.isFiltered)
        assertTrue("未过滤、服务可操作时可以拖动", callback.getMovementFlags(s.list, holder) != 0)

        s.adapter.filter("singapore")
        UiSettle.settle()
        assertTrue(s.adapter.isFiltered)
        assertEquals("过滤中禁用拖动", 0, callback.getMovementFlags(s.list, holder))

        s.adapter.filter("")
        UiSettle.settle()
        assertTrue("清空后恢复", callback.getMovementFlags(s.list, s.holders().first()) != 0)
    }

    // ---------------------------------------------------------------- 拖动排序

    @Test
    fun dragReorderPersistsAndSurvivesReload() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()

        // 与 ItemTouchHelper 回调一致：onMove → adapter.move，clearView → adapter.commitMove
        assertTrue(s.adapter.move(0, 2))
        UiSettle.settle()
        val dragged = listOf(before[1], before[2], before[0]) + before.drop(3)
        assertEquals(dragged, s.ids())
        s.assertScreenMatchesList()
        assertEquals("拖动期间不落库", before, dbIds(groupId))

        s.adapter.commitMove()
        UiSettle.settle()
        assertEquals("落下后库里是同一顺序", dragged, dbIds(groupId))

        // 重读读出同样的顺序：差量无变化，列表不动
        val events = s.watch()
        viaWorker { runBlocking { GroupRepository.postReload(groupId) } }
        assertEquals(dragged, s.ids())
        s.assertScreenMatchesList()
        assertEquals(0, events.total)
    }

    @Test
    fun reloadDuringDragWaitsForDrop() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()
        assertTrue(s.adapter.move(0, 1))
        UiSettle.settle()
        val dragged = s.ids()
        assertTrue(before != dragged)

        // 拖动中来了一次整组重读（库里还是旧顺序，比如订阅更新或测速结束）：不能把正在拖的列表拽回去
        viaWorker { runBlocking { GroupRepository.postReload(groupId) } }
        assertEquals(dragged, s.ids())

        s.adapter.commitMove()
        UiSettle.settle()
        assertEquals(dragged, dbIds(groupId))
        assertEquals("落库后补读一次，列表与库一致", dragged, s.ids())
        s.assertScreenMatchesList()
    }

    // ---------------------------------------------------------------- 删除与撤销

    @Test
    fun removeButtonThenUndoRestoresPosition() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()
        val events = s.watch()
        val victim = before[2]

        s.holderOf(victim)!!.removeButton.performClick()
        UiSettle.settle()
        assertEquals(before - victim, s.ids())
        s.assertScreenMatchesList()
        assertEquals("撤销窗口里还没落库", before, dbIds(groupId))

        s.undoAction().performClick()
        UiSettle.settle()
        assertEquals("撤销按原位置插回", before, s.ids())
        s.assertScreenMatchesList()
        assertEquals(before, dbIds(groupId))
        assertEquals(1, events.removed)
        assertEquals(1, events.inserted)
        assertEquals("删除与撤销都是精确通知，没有整表重置", 0, events.reset)

        UiSettle.settle(advanceMs = 10_000)
        assertEquals("窗口随撤销关闭，之后什么都不会再被删", before, dbIds(groupId))
        assertEquals(before, s.ids())
    }

    @Test
    fun removeCommitsAfterWindowAndListStaysConsistent() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()
        val victim = before[2]

        s.holderOf(victim)!!.removeButton.performClick()
        UiSettle.settle()
        s.page.undoManager.flush()
        UiSettle.settle()

        assertEquals(before - victim, dbIds(groupId))
        assertEquals(before - victim, s.ids())
        s.assertScreenMatchesList()
    }

    @Test
    fun removeWhileFilteredThenUndoThenClearKeepsOrder() {
        val s = open()
        val p = s.fixtures.profiles
        val before = s.ids()

        s.adapter.filter("vmess")
        UiSettle.settle()
        assertEquals(listOf(p[0].id, p[5].id), s.ids())
        s.holderOf(p[5].id)!!.removeButton.performClick()
        UiSettle.settle()
        assertEquals(listOf(p[0].id), s.ids())
        s.undoAction().performClick()
        UiSettle.settle()
        assertEquals(listOf(p[0].id, p[5].id), s.ids())

        s.adapter.filter("")
        UiSettle.settle()
        assertEquals("搜索中删除再撤销，清空后回到原顺序", before, s.ids())
        assertEquals(s.ids().toSet().size, s.ids().size)
    }

    // ---------------------------------------------------------------- 新增 / 导入

    @Test
    fun newProfilesAppendAtTheEnd() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()
        val events = s.watch()

        val created = viaWorker {
            runBlocking {
                ProfileRepository.createProfiles(groupId, (1..3).map {
                    SOCKSBean().apply { name = "Imported $it"; serverAddress = "192.0.2.${100 + it}"; serverPort = 1080 }
                })
            }
        }

        assertEquals(before + created.map { it.id }, s.ids())
        s.assertScreenMatchesList()
        assertEquals(3, events.inserted)
        assertEquals(0, events.reset)
        assertEquals(dbIds(groupId), s.ids())
    }

    @Test
    fun addAfterConcurrentReloadDoesNotDuplicate() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        // 直接写库、不经监听：先让整组重读读到新行，再补发 onAdd，复现「重读先到、onAdd 后到」
        val created = ui.offMain {
            val entity = io.nekohasekai.sagernet.database.ProxyEntity(groupId = groupId).apply {
                putBean(SOCKSBean().apply { name = "Raced"; serverAddress = "192.0.2.201"; serverPort = 1080; initializeDefaultValues() })
                userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
            }
            entity.id = SagerDatabase.proxyDao.addProxy(entity)
            entity
        }
        viaWorker { runBlocking { GroupRepository.postReload(groupId) } }
        assertTrue("前置条件：重读已读进新行", created.id in s.ids())

        viaWorker { runBlocking { s.adapter.onAdd(created) } }

        val ids = s.ids()
        assertEquals("onAdd 不能再追加一行", 1, ids.count { it == created.id })
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(dbIds(groupId), ids)
        assertEquals(ids.size, s.adapter.itemCount)
        s.assertScreenMatchesList()
    }

    @Test
    fun undoAfterConcurrentReloadDoesNotDuplicate() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val before = s.ids()
        s.holderOf(before[2])!!.removeButton.performClick()
        val action = s.undoAction()
        // 整组重读的主线程部分排在撤销的 post 之前：它读到的库里还有这一行
        ui.offMain { s.adapter.reloadProfiles() }
        action.performClick()
        UiSettle.settle()

        val ids = s.ids()
        assertEquals(before, ids)
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(ids.size, s.adapter.itemCount)
        s.assertScreenMatchesList()
        assertEquals(before, dbIds(groupId))
    }

    // ---------------------------------------------------------------- 排序方式

    @Test
    fun orderModeSwitchMovesRowsWithoutRebinding() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val p = s.fixtures.profiles.take(7).map { it.id }
        val events = s.watch()

        setOrder(groupId, GroupOrder.BY_NAME)
        assertEquals("按名称", listOf(p[5], p[3], p[6], p[2], p[4], p[1], p[0]), s.ids())
        s.assertScreenMatchesList()
        assertTrue("改排序方式只移动行", events.moved > 0)
        assertEquals(0, events.reset)
        assertEquals("内容没变的行不重绑", 0, events.changed.size)

        setOrder(groupId, GroupOrder.BY_DELAY)
        assertEquals("按延迟：可用的按 ping 升序，其余按原顺序", listOf(p[0], p[1], p[5], p[2], p[3], p[4], p[6]), s.ids())
        s.assertScreenMatchesList()

        setOrder(groupId, GroupOrder.ORIGIN)
        assertEquals("原始顺序", p, s.ids())
        s.assertScreenMatchesList()
        assertEquals(0, events.reset)
    }

    @Test
    fun testResultsReorderInDelayMode() {
        val s = open()
        val groupId = s.fixtures.normalGroup.id
        val p = s.fixtures.profiles.take(7).map { it.id }
        setOrder(groupId, GroupOrder.BY_DELAY)
        val events = s.watch()

        // 未测的节点测出 20 ms：整组重读把它排到最前，状态行同时就地重绑
        postTestResults(groupId, Triple(p[4], 1, 20))

        assertEquals(listOf(p[4], p[0], p[1], p[5], p[2], p[3], p[6]), s.ids())
        s.assertScreenMatchesList()
        assertEquals(0, events.reset)
        assertTrue(events.changed.all { it.third == "updated" })
        assertEquals(s.list.resources.getString(R.string.available, 20), s.holderOf(p[4])!!.profileStatus.text.toString())
    }

    // ---------------------------------------------------------------- 滚动位置

    @Test
    fun reloadDoesNotPullBackToSelectedRow() {
        val s = open(extra = 30)
        val groupId = s.fixtures.normalGroup.id
        UiSettle.settle(advanceMs = 500) // 先让首次载入的定位（含 300 ms 后的平滑滚动）走完
        s.list.scrollBy(0, 1500)
        UiSettle.settle()
        val manager = s.list.layoutManager as LinearLayoutManager
        val firstBefore = manager.findFirstVisibleItemPosition()
        assertTrue("前置条件：列表已滚动（第一个可见项 $firstBefore）", firstBefore > 0)
        val idBefore = s.adapter.items[firstBefore].id
        val offsetBefore = s.list.computeVerticalScrollOffset()

        // 测速结果、订阅更新都是整组重读：以前每次都 scrollTo(选中项)，把用户拽回顶部
        postTestResults(groupId, Triple(s.adapter.items[firstBefore + 1].id, 1, 50))
        UiSettle.settle(advanceMs = 500) // 旧实现的平滑滚动在 300 ms 后启动

        assertEquals(idBefore, s.adapter.items[manager.findFirstVisibleItemPosition()].id)
        assertEquals(offsetBefore, s.list.computeVerticalScrollOffset())
    }

    @Test
    fun firstLoadStillScrollsToSelectedRow() {
        val s = open(extra = 30, selectIndex = 25)
        UiSettle.settle(advanceMs = 500)
        val manager = s.list.layoutManager as LinearLayoutManager
        val selected = s.adapter.indexOfId(ui.offMain { DataStore.selectedProxy })
        assertEquals(25, selected)
        assertTrue(
            "首次载入把选中项滚进视野（可见 ${manager.findFirstVisibleItemPosition()}..${manager.findLastVisibleItemPosition()}）",
            selected in manager.findFirstVisibleItemPosition()..manager.findLastVisibleItemPosition(),
        )
    }

    // ---------------------------------------------------------------- 选择模式（选择节点页、链成员选择）

    @Test
    fun selectModeListsRowsAndReturnsClickedProfile() {
        val fixtures = UiFixtures.install()
        val activity = ui.launch<io.nekohasekai.sagernet.ui.ProfileSelectActivity>()
        val config = activity.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment
        val page = config.getCurrentGroupFragment()!!
        val s = Setup(activity, config, page, fixtures)
        assertEquals(fixtures.profiles.take(7).map { it.id }, s.ids())
        assertTrue("选择模式没有删除 / 编辑按钮", s.holders().all { it.removeButton.visibility == View.GONE && it.editButton.visibility == View.GONE })
        s.assertScreenMatchesList()

        // 选择模式下搜索 / 重读同样走差量
        val events = s.watch()
        viaWorker { runBlocking { GroupRepository.postReload(fixtures.normalGroup.id) } }
        assertEquals(0, events.total)

        s.holderOf(fixtures.profiles[2].id)!!.view.performClick()
        val shadow = org.robolectric.Shadows.shadowOf(activity.get())
        assertEquals(android.app.Activity.RESULT_OK, shadow.resultCode)
        assertEquals(
            fixtures.profiles[2].id,
            shadow.resultIntent.getLongExtra(io.nekohasekai.sagernet.ui.ProfileSelectActivity.EXTRA_PROFILE_ID, -1L),
        )
    }

    // ---------------------------------------------------------------- 大分组

    @Test
    fun largeGroupDiffStaysCorrect() {
        val s = open(extra = 600)
        val groupId = s.fixtures.normalGroup.id
        assertEquals(607, s.adapter.itemCount)
        val events = s.watch()

        // 整张表换序（按名称）与还原：差量要给出正确结果，数百行主线程算得过来
        val start = System.nanoTime()
        setOrder(groupId, GroupOrder.BY_NAME)
        val byName = s.ids()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("LARGE-GROUP reorder 607 rows: $elapsedMs ms (含读库与 settle)")
        assertEquals(ui.offMain { SagerDatabase.proxyDao.getByGroup(groupId) }.sortedBy { it.displayName() }.map { it.id }, byName)
        assertEquals(607, byName.toSet().size)
        assertEquals(0, events.reset)

        setOrder(groupId, GroupOrder.ORIGIN)
        assertEquals(dbIds(groupId), s.ids())
        s.assertScreenMatchesList()
    }
}
