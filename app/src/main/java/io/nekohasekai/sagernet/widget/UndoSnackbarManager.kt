package io.nekohasekai.sagernet.widget

import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ThemedActivity

/**
 * @param activity ThemedActivity.
 * //@param view The view to find a parent from.
 * @param undo Callback for undoing removals.
 * @param commit Callback for committing removals.
 * @tparam T Item type.
 */
class UndoSnackbarManager<in T>(
    private val activity: ThemedActivity,
    private val callback: Interface<T>,
) {

    interface Interface<in T> {
        fun undo(actions: List<Pair<Int, T>>)
        fun commit(actions: List<Pair<Int, T>>)
    }

    private val recycleBin = ArrayList<Pair<Int, T>>()
    private val removedCallback = object : Snackbar.Callback() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
            if (last === transientBottomBar && event != DISMISS_EVENT_ACTION) {
                commitPending()
                last = null
            }
        }
    }

    private var last: Snackbar? = null

    fun remove(items: Collection<Pair<Int, T>>) {
        recycleBin.addAll(items)
        val count = recycleBin.size
        activity.snackbar(activity.resources.getQuantityString(R.plurals.removed, count, count))
            .apply {
                addCallback(removedCallback)
                setAction(R.string.undo) {
                    callback.undo(recycleBin.reversed())
                    recycleBin.clear()
                }
                last = this
                show()
            }
    }

    fun remove(vararg items: Pair<Int, T>) = remove(items.toList())

    // 别处要改动列表时先调它：待删项立即提交，之后撤销即使还点得到也无事可做。不能只 dismiss：
    // Snackbar 的关闭经 Handler 投递、淡出动画结束才回调 onDismissed，这段时间里点撤销会按删除时的
    // 下标插回已经变了的列表
    fun flush() {
        val bar = last ?: return
        last = null
        commitPending()
        bar.dismiss()
    }

    // 列表变更的唯一入口：别处（监听回调、整表重读、搜索过滤）要改列表时，把改动写进 block，
    // 先提交撤销窗口再执行。所有适配器的改列表路径都走它，不再各自散落 flush()；
    // 窗口因此提前关闭是设计（撤销的下标只对删除当时的列表有效）。
    // 撤销自身与用户的滑动删除不走这里：它们就是窗口的开与用
    inline fun <R> mutate(block: () -> R): R {
        flush()
        return block()
    }

    // 待删项交给 commit 并清空；传副本，commit 的实现可以放心异步使用
    private fun commitPending() {
        if (recycleBin.isEmpty()) return
        callback.commit(ArrayList(recycleBin))
        recycleBin.clear()
    }
}

/**
 * 把撤销的项插回列表：下标夹在当前列表范围内，列表在窗口期间被改短也不会越界。
 * 返回实际插入的位置，调用方按它发 notifyItemInserted。
 */
fun <T> MutableList<T>.insertClamped(index: Int, item: T): Int {
    val at = index.coerceIn(0, size)
    add(at, item)
    return at
}
