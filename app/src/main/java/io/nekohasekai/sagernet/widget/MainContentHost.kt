package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.NestedScrollingChild
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.BuildConfig
import java.util.WeakHashMap

/*
 * MainActivity 托管的页面怎么放一个滚动视图：只走 [hostMainScrollable]。
 *
 * 主界面的 Dock、连接按钮、状态卡片悬浮在全屏的 fragment_holder 之上，列表本来就画到它们下面，
 * 所以每个页面的滚动视图都要做同样的三件事：
 * 1. 底部让开系统栏 + Dock + 状态卡片（padForSystemBars 的 bottomExtra，clipToPadding = false，
 *    内容滚进留白区、画在悬浮层后面）；
 * 2. 顶部栏随滚动抬升（页面里没有 CoordinatorLayout，只能手动驱动，见 AppBarLift.kt）；
 * 3. 嵌套滚动一路传到外层，Dock 与状态卡片才会随列表收起。
 * 以前九个页面各写一遍，现在收在这一处（[MainContentHost]）。
 */

/** 持有 [MainContentHost] 的宿主（MainActivity）。页面经它找到托管入口，不依赖具体的 Activity 类 */
interface MainContentHostOwner {
    val contentHost: MainContentHost
}

/**
 * 主界面托管内容的登记处与事件汇聚点，一个 MainActivity 一个。
 *
 * - [bottomClearance]：滚动视图底部要为悬浮的 Dock 与状态卡片让出的高度（px）。宿主在它变化时
 *   调 [refreshClearance]，各滚动视图的 padForSystemBars 随之更新；
 * - [sources]：当前挂在窗口上的、被托管的滚动视图（缓存里脱离窗口的页面不算）；
 * - [addListener]：订阅全部托管视图的滚动（[ContentListener.onContentScrolled]）与结构变化
 *   （[ContentListener.onContentChanged]：挂上 / 摘下窗口、重新布局）。事件的来源是每个视图自己的
 *   [ScrollHub]，这里不另占 View 唯一的滚动回调槽。顶部栏抬升订阅各视图的 hub。
 *
 * 只在主线程使用。
 */
class MainContentHost(private val clearance: () -> Int) {

    private val listeners = ArrayList<ContentListener>()

    // 登记过的视图 -> 是否会发滚动事件。弱引用的键：从未挂上窗口的视图不会被这里拖住
    private val registered = WeakHashMap<View, Boolean>()

    fun bottomClearance(): Int = clearance()

    /**
     * 让位高度变了（状态卡片滑入 / 滑出的每一帧、卡片高度变化）：所有挂在窗口上的托管视图按最近一次的
     * 插入区重算底部留白，不重新分发整棵树的插入区。脱离窗口的缓存页不用管，挂回时会请求分发、读到当时的值
     */
    fun refreshClearance() {
        for (view in registered.keys.toTypedArray()) {
            if (view.isAttachedToWindow) view.reapplySystemBarPadding()
        }
    }

    /** 当前挂在窗口上的托管视图 */
    val sources: List<View>
        get() = registered.keys.filter { it.isAttachedToWindow }

    /**
     * [view] 是否会发滚动事件。Dashboard 的 WebView 容器不会（WebView 自己在内部滚动，
     * 既不发嵌套滚动也不触发容器的滚动回调），订阅者对它只能靠 [ContentListener.onContentChanged]
     * 或自己的定时重录，不能等滚动事件。
     */
    fun reportsScroll(view: View): Boolean = registered[view] == true

