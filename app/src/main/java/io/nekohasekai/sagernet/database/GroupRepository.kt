package io.nekohasekai.sagernet.database

// 界面层访问分组数据的入口：转发 GroupManager 与分组 DAO；线程契约与节点侧相同，见 ProfileRepository。
//
// 线程：非 suspend 的读取（getGroup、getAllGroups、currentGroup、currentGroupId、selectedGroupForImport）
// 与 updateUserOrders 是阻塞调用，只能在后台线程调用（SagerDatabase 不开 allowMainThreadQueries）；
// suspend 方法不自行换调度器。currentGroup / currentGroupId / selectedGroupForImport 在没有选中分组时会
// 回退到第一个分组、必要时新建 ungrouped 分组，并把结果写回 DataStore.selectedGroup。
// userInterface 只是一个引用，不查库。
//
// 事务：updateUserOrders 整批一个事务；deleteGroups 把删分组、删分组内节点、修正悬挂的前置 / 落地
// 引用放在一个事务里；clearGroup 把清空节点与修正悬挂引用放在一个事务里。监听器通知都在提交之后。
//
// 通知：createGroup 发 groupAdd；updateGroup 与 postUpdate 发 groupUpdated(group)；updateSortOrder 改了行才发；
// deleteGroup(s) 逐个 groupRemoved；clearGroup 与 postReload 发 groupUpdated(groupId)；
// updateUserOrders 只写 userOrder 列，不通知。
// getGroup 与 GroupManager.getGroup 同一语义：库打不开抛 IOException，其它 SQLException 记日志后返回 null。
object GroupRepository {

    fun addListener(listener: GroupManager.Listener) = GroupManager.addListener(listener)

    fun removeListener(listener: GroupManager.Listener) = GroupManager.removeListener(listener)

    // 订阅更新结果的确认 / 提示由界面实现（MainActivity 在前台时挂上，离开时清掉）
    var userInterface: GroupManager.Interface?
        get() = GroupManager.userInterface
        set(value) {
            GroupManager.userInterface = value
        }

    // 按 id 读一行，不存在返回 null；异常语义见文件头
    fun getGroup(groupId: Long): ProxyGroup? = GroupManager.getGroup(groupId)

    fun getAllGroups(): List<ProxyGroup> = SagerDatabase.groupDao.allGroups()

    // 当前选中的分组 id；没有选中时按 currentGroup 回退并写回选择
    fun currentGroupId(): Long = GroupManager.currentGroupId()

    // 当前选中的分组行；选中的已不存在时回退到第一个分组，没有分组就新建 ungrouped 分组
    fun currentGroup(): ProxyGroup = GroupManager.currentGroup()

    // 导入节点的目标分组：当前分组是普通分组就用它，否则取第一个普通分组，都没有时用当前分组
    fun selectedGroupForImport(): Long = GroupManager.selectedGroupForImport()

    suspend fun createGroup(group: ProxyGroup): ProxyGroup = GroupManager.createGroup(group)

    suspend fun updateGroup(group: ProxyGroup, preserveSubscriptionRuntime: Boolean = false) =
        GroupManager.updateGroup(group, preserveSubscriptionRuntime)

    suspend fun updateSortOrder(groupId: Long, order: Int) = GroupManager.updateSortOrder(groupId, order)

    fun updateUserOrders(groups: Collection<ProxyGroup>) = GroupManager.updateUserOrders(groups)

    suspend fun deleteGroup(groupId: Long) = GroupManager.deleteGroup(groupId)

    suspend fun deleteGroups(groups: List<ProxyGroup>) = GroupManager.deleteGroup(groups)

    suspend fun clearGroup(groupId: Long) = GroupManager.clearGroup(groupId)

    // 重读这一行再发 groupUpdated(group)；行已不存在时什么都不发
    suspend fun postUpdate(groupId: Long) = GroupManager.postUpdate(groupId)

    suspend fun postReload(groupId: Long) = GroupManager.postReload(groupId)

}
