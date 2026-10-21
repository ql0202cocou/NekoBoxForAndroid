package io.nekohasekai.sagernet.uitest

import android.content.DialogInterface
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import kotlinx.coroutines.runBlocking
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import org.robolectric.android.controller.ActivityController

/**
 * 行内校验用例的公共动作：开编辑器、点偏好项弹对话框、读对话框里的文本框 / 错误行 / 按钮。
 * 对话框的 Fragment 标签与 androidx.preference 基类相同。
 */
internal object InlineValidationHarness {

    const val DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG"

    /** 夹具里第一个节点是 VMess（端口 443、服务器 192.0.2.10） */
    fun vmessEditor(ui: UiTestRule): ActivityController<VMessSettingsActivity> {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), VMessSettingsActivity::class.java)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[0].id)
        return ui.launch(VMessSettingsActivity::class.java, intent)
    }

    /** 新建一个 WireGuard 节点（夹具里没有）并打开它的编辑器 */
    fun wireGuardEditor(ui: UiTestRule): ActivityController<WireGuardSettingsActivity> {
        val fixtures = UiFixtures.install()
        val bean = WireGuardBean().apply {
            name = "WG test"
            serverAddress = "192.0.2.80"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = "A".repeat(43) + "="
            peerPublicKey = "B".repeat(43) + "="
        }
        val entity = ui.offMain { runBlocking { ProfileRepository.createProfile(fixtures.normalGroup.id, bean) } }
        val intent = Intent(ApplicationProvider.getApplicationContext(), WireGuardSettingsActivity::class.java)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, entity.id)
        return ui.launch(WireGuardSettingsActivity::class.java, intent)
    }

    /** 设置页（分类页）里的文本编辑项：SettingsFragment 的子 Fragment 才是偏好列表 */
    fun settingsEditor(ui: UiTestRule, sectionIndex: Int): SettingsPreferenceFragment {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        main.get().displayFragment(SettingsFragment.section(SettingsPreferenceFragment.sections[sectionIndex].first))
        UiSettle.settle()
        val host = main.get().supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
        return host.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment
    }

    fun SettingsPreferenceFragment.openDialog(key: String): AlertDialog {
        findPreference<EditTextPreference>(key)!!.performClick()
        UiSettle.settle()
        return (parentFragmentManager.findFragmentByTag(DIALOG_TAG) as DialogFragment).dialog as AlertDialog
    }

    fun EditorActivity.pref(key: String): EditTextPreference = child!!.findPreference(key)!!

    /** 点偏好项，等对话框显示出来 */
    fun EditorActivity.openDialog(key: String): AlertDialog {
        check(pref(key).isEnabled) { "前置条件：$key 不可点" }
        pref(key).performClick()
        UiSettle.settle()
        return currentDialog()
    }

    fun EditorActivity.currentDialogFragment(): DialogFragment =
        supportFragmentManager.findFragmentByTag(DIALOG_TAG) as? DialogFragment ?: error("没有偏好对话框")

    fun EditorActivity.currentDialog(): AlertDialog = currentDialogFragment().dialog as AlertDialog

    fun AlertDialog.edit(): EditText = findViewById(android.R.id.edit)!!

    /** 行内错误行；对话框不是带行内校验的那种（默认对话框）时为 null */
    fun AlertDialog.errorView(): TextView? = findViewById(R.id.input_error)

    /** 错误行显示的文字；错误行隐藏（输入合法）时为 null */
    fun AlertDialog.error(): String? = errorView()!!.takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    fun AlertDialog.okEnabled(): Boolean = getButton(DialogInterface.BUTTON_POSITIVE).isEnabled

    /** 像用户输入一样改文本框内容（触发 TextWatcher） */
    fun AlertDialog.type(text: String) {
        edit().setText(text)
        UiSettle.settle()
    }

    fun AlertDialog.clickOk() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        UiSettle.settle()
    }

    fun AlertDialog.clickCancel() {
        getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        UiSettle.settle()
    }
}
