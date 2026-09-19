package io.nekohasekai.sagernet.database

// Thin UI-facing facade over GroupManager and the group DAO; see
// ProfileRepository for the threading contract.
object GroupRepository {

    fun addListener(listener: GroupManager.Listener) = GroupManager.addListener(listener)

    fun removeListener(listener: GroupManager.Listener) = GroupManager.removeListener(listener)

    fun getAllGroups(): List<ProxyGroup> = SagerDatabase.groupDao.allGroups()

    fun updateUserOrders(groups: Collection<ProxyGroup>) = GroupManager.updateUserOrders(groups)

    suspend fun deleteGroups(groups: List<ProxyGroup>) = GroupManager.deleteGroup(groups)

    suspend fun clearGroup(groupId: Long) = GroupManager.clearGroup(groupId)

    suspend fun postReload(groupId: Long) = GroupManager.postReload(groupId)

}
