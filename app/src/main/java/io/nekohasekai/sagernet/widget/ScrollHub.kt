package io.nekohasekai.sagernet.widget

import android.view.View
import io.nekohasekai.sagernet.R

/** 滚动视图上发生的事，由该视图的 [ScrollHub] 分发 */
fun interface ContentListener {
    /** [source] 的滚动位置变了（RecyclerView 也走这里：它在 dispatchOnScrolled 里触发 View 的滚动回调） */
    fun onContentScrolled(source: View)
}

/**
 * 一个滚动视图唯一的滚动分发器。
 *
 * View 的滚动回调（OnScrollChangeListener）只有一个槽，顶部栏抬升等订阅者都经它收滚动，
 * 所以整个应用里只有这个类会去设置它，其余地方一律 [of] 取分发器再 [addListener]；
 * 别处再设置会悄悄顶掉分发器（`MainContentHostSourceTest` 扫源码拦这一点）。
 * 分发器以 tag 挂在视图上，随视图一起回收；重复订阅同一个对象无效，所以页面重新绑定时不会叠加。
 * 只在主线程使用。
 */
class ScrollHub private constructor(val view: View) {

    private val listeners = ArrayList<ContentListener>()

    fun addListener(listener: ContentListener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    fun removeListener(listener: ContentListener) {
        listeners.removeAll { it === listener }
    }

    private fun dispatchScrolled() {
        // 回调里可能增删订阅者，遍历副本
        for (listener in listeners.toTypedArray()) listener.onContentScrolled(view)
    }

    companion object {
        fun of(view: View): ScrollHub {
            (view.getTag(R.id.scroll_hub) as? ScrollHub)?.let { return it }
            val hub = ScrollHub(view)
            view.setTag(R.id.scroll_hub, hub)
            view.setOnScrollChangeListener { _, _, _, _, _ -> hub.dispatchScrolled() }
            return hub
        }
    }
}
