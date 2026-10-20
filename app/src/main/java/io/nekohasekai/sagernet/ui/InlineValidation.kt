package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * 一个编辑项的输入规则：[isValid] 判定输入框里的整段文本，[message] 给出不合法时显示的原因。
 * 与 change 监听器里「确定」时的拒绝用同一条规则（见 `bindIntegerPreference` 等）。
 */
class InlineValidation(
    val message: (Context) -> String,
    val isValid: (String) -> Boolean,
)

/**
 * 编辑项 → 输入规则的登记表，由各 `bind*Preference` 填写，对话框打开时查。
 * 按 Preference 实例登记（弱引用）：Fragment 重建后偏好项重新创建、binder 重新登记，
 * 被系统重建出来的对话框再从当前的偏好项查规则，不在对话框自己的状态里存任何东西。
 * 只在主线程读写，加锁只是防守。
 */
object InlineValidations {
    private val rules = Collections.synchronizedMap(WeakHashMap<Preference, InlineValidation>())

    fun register(preference: EditTextPreference, validation: InlineValidation) {
        rules[preference] = validation
    }

    fun of(preference: Preference): InlineValidation? = rules[preference]
}

// 与 PreferenceFragmentCompat 里私有常量同值：基类用它防止同一个对话框弹两次
private const val DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG"

// editTextPreferenceStyle 默认就带着 androidx 自己的 preference_dialog_edittext；
// 只有指向别的布局（密码框、测速对话框）才算自带 dialogLayout
private fun EditTextPreference.hasCustomDialogLayout() =
    dialogLayoutResource != 0 && dialogLayoutResource != androidx.preference.R.layout.preference_dialog_edittext

/**
 * 偏好页的 [PreferenceFragmentCompat.onDisplayPreferenceDialog] 调用（编辑器与设置页都用）：
 * 没有自定义 dialogLayout 的 EditTextPreference 一律弹 [OutlinedEditTextDialogFragment]，返回 true，
 * 这样同一个页面里的文本对话框外观一致；登记过输入规则的项在里面带行内校验。
 * 其它偏好项（含自带 dialogLayout 的密码框、测速对话框）返回 false，交给基类。
 */
@Suppress("DEPRECATION")
fun PreferenceFragmentCompat.showOutlinedDialog(preference: Preference): Boolean {
    if (preference !is EditTextPreference || preference.hasCustomDialogLayout()) return false
    if (parentFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) return true
    // 目标 Fragment 是 androidx.preference 对话框找回偏好项的唯一途径（基类同样这么做）
    OutlinedEditTextDialogFragment.newInstance(preference.key).also {
        it.setTargetFragment(this, 0)
        it.show(parentFragmentManager, DIALOG_FRAGMENT_TAG)
    }
    return true
}

/**
 * EditTextPreference 的 Outlined 文本框对话框。偏好项登记了输入规则（[InlineValidations]）时带行内校验：
 * 输入时实时校验，不合法就在文本框下显示原因（TextInputLayout 的错误行，TalkBack 会朗读）并禁用「确定」，
 * 合法则清除错误、启用「确定」；打开时先判一次，缓存里已经不合法的值立刻显示错误、「确定」不可用，仍可取消。
 * 没有规则的项只是换了外观：没有错误行，「确定」始终可用。
 *
 * 系统会用无参构造 + arguments 重建本类，所以规则从偏好项重新查，不放在构造参数或字段里；
 * 输入类型等 `setOnBindEditTextListener` 的修饰由基类的 onBindDialogView 照常套用。
 */
class OutlinedEditTextDialogFragment : EditTextPreferenceDialogFragmentCompat() {

    companion object {
        // PreferenceDialogFragmentCompat.ARG_KEY 是 protected，这里写字面量
        fun newInstance(key: String) = OutlinedEditTextDialogFragment().apply {
            arguments = Bundle(1).apply { putString("key", key) }
        }
    }

    private var inputLayout: TextInputLayout? = null
    private var editText: EditText? = null
    private var validation: InlineValidation? = null

    // 对话框内容视图交给 AlertDialog 挂载，没有父容器可传
    @SuppressLint("InflateParams")
    override fun onCreateDialogView(context: Context): View =
        LayoutInflater.from(context).inflate(R.layout.layout_preference_dialog_input, null)

    override fun onBindDialogView(view: View) {
        // 基类绑定 @android:id/edit：初始文本、输入类型等修饰
        super.onBindDialogView(view)
        validation = InlineValidations.of(preference)
        // 错误行只在有规则时启用，没规则的项下面不留空行
        inputLayout = view.findViewById<TextInputLayout>(R.id.input_layout).also {
            it.isErrorEnabled = validation != null
        }
        editText = view.findViewById<EditText>(android.R.id.edit).also { edit ->
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
    }

    // 对话框在这里才真正显示，「确定」按钮才拿得到；重建后恢复的输入文本也已就位
    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        val rule = validation ?: return
        val edit = editText ?: return
        val valid = rule.isValid(edit.text.toString())
        val layout = inputLayout
        if (layout != null) {
            // 状态没变就不重设：每次 setError 都会让 TalkBack 重新朗读
            val message = if (valid) null else rule.message(edit.context)
            if (layout.error?.toString() != message) layout.error = message
        }
        (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = valid
    }
}
