package io.nekohasekai.sagernet.uitest

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * U4c c2 路由列表 ListAdapter + DiffUtil：增 / 改 / 删 / 重置经差量事件反映到列表，没变的行不被重绑；
 * 开关就地更新；拖动排序落库后重开顺序不变；删除后撤销位置正确；出站节点改名后行文字更新；空状态占位。
 * 不截图，所有平台都跑。每个用例自己一个 Activity（Robolectric 同一用例内资源缓存会串）。
 */
class RouteListDiffTest : UiTestBase() {

    /** 记录适配器收到的数据事件；不含初始载入（注册在载入之后） */
    private class Recorder : RecyclerView.AdapterDataObserver() {
        val events = ArrayList<String>()
        override fun onChanged() { events += "all" }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "insert($positionStart,$itemCount)" }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "remove($positionStart,$itemCount)" }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            events += "move($fromPosition,$toPosition,$itemCount)"
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            events += "change($positionStart,$itemCount,${payload ?: "-"})"
        }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            events += "change($positionStart,$itemCount,-)"
        }

        fun take(): List<String> = events.toList().also { events.clear() }
    }

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    private fun open(): Pair<ActivityController<MainActivity>, RouteFragment> {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(R.id.nav_route)
        UiSettle.settle()
        val fragment = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
        assertTrue("前置条件：路由页不足 3 条规则", fragment.ruleAdapter.itemCount >= 3)
        return main to fragment
    }

    private fun RouteFragment.watch(): Recorder = Recorder().also { ruleAdapter.registerAdapterDataObserver(it) }

    private fun RouteFragment.holder(position: Int): RouteFragment.RuleAdapter.RuleHolder {
        ruleListView.measure(
            View.MeasureSpec.makeMeasureSpec(ruleListView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ruleListView.height, View.MeasureSpec.EXACTLY),
        )
        ruleListView.layout(ruleListView.left, ruleListView.top, ruleListView.right, ruleListView.bottom)
        return ruleListView.findViewHolderForAdapterPosition(position) as? RouteFragment.RuleAdapter.RuleHolder
            ?: error("位置 $position 没有已绑定的行")
    }

    private fun RouteFragment.shownNames() = (0 until ruleAdapter.itemCount).map { holder(it).profileName.text.toString() }

    private fun RouteFragment.ids() = ruleAdapter.ruleList.map { it.id }

    private fun dbIds(): List<Long> = bg { RuleRepository.getRules() }.map { it.id }

    private fun RouteFragment.emptyVisible() = view!!.findViewById<View>(R.id.route_list_empty).visibility == View.VISIBLE

    private fun RouteFragment.assertConsistent(message: String) {
        assertEquals("$message：适配器条数与列表一致", ruleAdapter.ruleList.size, ruleAdapter.itemCount)
        assertEquals("$message：适配器内容与列表一致", ids(), ruleAdapter.currentList.map { it.id })
        assertEquals("$message：列表与库一致", dbIds(), ids())
    }

    @Test
    fun addUpdateRemoveGoThroughDiff() {
        val (_, fragment) = open()
        val recorder = fragment.watch()
        val holdersBefore = (0 until 3).map { fragment.holder(it) }

        // 新增：只在末尾插一行，已有的行不动
        val created = bg { RuleRepository.createRule(RuleEntity(name = "Extra", enabled = true, domains = "domain:extra.example.com")) }
        UiSettle.settle()
        assertEquals(listOf("insert(3,1)"), recorder.take())
        assertEquals("Extra", fragment.holder(3).profileName.text.toString())
        fragment.assertConsistent("新增后")

        // 修改：只有这一行收到变化事件，且是整行重绑（没有 payload，与改动前一致）
        bg { RuleRepository.updateRule(created.copy(name = "Extra renamed")) }
        UiSettle.settle()
        assertEquals(listOf("change(3,1,-)"), recorder.take())
        assertEquals("Extra renamed", fragment.holder(3).profileName.text.toString())

        // 内容没变的更新不产生任何事件
        bg { RuleRepository.updateRule(created.copy(name = "Extra renamed")) }
        UiSettle.settle()
        assertEquals(emptyList<String>(), recorder.take())

        // 删除：只在原位置删一行
        bg { RuleRepository.deleteRule(fragment.ids()[1]) }
        UiSettle.settle()
        assertEquals(listOf("remove(1,1)"), recorder.take())
        fragment.assertConsistent("删除后")

        // 没被波及的行还是原来的 ViewHolder（没有整表重绑）
        assertSame(holdersBefore[0], fragment.holder(0))
        assertSame(holdersBefore[2], fragment.holder(1))
    }

    @Test
    fun resetRoutesClearsThenRebuilds() {
        val (_, fragment) = open()
        val recorder = fragment.watch()
        val count = fragment.ruleAdapter.itemCount

        // 与菜单「重置路由」同一条路径：先清空（RuleListener.onCleared），再清标记、重读建出默认规则
        bg { RuleRepository.clearRules() }
        UiSettle.settle()
        assertEquals(listOf("remove(0,$count)"), recorder.take())
        assertEquals(0, fragment.ruleAdapter.itemCount)
        assertTrue("清空后应显示空状态占位", fragment.emptyVisible())

        DataStore.rulesFirstCreate = false
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        val events = recorder.take()
        assertTrue("重建默认规则后应只有插入事件：$events", events.isNotEmpty() && events.all { it.startsWith("insert(") })
        assertTrue(fragment.ruleAdapter.itemCount > 0)
        assertFalse("有规则后占位应隐藏", fragment.emptyVisible())
        fragment.assertConsistent("重置后")
    }

    @Test
    fun enableSwitchRebindsInPlace() {
        val (_, fragment) = open()
        val recorder = fragment.watch()
        val first = fragment.holder(0)
        val before = first.enableSwitch.isChecked

        first.enableSwitch.performClick()
        UiSettle.settle()

        // 开关已是新状态，差量只发一个带 payload 的变化事件：就地重绑，不触发整卡交叉淡变
        assertEquals(listOf("change(0,1,enabled)"), recorder.take())
        assertSame(first, fragment.holder(0))
        assertEquals(!before, first.enableSwitch.isChecked)
        assertEquals(!before, bg { RuleRepository.getRules() }.first().enabled)

        // 再提交一次相同内容不再有事件
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(emptyList<String>(), recorder.take())
    }

    @Test
    fun dragReorderPersistsAcrossReopen() {
        val (_, fragment) = open()
        val recorder = fragment.watch()
        val before = fragment.ids()
        val adapter = fragment.ruleAdapter

        // 拖动期间每次换位都是一个移动事件，列表与显示同步
        assertTrue(adapter.move(0, 2))
        val first = recorder.take()
        assertEquals(1, first.size)
        assertTrue("拖动换位应是一次移动：$first", first.single().startsWith("move("))
        val expected = before.toMutableList().apply { add(2, removeAt(0)) }
        assertEquals(expected, fragment.ids())
        assertEquals(expected, adapter.currentList.map { it.id })

        assertTrue(adapter.move(1, 0))
        assertEquals(1, recorder.take().size)
        expected.add(0, expected.removeAt(1))
        assertEquals(expected, fragment.ids())

        // 松手：与 clearView 一致，提交顺序
        adapter.commitMove()
        UiSettle.settle()
        assertEquals("落库顺序应与列表一致", expected, dbIds())

        // 重开：新建 Activity 读库后顺序不变
        ui.finishActivities()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(R.id.nav_route)
        UiSettle.settle()
        val reopened = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
        assertEquals(expected, reopened.ids())
        assertEquals(expected, reopened.ruleAdapter.currentList.map { it.id })
        assertEquals(reopened.ruleAdapter.ruleList.map { it.displayName() }, reopened.shownNames())
    }

    @Test
    fun deleteThenUndoRestoresPosition() {
        val (main, fragment) = open()
        val recorder = fragment.watch()
        val before = fragment.ids()
        val names = fragment.shownNames()

        val index = 1
        val rule = fragment.ruleAdapter.ruleList[index]
        fragment.ruleAdapter.remove(index)
        fragment.undoManager.remove(index to rule)
        assertEquals(listOf("remove(1,1)"), recorder.take())
        assertEquals(before - rule.id, fragment.ids())

        UiSettle.settle()
        val undo = main.get().window.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_action)
        assertNotNull("前置条件：没有撤销按钮", undo)
        undo.performClick()
        UiSettle.settle()
        assertEquals(listOf("insert(1,1)"), recorder.take())
        assertEquals(before, fragment.ids())
        assertEquals(names, fragment.shownNames())
        fragment.assertConsistent("撤销后")
    }

    @Test
    fun deleteCommitsAfterUndoWindow() {
        val (_, fragment) = open()
        val before = fragment.ids()
        val rule = fragment.ruleAdapter.ruleList[0]
        fragment.ruleAdapter.remove(0)
        fragment.undoManager.remove(0 to rule)

        // 撤销窗口走完：删除落库，列表不变
        UiSettle.settle(advanceMs = 30_000)
        assertEquals(before - rule.id, dbIds())
        assertEquals(before - rule.id, fragment.ids())
        fragment.assertConsistent("窗口到期后")
    }

    @Test
    fun outboundNodeRenameUpdatesRow() {
        val node = UiFixtures.install().profiles.first()
        val oldName = node.displayName()
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(R.id.nav_route)
        UiSettle.settle()
        val fragment = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
        val recorder = fragment.watch()

        val created = bg { RuleRepository.createRule(RuleEntity(name = "Via node", enabled = true, outbound = node.id, domains = "domain:node.example.com")) }
        UiSettle.settle()
        val index = fragment.ids().indexOf(created.id)
        assertEquals(listOf("insert($index,1)"), recorder.take())
        assertEquals(oldName, fragment.holder(index).routeOutbound.text.toString())

        // 节点改名不通知规则监听器：下一次整表重读时只有这一行变，其余行不动
        bg {
            val entity = ProfileRepository.getProfile(node.id)!!
            entity.requireBean().name = "Renamed node"
            ProfileRepository.updateProfile(entity)
        }
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(listOf("change($index,1,-)"), recorder.take())
        assertEquals("Renamed node", fragment.holder(index).routeOutbound.text.toString())

        // 重读但没有变化：没有事件
        fragment.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(emptyList<String>(), recorder.take())

        // 经规则更新回调（onUpdated）带出的新名字同样更新这一行
        bg {
            val entity = ProfileRepository.getProfile(node.id)!!
            entity.requireBean().name = "Renamed twice"
            ProfileRepository.updateProfile(entity)
        }
        bg { RuleRepository.updateRule(created.copy(domains = "domain:node.example.com\ndomain:more.example.com")) }
        UiSettle.settle()
        assertEquals(listOf("change($index,1,-)"), recorder.take())
        assertEquals("Renamed twice", fragment.holder(index).routeOutbound.text.toString())
    }

    @Test
    fun emptyStateFollowsList() {
        val (_, fragment) = open()
        assertFalse(fragment.emptyVisible())
        val ids = fragment.ids()
        ids.forEach { id -> bg { RuleRepository.deleteRule(id) } }
        UiSettle.settle()
        assertEquals(0, fragment.ruleAdapter.itemCount)
        assertTrue(fragment.emptyVisible())

        bg { RuleRepository.createRule(RuleEntity(name = "Back", enabled = true, domains = "domain:back.example.com")) }
        UiSettle.settle()
        assertEquals(1, fragment.ruleAdapter.itemCount)
        assertFalse(fragment.emptyVisible())
    }
}
