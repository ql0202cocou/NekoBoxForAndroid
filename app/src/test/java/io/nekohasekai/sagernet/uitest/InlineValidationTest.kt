package io.nekohasekai.sagernet.uitest

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.OutlinedEditTextDialogFragment
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.DIALOG_TAG
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.clickCancel
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.clickOk
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.currentDialog
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.currentDialogFragment
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.edit
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.error
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.inputLayout
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.okEnabled
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.openDialog
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.pref
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.type
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.vmessEditor
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.wireGuardEditor
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.settingsEditor
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器文本对话框的行内校验：输入时实时校验、不合法就在文本框下显示原因并禁用「确定」；打开时先判一次；
 * 重建后仍在校验；没登记规则的编辑项仍是默认对话框。不截图，所有平台都跑。
 * 每个 @Test 是自己的用例（Robolectric 同一用例内的资源缓存会串）。
 */
class InlineValidationTest : UiTestBase() {

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private val portError get() = string(R.string.integer_range_error, 1, 65535)

    @Test
    fun portDialogValidatesWhileTyping() {
        val editor = vmessEditor(ui).get()
        val dialog = editor.openDialog(Key.SERVER_PORT)
        assertTrue("应该是带行内校验的对话框", editor.currentDialogFragment() is OutlinedEditTextDialogFragment)
        // 夹具里的端口 443 合法
        assertEquals("443", dialog.edit().text.toString())
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())

        dialog.type("70000")
        assertEquals(portError, dialog.error())
        assertFalse(dialog.okEnabled())

