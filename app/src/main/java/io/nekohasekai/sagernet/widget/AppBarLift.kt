package io.nekohasekai.sagernet.widget

import android.view.View
import com.google.android.material.appbar.AppBarLayout

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
 * 主界面分页的版本是 ConfigurationFragment.updateAppBarLift（按当前页的列表驱动），
 * 工具页的分页版本是 ToolsFragment.updateAppBarLift，做法相同。
 */

/**
 * 让本顶部栏随 [scrolling]（RecyclerView / NestedScrollView）的滚动位置抬升。
 * View.OnScrollChangeListener 对这两种视图都会回调（RecyclerView 在 dispatchOnScrolled 里触发）。
 */
fun AppBarLayout.liftOnScrollOf(scrolling: View) {
    val update = { setLifted(scrolling.canScrollVertically(-1)) }
    scrolling.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    // 恢复的滚动位置、旋转重建后没有滚动事件，布局完成后补一次
    scrolling.post { update() }
}
