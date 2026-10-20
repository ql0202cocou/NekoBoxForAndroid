package io.nekohasekai.sagernet.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配置页（[ConfigurationFragment]，含 tab 条与各分组页的父级）的页面级状态，旋转时留存、页面真正关闭时随 [onCleared] 清掉：
 * - [groupList]：tab 条对应的分组列表。旋转后新的 [GroupPagerAdapter] 直接用它，不重读库，
 *   ViewPager2 自己的状态（当前页、各页 Fragment）因此能在第一次布局时就恢复；
 *   进程重建（ViewModel 是新的）时先用 SavedStateHandle 里存的精简副本（只有 id、名字、类型、排序方式，不含订阅链接等凭据）
 *   顶着：FragmentStateAdapter 恢复时会回收列表里没有 id 的页，列表是空的话各页（连同滚动位置）都被丢掉；随后再读库校正；
 * - 对 [ProfileRepository] / [GroupRepository] 的监听器订阅：ViewModel 创建时订阅、清除时摘掉，旋转期间到达的通知也落在列表上；
 * - [enterAnimationPlayed] 与 [pageSelections]：节点卡片的入场动画「这个分组播过没有」「用户切过几次页」。
 *   旋转后视图重建，不能因为标志丢了就再播一次；Dock 往返回到配置页时是新的 ViewModel，行为与冷启动一致；
 * - [pendingExportConfig]：等待用户选定文件的配置导出内容；进程重建会丢（不写进 SavedStateHandle：配置里有凭据，
 *   也可能很大，Bundle 放不下），writeToDocument 对空内容拒绝写入，不会截断用户选的文件；
 * - SavedStateHandle 里的 [selectedGroupId]（选择模式下用户翻到的分组）与 [searchQuery]（搜索框里的字）：进程重建后带回。
 *   普通模式下选中的分组仍以 DataStore.selectedGroup 为准（别处也读写它）。
 *
 * 列表与 [Host] 只在主线程读写。监听回调跑在调用方线程，读库在那里做，改列表一律切回主线程。
 * 界面刷新由当前的 [Host]（页面里的适配器）完成；没有 [Host]（旋转的空档）时只改列表，
 * 新适配器建好时直接取现成的列表。ViewModel 不碰 Activity 与 View。
 */
