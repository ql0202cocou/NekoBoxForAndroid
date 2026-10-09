/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: SimpleMenuAdapter

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val mSpinner = holder.itemView.findViewById<Spinner>(androidx.preference.R.id.spinner)
        mSpinner.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        // 弹出窗口用 M3 菜单背景（popupMenuBackground：surfaceContainer、4dp 圆角、上下 8dp 内边距），
        // 不用 Spinner 默认的 colorBackgroundFloating
        val popupBackground = TypedValue()
        if (mSpinner.context.theme.resolveAttribute(
                com.google.android.material.R.attr.popupMenuBackground, popupBackground, true
            ) && popupBackground.resourceId != 0
        ) {
            mSpinner.setPopupBackgroundResource(popupBackground.resourceId)
        }
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = SimpleMenuAdapter(getContext(), R.layout.simple_menu_dropdown_item)
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = entryValues.indexOf(value)
            mAdapter.notifyDataSetChanged()
        }
    }

    private class SimpleMenuAdapter(context: Context, resource: Int) :
        ArrayAdapter<CharSequence?>(context, resource) {

        var currentPosition = -1

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View = super.getDropDownView(position, convertView, parent)
            // 选中行 secondaryContainer；其余行透明，透出弹出窗口的 surfaceContainer，
            // 列表的按压反馈画在行下面，行不能是不透明底色
            if (position == currentPosition) {
                view.setBackgroundColor(context.getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer))
            } else {
                view.background = null
            }
            return view
        }
    }
}