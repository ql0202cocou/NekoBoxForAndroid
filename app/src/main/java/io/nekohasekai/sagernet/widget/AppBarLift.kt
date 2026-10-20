package io.nekohasekai.sagernet.widget

import android.view.View
import android.view.ViewGroup
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R

/*
 * 顶部栏随内容滚动抬升（liftOnScroll）的手动驱动。
 *
 * 这些页面没有 CoordinatorLayout，也不能加：MainActivity 里的页面套上 CoordinatorLayout 后，
 * AppBarLayout.Behavior 会接走嵌套滚动，底部 Dock 与状态卡片就不再随列表收起。
 * material 1.14.0 的 AppBarLayout 只在 Behavior 的 onNestedPreScroll / onLayoutChild 里调
 * shouldLift，没有 CoordinatorLayout 就永远不会自己抬升；但 setLifted(boolean) 单独可用，
 * 自带颜色动画，liftable 会在 onLayout 时随 app:liftOnScroll="true" 自动置真。
 * 所以由页面自己监听滚动视图，滚离顶部就 setLifted(true)，回到顶部还原。
 *
 * 全部走 [AppBarLiftController]：单页由 [liftOnScrollOf] 建一个，分页宿主（主界面的节点页、
 * 工具页）自己持有一个，当前页的滚动视图由它的 source 回答。滚动事件来自 [ScrollHub]，
 * 与玻璃共用同一个分发器，不再各占一次 View 唯一的滚动回调槽。
 */

/**
 * 顶部栏抬升状态 = 「source 给出的滚动视图还能不能向上滚」。
 *
 * - 单页：source 固定返回那个滚动视图；
 * - 分页：source 按分页当前页取滚动视图，每个页面的滚动视图都 [follow] 同一个控制器，
 *   非当前页的滚动事件触发 [update] 也无妨，读的始终是当前页。
 *
 * 控制器可以先于顶部栏存在（持有它的 Fragment 构造时就有），[attach] 在视图创建后绑定顶部栏，
 * [detach] 在视图销毁时解开。没绑顶部栏时 [update] 什么也不做。
 */
class AppBarLiftController(private val source: () -> View?) : ContentListener {

    private var appBar: AppBarLayout? = null

    fun attach(appBar: AppBarLayout) {
        this.appBar = appBar
    }

    fun detach() {
        appBar = null
    }

    /** 按当前页的滚动位置重新判断。没有可取的滚动视图时按「在顶部」处理 */
    fun update() {
        appBar?.setLifted(source()?.canScrollVertically(-1) == true)
    }

    /**
     * 订阅 [scrolling] 的滚动事件，并在布局完成后补一次判断：恢复的滚动位置、旋转重建后没有滚动事件。
     * 同一个视图重复 follow 无效。
     */
    fun follow(scrolling: View) {
        ScrollHub.of(scrolling).addListener(this)
        scrolling.post { update() }
    }

    override fun onContentScrolled(source: View) = update()
}

/**
 * 让本顶部栏随 [scrolling]（RecyclerView / NestedScrollView）的滚动位置抬升。
 * View.OnScrollChangeListener 对这两种视图都会回调（RecyclerView 在 dispatchOnScrolled 里触发）。
 */
fun AppBarLayout.liftOnScrollOf(scrolling: View) {
    AppBarLiftController { scrolling }.also {
        it.attach(this)
        it.follow(scrolling)
    }
}

/**
 * 让本视图所在页面的顶部栏随本视图的滚动抬升：从 parent 往上找，取第一个直接含 R.id.appbar
 * 子视图的祖先，对它的 AppBarLayout 调 [liftOnScrollOf]；找不到就什么都不做。
 *
 * 不用 activity.findViewById(R.id.appbar)：同一棵视图树里可能有别的页面的顶部栏
 * （如 MainActivity 里被替换下来但尚未移除的页面，或同时存在的其它 fragment），
 * 按 id 全局查找可能取到错的那个。
 */
fun View.liftAncestorAppBar() {
    var node = parent
    while (node is ViewGroup) {
        // 只认直接子视图：深搜可能先取到更深层、属于别的页面的顶部栏
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            if (child.id == R.id.appbar && child is AppBarLayout) {
                child.liftOnScrollOf(this)
                return
            }
        }
        node = node.parent
    }
}
