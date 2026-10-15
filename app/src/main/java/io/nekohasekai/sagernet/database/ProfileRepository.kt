package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean

// 界面层访问节点数据的入口：转发 ProfileManager 与节点 DAO，不做缓存，也不提供响应式流。
//
// 线程：本类不切线程，调用方沿用各自的调度器（runOnDefaultDispatcher 等）。
// - 非 suspend 的读取（getProfile、getProfilesByGroup、countProfilesByGroup）是阻塞查询，
//   靠 SagerDatabase 的 allowMainThreadQueries 才能在主线程调用；新代码应放到后台线程。
// - suspend 方法不自行换调度器，在调用方协程里完成写库，随后在同一协程里逐个通知监听器。
// - 非 suspend 的写入（updateUserOrders）同样是阻塞调用，应在后台线程调用。
//
// 事务：批量写入（createProfiles、updateUserOrders、updateTestResults、clearTraffic、clearTestResults、
// deleteProfiles）整批在一个 Room 事务里；监听器通知一律在事务提交之后。
//
// 通知（写操作是否通知监听器）：
// - 通知：createProfiles 逐个 onAdd，deleteProfiles 逐个 onRemoved，postUpdate 发 onUpdated。
// - 不通知：updateUserOrders、updateTestResults、clearTraffic、clearTestResults 只写列，由调用方
//   自己整组刷新（例如 GroupManager.postReload）。
// - getProfile 只读；与 ProfileManager.getProfile 不同，它不吞 SQLException，调用方沿用原来直接
//   查 DAO 的异常行为。
object ProfileRepository {

    fun addListener(listener: ProfileManager.Listener) = ProfileManager.addListener(listener)

    fun removeListener(listener: ProfileManager.Listener) = ProfileManager.removeListener(listener)

    // 按 id 读一行，不存在返回 null；直接查 DAO，不带 ProfileManager.getProfile 的异常兜底
    fun getProfile(profileId: Long): ProxyEntity? = SagerDatabase.proxyDao.getById(profileId)

    fun getProfilesByGroup(groupId: Long): List<ProxyEntity> =
        SagerDatabase.proxyDao.getByGroup(groupId)

    fun countProfilesByGroup(groupId: Long): Long = SagerDatabase.proxyDao.countByGroup(groupId)

    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> =
        ProfileManager.createProfiles(groupId, beans)

    fun updateUserOrders(profiles: Collection<ProxyEntity>) =
        ProfileManager.updateUserOrders(profiles)

    suspend fun deleteProfiles(profiles: List<ProxyEntity>) =
        ProfileManager.deleteProfiles(profiles)

    suspend fun updateTestResults(results: List<ProxyEntity>) = ProfileManager.updateTestResults(results)

    suspend fun postUpdate(profile: ProxyEntity) = ProfileManager.postUpdate(profile)

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
