package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected)?.groupId == groupId) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.proxyDao.deleteAll(groupId)
        resetDanglingGroupProxies()
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        // userOrder only: a full-row @Update would roll back status/ping/tx/rx that
        // TrafficLooper and URL tests persist between the read and the write
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.proxyDao.getByGroup(groupId).forEachIndexed { index, entity ->
                SagerDatabase.proxyDao.updateOrder(entity.id, (index + 1).toLong())
            }
        }
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup, preserveSubscriptionRuntime: Boolean = false) {
        var updated: ProxyGroup? = null
        SagerDatabase.instance.runInTransaction {
            val current = SagerDatabase.groupDao.getById(group.id) ?: return@runInTransaction
            // The editor does not own list position or the ungrouped marker.
            group.userOrder = current.userOrder
            group.ungrouped = current.ungrouped
            if (preserveSubscriptionRuntime) {
                val currentSubscription = current.subscription
                group.subscription?.apply {
                    lastUpdated = currentSubscription?.lastUpdated ?: lastUpdated
                    subscriptionUserinfo =
                        currentSubscription?.subscriptionUserinfo ?: subscriptionUserinfo
                    bytesUsed = currentSubscription?.bytesUsed ?: bytesUsed
                    bytesRemaining = currentSubscription?.bytesRemaining ?: bytesRemaining
                    expiryDate = currentSubscription?.expiryDate ?: expiryDate
                }
            }
            SagerDatabase.groupDao.updateGroup(group)
            updated = group
        }
        val current = updated ?: return
        iterator { groupUpdated(current) }
        // 分组类型可能在订阅与基本之间切换：不再订阅的分组要取消周期任务，
        // 新订阅的分组要排上；reconfigureUpdater 按现状更新，全部关闭时才取消
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun updateSortOrder(groupId: Long, order: Int) {
        if (SagerDatabase.groupDao.updateSortOrder(groupId, order) == 0) return
        postUpdate(groupId)
    }

    suspend fun deleteGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected)?.groupId == groupId) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteById(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            resetDanglingGroupProxies()
        }
        if (DataStore.selectedGroup == groupId) resetSelectedGroup()
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val selected = DataStore.selectedProxy
        val selectedGroupId = if (selected > 0L) {
            SagerDatabase.proxyDao.getById(selected)?.groupId
        } else null
        if (selectedGroupId != null && group.any { it.id == selectedGroupId }) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.groupDao.deleteGroup(group)
            SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
            resetDanglingGroupProxies()
        }
        if (group.any { it.id == DataStore.selectedGroup }) resetSelectedGroup()
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    // Profiles deleted with their group may still be referenced as another
    // group's frontProxy/landingProxy; ConfigBuilder.resolveChain would get
    // null from getById and silently drop the user's front/landing proxy.
    fun resetDanglingGroupProxies() {
        // SQL-only column updates avoid writing stale subscription fields from
        // group snapshots while the :bg updater is persisting fresh metadata.
        SagerDatabase.groupDao.resetDanglingFrontProxies()
        SagerDatabase.groupDao.resetDanglingLandingProxies()
    }

    // Mirrors the fallback in DataStore.currentGroup(): fall back to the first
    // remaining group, or -1 so currentGroup() recreates the ungrouped group.
    private fun resetSelectedGroup() {
        DataStore.selectedGroup = SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
    }

}
