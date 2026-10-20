package io.nekohasekai.sagernet.uitest

import android.view.View
import android.view.ViewGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import kotlinx.coroutines.runBlocking
import org.robolectric.android.controller.ActivityController

/**
 * U4c c1 撤销入口的场景：删一行，在撤销窗口内改列表（走真实的仓库监听回调），再点撤销；
 * 以及连删几行后撤销、撤销时列表已变短。判定函数返回 [Check]，不自己断言。
 */
class UndoScenarios(private val ui: UiTestRule) {

    private fun main(): ActivityController<MainActivity> {
        UiFixtures.install()
        return ui.launch<MainActivity>()
    }

    private fun ActivityController<MainActivity>.go(page: Page) {
        get().displayFragmentWithId(page.navId)
        UiSettle.settle()
    }

    private fun MainActivity.page() = supportFragmentManager.findFragmentById(R.id.fragment_holder)

    /** 当前窗口里「撤销」按钮：删除后立刻取一次，之后即使气泡正在关闭也能点它 */
    private fun View.findUndoAction(): View? {
        if (id == com.google.android.material.R.id.snackbar_action) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findUndoAction()?.let { return it }
        return null
    }

    private fun MainActivity.undoAction(): View {
        UiSettle.settle()
        return window.decorView.findUndoAction() ?: error("前置条件：没有撤销按钮")
    }

    private fun View.clickUndo() {
        performClick()
        UiSettle.settle()
    }

    private fun ruleIds(fragment: RouteFragment) = fragment.ruleAdapter.ruleList.map { it.id }

    private fun dbRuleIds(): List<Long> = ui.offMain { runBlocking { RuleRepository.getRules() } }.map { it.id }

    private fun routeFragment(main: ActivityController<MainActivity>): RouteFragment {
        main.go(Page.ROUTES)
        val fragment = main.get().page() as RouteFragment
        if (fragment.ruleAdapter.ruleList.size < 3) error("前置条件：路由页不足 3 条规则")
        return fragment
    }

    private fun swipeRule(fragment: RouteFragment, index: Int) {
        val rule = fragment.ruleAdapter.ruleList[index]
        fragment.ruleAdapter.remove(index)
        fragment.undoManager.remove(index to rule)
    }

    /** 路由：删一行后别处新增规则（onAdd 回调），再点撤销——不崩，列表与库一致，被删的不会回来 */
    fun routeUndoAfterAdd(): Check {
        val main = main()
        val fragment = routeFragment(main)
        val before = ruleIds(fragment)
        swipeRule(fragment, 1)
        val action = main.get().undoAction()
        ui.offMain {
            runBlocking { RuleRepository.createRule(RuleEntity(name = "Extra", enabled = true, outbound = 0L, domains = "domain:extra.example.com")) }
        }
        UiSettle.settle()
        val afterAdd = ruleIds(fragment)
        action.clickUndo()
        val after = ruleIds(fragment)
        val db = dbRuleIds()
        if (before[1] in after) return Check(false, "撤销窗口已被新增关闭，被删的规则不应再回来：$after")
        if (after != afterAdd) return Check(false, "点撤销改动了列表：$afterAdd -> $after")
        if (after.toSet().size != after.size) return Check(false, "列表有重复项：$after")
        return if (after == db) Check(true) else Check(false, "列表与库不一致：列表 $after，库 $db")
    }

    /** 路由：删一行后别处更新另一条规则（onUpdated 回调），再点撤销 */
    fun routeUndoAfterUpdate(): Check {
        val main = main()
        val fragment = routeFragment(main)
        val target = fragment.ruleAdapter.ruleList[2]
        swipeRule(fragment, 0)
        val action = main.get().undoAction()
        val updated = target.copy().apply { name = "Renamed" }
        ui.offMain { runBlocking { RuleRepository.updateRule(updated) } }
        UiSettle.settle()
        action.clickUndo()
        val after = ruleIds(fragment)
        val db = dbRuleIds()
        if (after.toSet().size != after.size) return Check(false, "列表有重复项：$after")
        return if (after == db) Check(true) else Check(false, "列表与库不一致：列表 $after，库 $db")
    }

    /** 路由：连删两行再撤销，顺序与删除前相同 */
    fun routeUndoSeveralDeletions(): Check {
        val main = main()
        val fragment = routeFragment(main)
        val before = ruleIds(fragment)
        swipeRule(fragment, 2)
        swipeRule(fragment, 0)
        main.get().undoAction().clickUndo()
        val after = ruleIds(fragment)
        val db = dbRuleIds()
        if (after != before) return Check(false, "撤销后顺序变了：$before -> $after")
        return if (db == before) Check(true) else Check(false, "撤销后库里的规则变了：$db")
    }

