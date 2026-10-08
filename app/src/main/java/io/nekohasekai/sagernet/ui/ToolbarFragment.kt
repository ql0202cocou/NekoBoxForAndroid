package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    /**
     * 从设置页末尾进入的二级页面（日志、工具、关于、仪表板）：顶部栏左侧是返回箭头，回到设置页。
     * 一级页面由底部 Dock 切换，顶部栏没有导航图标
     */
    open val opensFromSettings = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        if (opensFromSettings) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            // 读屏朗读用 AppCompat 自带的「向上导航」文案（各语言都有翻译），不新增字符串
            toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
}
