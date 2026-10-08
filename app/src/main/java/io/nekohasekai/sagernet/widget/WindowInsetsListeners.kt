package io.nekohasekai.sagernet.widget

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/** The inset types a view must keep its content clear of: system bars and display cutouts. */
val safeDrawingTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/**
 * Keep this view's interactive content clear of system bars and display cutouts while
 * preserving the padding declared by its layout. The view background still extends into
 * the inset area, which keeps edge-to-edge layouts visually continuous.
 *
 * @param statusBarTop pad the top by the status bar inset too, for full-height views.
 * @param bottom whether to apply the bottom system-bar inset. A scrollable [ViewGroup]
 *   then also gets `clipToPadding = false` so its content scrolls through the inset area.
 * @param bottomAtLeast the declared bottom padding already reserves space (e.g. for a
 *   FAB): grow it to the navigation bar inset instead of adding the two together.
 * @param ime 底部同时让开软键盘：取导航栏与键盘两者较高的一个。只给上方有输入框、
 *   键盘弹出时仍要能滚到最后一项的列表用（edge-to-edge 窗口不会因键盘缩小）。
 * @param bottomExtra 底部在插入区之外再让出的高度（px），每次分发插入区时取值：主界面的列表
 *   要让开悬浮的 Dock 与状态卡片，宿主在它们变化时重新请求分发。只在 [bottom] 为真、
 *   键盘没有弹出时生效（键盘盖住了 Dock 与卡片）。
 * @param consume forward the insets to children with the handled types zeroed. Only for a
 *   view that owns its whole subtree (e.g. a WebView container): below API 30 consumed
 *   insets also stop reaching later siblings.
 */
fun View.padForSystemBars(
    statusBarTop: Boolean = false,
    bottom: Boolean = true,
    bottomAtLeast: Boolean = false,
    ime: Boolean = false,
    consume: Boolean = false,
    bottomExtra: () -> Int = { 0 },
) {
    val base = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    if (bottom) (this as? ViewGroup)?.clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val safeDrawing = insets.getInsets(safeDrawingTypes)
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bottomInset = if (ime) maxOf(safeDrawing.bottom, imeBottom) else safeDrawing.bottom
        // 键盘弹出时 Dock 与状态卡片被它盖住，不再为它们留白
        val extra = if (imeBottom > 0) 0 else bottomExtra()
        v.updatePadding(
            left = base.left + safeDrawing.left,
            top = if (statusBarTop) {
                base.top + safeDrawing.top
            } else base.top,
            right = base.right + safeDrawing.right,
            bottom = when {
                !bottom -> base.bottom
                bottomAtLeast -> maxOf(base.bottom, bottomInset) + extra
                else -> base.bottom + bottomInset + extra
            },
        )
        if (consume) {
            WindowInsetsCompat.Builder(insets).setInsets(safeDrawingTypes, Insets.NONE).build()
        } else insets
    }
    // 窗口首次分发之后才挂上的视图（切换页面）收不到插入区，要自己请求；每次挂回窗口都请求：
    // ViewPager2 缓存里脱离窗口的页面错过了期间的分发（如连接状态变化后的 bottomExtra）
    if (isAttachedToWindow) ViewCompat.requestApplyInsets(this)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = ViewCompat.requestApplyInsets(v)
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}

/**
 * 悬浮在内容上方、贴底边的控件（主界面的 Dock 等）：在布局声明的起止与底部外边距上，加上
 * 系统栏与刘海的插入区。返回的函数由宿主在收到插入区时调用，这里不注册监听：宿主在更上层
 * 统一取插入区，避免被前面的兄弟视图清零（见 [padForSystemBars] 的 consume）。
 */
fun View.systemBarMargins(): (Insets) -> Unit {
    val declared = layoutParams as ViewGroup.MarginLayoutParams
    val baseStart = declared.marginStart
    val baseEnd = declared.marginEnd
    val baseBottom = declared.bottomMargin
    return { safeDrawing ->
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = baseStart + if (rtl) safeDrawing.right else safeDrawing.left
            marginEnd = baseEnd + if (rtl) safeDrawing.left else safeDrawing.right
            bottomMargin = baseBottom + safeDrawing.bottom
        }
    }
}
