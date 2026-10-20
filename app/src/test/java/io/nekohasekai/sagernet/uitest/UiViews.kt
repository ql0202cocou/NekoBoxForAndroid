package io.nekohasekai.sagernet.uitest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** 视图树里高度最大的 RecyclerView（设置分类页、编辑器的主列表） */
fun View.largestRecyclerView(): RecyclerView? {
    var best: RecyclerView? = null
    fun walk(v: View) {
        if (v is RecyclerView && v.height > (best?.height ?: -1)) best = v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(this)
    return best
}

/**
 * 整页（全长）截图：把窗口根视图手动重新测量 / 布局成「内容刚好放下」的高度再画到 Bitmap，
 * 不去动 Activity 的窗口。直接 draw 而不经 Roborazzi 的视图捕获：捕获前它会空转主 looper，
 * ViewRootImpl 的下一次遍历会把根视图按窗口大小重新布局回去。
 * 内容高度 = 当前高度 − 列表可视高度 + 列表滚动范围；滚动范围在全部子项布局后才精确，所以迭代到稳定。
 * 内容比窗口短时保持窗口高度。
 */
fun View.drawFullLength(): Bitmap {
    val decor = rootView
    val width = decor.width
    var height = decor.height
    fun relayout(h: Int) {
        decor.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, h)
    }
    repeat(6) {
        relayout(height)
        val list = decor.largestRecyclerView() ?: return@repeat
        val needed = height - list.height + list.computeVerticalScrollRange()
        if (needed <= height) return@repeat
        height = needed
    }
    relayout(height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    decor.draw(Canvas(bitmap))
    return bitmap
}
