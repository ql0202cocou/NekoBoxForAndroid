package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import org.junit.Test
import org.robolectric.android.controller.ActivityController

/**
 * 截图基线用例：每个画面一个用例，档位取自 [ScreenshotMatrix]。
 * 夹具、环境归零、settle 见 UiFixtures / UiTestRule / UiSettle。
 */
class ScreenshotTest : UiTestBase() {

    private fun shotsOf(screen: Screen) = ScreenshotMatrix.shots.filter { it.screen == screen }

    private fun mainActivity(): ActivityController<MainActivity> {
        UiFixtures.install()
        return ui.launch<MainActivity>()
    }

    private fun ActivityController<MainActivity>.page(id: Int) {
        get().displayFragmentWithId(id)
        UiSettle.settle()
    }

    private fun ActivityController<MainActivity>.shot(shot: Shot) {
        Screenshots.check(shot, get().window.decorView)
    }

    @Test
    fun configList() {
        val main = mainActivity()
        for (shot in shotsOf(Screen.CONFIG_LIST)) main.shot(shot)
    }

    @Test
    fun configConnected() {
        val main = mainActivity()
        main.get().stateChanged(BaseService.State.Connected, null, null)
        // 状态卡片在 delay(100) 之后才展开，推进虚拟时钟跑掉它
        UiSettle.settle(advanceMs = 500)
        for (shot in shotsOf(Screen.CONFIG_CONNECTED)) main.shot(shot)
    }

    @Test
    fun groups() {
        val main = mainActivity()
        main.page(R.id.nav_group)
        for (shot in shotsOf(Screen.GROUPS)) main.shot(shot)
    }

    @Test
    fun routes() {
        val main = mainActivity()
        main.page(R.id.nav_route)
        for (shot in shotsOf(Screen.ROUTES)) main.shot(shot)
    }

    @Test
    fun settingsHub() {
        val main = mainActivity()
        main.page(R.id.nav_settings)
        for (shot in shotsOf(Screen.SETTINGS_HUB)) main.shot(shot)
    }

    // 设置的七个分类页，截全长
    private fun settingsSection(screen: Screen, index: Int) {
        val main = mainActivity()
        val key = SettingsPreferenceFragment.sections[index].first
        main.get().displayFragment(SettingsFragment.section(key))
        UiSettle.settle()
        for (shot in shotsOf(screen)) Screenshots.checkFullLength(shot, main.get().window.decorView)
    }

    @Test fun settingsService() = settingsSection(Screen.SETTINGS_SERVICE, 0)
    @Test fun settingsInterface() = settingsSection(Screen.SETTINGS_INTERFACE, 1)
    @Test fun settingsNotification() = settingsSection(Screen.SETTINGS_NOTIFICATION, 2)
    @Test fun settingsRoute() = settingsSection(Screen.SETTINGS_ROUTE, 3)
    @Test fun settingsDns() = settingsSection(Screen.SETTINGS_DNS, 4)
    @Test fun settingsInbound() = settingsSection(Screen.SETTINGS_INBOUND, 5)
    @Test fun settingsAdvanced() = settingsSection(Screen.SETTINGS_ADVANCED, 6)

    // 节点编辑器：长表单（VMess）与短表单（Shadowsocks），截全长
    private fun <A : Activity> editor(type: Class<A>, profileIndex: Int): ActivityController<A> {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), type)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[profileIndex].id)
        return ui.launch(type, intent)
    }

    @Test
    fun editorVmess() {
        val editor = editor(VMessSettingsActivity::class.java, 0)
        for (shot in shotsOf(Screen.EDITOR_VMESS)) Screenshots.checkFullLength(shot, editor.get().window.decorView)
    }

    @Test
    fun editorShadowsocks() {
        val editor = editor(ShadowsocksSettingsActivity::class.java, 1)
        for (shot in shotsOf(Screen.EDITOR_SHADOWSOCKS)) Screenshots.checkFullLength(shot, editor.get().window.decorView)
    }

    // 对话框：和下面的页面一起截（对话框在自己的窗口里）
    @Test
    fun dialogDeleteConfirm() {
        val editor = editor(ShadowsocksSettingsActivity::class.java, 1)
        editor.get().deleteConfirmationDialog().show(editor.get().supportFragmentManager, null)
        UiSettle.settle()
        for (shot in shotsOf(Screen.DIALOG_DELETE_CONFIRM)) Screenshots.checkScreen(shot)
    }

    @Test
    fun dialogUnsavedChanges() {
        val editor = editor(ShadowsocksSettingsActivity::class.java, 1)
        // 改过内容再按返回：和用户一样走返回键，由编辑器自己弹提示
        EditorCache.dirty = true
        editor.get().onBackPressedDispatcher.onBackPressed()
        UiSettle.settle()
        for (shot in shotsOf(Screen.DIALOG_UNSAVED_CHANGES)) Screenshots.checkScreen(shot)
    }

    // 设置页上点一行，弹出它的对话框
    private fun settingsDialog(screen: Screen, sectionIndex: Int, rowTitle: String) {
        val main = mainActivity()
        val key = SettingsPreferenceFragment.sections[sectionIndex].first
        main.get().displayFragment(SettingsFragment.section(key))
        UiSettle.settle()
        clickRow(main.get().window.decorView, rowTitle)
        UiSettle.settle()
        for (shot in shotsOf(screen)) Screenshots.checkScreen(shot)
    }

    @Test fun dialogThemePicker() = settingsDialog(Screen.DIALOG_THEME_PICKER, 1, "Theme")
    // 单选列表对话框（MTU）与文本输入对话框（Remote DNS 的 EditTextPreference）
    @Test fun dialogListChoice() = settingsDialog(Screen.DIALOG_LIST_CHOICE, 0, "MTU")
    @Test fun dialogTextInput() = settingsDialog(Screen.DIALOG_TEXT_INPUT, 4, "Remote DNS")

    // 找到标题为 [title] 的行，点击它的可点击祖先（偏好行）
    private fun clickRow(root: View, title: String) {
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString() == title) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var row: View = find(root) ?: error("找不到行：$title")
        while (!row.isClickable) row = row.parent as View
        row.performClick()
    }
}
