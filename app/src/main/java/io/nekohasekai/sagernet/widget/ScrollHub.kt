package io.nekohasekai.sagernet.widget

import android.view.View
import io.nekohasekai.sagernet.R

/**
 * 滚动视图上发生的事。[ScrollHub]（单个视图）与 [MainContentHost]（主界面托管的全部滚动视图）
 * 用同一个接口分发，方法都有空实现，订阅者只实现自己关心的。
 */
interface ContentListener {
    /** [source] 的滚动位置变了（RecyclerView 也走这里：它在 dispatchOnScrolled 里触发 View 的滚动回调） */
    fun onContentScrolled(source: View) = Unit

    /**
     * 托管内容的结构变了：[source] 挂上 / 摘下窗口，或它重新布局（数据变化、尺寸变化）。
     * 只由 [MainContentHost] 分发；[source] 为 null 表示不止一个视图受影响
     */
    fun onContentChanged(source: View?) = Unit
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

    /** 当前托管这个视图的 [MainContentHost]；只由 host 读写，换宿主时用来摘掉旧登记 */
    internal var host: MainContentHost? = null

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
