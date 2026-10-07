package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import com.google.android.material.navigation.NavigationView

/**
 * 侧边抽屉。NavigationView 自己的插入区监听（fitsSystemWindows）只把上下插入区交给菜单，
 * 不管左右：横屏时导航栏或刘海在起始侧，会盖住菜单图标。这里把起始侧的系统栏与刘海
 * 插入区加到起始侧 padding 上，上下仍交给原来的监听。
 *
 * 不用 setOnApplyWindowInsetsListener：一个 View 只有一个监听，设了会替换掉
 * NavigationView 自己的那个。
 */
class InsetNavigationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : NavigationView(context, attrs) {
    private val baseStart = paddingStart

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val safeDrawing =
            WindowInsetsCompat.toWindowInsetsCompat(insets, this).getInsets(safeDrawingTypes)
        val start = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            safeDrawing.right
        } else safeDrawing.left
        updatePaddingRelative(start = baseStart + start)
        return super.dispatchApplyWindowInsets(insets)
    }
}
