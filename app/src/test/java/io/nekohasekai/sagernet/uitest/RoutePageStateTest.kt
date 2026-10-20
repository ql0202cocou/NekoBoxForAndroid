package io.nekohasekai.sagernet.uitest

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import kotlin.concurrent.thread

/**
 * U4d d3 路由页 ViewModel：规则列表、出站节点名、滑掉的规则记录、仓库监听都由 [io.nekohasekai.sagernet.ui.RouteViewModel] 持有。
 * - 旋转后不重读库（规则、出站节点名都是载入时的那一份）、滚动位置保留；
 * - 旋转后仍能收到真正的变更通知，且监听器不重复；
 * - 旋转时撤销窗口里待删的规则：旋转后不回来，库里也已删掉；
 * - 进程重建（ViewModel 是新的）才重新读库。
 * 不截图，所有平台都跑。
 */
class RoutePageStateTest : UiTestBase() {

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    private class Opened(val main: ActivityController<MainActivity>, val nodeId: Long)

    private fun open(extraRules: Int = 0): Opened {
        val fixtures = UiFixtures.install()
        val node = fixtures.profiles.first()
        bg {
            // 一条出站指定节点的规则：行上显示节点名，名字由 ViewModel 在载入时一并查好
            RuleRepository.createRule(RuleEntity(name = "Via node", enabled = true, outbound = node.id, domains = "domain:node.example.com"))
            repeat(extraRules) { n ->
                RuleRepository.createRule(RuleEntity(name = "Extra %02d".format(n + 1), enabled = true, outbound = -1L, domains = "domain:e$n.example.com"))
            }
        }
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.ROUTES.navId)
        UiSettle.settle()
        return Opened(main, node.id)
    }

    private fun ActivityController<MainActivity>.routeFragment() =
        get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment

    private fun RouteFragment.ids() = ruleAdapter.ruleList.map { it.id }

    private fun dbIds(): List<Long> = bg { RuleRepository.getRules() }.map { it.id }

    /** 位置 [position] 当前显示的出站文字（先布局一遍，保证该位置有已绑定的行） */
    private fun RouteFragment.outboundText(position: Int): String {
        ruleListView.measure(
            View.MeasureSpec.makeMeasureSpec(ruleListView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ruleListView.height, View.MeasureSpec.EXACTLY),
        )
        ruleListView.layout(ruleListView.left, ruleListView.top, ruleListView.right, ruleListView.bottom)
        val holder = ruleListView.findViewHolderForAdapterPosition(position) as? RouteFragment.RuleAdapter.RuleHolder
            ?: error("位置 $position 没有已绑定的行")
        return holder.routeOutbound.text.toString()
    }

    // 在后台线程跑会触发仓库监听回调的操作：回调要排回主 looper，主线程不能阻塞等它，轮询 settle 直到 worker 结束
    private fun <T> viaWorker(block: () -> T): T {
        var result: Result<T>? = null
        val worker = thread(name = "ui-test-bg") { result = runCatching(block) }
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    // ------------------------------------------------------------- 旋转：不重读库、滚动位置

    @Test
    fun rotationKeepsScrollPositionAndDoesNotRereadDatabase() {
        val opened = open(extraRules = 30)
        val fragment = opened.main.routeFragment()
        val list: RecyclerView = fragment.ruleListView
        list.scrollBy(0, 900)
        UiSettle.settle()
        val position = list.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)
        val ids = fragment.ids()
        val names = fragment.ruleAdapter.ruleList.map { it.name }
        val viaNode = ids.indexOf(fragment.ruleAdapter.ruleList.first { it.name == "Via node" }.id)
        // 滚回顶部读出站文字，再恢复位置不必要：出站名从 ViewModel 的 outboundNames 取，用适配器的行内容断言
        val nodeText = fragment.ruleAdapter.currentList[viaNode].outbound

        // 绕过 Repository 直接改库（不发通知）：改名一条、删掉出站节点、再插入一条。旋转若重读了库，改动就会出现在页面上
        ui.offMain {
            val first = SagerDatabase.rulesDao.getById(ids.first())!!.apply { name = "Renamed under the hood" }
            SagerDatabase.rulesDao.updateRule(first)
            SagerDatabase.proxyDao.deleteById(opened.nodeId)
            SagerDatabase.rulesDao.createRule(RuleEntity(name = "Inserted under the hood", userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1))
        }
        opened.main.rotate()

        val rotated = opened.main.routeFragment()
        assertEquals("旋转后规则列表仍是载入时的那一份", ids, rotated.ids())
        assertEquals("旋转后规则名仍是载入时的", names, rotated.ruleAdapter.ruleList.map { it.name })
        assertEquals("旋转后出站节点名仍是载入时查好的（节点已被删，重读的话会显示错误）",
            nodeText, rotated.ruleAdapter.currentList[viaNode].outbound)
        assertEquals("旋转后滚动位置保留", position, rotated.ruleListView.firstVisible())
    }

    @Test
    fun rotationKeepsScrollPositionWhenDatabaseUnchanged() {
        val opened = open(extraRules = 30)
        val fragment = opened.main.routeFragment()
        fragment.ruleListView.scrollBy(0, 900)
        UiSettle.settle()
        val position = fragment.ruleListView.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)
        val ids = fragment.ids()

        opened.main.rotate()

        val rotated = opened.main.routeFragment()
        assertEquals(ids, rotated.ids())
        assertEquals("库没变时旋转也保持滚动位置（列表是现成的，不是等重读完再恢复）", position, rotated.ruleListView.firstVisible())
    }

    @Test
    fun processRecreationRestoresScrollPositionOnceListArrives() {
        val opened = open(extraRules = 30)
        opened.main.routeFragment().ruleListView.scrollBy(0, 900)
        UiSettle.settle()
        val position = opened.main.routeFragment().ruleListView.firstVisible()
        assertTrue("前置条件：列表没有滚动（第一个可见项 $position）", position > 0)

        val state = android.os.Bundle()
        opened.main.pause().saveInstanceState(state)
        ui.destroyNow(opened.main)
        val restored = ui.launchRestored(MainActivity::class.java, null, state)

        // 新 ViewModel 的列表是异步读回来的：布局状态等到列表非空再恢复（PREVENT_WHEN_EMPTY），不在空列表上丢掉
        assertEquals(position, restored.routeFragment().ruleListView.firstVisible())
    }

    @Test
    fun rotationShowsLoadedEmptyStateImmediately() {
        UiFixtures.install()
        // 规则全部清空（库里没有规则）：ViewModel 读过库、结果为空，旋转后页面直接显示占位，不必再等一次读库
        bg { RuleRepository.clearRules() }
        val main = ui.launch<MainActivity>()
        main.get().displayFragmentWithId(Page.ROUTES.navId)
        UiSettle.settle()
        assertEquals("前置条件：规则为空", 0, main.routeFragment().ruleAdapter.itemCount)
        assertEquals(View.VISIBLE, main.routeFragment().view!!.findViewById<View>(R.id.route_list_empty).visibility)

        // 旋转后库里多出一条规则（绕过通知）：页面仍是空的 → 没有重读
        ui.offMain { SagerDatabase.rulesDao.createRule(RuleEntity(name = "Inserted under the hood", userOrder = 1)) }
        main.rotate()

        assertEquals(0, main.routeFragment().ruleAdapter.itemCount)
        assertEquals(View.VISIBLE, main.routeFragment().view!!.findViewById<View>(R.id.route_list_empty).visibility)
    }

    // ------------------------------------------------------------- 监听器订阅

    @Test
    fun changeNotificationsStillArriveAfterRotationAndListenerIsNotDuplicated() {
        val opened = open()
        val main = opened.main
        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        val base = ListenerTable.RULE.count()
        main.get().displayFragmentWithId(Page.ROUTES.navId)
        UiSettle.settle()
        assertEquals("路由页挂了一个监听器（在 ViewModel 上）", base + 1, ListenerTable.RULE.count())

        main.rotate()
        val rotated = main.routeFragment()
        assertEquals("旋转后监听器没有重复也没有漏", base + 1, ListenerTable.RULE.count())

        val target = rotated.ruleAdapter.ruleList.first()
        val renamed = bg { RuleRepository.getRule(target.id)!! }.copy(name = "Renamed after rotation")
        viaWorker { runBlocking { RuleRepository.updateRule(renamed) } }
        assertEquals("旋转后真正的变更通知照常到达", "Renamed after rotation", rotated.ruleAdapter.ruleList.first { it.id == target.id }.name)
        assertEquals(rotated.ids().size, rotated.ruleAdapter.itemCount)

        main.get().displayFragmentWithId(Page.SETTINGS.navId)
        UiSettle.settle()
        assertEquals("离开路由页后监听器摘干净", base, ListenerTable.RULE.count())
    }

    // ------------------------------------------------------------- 旋转时撤销窗口里待删的规则

    @Test
    fun swipedRuleStaysGoneAfterRotationAndIsDeleted() {
        val opened = open(extraRules = 2)
        val fragment = opened.main.routeFragment()
        val index = 1
        val rule = fragment.ruleAdapter.ruleList[index]
        fragment.ruleAdapter.remove(index)
        fragment.undoManager.remove(index to rule)
        UiSettle.settle()

        opened.main.rotate()

        val rotated = opened.main.routeFragment()
        assertFalse("旋转后滑掉的规则不回来", rule.id in rotated.ids())
        assertFalse("旋转时撤销窗口提交了删除", rule.id in dbIds())
        assertEquals(dbIds(), rotated.ids())
        assertEquals(rotated.ids().size, rotated.ruleAdapter.itemCount)

        // 之后的整表重读（比如别处的变更通知）也不会把它读回来
        rotated.ruleAdapter.reload()
        UiSettle.settle()
        assertEquals(dbIds(), rotated.ids())
    }

    // ------------------------------------------------------------- 进程重建

    @Test
    fun processRecreationRereadsDatabase() {
        val opened = open()
        val before = opened.main.routeFragment().ids()
        val state = android.os.Bundle()
        opened.main.pause().saveInstanceState(state)
        ui.destroyNow(opened.main)

        val added = ui.offMain {
            SagerDatabase.rulesDao.createRule(RuleEntity(name = "Added while dead", userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1))
        }
        val restored = ui.launchRestored(MainActivity::class.java, null, state)

        assertEquals("进程重建后 ViewModel 是新的，重新读库", before + added, restored.routeFragment().ids())
    }
}
