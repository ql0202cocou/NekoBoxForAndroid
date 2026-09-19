package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.util.concurrent.CopyOnWriteArrayList

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

    // copy-on-write: iteration walks a snapshot, so a listener may add or
    // remove listeners while being notified
    private val listeners = CopyOnWriteArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        for (listener in listeners) what(listener)
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // Same SQLException handling as ProfileManager.getProfile.
    fun getGroup(groupId: Long): ProxyGroup? = guardedRead(null) {
        SagerDatabase.groupDao.getById(groupId)
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

    // Same column-only, single-transaction write as rearrange, for orders the
    // user dragged into place rather than ones derived from the row order.
    fun updateUserOrders(groups: Collection<ProxyGroup>) {
        SagerDatabase.instance.runInTransaction {
            for (group in groups) {
                SagerDatabase.groupDao.updateUserOrder(group.id, group.userOrder)
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

    suspend fun deleteGroup(groupId: Long) = deleteGroups(listOf(groupId)) {
        SagerDatabase.groupDao.deleteById(groupId)
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) = deleteGroups(group.map { it.id }) {
        SagerDatabase.groupDao.deleteGroup(group)
    }

    // deleteRows removes the group rows themselves inside the transaction.
    private suspend fun deleteGroups(groupIds: List<Long>, deleteRows: () -> Unit) {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected)?.groupId in groupIds) {
            DataStore.selectedProxy = 0L
        }
        SagerDatabase.instance.runInTransaction {
            deleteRows()
            SagerDatabase.proxyDao.deleteByGroup(groupIds.toLongArray())
            resetDanglingGroupProxies()
        }
        if (DataStore.selectedGroup in groupIds) resetSelectedGroup()
        for (groupId in groupIds) iterator { groupRemoved(groupId) }
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

    // Trusts any positive selection without a lookup (callers that need the
    // row use currentGroup()); otherwise falls back like currentGroup().
    @Synchronized
    fun currentGroupId(): Long {
        val currentSelected = DataStore.configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        return currentGroup().id
    }

    // The selected group, or the first existing one, or a fresh ungrouped
    // group; the fallback is written back as the selection.
    @Synchronized
    fun currentGroup(): ProxyGroup {
        val currentSelected = DataStore.configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            SagerDatabase.groupDao.getById(currentSelected)?.let { return it }
        }
        val group = SagerDatabase.groupDao.allGroups().firstOrNull()
            ?: ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        DataStore.selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        // no BASIC group yet (e.g. fresh install importing a subscription first):
        // fall back to the current group, which currentGroup() creates if needed
        return groups.find { it.type == GroupType.BASIC }?.id ?: current.id
    }

    // Mirrors the fallback in currentGroup(): fall back to the first remaining
    // group, or -1 so currentGroup() recreates the ungrouped group.
    fun resetSelectedGroup() {
        DataStore.selectedGroup = SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
    }

}
