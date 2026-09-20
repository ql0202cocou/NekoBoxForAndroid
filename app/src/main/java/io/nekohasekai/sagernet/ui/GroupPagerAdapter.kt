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
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class GroupPagerAdapter(private val fragment: ConfigurationFragment) : FragmentStateAdapter(fragment),
    ProfileManager.Listener,
    GroupManager.Listener {

    var selectedGroupIndex = 0
    var groupList: ArrayList<ProxyGroup> = ArrayList()
    var groupFragments: HashMap<Long, ProfileListFragment> = HashMap()

    fun reload(now: Boolean = false) {

        if (!fragment.select) {
            fragment.groupPager.unregisterOnPageChangeCallback(fragment.updateSelectedCallback)
        }

        runOnDefaultDispatcher {
            var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
            if (newGroupList.isEmpty()) {
                // 走 GroupManager 的锁内双检创建：直接写 DAO 时，并发 reload 或与
                // :bg 的 currentGroup() 竞争会落出两个 ungrouped 分组
                GroupManager.currentGroup()
                newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
            }
            newGroupList.find { it.ungrouped }?.let {
                if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                    newGroupList.remove(it)
                }
            }

            var selectedGroup = fragment.selectedItem?.groupId ?: GroupManager.currentGroupId()
            var set = false
            if (selectedGroup > 0L) {
                selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                set = true
            } else if (groupList.size == 1) {
                selectedGroup = groupList[0].id
                if (DataStore.selectedGroup != selectedGroup) {
                    DataStore.selectedGroup = selectedGroup
                }
            }

            val runFunc = if (now) fragment.activity?.let { it::runOnUiThread } else fragment.groupPager::post
            if (runFunc != null) {
                runFunc {
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
            groupFragments[proxyGroup.id] = this
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

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        val group = onMainDispatcher { groupList.find { it.id == groupId } } ?: return
        if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
            reload()
        }
    }
}
