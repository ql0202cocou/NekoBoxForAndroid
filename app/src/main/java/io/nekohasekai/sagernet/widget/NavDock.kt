package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * 主界面底部的悬浮 Dock：四个一级页面（配置、分组、路由、设置）的入口，横向等分。
 *
 * 外壳是全圆角的卡片（surfaceContainer 底，阴影 3dp），每项的选中胶囊、图标与文字颜色见
 * `nav_dock_item_background` / `nav_dock_item`。选中哪一项由宿主按当前页面调用 [select]，
 * 点击只通过 [onItemSelected] 交给宿主，自己不改选中状态。
 */
class NavDock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    private class Item(@IdRes val id: Int, @DrawableRes val icon: Int, @StringRes val title: Int)

    private val items = listOf(
        Item(R.id.nav_configuration, R.drawable.ic_action_description, R.string.menu_configuration),
        Item(R.id.nav_group, R.drawable.ic_baseline_view_list_24, R.string.menu_group),
        Item(R.id.nav_route, R.drawable.ic_maps_directions, R.string.menu_route),
        Item(R.id.nav_settings, R.drawable.ic_action_settings, R.string.settings),
    )

    private val itemViews = mutableMapOf<Int, View>()

    // 每一项在无障碍树里报成按钮，读屏才会读出「按钮」；选中状态仍由 isSelected 报告
    private val buttonRole = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    /** 点击某一项时回调，参数是该项对应的页面 id */
    var onItemSelected: ((Int) -> Unit)? = null

    init {
        radius = resources.getDimension(R.dimen.nav_dock_radius)
        cardElevation = resources.getDimension(R.dimen.nav_dock_elevation)
        strokeWidth = 0
        setCardBackgroundColor(context.getColorAttr(com.google.android.material.R.attr.colorSurfaceContainer))
        val padding = resources.getDimensionPixelSize(R.dimen.nav_dock_padding)
        setContentPadding(padding, padding, padding, padding)

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val inflater = LayoutInflater.from(context)
        for (item in items) {
            val view = inflater.inflate(R.layout.layout_nav_dock_item, row, false)
            val title = context.getText(item.title)
            view.findViewById<ImageView>(R.id.nav_dock_icon).setImageResource(item.icon)
            view.findViewById<TextView>(R.id.nav_dock_label).text = title
            view.contentDescription = title
            ViewCompat.setAccessibilityDelegate(view, buttonRole)
            view.setOnClickListener { onItemSelected?.invoke(item.id) }
            row.addView(view)
            itemViews[item.id] = view
        }
    }

    /** 把 [id] 对应的项设为选中（无障碍也据此报「已选中」），其余取消 */
    fun select(@IdRes id: Int) {
        for ((itemId, view) in itemViews) view.isSelected = itemId == id
    }
}
