package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

/**
 * 截图基线用例：[ScreenshotMatrix] 的每个档位一个用例（参数化），各自：写夹具 → 设备配置（宽度、字号、夜间）与主题
 * → 启动页面 → 截图。必须一档一个用例：Robolectric 在同一个用例里换夜间模式 / 主题后，已缓存的颜色状态列表
 * 会沿用上一档的解析结果（实测：同一用例里先亮后暗，暗色下选中的 Tab 文字仍是亮色下的黑色），
 * 每个用例有全新的 Resources，才能让每张图与执行顺序无关。
 * 夹具、环境归零、settle 见 UiFixtures / UiTestRule / UiSettle。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ScreenshotTest(private val shot: Shot) : UiTestBase() {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = ScreenshotMatrix.shots.map { arrayOf(it) }
    }

    private lateinit var fixtures: UiFixtures.Result

    @Test
    fun render() {
        fixtures = UiFixtures.install()
        applyDevice(shot)
        when (shot.screen) {
            Screen.CONFIG_LIST -> mainActivity().shot()
            Screen.CONFIG_CONNECTED -> {
                val main = mainActivity()
                main.get().stateChanged(BaseService.State.Connected, null, null)
                // 状态卡片在 delay(100) 之后才展开，推进虚拟时钟跑掉它
                UiSettle.settle(advanceMs = 500)
                main.shot()
            }
            Screen.GROUPS -> mainActivity().apply { page(R.id.nav_group) }.shot()
            Screen.ROUTES -> mainActivity().apply { page(R.id.nav_route) }.shot()
            Screen.SETTINGS_HUB -> mainActivity().apply { page(R.id.nav_settings) }.shot()

            // 设置的七个分类页，截全长
            Screen.SETTINGS_SERVICE -> settingsSection(0)
            Screen.SETTINGS_INTERFACE -> settingsSection(1)
            Screen.SETTINGS_NOTIFICATION -> settingsSection(2)
            Screen.SETTINGS_ROUTE -> settingsSection(3)
            Screen.SETTINGS_DNS -> settingsSection(4)
            Screen.SETTINGS_INBOUND -> settingsSection(5)
            Screen.SETTINGS_ADVANCED -> settingsSection(6)

            // 节点编辑器：长表单（VMess）与短表单（Shadowsocks），截全长
            Screen.EDITOR_VMESS ->
                Screenshots.checkFullLength(shot, editor(VMessSettingsActivity::class.java, 0).get().window.decorView)
            Screen.EDITOR_SHADOWSOCKS ->
                Screenshots.checkFullLength(shot, editor(ShadowsocksSettingsActivity::class.java, 1).get().window.decorView)

            // 对话框：和下面的页面一起截（对话框在自己的窗口里）
            Screen.DIALOG_DELETE_CONFIRM -> {
                val editor = editor(ShadowsocksSettingsActivity::class.java, 1)
                editor.get().deleteConfirmationDialog().show(editor.get().supportFragmentManager, null)
                UiSettle.settle()
                Screenshots.checkScreen(shot)
            }
            Screen.DIALOG_UNSAVED_CHANGES -> {
                val editor = editor(ShadowsocksSettingsActivity::class.java, 1)
                // 改过内容再按返回：和用户一样走返回键，由编辑器自己弹提示
                EditorCache.dirty = true
                editor.get().onBackPressedDispatcher.onBackPressed()
                UiSettle.settle()
                Screenshots.checkScreen(shot)
            }
            Screen.DIALOG_THEME_PICKER -> settingsDialog(1, "Theme")
            // 单选列表对话框（MTU）与文本输入对话框（Remote DNS 的 EditTextPreference）
            Screen.DIALOG_LIST_CHOICE -> settingsDialog(0, "MTU")
            Screen.DIALOG_TEXT_INPUT -> settingsDialog(4, "Remote DNS")
        }
    }

    // 设备与主题要在启动页面之前定好：宽高与亮暗走 qualifiers，字号走 fontScale，颜色与夜间模式走 DataStore
    private fun applyDevice(shot: Shot) {
        val night = if (shot.dark) "night" else "notnight"
        RuntimeEnvironment.setQualifiers("en-rUS-w${shot.widthDp}dp-h${shot.heightDp}dp-port-$night-xhdpi")
        RuntimeEnvironment.setFontScale(shot.fontScale)
        DataStore.appTheme = shot.theme.appTheme
        DataStore.nightTheme = if (shot.dark) 1 else 2 // 1 = 始终暗色，2 = 始终亮色，见 Theme.getNightMode
        Theme.currentNightMode = -1
    }

    private fun mainActivity(): ActivityController<MainActivity> = ui.launch<MainActivity>()

    private fun ActivityController<MainActivity>.page(id: Int) {
        get().displayFragmentWithId(id)
        UiSettle.settle()
    }

    private fun ActivityController<MainActivity>.shot() {
        Screenshots.check(shot, get().window.decorView)
    }

    private fun settingsSection(index: Int) {
        val main = mainActivity()
        val key = SettingsPreferenceFragment.sections[index].first
        main.get().displayFragment(SettingsFragment.section(key))
        UiSettle.settle()
        Screenshots.checkFullLength(shot, main.get().window.decorView)
    }

    private fun <A : Activity> editor(type: Class<A>, profileIndex: Int): ActivityController<A> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), type)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[profileIndex].id)
        return ui.launch(type, intent)
    }

    // 设置页上点一行，弹出它的对话框
    private fun settingsDialog(sectionIndex: Int, rowTitle: String) {
        val main = mainActivity()
        val key = SettingsPreferenceFragment.sections[sectionIndex].first
        main.get().displayFragment(SettingsFragment.section(key))
        UiSettle.settle()
        clickRow(main.get().window.decorView, rowTitle)
        UiSettle.settle()
        Screenshots.checkScreen(shot)
    }
}

// 找到标题为 [title] 的行，点击它的可点击祖先（偏好行）
internal fun clickRow(root: View, title: String) {
    fun find(v: View): TextView? {
        if (v is TextView && v.text.toString() == title) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
    var row: View = find(root) ?: error("找不到行：$title")
    while (!row.isClickable) row = row.parent as View
    row.performClick()
}