        dialog.type("8443")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())

        // 端口不允许为空（与 bindPortPreference 一致）
        dialog.type("")
        assertEquals(portError, dialog.error())
        assertFalse(dialog.okEnabled())
    }

    @Test
    fun portDialogKeepsInputTypeAndOkAppliesValue() {
        val editor = vmessEditor(ui).get()
        val dialog = editor.openDialog(Key.SERVER_PORT)
        // setOnBindEditTextListener 的修饰（端口：数字键盘、最多 5 位）仍然生效
        assertEquals(EditorInfo.TYPE_CLASS_NUMBER, dialog.edit().inputType and EditorInfo.TYPE_MASK_CLASS)
        assertEquals(1, dialog.edit().filters.size)
        dialog.type("8443")
        dialog.clickOk()
        assertEquals("8443", editor.pref(Key.SERVER_PORT).text)
        assertNull("对话框应已关闭", editor.supportFragmentManager.findFragmentByTag(DIALOG_TAG))
    }

    @Test
    fun alreadyInvalidValueShowsErrorOnOpenAndCanStillCancel() {
        val editor = vmessEditor(ui).get()
        editor.pref(Key.SERVER_PORT).text = "0"
        UiSettle.settle()
        val dialog = editor.openDialog(Key.SERVER_PORT)
        assertEquals("0", dialog.edit().text.toString())
        assertEquals(portError, dialog.error())
        assertFalse("缓存里已不合法的值：确定应不可用", dialog.okEnabled())
        assertTrue("取消应仍可用", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled)
        dialog.clickCancel()
        assertEquals("取消不改值", "0", editor.pref(Key.SERVER_PORT).text)
        assertNull("对话框应已关闭", editor.supportFragmentManager.findFragmentByTag(DIALOG_TAG))
    }

    @Test
    fun serverAddressRejectsSpacesAndEmpty() {
        val editor = vmessEditor(ui).get()
        val dialog = editor.openDialog(Key.SERVER_ADDRESS)
        val message = string(R.string.server_address_error)
        assertNull(dialog.error())
        dialog.type("a b")
        assertEquals(message, dialog.error())
        assertFalse(dialog.okEnabled())
        dialog.type("")
        assertEquals(message, dialog.error())
        dialog.type("node.example.com")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
    }

    @Test
    fun certificateFingerprintAllowsBlankAndChecksHex() {
        val editor = vmessEditor(ui).get()
        val dialog = editor.openDialog("certificateFingerprint")
        val message = string(R.string.certificate_fingerprint_error)
        // 空表示不固定证书，合法
        assertEquals("", dialog.edit().text.toString())
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
        dialog.type("abc")
        assertEquals(message, dialog.error())
        assertFalse(dialog.okEnabled())
        dialog.type("ab".repeat(32))
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
    }

    @Test
    fun dialogStillValidatesAfterRecreate() {
        val controller = vmessEditor(ui)
        val first = controller.get().openDialog(Key.SERVER_PORT)
        first.type("70000")
        assertFalse(first.okEnabled())

        controller.recreate()
        UiSettle.settle()
        val editor = controller.get()
        val dialog = editor.currentDialog()
        assertTrue("重建后应仍是带行内校验的对话框", editor.currentDialogFragment() is OutlinedEditTextDialogFragment)
        // 输入的文本随对话框恢复，错误与按钮状态按它重新判定
        assertEquals("70000", dialog.edit().text.toString())
        assertEquals(portError, dialog.error())
        assertFalse(dialog.okEnabled())

        dialog.type("443")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
        dialog.type("65536")
        assertEquals(portError, dialog.error())
        assertFalse(dialog.okEnabled())
    }

    @Test
    fun preferenceWithoutRuleGetsOutlinedDialogWithoutErrorRow() {
        val editor = vmessEditor(ui).get()
        // 用户名没有输入规则：同样是 Outlined 对话框（页面里的文本对话框外观一致），但没有校验
        val dialog = editor.openDialog("username")
        assertTrue(editor.currentDialogFragment() is OutlinedEditTextDialogFragment)
        val layout = dialog.inputLayout()!!
        assertFalse("没规则的项不留错误行", layout.isErrorEnabled)
        dialog.type("anything goes")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
        dialog.type("")
        assertTrue("没规则：空值也不禁用确定", dialog.okEnabled())
    }

    @Test
    fun preferenceWithCustomDialogLayoutKeepsItsOwnDialog() {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShadowsocksSettingsActivity::class.java)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[1].id)
        val editor = ui.launch(ShadowsocksSettingsActivity::class.java, intent).get()
        // 密码项自带 dialogLayout（layout_password_dialog）：交回基类，不被换成 Outlined 对话框
        editor.openDialog("password")
        assertEquals(EditTextPreferenceDialogFragmentCompat::class.java, editor.currentDialogFragment().javaClass)
    }

    @Test
    fun multiLineListKeepsNewlinesAndIsValid() {
        val editor = wireGuardEditor(ui).get()
        // 已有的多行值：换行照原样显示，不被压成空格
        val twoLines = "10.0.0.2/32\nfd00::2/128"
        editor.pref("localAddress").text = twoLines
        UiSettle.settle()
        val dialog = editor.openDialog("localAddress")
        assertEquals(twoLines, dialog.edit().text.toString())
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
        // 输入框允许输入换行，两行都合法的值有效，确定后换行原样写回
        val other = "10.0.0.3/32\nfd00::3/128"
        dialog.type(other)
        assertEquals(other, dialog.edit().text.toString())
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
        dialog.clickOk()
        assertEquals(other, editor.pref("localAddress").text)
        // 其中一行非法仍被拦下
        val again = editor.openDialog("localAddress")
        again.type("10.0.0.3/32\nnot-an-address")
        assertEquals(string(R.string.wireguard_address_error), again.error())
        assertFalse(again.okEnabled())
    }

    @Test
    fun settingsPageMixedPortValidatesInline() {
        val settings = settingsEditor(ui, 5)
        val dialog = settings.openDialog(Key.MIXED_PORT)
        assertTrue(settings.parentFragmentManager.findFragmentByTag(DIALOG_TAG) is OutlinedEditTextDialogFragment)
        val message = string(R.string.integer_range_error, 1025, 65535)
        dialog.type("80")
        assertEquals(message, dialog.error())
        assertFalse(dialog.okEnabled())
        dialog.type("2080")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
    }

    @Test
    fun settingsPageOtherTextDialogsAreOutlinedToo() {
        val settings = settingsEditor(ui, 4)
        // Remote DNS：没有规则，Outlined 外观、没有错误行
        val dialog = settings.openDialog(Key.REMOTE_DNS)
        assertTrue(settings.parentFragmentManager.findFragmentByTag(DIALOG_TAG) is OutlinedEditTextDialogFragment)
        assertFalse(dialog.inputLayout()!!.isErrorEnabled)
        assertTrue(dialog.okEnabled())
    }

    @Test
    fun changeListenerStillRejectsInvalidValue() {
        val editor = vmessEditor(ui).get()
        // 兜底：绕过对话框直接走 change 监听器，不合法的值照旧被拒
        assertFalse(editor.pref(Key.SERVER_PORT).callChangeListener("0"))
        assertTrue(editor.pref(Key.SERVER_PORT).callChangeListener("443"))
    }

    @Test
    fun groupEditorDelayDialogValidates() {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), GroupSettingsActivity::class.java)
            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, fixtures.subscriptionGroup.id)
        val editor = ui.launch(GroupSettingsActivity::class.java, intent).get()
        // 更新间隔项在「自动更新」关着时不可点；代码设置 isChecked 不触发开关的监听器，直接放开这一项
        editor.pref(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY).isEnabled = true
        val dialog = editor.openDialog(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)
        val message = string(R.string.auto_update_delay_error)
        dialog.type("5")
        assertEquals(message, dialog.error())
        assertFalse(dialog.okEnabled())
        dialog.type("60")
        assertNull(dialog.error())
        assertTrue(dialog.okEnabled())
    }
}
