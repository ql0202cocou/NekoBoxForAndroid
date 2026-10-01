package io.nekohasekai.sagernet.ui

import android.view.View
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.util.concurrent.atomic.AtomicBoolean

class GroupPagerAdapter(private val fragment: ConfigurationFragment) : FragmentStateAdapter(fragment),
    ProfileManager.Listener,
    GroupManager.Listener {

    var selectedGroupIndex = 0
    var groupList: ArrayList<ProxyGroup> = ArrayList()

    // 批量删除提交后才逐条发 onRemoved，ungrouped 被删空时每条都会看到 0：
    // 已排上一次 reload 就不再查数、不再重复 reload，reload 换完列表后复位
    private val emptyUngroupedReloadPending = AtomicBoolean(false)

    fun reload(now: Boolean = false) {
        runOnDefaultDispatcher {
            var newGroupList = ArrayList(GroupRepository.getAllGroups())
            if (newGroupList.isEmpty()) {
                // 走 GroupManager 的锁内双检创建：直接写 DAO 时，并发 reload 或与
                // :bg 的 currentGroup() 竞争会落出两个 ungrouped 分组
                GroupManager.currentGroup()
                newGroupList = ArrayList(GroupRepository.getAllGroups())
            }
            newGroupList.find { it.ungrouped }?.let {
                if (ProfileRepository.countProfilesByGroup(it.id) == 0L) {
                    newGroupList.remove(it)
                }
            }

            // 这里在后台：groupList / selectedGroupIndex 归主线程（增删回调与 createFragment
            // 都在主线程），只读写本地的新列表，两个字段到下面的主线程块里一起换
            val selectedGroup = fragment.selectedItem?.groupId ?: GroupManager.currentGroupId()
            val set = selectedGroup > 0L
            val newSelectedIndex = if (set) newGroupList.indexOfFirst { it.id == selectedGroup } else -1
            if (!set && newGroupList.size == 1) {
                val onlyGroup = newGroupList[0].id
                if (DataStore.selectedGroup != onlyGroup) {
                    DataStore.selectedGroup = onlyGroup
                }
            }

            val runFunc = if (now) fragment.activity?.let { it::runOnUiThread } else fragment.groupPager::post
            if (runFunc == null) {
                emptyUngroupedReloadPending.set(false)
            } else {
                runFunc {
                    emptyUngroupedReloadPending.set(false)
                    // 回调只在主线程增删：reload 也会从后台的监听器（onAdd / onRemoved）
                    // 调用，主线程这时可能正在分发回调。数据集变化和 setCurrentItem
                    // 期间摘掉它，免得把 selectedGroup 写成过渡中的页
                    if (!fragment.select) {
                        fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
                    }
                    if (set) selectedGroupIndex = newSelectedIndex
                    groupList = newGroupList
                    notifyDataSetChanged()
                    if (set) fragment.groupPager.setCurrentItem(selectedGroupIndex, false)
                    val hideTab = groupList.size < 2
                    fragment.tabLayout.isGone = hideTab
                    fragment.toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                    if (!fragment.select) {
                        fragment.groupPager.registerOnPageChangeCallback(fragment.updateSelectedCallback)
                    }
                }
            }
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

    override suspend fun groupAdd(group: ProxyGroup) {
        fragment.tabLayout.post {
            groupList.add(group)

            if (groupList.any { !it.ungrouped }) fragment.tabLayout.post {
                fragment.tabLayout.visibility = View.VISIBLE
            }

            notifyItemInserted(groupList.size - 1)
            fragment.tabLayout.getTabAt(groupList.size - 1)?.select()
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        // These callbacks run on the caller's (background) dispatcher while
        // the main thread mutates groupList; only touch the list in post.
        fragment.tabLayout.post {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return@post
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        fragment.tabLayout.post {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return@post
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