    fun addListener(listener: ContentListener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    fun removeListener(listener: ContentListener) {
        listeners.removeAll { it === listener }
    }

    /** 登记一个托管视图；同一个视图重复登记只更新标志，不叠加监听 */
    internal fun register(view: View, reportsScroll: Boolean) {
        val first = !registered.containsKey(view)
        registered[view] = reportsScroll
        if (first) {
            view.addOnAttachStateChangeListener(attachListener)
            view.addOnLayoutChangeListener(layoutListener)
            if (reportsScroll) ScrollHub.of(view).addListener(scrollForwarder)
        }
        if (view.isAttachedToWindow) dispatchChanged(view)
    }

    private fun dispatchChanged(source: View?) {
        for (listener in listeners.toTypedArray()) listener.onContentChanged(source)
    }

    private val scrollForwarder = object : ContentListener {
        override fun onContentScrolled(source: View) {
            for (listener in listeners.toTypedArray()) listener.onContentScrolled(source)
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = dispatchChanged(v)
        override fun onViewDetachedFromWindow(v: View) = dispatchChanged(v)
    }

    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        dispatchChanged(v)
    }
}

/**
 * 把 [scroll] 作为本页的滚动视图放进 MainActivity 托管的主界面：
 * - 底部留白 = 系统栏 + Dock + 状态卡片（[MainContentHost.bottomClearance]），键盘弹出时去掉后两者，
 *   即 `padForSystemBars(bottomExtra = …)`；[ime]、[consume] 原样传给它；
 * - 顶部栏抬升，三选一（都不传则不管顶部栏）：[appBar] 单页直接绑；[lift] 分页宿主持有的控制器
 *   （由它按当前页取滚动视图，本页的滚动视图只负责 follow）；[liftAncestorAppBar] 从 [scroll] 往上
 *   找页面里的 R.id.appbar；
 * - 嵌套滚动：RecyclerView / NestedScrollView（[NestedScrollingChild]）显式开启，debug 构建下对不能
 *   嵌套滚动的视图直接报错；
 * - 登记到 [MainContentHost]，滚动与结构变化汇入同一个订阅点。
 *
 * 当前宿主不是 [MainContentHostOwner]（选节点页、通知里的切换对话框：没有 Dock）时只做留白为 0 的
 * 系统栏适配和抬升，不登记。没有 Dock 的页面本来就该直接用 padForSystemBars / liftOnScrollOf。
 *
 * 唯一的例外是 Dashboard（WebviewFragment）：它传入的是 WebView 的外层容器，WebView 不发嵌套滚动，
 * 状态卡片在该页收不起（open-issues 已记，已定保持例外）。这种页面显式传 `nestedScrolling = false`：
 * 不开嵌套滚动、不断言类型，也不订阅滚动，登记时标为不发滚动事件（[MainContentHost.reportsScroll]）。
 */
fun Fragment.hostMainScrollable(
    scroll: View,
    appBar: AppBarLayout? = null,
    lift: AppBarLiftController? = null,
    liftAncestorAppBar: Boolean = false,
    ime: Boolean = false,
    consume: Boolean = false,
    nestedScrolling: Boolean = true,
) {
    require(listOf(appBar != null, lift != null, liftAncestorAppBar).count { it } <= 1) {
        "appBar / lift / liftAncestorAppBar 只能传一个"
    }
    // 每次分发插入区时才取宿主：页面脱离 Activity 或宿主不是 MainActivity 时为 0
    scroll.padForSystemBars(
        ime = ime,
        consume = consume,
        bottomExtra = { (activity as? MainContentHostOwner)?.contentHost?.bottomClearance() ?: 0 },
    )

    if (nestedScrolling) {
        if (BuildConfig.DEBUG) {
            check(scroll is NestedScrollingChild) {
                "${scroll.javaClass.simpleName} 不发嵌套滚动，Dock 与状态卡片不会随它收起；" +
                    "换成 RecyclerView / NestedScrollView，或显式传 nestedScrolling = false 声明例外"
            }
        }
        ViewCompat.setNestedScrollingEnabled(scroll, true)
    }

    when {
        appBar != null -> appBar.liftOnScrollOf(scroll)
        lift != null -> lift.follow(scroll)
        liftAncestorAppBar -> scroll.liftAncestorAppBar()
    }

    (activity as? MainContentHostOwner)?.contentHost?.register(scroll, reportsScroll = nestedScrolling)
}
