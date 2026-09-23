package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean

// Thin UI-facing facade over ProfileManager and the profile DAO: forwarding
// only, no caching or reactive streams. It does no threading of its own;
// callers keep their usual dispatchers (runOnDefaultDispatcher & co.), and
// blocking reads rely on the database's allowMainThreadQueries exactly as
// the direct DAO calls did.
object ProfileRepository {

    fun addListener(listener: ProfileManager.Listener) = ProfileManager.addListener(listener)

    fun removeListener(listener: ProfileManager.Listener) = ProfileManager.removeListener(listener)

    fun getProfilesByGroup(groupId: Long): List<ProxyEntity> =
        SagerDatabase.proxyDao.getByGroup(groupId)

    fun countProfilesByGroup(groupId: Long): Long = SagerDatabase.proxyDao.countByGroup(groupId)

    suspend fun createProfile(groupId: Long, bean: AbstractBean, core: Int = 0): ProxyEntity =
        ProfileManager.createProfile(groupId, bean, core)

    fun updateUserOrders(profiles: Collection<ProxyEntity>) =
        ProfileManager.updateUserOrders(profiles)

    suspend fun deleteProfiles(profiles: List<ProxyEntity>) =
        ProfileManager.deleteProfiles(profiles)

    suspend fun updateStatus(profile: ProxyEntity) = ProfileManager.updateStatus(profile)

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) =
        ProfileManager.postUpdate(profile, noTraffic)

    suspend fun clearTraffic(profiles: List<ProxyEntity>) {
        for (profile in profiles) {
            profile.tx = 0
            profile.rx = 0
        }
        ProfileManager.updateTraffic(profiles)
    }

    suspend fun clearTestResults(profiles: List<ProxyEntity>) {
        for (profile in profiles) {
            profile.status = 0
            profile.ping = 0
            profile.error = null
        }
        ProfileManager.updateStatus(profiles)
    }

}
