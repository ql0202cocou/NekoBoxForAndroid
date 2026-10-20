package io.nekohasekai.sagernet.ui

import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class GroupPagerAdapter(private val fragment: ConfigurationFragment) : FragmentStateAdapter(fragment),
    ProfileManager.Listener,
    GroupManager.Listener {

    var selectedGroupIndex = 0
    var groupList: ArrayList<ProxyGroup> = ArrayList()

    // 批量删除提交后才逐条发 onRemoved，ungrouped 被删空时每条都会看到 0：
    // 已排上一次 reload 就不再查数、不再重复 reload，reload 换完列表后复位
    private val emptyUngroupedReloadPending = AtomicBoolean(false)

    // 页面作用域：适配器随视图重建（ConfigurationFragment.onViewCreated 每次都新建），
    // 此刻视图已存在；视图销毁时取消，之后的读库结果与 View 更新一并作废
    private val scope = fragment.viewLifecycleOwner.lifecycleScope

    private class Loaded(val groups: ArrayList<ProxyGroup>, val set: Boolean, val selectedIndex: Int)

    /**
     * 重读分组并换掉 tab 列表。读库在 IO 线程，所有 View 与 groupList / selectedGroupIndex 的读写都回到主线程；
     * 可从任意线程调用（监听器回调在后台）。[now] 为真时（视图尚未 attach 的首次载入）直接在主线程应用，
     * 否则等 groupPager attach 后再应用
     */
    fun reload(now: Boolean = false) {
        // arguments 里的已选节点（选择模式）是页面状态，在主线程取；读库兜底的部分才进 IO
        val argGroup = fragment.selectedItem?.groupId
        scope.launch {
            var handedOver = false
            try {
                val loaded = withContext(Dispatchers.IO) {
                    var newGroupList = ArrayList(GroupRepository.getAllGroups())
                    if (newGroupList.isEmpty()) {
                        // 走 GroupManager 的锁内双检创建：直接写 DAO 时，并发 reload 或与
                        // :bg 的 currentGroup() 竞争会落出两个 ungrouped 分组
                        GroupRepository.currentGroup()
                        newGroupList = ArrayList(GroupRepository.getAllGroups())
                    }
                    newGroupList.find { it.ungrouped }?.let {
                        if (ProfileRepository.countProfilesByGroup(it.id) == 0L) {
                            newGroupList.remove(it)
                        }
                    }

                    // groupList / selectedGroupIndex 归主线程（增删回调与 createFragment
                    // 都在主线程），这里只读写本地的新列表，两个字段到主线程块里一起换
                    val selectedGroup = argGroup ?: GroupRepository.currentGroupId()
                    val set = selectedGroup > 0L
                    val newSelectedIndex = if (set) newGroupList.indexOfFirst { it.id == selectedGroup } else -1
                    if (!set && newGroupList.size == 1) {
                        val onlyGroup = newGroupList[0].id
                        if (DataStore.selectedGroup != onlyGroup) {
                            DataStore.selectedGroup = onlyGroup
                        }
                    }
                    Loaded(newGroupList, set, newSelectedIndex)
                }
                handedOver = true
                if (now) {
                    applyLoaded(loaded)
                } else {
                    fragment.groupPager.post {
                        if (scope.isActive) applyLoaded(loaded) else emptyUngroupedReloadPending.set(false)
                    }
                }
            } finally {
                // 正常路径由 applyLoaded 复位；读库期间被取消（视图销毁）或失败时在这里兜底，
                // 免得标记一直挂着、之后的空分组 reload 全被跳过
                if (!handedOver) emptyUngroupedReloadPending.set(false)
            }
        }
    }

    private fun applyLoaded(loaded: Loaded) {
        emptyUngroupedReloadPending.set(false)
        // 回调只在主线程增删：reload 也会从后台的监听器（onAdd / onRemoved）
        // 调用，主线程这时可能正在分发回调。数据集变化和 setCurrentItem
        // 期间摘掉它，免得把 selectedGroup 写成过渡中的页
        if (!fragment.select) {
            fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
        }
        if (loaded.set) selectedGroupIndex = loaded.selectedIndex
        groupList = loaded.groups
        notifyDataSetChanged()
        if (loaded.set) fragment.groupPager.setCurrentItem(selectedGroupIndex, false)
        // 换页后新页的滚动位置不同，等页面挂上再取一次抬升状态
        fragment.groupPager.post { fragment.updateAppBarLift() }
        val hideTab = groupList.size < 2
        fragment.tabLayout.isGone = hideTab
        if (!fragment.select) {
            fragment.groupPager.registerOnPageChangeCallback(fragment.updateSelectedCallback)
        }
    }

    init {
        reload(true)
    }

    override fun getItemCount(): Int {
        return groupList.size
    }

    override fun createFragment(position: Int): Fragment {
        return ProfileListFragment().apply {
            proxyGroup = groupList[position]
            if (position == selectedGroupIndex) {
                selected = true
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return groupList[position].id
    }

    override fun containsItem(itemId: Long): Boolean {
        return groupList.any { it.id == itemId }
    }

    // 下面三个回调跑在调用方的后台线程：只在页面作用域的主线程块里碰 tabLayout 与 groupList，
    // 视图销毁后整块作废（不再读 fragment.tabLayout 这个陈旧引用）
    override suspend fun groupAdd(group: ProxyGroup) {
        scope.launch(Dispatchers.Main) {
            groupList.add(group)

            if (groupList.any { !it.ungrouped }) fragment.tabLayout.post {
                fragment.tabLayout.visibility = View.VISIBLE
            }

            notifyItemInserted(groupList.size - 1)
            fragment.tabLayout.getTabAt(groupList.size - 1)?.select()
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        scope.launch(Dispatchers.Main) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return@launch
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        scope.launch(Dispatchers.Main) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return@launch
            fragment.tabLayout.getTabAt(index)?.text = group.displayName()
        }
    }

    override suspend fun groupUpdated(groupId: Long) = Unit

    override suspend fun onAdd(profile: ProxyEntity) {
        val groupMissing = onMainDispatcher { groupList.none { it.id == profile.groupId } }
        if (groupMissing) {
            DataStore.selectedGroup = profile.groupId
            reload()
        }
    }

    override suspend fun onUpdated(data: TrafficData) = Unit

    override suspend fun onUpdated(profile: ProxyEntity) = Unit

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (emptyUngroupedReloadPending.get()) return
        val group = onMainDispatcher { groupList.find { it.id == groupId } } ?: return
        if (group.ungrouped && ProfileRepository.countProfilesByGroup(groupId) == 0L &&
            emptyUngroupedReloadPending.compareAndSet(false, true)
        ) {
            reload()
        }
    }
}
