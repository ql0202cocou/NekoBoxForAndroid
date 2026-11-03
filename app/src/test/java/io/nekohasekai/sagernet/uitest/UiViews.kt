package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import org.robolectric.android.controller.ActivityController

/** 主界面 fragment_holder 里当前显示的页面，按 [F] 取 */
inline fun <reified F : Fragment> FragmentActivity.hostedFragment(): F =
    supportFragmentManager.findFragmentById(R.id.fragment_holder) as F

inline fun <reified F : Fragment> ActivityController<out FragmentActivity>.hostedFragment(): F = get().hostedFragment()

/**
 * Snackbar 上的「撤销」按钮，没有就报前置条件错误。默认先等界面安静；IO 门关着时不能 settle（见 IoGate），
 * 那时传 [settle] = false，调用方自己空转主 looper
 */
fun Activity.undoAction(settle: Boolean = true): View {
    if (settle) UiSettle.settle()
    return window.decorView.findViewById(com.google.android.material.R.id.snackbar_action) ?: error("前置条件：没有撤销按钮")
}

/**
 * 记录适配器发出的每类通知：按类计数、变更事件的（起点，条数，payload），以及按顺序的事件描述 [log]
 * （all / insert(起点,条数) / remove(起点,条数) / move(起点,终点,条数) / change(起点,条数,payload 或 -)）。
 * 注册在初始载入之后，就不含载入本身
 */
class AdapterEvents : RecyclerView.AdapterDataObserver() {
    val changed = ArrayList<Triple<Int, Int, Any?>>()
    var inserted = 0
    var removed = 0
    var moved = 0
    var reset = 0
    val log = ArrayList<String>()

    override fun onChanged() {
        reset++
        log += "all"
    }

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
        changed += Triple(positionStart, itemCount, payload)
        log += "change($positionStart,$itemCount,${payload ?: "-"})"
    }

    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        inserted += itemCount
        log += "insert($positionStart,$itemCount)"
    }

    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
        removed += itemCount
        log += "remove($positionStart,$itemCount)"
    }

    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
        moved += itemCount
        log += "move($fromPosition,$toPosition,$itemCount)"
    }

    val total get() = changed.size + inserted + removed + moved + reset

    /** 取走到目前为止的事件描述并清零 */
    fun take(): List<String> = log.toList().also { clear() }

    fun clear() {
        changed.clear()
        inserted = 0
        removed = 0
        moved = 0
        reset = 0
        log.clear()
    }
}

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
 * 内容高度的算法：先撑到足够高（所有子项都被布局出来），读最后一项的底边加列表底部内边距，
 * 加上列表顶边在根视图里的位置——不用 computeVerticalScrollRange，它在子项高度不一时只是估计值，会少几行。
 * 内容比窗口短时保持窗口高度。
 */
fun View.drawFullLength(): Bitmap {
    val decor = rootView
    val width = decor.width
    val windowHeight = decor.height
    fun relayout(h: Int) {
        decor.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, h)
    }
    var height = windowHeight
    relayout(TALL)
    val list = decor.largestRecyclerView()
    if (list != null) {
        val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        check(manager.findLastVisibleItemPosition() == list.adapter!!.itemCount - 1) {
            "撑到 $TALL px 高仍有列表项没有布局出来"
        }
        // 内容底边（含列表底部内边距，Dock 要让出的空白在里面）换算到根视图坐标，就是需要的窗口高度；
        // 列表本身可能是 wrap_content（编辑器），不能用「撑高后的列表高度」去减
        val contentBottom = list.getChildAt(list.childCount - 1).bottom + list.paddingBottom
        height = maxOf(windowHeight, topInRoot(list, decor) + contentBottom)
    }
    relayout(height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    decor.draw(Canvas(bitmap))
    return bitmap
}

private fun topInRoot(view: View, root: View): Int {
    var top = 0
    var v: View = view
    while (v !== root) {
        top += v.top
        v = v.parent as View
    }
    return top
}

// 先撑到的高度（px）：比任何一页的内容都高
private const val TALL = 20000
