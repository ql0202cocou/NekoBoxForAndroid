package io.nekohasekai.sagernet.ui

import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup

/**
 * 配置页的 tab 页适配器，每个分组一页（[ProfileListFragment]）。分组列表、读库与仓库监听都在
 * [ConfigurationViewModel] 里，旋转后新的适配器直接用现成的列表；这里只管把变化转成 ViewPager2 / TabLayout 的更新。
 */
class GroupPagerAdapter(private val fragment: ConfigurationFragment) : FragmentStateAdapter(fragment),
    ConfigurationViewModel.Host {

    // 数据在 ViewModel 里。ViewModel 在适配器构造时取一次存着：读库结果晚于视图销毁时也不必再经 Fragment 去找它
    private val model = fragment.model

    var selectedGroupIndex = 0

    val groupList: ArrayList<ProxyGroup> get() = model.groupList

    /** 重读分组并换掉 tab 列表，可从任意线程调用 */
    fun reload(now: Boolean = false) = model.reload(now)

    /**
     * 页面接线（groupPager.adapter、TabLayoutMediator）完成后调用：接上 ViewModel，
     * 列表已经读过（旋转）就直接用，否则读库
     */
    fun start() {
        model.attach(this)
        if (model.groupsLoaded) {
            // 现成的列表：ViewPager2 的状态（当前页、各页 Fragment）在第一次布局时按它恢复，不必再切页
            selectedGroupIndex = groupList.indexOfFirst { it.id == currentGroupId() }.coerceAtLeast(0)
            fragment.tabLayout.isGone = groupList.size < 2
            registerSelectedCallback()
        } else {
            reload(true)
        }
    }

    /** 视图销毁时调用：摘掉适配器，之后到达的读库结果只换 ViewModel 里的列表 */
    fun stop() = model.detach(this)

    // 选中的分组：选择模式看用户翻到的页，否则看 DataStore（别处也会改它）
    private fun currentGroupId(): Long =
        if (fragment.select) model.selectedGroupId.takeIf { it > 0L } ?: model.argGroupId ?: -1L
        else DataStore.selectedGroup

    private fun registerSelectedCallback() {
        if (!fragment.select) {
            fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
            fragment.groupPager.registerOnPageChangeCallback(fragment.updateSelectedCallback)
        }
    }

    // ConfigurationViewModel.Host
    override fun applyGroups(groups: ArrayList<ProxyGroup>, selectedGroup: Long, now: Boolean) {
        if (now) {
            applyLoaded(groups, selectedGroup)
        } else {
            fragment.groupPager.post {
                // 视图销毁（或已换成新适配器）之后只把读到的列表交给 ViewModel，不再碰 View
                if (model.isAttached(this)) applyLoaded(groups, selectedGroup) else model.commitGroups(groups)
            }
        }
    }

    private fun applyLoaded(groups: ArrayList<ProxyGroup>, selectedGroup: Long) {
        // 回调只在主线程增删：reload 也会从后台的监听器（onAdd / onRemoved）
        // 调用，主线程这时可能正在分发回调。数据集变化和 setCurrentItem
        // 期间摘掉它，免得把 selectedGroup 写成过渡中的页
        if (!fragment.select) {
            fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
        }
        val set = selectedGroup > 0L
        if (set) selectedGroupIndex = groups.indexOfFirst { it.id == selectedGroup }
        // 按分组 id 差量刷新：没变的页（及其 Fragment 与滚动位置）原样保留，
        // 新增 / 删除 / 挪动只发对应的细粒度通知。getItemId / containsItem 按 id 回答，
        // FragmentStateAdapter 靠它们认出哪些 Fragment 还在、哪些该回收。
        // 先换 groupList 再分发：分发期间 getItemCount / getItemId 读的是新列表
        val pager = fragment.groupPager
        val old = groupList
        val shownId = old.getOrNull(pager.currentItem)?.id
        model.commitGroups(groups)
        DiffUtil.calculateDiff(GroupDiff(old, groups)).dispatchUpdatesTo(this)
        // 差量下 RecyclerView 让原来那一页跟着挪到新下标，ViewPager2 的 currentItem 却要等下一次布局才更新；
        // 此刻直接 setCurrentItem(目标) 会因为「目标 == 旧 currentItem」被当成已在该页而什么也不做
        // （比如导入落到排在最前的新分组）。所以先让页码对上正在显示的那一页，再去目标页
        val shownIndex = groups.indexOfFirst { it.id == shownId }
        if (shownIndex >= 0) pager.setCurrentItem(shownIndex, false)
        if (set) pager.setCurrentItem(selectedGroupIndex, false)
        // 换页后新页的滚动位置不同，等页面挂上再取一次抬升状态
        pager.post { fragment.updateAppBarLift() }
        fragment.tabLayout.isGone = groups.size < 2
        registerSelectedCallback()
    }

    // 分组的身份是 id；tab 上显示的只有名字（displayName），名字或是否为 ungrouped 变了才算内容变化
    private class GroupDiff(
        private val old: List<ProxyGroup>,
        private val new: List<ProxyGroup>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition].id == new[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val a = old[oldItemPosition]
            val b = new[newItemPosition]
            return a.name.orEmpty() == b.name.orEmpty() && a.ungrouped == b.ungrouped && a.type == b.type
        }
    }

    override fun getItemCount(): Int {
        return groupList.size
    }

    override fun createFragment(position: Int): Fragment {
        val group = groupList[position]
        return ProfileListFragment.newInstance(group, selected = position == selectedGroupIndex)
    }

    override fun getItemId(position: Int): Long {
        return groupList[position].id
    }

    override fun containsItem(itemId: Long): Boolean {
        return groupList.any { it.id == itemId }
    }

    override fun groupInserted(group: ProxyGroup) {
        if (groupList.any { !it.ungrouped }) fragment.tabLayout.post {
            fragment.tabLayout.visibility = View.VISIBLE
        }

        notifyItemInserted(groupList.size - 1)
        fragment.tabLayout.getTabAt(groupList.size - 1)?.select()
    }

    override fun groupRemovedAt(index: Int) {
        notifyItemRemoved(index)
    }

    override fun groupRenamed(index: Int, group: ProxyGroup) {
        fragment.tabLayout.getTabAt(index)?.text = group.displayName()
    }
}
