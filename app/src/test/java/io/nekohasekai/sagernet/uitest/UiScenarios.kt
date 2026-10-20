package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileListFragment
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import moe.matsuri.nb4a.TempDatabase
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

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

    companion object {
        const val ORIGINAL_ADDRESS = "192.0.2.20"
        const val EDITED_ADDRESS = "192.0.2.99"
        const val SAVED_JSON = "{\"saved\":true}"
    }
}
