package io.nekohasekai.sagernet.database

// 界面层访问分组数据的入口：转发 GroupManager 与分组 DAO；线程契约与节点侧相同，见 ProfileRepository。
//
// 线程：非 suspend 的读取（getGroup、getAllGroups）与 updateUserOrders 是阻塞调用，靠
// allowMainThreadQueries 才能在主线程调用，新代码应放到后台线程；suspend 方法不自行换调度器。
//
// 事务：updateUserOrders 整批一个事务；deleteGroups 把删分组、删分组内节点、修正悬挂的前置 / 落地
// 引用放在一个事务里；clearGroup 把清空节点与修正悬挂引用放在一个事务里。监听器通知都在提交之后。
//
// 通知：deleteGroups 逐个 groupRemoved，clearGroup 与 postReload 发 groupUpdated(groupId)；
// updateUserOrders 只写 userOrder 列，不通知。getGroup 直接查 DAO，不吞 SQLException
// （与 GroupManager.getGroup 的兜底不同）。
object GroupRepository {

    fun addListener(listener: GroupManager.Listener) = GroupManager.addListener(listener)

    fun removeListener(listener: GroupManager.Listener) = GroupManager.removeListener(listener)

    // 按 id 读一行，不存在返回 null
    fun getGroup(groupId: Long): ProxyGroup? = SagerDatabase.groupDao.getById(groupId)

    fun getAllGroups(): List<ProxyGroup> = SagerDatabase.groupDao.allGroups()

    fun updateUserOrders(groups: Collection<ProxyGroup>) = GroupManager.updateUserOrders(groups)

    suspend fun deleteGroups(groups: List<ProxyGroup>) = GroupManager.deleteGroup(groups)

    suspend fun clearGroup(groupId: Long) = GroupManager.clearGroup(groupId)

    suspend fun postReload(groupId: Long) = GroupManager.postReload(groupId)

}
