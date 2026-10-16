package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean

// 界面层访问节点数据的入口：转发 ProfileManager 与节点 DAO，不做缓存，也不提供响应式流。
//
// 线程：本类不切线程，调用方沿用各自的调度器（runOnDefaultDispatcher 等）。
// - 非 suspend 的读取（getProfile、getProfiles、getProfilesByGroup、countProfilesByGroup）是阻塞查询，
//   只能在后台线程调用：SagerDatabase 不开 allowMainThreadQueries，主线程调用会被 Room 拒绝（抛 IllegalStateException）。
// - suspend 方法不自行换调度器，在调用方协程里完成读写库，随后在同一协程里逐个通知监听器；
//   同样只能在后台调度器上调用。
// - 非 suspend 的写入（updateUserOrders）同样是阻塞调用，应在后台线程调用。
// - liveTraffic 是内存里的表，不查库，任意线程可读。
//
// 事务：批量写入（createProfiles、updateUserOrders、updateTestResults、clearTraffic、clearTestResults、
// deleteProfiles）整批在一个 Room 事务里；监听器通知一律在事务提交之后。
//
// 通知（写操作是否通知监听器）：
// - 通知：createProfile(s) 逐个 onAdd，updateProfile、moveProfile 与 postUpdate 发 onUpdated，
//   deleteProfile(s) 逐个 onRemoved。
// - 不通知：updateUserOrders、updateTestResults、clearTraffic、clearTestResults 只写列，由调用方
//   自己整组刷新（例如 GroupManager.postReload）。
// - getProfile / getProfiles 只读，与 ProfileManager 同一语义：id 为 0 直接返回 null 不查库；库打不开抛
//   IOException；其它 SQLException 记日志后当作「没有这一行」（null / 空列表），调用方按节点已删除处理。
object ProfileRepository {

    fun addListener(listener: ProfileManager.Listener) = ProfileManager.addListener(listener)

    fun removeListener(listener: ProfileManager.Listener) = ProfileManager.removeListener(listener)

    // 主进程收到的最近一次实时流量，见 ProfileManager.liveTraffic
    val liveTraffic get() = ProfileManager.liveTraffic

    // 按 id 读一行，不存在返回 null；异常语义见文件头
    fun getProfile(profileId: Long): ProxyEntity? = ProfileManager.getProfile(profileId)

    // 按 id 读多行，缺失的省略；异常语义见文件头
    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> = ProfileManager.getProfiles(profileIds)

    fun getProfilesByGroup(groupId: Long): List<ProxyEntity> =
        SagerDatabase.proxyDao.getByGroup(groupId)

    fun countProfilesByGroup(groupId: Long): Long = SagerDatabase.proxyDao.countByGroup(groupId)

    suspend fun createProfile(groupId: Long, bean: AbstractBean, core: Int = 0): ProxyEntity =
        ProfileManager.createProfile(groupId, bean, core)

    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> =
        ProfileManager.createProfiles(groupId, beans)

    // 返回 false：这一行已不在库里，什么都没写（见 ProfileManager.updateProfile）
    suspend fun updateProfile(profile: ProxyEntity): Boolean = ProfileManager.updateProfile(profile)

    // 返回移动后的行；节点已不存在时返回 null
    suspend fun moveProfile(profileId: Long, groupId: Long): ProxyEntity? =
        ProfileManager.moveProfile(profileId, groupId)

    fun updateUserOrders(profiles: Collection<ProxyEntity>) =
        ProfileManager.updateUserOrders(profiles)

    suspend fun deleteProfile(groupId: Long, profileId: Long) =
        ProfileManager.deleteProfile(groupId, profileId)

    suspend fun deleteProfiles(profiles: List<ProxyEntity>) =
        ProfileManager.deleteProfiles(profiles)

    suspend fun updateTestResults(results: List<ProxyEntity>) = ProfileManager.updateTestResults(results)

    suspend fun postUpdate(profile: ProxyEntity) = ProfileManager.postUpdate(profile)

    // 重读这一行再发 onUpdated；行已不存在时什么都不发
    suspend fun postUpdate(profileId: Long) = ProfileManager.postUpdate(profileId)

    // 实时流量推送：记进 liveTraffic 再发 onUpdated(TrafficData)，不读库
    suspend fun postUpdate(data: TrafficData) = ProfileManager.postUpdate(data)

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
