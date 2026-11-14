package io.nekohasekai.sagernet.widget

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.NestedScrollingChild
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * 主界面托管内容的登记处，一个 MainActivity 一个。
 *
 * [bottomClearance]：滚动视图底部要为悬浮的 Dock 与状态卡片让出的高度（px）。宿主在它变化时
 * 调 [refreshClearance]。实际留白在手势和惯性期间冻结，缩小时只回收不会推动当前内容的部分；
 * 剩余部分经 [ScrollHub] 在滚回安全位置后重试。只在主线程使用。
 */
class MainContentHost(private val clearance: () -> Int) {

    // 值不持有视图，避免 WeakHashMap 的值反过来把弱键留住。
    private class PaddingState(var extra: Int, var nestedTypes: Int = 0) {
        /** 手势、惯性或程序滚动进行中：留白保持不变 */
        fun frozen(view: View) =
            nestedTypes != 0 || view is RecyclerView && view.scrollState != RecyclerView.SCROLL_STATE_IDLE
    }
    private val registered = WeakHashMap<View, PaddingState>()
    private val bounds = Rect()

    fun bottomClearance(): Int = clearance()

    /** 卡片到位或高度变化后，按各列表的位置重算；不重新分发整棵树的插入区。 */
    fun refreshClearance() = hostedViews.forEach { it.reapplySystemBarPadding() }

    /** 系统栏仍照常适配，只有悬浮控件的额外留白参与冻结和安全回收。 */
    internal fun bottomClearance(view: View): Int {
        val state = registered[view] ?: return clearance()
        if (state.frozen(view)) return state.extra
        val target = clearance()
        state.extra = if (target >= state.extra || !view.isLaidOut || !view.canScrollVertically(-1)) {
            target
        } else {
            // 缩小留白相当于增加可见高度。末端内容必须仍填满新增区域，才能避免布局器向回补空隙。
            maxOf(target, state.extra - contentBelowViewport(view))
        }
        return state.extra
    }

    /** 末端内容超出可视下沿的高度（px）；认不出布局方式时为 0，即不回收 */
    private fun contentBelowViewport(view: View): Int {
        val edge = view.height - view.paddingBottom
        val bottom = when (view) {
            is RecyclerView -> {
                val manager = view.layoutManager as? LinearLayoutManager
                if (manager == null || manager.reverseLayout || manager.stackFromEnd) return 0
                (0 until view.childCount).maxOfOrNull {
                    manager.getDecoratedBoundsWithMargins(view.getChildAt(it), bounds)
                    bounds.bottom
                }
            }
            is NestedScrollView -> view.getChildAt(0)?.let { child ->
                val margin = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                child.bottom + margin - view.scrollY
            }
            else -> null
        } ?: return 0
        return (bottom - edge).coerceAtLeast(0)
    }

    internal fun scrollStarted(view: View, type: Int) {
        registered[view]?.let { it.nestedTypes = it.nestedTypes or (1 shl type) }
    }

    internal fun scrollStopped(view: View, type: Int) {
        val state = registered[view] ?: return
        state.nestedTypes = state.nestedTypes and (1 shl type).inv()
        // 另一种类型（触摸 / 惯性）还在滚动时留白仍冻结，等它结束再算。
        if (state.nestedTypes != 0) return
        // 触摸结束和惯性开始可能在同一轮消息里，下一轮再读状态，不能在两者之间释放留白。
        view.post { view.reapplyIfAttached() }
    }

    /** 当前挂在窗口上的托管视图 */
    internal val hostedViews: List<View>
        get() = registered.keys.filter { it.isAttachedToWindow }

    /** 登记一个托管视图；重复登记无效 */
    internal fun register(view: View) {
        if (registered.containsKey(view)) return
        val state = PaddingState(clearance())
        registered[view] = state
        val retry = Runnable { view.reapplyIfAttached() }
        fun retryIfNeeded() {
            // 冻结期间重试也改不了留白，等嵌套滚动结束或回到空闲时再来。
            if (state.frozen(view) || state.extra == clearance()) return
            view.removeCallbacks(retry)
            view.post(retry)
        }
        ScrollHub.of(view).addListener { retryIfNeeded() }
        // 没有位移的滚动（到头后的程序滚动）不发滚动回调，冻结只能靠回到空闲来解除。
        (view as? RecyclerView)?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) retryIfNeeded()
            }
        })
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> retryIfNeeded() }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = retryIfNeeded()
            override fun onViewDetachedFromWindow(v: View) {
                state.nestedTypes = 0
                view.removeCallbacks(retry)
            }
        })
    }
}

private fun View.reapplyIfAttached() {
    if (isAttachedToWindow) reapplySystemBarPadding()
}

/**
 * 把 [scroll] 作为本页的滚动视图放进 MainActivity 托管的主界面：
 * - 底部留白 = 系统栏 + Dock + 状态卡片（滚动期间冻结，接近底部时暂留必要空间），键盘弹出时去掉后两者，
 *   即 `padForSystemBars(bottomExtra = …)`；[ime]、[consume] 原样传给它；
 * - 顶部栏抬升，三选一（都不传则不管顶部栏）：[appBar] 单页直接绑；[lift] 分页宿主持有的控制器
 *   （由它按当前页取滚动视图，本页的滚动视图只负责 follow）；[liftAncestorAppBar] 从 [scroll] 往上
 *   找页面里的 R.id.appbar；
 * - 嵌套滚动：RecyclerView / NestedScrollView（[NestedScrollingChild]）显式开启，debug 构建下对不能
 *   嵌套滚动的视图直接报错；
 * - 登记到 [MainContentHost]，让位高度变化时随之重算底部留白。
 *
 * 当前宿主不是 [MainContentHostOwner]（选节点页、通知里的切换对话框：没有 Dock）时只做留白为 0 的
 * 系统栏适配和抬升，不登记。没有 Dock 的页面本来就该直接用 padForSystemBars / liftOnScrollOf。
 *
 * 唯一的例外是 Dashboard（WebviewFragment）：它传入的是 WebView 的外层容器，WebView 不发嵌套滚动，
 * 状态卡片在该页收不起（open-issues 已记，已定保持例外）。这种页面显式传 `nestedScrolling = false`：
 * 不开嵌套滚动、不断言类型。
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
    (activity as? MainContentHostOwner)?.contentHost?.register(scroll)
    // 每次分发插入区时才取宿主：页面脱离 Activity 或宿主不是 MainActivity 时为 0
    scroll.padForSystemBars(
        ime = ime,
        consume = consume,
        bottomExtra = { (activity as? MainContentHostOwner)?.contentHost?.bottomClearance(scroll) ?: 0 },
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
}