class ConfigurationViewModel(private val handle: SavedStateHandle) : ViewModel(),
    ProfileManager.Listener,
    GroupManager.Listener {

    /** 页面里的 tab 页适配器：ViewModel 把「改 tab 列表」的通知交给它 */
    interface Host {
        /**
         * 读库结果到了：[groups] 是新列表，[selectedGroup] 大于 0 时要切到该分组。
         * 适配器做差量刷新，最后用 [commitGroups] 把新列表交回 ViewModel。[now] 为真时直接应用，否则等 ViewPager 挂上之后
         */
        fun applyGroups(groups: ArrayList<ProxyGroup>, selectedGroup: Long, now: Boolean)

        /** 新分组已追加到 [groupList] 末尾：补 tab、通知插入并选中它 */
        fun groupInserted(group: ProxyGroup)

        /** 第 [index] 个分组已从 [groupList] 摘掉：通知移除 */
        fun groupRemovedAt(index: Int)

        /** 第 [index] 个分组改了名：更新 tab 文字 */
        fun groupRenamed(index: Int, group: ProxyGroup)
    }

    /** tab 条的分组列表；换列表只经 [commitGroups] */
    var groupList = restoreGroups()
        private set

    /** 分组列表是否读过一次库（进程重建后顶着的精简副本不算）；为假时新适配器要自己触发读库 */
    var groupsLoaded = false
        private set

    /** 选择模式下 arguments 里已选节点所在的分组，读库时优先于 DataStore 里的选中分组 */
    var argGroupId: Long? = null

    /** 选择模式下用户翻到的分组 id（0 表示没翻过）。进程重建后 ViewPager 要回到它 */
    var selectedGroupId: Long
        get() = handle[KEY_SELECTED_GROUP] ?: 0L
        set(value) {
            handle[KEY_SELECTED_GROUP] = value
        }

    /** 搜索框里的字（原样，不转小写）；旋转与进程重建后据此恢复搜索框 */
    var searchQuery: String
        get() = handle[KEY_SEARCH_QUERY] ?: ""
        set(value) {
            handle[KEY_SEARCH_QUERY] = value
        }

    /** 已经播过入场动画的分组 id。不进 SavedStateHandle：进程重建等同冷启动，该播 */
    val enterAnimationPlayed = HashSet<Long>()

    /** ViewPager 建好后的 onPageSelected 次数；第一次是初始页，不算用户切换 */
    var pageSelections = 0

    var pendingExportConfig = ""

    private var host: Host? = null

    // 批量删除提交后才逐条发 onRemoved，ungrouped 被删空时每条都会看到 0：
    // 已排上一次 reload 就不再查数、不再重复 reload，reload 换完列表后复位
    private val emptyUngroupedReloadPending = AtomicBoolean(false)

    private class Loaded(val groups: ArrayList<ProxyGroup>, val selectedGroup: Long)

    init {
        ProfileRepository.addListener(this)
        GroupRepository.addListener(this)
    }

    override fun onCleared() {
        ProfileRepository.removeListener(this)
        GroupRepository.removeListener(this)
    }

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    fun isAttached(host: Host) = this.host === host

    /** 换 tab 列表；适配器做完差量刷新后调用。读库结果在应用前页面已销毁时也经它落进来 */
    fun commitGroups(groups: ArrayList<ProxyGroup>) {
        groupList = groups
        groupsLoaded = true
        emptyUngroupedReloadPending.set(false)
        saveGroups()
    }

    // 精简副本：5 个并行数组，Bundle 原生支持。名字为空记成空串，恢复后与库里的 null 同样显示为默认名
    private fun saveGroups() {
        handle[KEY_GROUP_IDS] = LongArray(groupList.size) { groupList[it].id }
        handle[KEY_GROUP_NAMES] = ArrayList(groupList.map { it.name.orEmpty() })
        handle[KEY_GROUP_FLAGS] = IntArray(groupList.size) { if (groupList[it].ungrouped) 1 else 0 }
        handle[KEY_GROUP_TYPES] = IntArray(groupList.size) { groupList[it].type }
        handle[KEY_GROUP_ORDERS] = IntArray(groupList.size) { groupList[it].order }
    }

    private fun restoreGroups(): ArrayList<ProxyGroup> {
        val ids = handle.get<LongArray>(KEY_GROUP_IDS) ?: return ArrayList()
        val names = handle.get<ArrayList<String>>(KEY_GROUP_NAMES)
        val flags = handle.get<IntArray>(KEY_GROUP_FLAGS)
        val types = handle.get<IntArray>(KEY_GROUP_TYPES)
        val orders = handle.get<IntArray>(KEY_GROUP_ORDERS)
        if (names == null || flags == null || types == null || orders == null ||
            names.size != ids.size || flags.size != ids.size || types.size != ids.size || orders.size != ids.size
        ) return ArrayList()
        return ids.indices.mapTo(ArrayList()) {
            ProxyGroup(
                id = ids[it], name = names[it].takeIf { name -> name.isNotEmpty() },
                ungrouped = flags[it] != 0, type = types[it], order = orders[it],
            )
        }
    }

    /**
     * 重读分组并换掉 tab 列表。读库在 IO 线程，列表与 View 的读写都回到主线程；
     * 可从任意线程调用（监听器回调在后台）。[now] 为真时（视图尚未 attach 的首次载入）直接应用，
     * 否则等 groupPager attach 后再应用。ViewModel 清除后什么也不做
     */
    fun reload(now: Boolean = false) {
        viewModelScope.launch {
            // arguments 里的已选节点（选择模式）所在分组和用户翻到的分组是页面状态，在主线程取；读库兜底的部分才进 IO
            val preferred = selectedGroupId.takeIf { it > 0L } ?: argGroupId
            var handedOver = false
            try {
                val loaded = withContext(Dispatchers.IO) {
                    var newGroupList = ArrayList(GroupRepository.getAllGroups())
                    if (newGroupList.isEmpty()) {
                        // 走 GroupManager 的锁内双检创建：直接写 DAO 时，并发 reload 或与
                        // :bg 的 currentGroup() 竞争会落出两个 ungrouped 分组
                        GroupRepository.currentGroup()
                        newGroupList = ArrayList(GroupRepository.getAllGroups())
                    }
                    newGroupList.find { it.ungrouped }?.let {
                        if (ProfileRepository.countProfilesByGroup(it.id) == 0L) {
                            newGroupList.remove(it)
                        }
                    }

                    val selectedGroup = preferred ?: GroupRepository.currentGroupId()
                    val set = selectedGroup > 0L
                    if (!set && newGroupList.size == 1) {
                        val onlyGroup = newGroupList[0].id
                        if (DataStore.selectedGroup != onlyGroup) {
                            DataStore.selectedGroup = onlyGroup
                        }
                    }
                    Loaded(newGroupList, if (set) selectedGroup else 0L)
                }
                handedOver = true
                val host = host
                if (host == null) {
                    // 旋转的空档：只换列表，新适配器建好时直接取
                    commitGroups(loaded.groups)
                } else {
                    host.applyGroups(loaded.groups, loaded.selectedGroup, now)
                }
            } finally {
                // 正常路径由 commitGroups 复位；读库期间被取消（ViewModel 清除）或失败时在这里兜底，
                // 免得标记一直挂着、之后的空分组 reload 全被跳过
                if (!handedOver) emptyUngroupedReloadPending.set(false)
            }
        }
    }

    // ------------------------------------------------------------- 仓库通知
    // 下面几个回调跑在调用方的后台线程：只在主线程块里碰 groupList 与 Host

    override suspend fun groupAdd(group: ProxyGroup) {
        viewModelScope.launch(Dispatchers.Main) {
            // 并发的整表重读可能已经把这个分组读进列表：按 id 去重，免得 FragmentStateAdapter 遇到重复 id
            if (groupList.any { it.id == group.id }) return@launch
            groupList.add(group)
            saveGroups()
            host?.groupInserted(group)
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        viewModelScope.launch(Dispatchers.Main) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return@launch
            groupList.removeAt(index)
            saveGroups()
            host?.groupRemovedAt(index)
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        viewModelScope.launch(Dispatchers.Main) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return@launch
            groupList[index] = group
            saveGroups()
            host?.groupRenamed(index, group)
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

    companion object {
        private const val KEY_SELECTED_GROUP = "selectedGroupId"
        private const val KEY_SEARCH_QUERY = "searchQuery"
        private const val KEY_GROUP_IDS = "groupIds"
        private const val KEY_GROUP_NAMES = "groupNames"
        private const val KEY_GROUP_FLAGS = "groupFlags"
        private const val KEY_GROUP_TYPES = "groupTypes"
        private const val KEY_GROUP_ORDERS = "groupOrders"
    }
}
