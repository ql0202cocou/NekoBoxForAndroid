package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.core.content.res.TypedArrayUtils
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.preference.EditTextPreference
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import okhttp3.HttpUrl.Companion.toHttpUrl

class LinkOrContentPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    init {
        dialogLayoutResource = R.layout.layout_urltest_preference_dialog

        setOnBindEditTextListener {
            val linkLayout = it.rootView.findViewById<TextInputLayout>(R.id.input_layout)
            fun validate() {
                val link = it.text.toString()
                val error = blockingError(link)
                    ?: if (link.isNotBlank() && link.toUri().scheme != "content" &&
                        "http".equals(link.toHttpUrl().scheme, true)
                    ) app.getString(R.string.cleartext_http_warning) else null
                linkLayout.error = error
                linkLayout.isErrorEnabled = error != null
            }
            validate()
            it.addTextChangedListener {
                validate()
            }
        }
    }

    // 输入框里的提示不拦保存，这里把真正用不了的值挡在保存前；http 只是警告，照常保存
    override fun callChangeListener(newValue: Any?): Boolean {
        val error = blockingError(newValue as? String ?: "")
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return false
        }
        return super.callChangeListener(newValue)
    }

    // 空值与 content:// 放行；其余必须是单行的合法 http(s) 链接。null 表示可以保存
    private fun blockingError(link: String): String? {
        if (link.isBlank() || link.toUri().scheme == "content") return null
        if (link.contains("\n")) return "Unexpected new line"
        return try {
            link.toHttpUrl()
            null
        } catch (e: Exception) {
            e.readableMessage
        }
    }

}