    /** 路由：撤销时列表已被绕过入口改短（模拟下标失效），按夹取插回，不崩 */
    fun routeUndoIntoShorterList(): Check {
        val main = main()
        val fragment = routeFragment(main)
        swipeRule(fragment, 2)
        swipeRule(fragment, 1)
        val action = main.get().undoAction()
        val adapter = fragment.ruleAdapter
        adapter.ruleList.clear()
        adapter.notifyDataSetChanged()
        action.clickUndo()
        return if (adapter.ruleList.size == 2 && adapter.itemCount == 2) Check(true)
        else Check(false, "期望插回 2 条，实际 ${adapter.ruleList.size} 条")
    }

    private fun groupFragment(main: ActivityController<MainActivity>): GroupFragment {
        main.go(Page.GROUPS)
        val fragment = main.get().page() as GroupFragment
        if (fragment.groupAdapter.groupList.size < 2) error("前置条件：分组页不足 2 个分组")
        return fragment
    }

    private fun swipeGroup(fragment: GroupFragment, index: Int): ProxyGroup {
        val group = fragment.groupAdapter.groupList[index]
        fragment.groupAdapter.remove(index)
        fragment.undoManager.remove(index to group)
        return group
    }

    /**
     * 后台线程建分组，监听回调（groupAdd）要切主线程：主线程不能阻塞等它，所以轮询 settle 直到 worker 结束
     */
    private fun createGroupViaListener(): ProxyGroup {
        var result: Result<ProxyGroup>? = null
        val worker = kotlin.concurrent.thread(name = "ui-test-bg") {
            result = runCatching { runBlocking { GroupRepository.createGroup(ProxyGroup(name = "Extra")) } }
        }
        // 回调在主线程上跑，worker 要等主线程处理完才结束：轮询让主线程处理，不阻塞它。
        // groupAdd 的 300 ms 是协程真实延迟，不推进虚拟时钟（推进会让撤销气泡超时，提前触发提交）
        while (worker.isAlive) UiSettle.settle()
        UiSettle.settle()
        return result!!.getOrThrow()
    }

    private fun dbGroupIds(): List<Long> = ui.offMain { runBlocking { GroupRepository.getAllGroups() } }.map { it.id }

    /** 分组：删一行后别处新增分组（groupAdd 回调，延迟 300 ms），再点撤销——不崩，列表一致，被删的不会回来 */
    fun groupUndoAfterAdd(): Check {
        val main = main()
        val fragment = groupFragment(main)
        val deleted = swipeGroup(fragment, fragment.groupAdapter.groupList.lastIndex)
        val action = main.get().undoAction()
        val created = createGroupViaListener()
        val afterAdd = fragment.groupAdapter.groupList.map { it.id }
        action.clickUndo()
        val after = fragment.groupAdapter.groupList.map { it.id }
        if (deleted.id in after) return Check(false, "撤销窗口已被新增关闭，被删的分组不应再回来：$after")
        if (created.id !in after) return Check(false, "新增的分组不在列表里：$after")
        if (after != afterAdd) return Check(false, "点撤销改动了列表：$afterAdd -> $after")
        if (after.toSet().size != after.size) return Check(false, "列表有重复项：$after")
        if (fragment.groupAdapter.itemCount != after.size) return Check(false, "适配器条数与列表不一致")
        return if (deleted.id !in dbGroupIds()) Check(true) else Check(false, "被删的分组没有落库删除")
    }

    /** 分组：连删两行再撤销，顺序与删除前相同 */
    fun groupUndoSeveralDeletions(): Check {
        val main = main()
        val fragment = groupFragment(main)
        val adapter = fragment.groupAdapter
        // 补一个分组，保证至少三行可删
        createGroupViaListener()
        val before = adapter.groupList.map { it.id }
        if (before.size < 3) error("前置条件：分组页不足 3 个分组")
        swipeGroup(fragment, before.lastIndex)
        swipeGroup(fragment, 0)
        main.get().undoAction().clickUndo()
        val after = adapter.groupList.map { it.id }
        return if (after == before) Check(true) else Check(false, "撤销后顺序变了：$before -> $after")
    }

    /** 分组：撤销时列表已被绕过入口改短，按夹取插回，不崩 */
    fun groupUndoIntoShorterList(): Check {
        val main = main()
        val fragment = groupFragment(main)
        val adapter = fragment.groupAdapter
        swipeGroup(fragment, adapter.groupList.lastIndex)
        val action = main.get().undoAction()
        adapter.groupList.clear()
        adapter.notifyDataSetChanged()
        action.clickUndo()
        return if (adapter.groupList.size == 1 && adapter.itemCount == 1) Check(true)
        else Check(false, "期望插回 1 个，实际 ${adapter.groupList.size} 个")
    }
}
