package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutDialogInputBinding

class MTUPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = androidx.preference.R.attr.dropdownPreferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {

    init {
        setSummaryProvider {
            value.toString()
        }
        dialogLayoutResource = R.layout.layout_mtu_help
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemView: View = holder.itemView
        itemView.setOnLongClickListener {
            val binding = LayoutDialogInputBinding.inflate(LayoutInflater.from(context))
            binding.inputLayout.hint = context.getString(R.string.mtu)
            binding.edit.apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                setText(preferenceDataStore?.getString(key, "") ?: "")
            }

            MaterialAlertDialogBuilder(context).setTitle("MTU")
                .setView(binding.root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val mtu = binding.edit.text.toString().toIntOrNull()
                    if (mtu == null || mtu < 1000 || mtu > 10000) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.integer_range_error, 1000, 10000),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                    // go through the change listener so SettingsPreferenceFragment's
                    // reloadListener fires; only persist when it accepts the value
                    if (callChangeListener(mtu.toString())) value = mtu.toString()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

}
