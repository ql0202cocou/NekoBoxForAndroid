package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R

/**
 * 设置页的外壳：一级页（[hub]，六个分类入口加页面入口）和六个分类二级页（[section]）共用。
 * 二级页的顶部栏是返回箭头，回一级页；列表本身是 [SettingsPreferenceFragment]，按分类 key 加载。
 */
class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    /** 分类 key（[SettingsPreferenceFragment.sections]）；一级页为 null */
    val sectionKey: String?
        get() = arguments?.getString(ARG_SECTION)

    override val opensFromSettings: Boolean
        get() = sectionKey != null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val key = sectionKey
        val title = SettingsPreferenceFragment.sections.firstOrNull { it.first == key }?.second
        toolbar.setTitle(title ?: R.string.settings)

        // 列表挂在自己的 childFragmentManager 上；重建时它已被自动恢复，不重复创建
        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment().apply {
                    // 标准参数：进程重建后 PreferenceFragmentCompat 仍能读到分类 key
                    arguments = Bundle().apply {
                        putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key)
                    }
                })
                .commitAllowingStateLoss()
        }
    }

    /** 一级列表当前的滚动状态，由 MainActivity 在进入二级页面前保存；二级页恒为 null */
    fun listState(): Parcelable? {
        if (sectionKey != null) return null
        return (childFragmentManager.findFragmentById(R.id.settings) as? SettingsPreferenceFragment)
            ?.listState()
    }

    companion object {
        private const val ARG_SECTION = "section"

        /** 一级页：六个分类入口加页面入口 */
        fun hub() = SettingsFragment()

        /** 分类二级页，[key] 取自 [SettingsPreferenceFragment.sections] */
        fun section(key: String) = SettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, key) }
        }
    }

}
