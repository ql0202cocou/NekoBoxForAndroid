package io.nekohasekai.sagernet.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分组页的页面级状态，旋转时留存、页面真正关闭时随 [onCleared] 清掉：
 * - [groupList]：列表的工作副本。旋转后新的适配器直接用它，不重读库、滚动位置也就能恢复；
 *   只有进程重建（ViewModel 是新的）才重新读库；
 * - 对 [GroupRepository] 的监听器订阅：ViewModel 创建时订阅、清除时摘掉，旋转期间到达的通知也落在列表上，
 *   不会像「适配器随视图销毁而摘监听」那样漏掉；
 * - [selectedGroupId] 在 SavedStateHandle 里：文件选择器的回调可能在进程重建后才送达一个全新的页面实例，
 *   那时点菜单的那一次点击已不存在，分组 id 只能从保存的状态里取。
 *
 * 列表与 [Host] 只在主线程读写。监听回调跑在调用方线程，读库在那里做，改列表一律切回主线程。
 * 界面刷新由当前的 [Host]（页面里的适配器）完成；没有 [Host]（旋转的空档）时只改列表，
 * 新适配器建好时直接取现成的列表。ViewModel 不碰 Activity 与 View。
 */
class GroupViewModel(private val handle: SavedStateHandle) : ViewModel(), GroupManager.Listener {

    /** 页面里的适配器：ViewModel 把「改列表」的通知交给它，由它转成 RecyclerView 的变更事件 */
    interface Host {
        /** 列表变更的统一入口：先提交撤销窗口（UndoSnackbarManager.mutate），再执行 [block] */
        fun mutate(block: () -> Unit)

        /** 整表换成 [groups]（就是 ViewModel 的工作副本本身），由 DiffUtil 发通知 */
        fun submit(groups: ArrayList<ProxyGroup>)

        /** 第 [index] 行的内容变了但差量看不出（同一实例被就地改过、节点数变化、订阅进度），就地通知 */
        fun notifyChanged(index: Int, payload: Any?)
    }

    /** 分组列表的工作副本，同时是当前适配器 differ 持有的那个实例；整表变化时换成新副本（[setGroups]） */
    var groupList = ArrayList<ProxyGroup>()
        private set

    /**
     * 点了哪个分组的「更多」菜单，导出到文件时要用。进程重建后文件选择器的结果送达新页面实例，
     * 此时只有 SavedStateHandle 里的这份还在
     */
    var selectedGroupId: Long
        get() = handle[KEY_SELECTED_GROUP] ?: 0L
        set(value) {
            handle[KEY_SELECTED_GROUP] = value
        }

    private var host: Host? = null

    // 拖动进行中（已有换位、尚未落下）；这期间不应用整表重读：库里还是拖动前的顺序，
    // 会把正在拖的列表拽回去。落下后补读一次
    private var dragging = false
    private var reloadAfterDrag = false

    init {
        GroupRepository.addListener(this)
        reload()
    }

    override fun onCleared() {
        GroupRepository.removeListener(this)
    }

    fun attach(host: Host) {
        this.host = host
    }

    /**
     * 视图销毁时摘掉 [host]。拖动中途销毁的话 ItemTouchHelper 不会再回调 clearView，换位只存在于内存里：
     * 放弃这次拖动并按库重读，免得列表顺序与库不一致
     */
    fun detach(host: Host) {
        if (this.host !== host) return
        this.host = null
        if (dragging) {
            dragging = false
            reloadAfterDrag = false
            reload()
        }
    }

    private fun mutateList(block: () -> Unit) {
        val host = host
        if (host == null) block() else host.mutate(block)
    }

    /** 提交一份新列表：传副本，此后的就地改动落在这份副本上。只在主线程调用 */
    private fun setGroups(groups: List<ProxyGroup>) {
        val next = ArrayList(groups)
        groupList = next
        host?.submit(next)
    }

    /** 整表重读。可从任意线程调用（监听回调、撤销提交、进程级协程）；ViewModel 清除后什么也不做 */
    fun reload() {
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                val groups = GroupRepository.getAllGroups().toMutableList()
                val ungrouped = groups.find { it.ungrouped }
                if (ungrouped != null && groups.size > 1 && ProfileRepository.countProfilesByGroup(ungrouped.id) == 0L) groups.removeAll { it.ungrouped }
                groups
            }
            if (dragging) {
                reloadAfterDrag = true
                return@launch
            }
            // 同节点列表的 reloadProfiles：整表重读会把撤销窗口里待删的分组读回来，先提交掉，
            // 否则撤销会按旧位置再插一份
            mutateList {
                setGroups(groups)
            }
        }
    }

    fun beginDrag() {
        dragging = true
    }

    /** 拖动落下。返回拖动期间是否有被挡下的重读，调用方在新顺序落库后补读 */
    fun endDrag(): Boolean {
        dragging = false
        return reloadAfterDrag.also { reloadAfterDrag = false }
    }

    // ------------------------------------------------------------- 仓库通知

    override suspend fun groupAdd(group: ProxyGroup) {
        // 这 300 ms 并不等任何动画：回调跑在保存方（分组编辑器）的协程里，延迟的实际效果是
        // 把编辑器的 finish() 与后面的监听器各推迟 300 ms，保留原样（去掉会改编辑器的关闭时机）
        delay(300L)

        onMainDispatcher {
            mutateList {
                // 延迟期间另一次整表重读可能已经把这个分组读进列表：按 id 去重，在就不再追加
                if (groupList.none { it.id == group.id }) {
                    setGroups(groupList + group)
                }
            }

            if (group.type == GroupType.SUBSCRIPTION) {
                GroupUpdater.startUpdate(group, true)
            }
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        // 该回调跑在调用方的后台线程，读库在这里做，主线程只动列表
        val groupCount = GroupRepository.getAllGroups().size
        onMainDispatcher {
            if (groupList.none { it.id == groupId }) return@onMainDispatcher
            mutateList {
                if (groupCount <= 2) {
                    reload()
                } else {
                    setGroups(groupList.filter { it.id != groupId })
                }
            }
        }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        // 回调跑在调用方（后台）线程，拖动排序在主线程改列表；只在主线程碰列表
        onMainDispatcher {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return@onMainDispatcher
            }
            mutateList {
                if (groupList[index] === group) {
                    // 同一个实例被就地改过：差量比不出变化，按原来的做法整卡重绑
                    host?.notifyChanged(index, null)
                } else {
                    setGroups(groupList.map { if (it.id == group.id) group else it })
                }
            }
        }
    }

    // 节点数变了（清空分组、节点增删）：分组本身没变，差量看不出，整卡重绑重查节点数
    override suspend fun groupUpdated(groupId: Long) = notifyGroupChanged(groupId, null)

    // 解析进度每前进一步都会到这里：进度不在 ProxyGroup 上，差量看不到，也不该为它整卡重绑、重查节点数，
    // 所以保留带 payload 的定点通知，由适配器只刷新进度条
    override suspend fun groupProgress(groupId: Long) = notifyGroupChanged(groupId, PAYLOAD_PROGRESS)

    private suspend fun notifyGroupChanged(groupId: Long, payload: Any?) {
        onMainDispatcher {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return@onMainDispatcher
            }
            host?.notifyChanged(index, payload)
        }
    }

    companion object {
        private const val KEY_SELECTED_GROUP = "selectedGroupId"

        // 只刷新订阅更新进度条的局部 bind 标记
        const val PAYLOAD_PROGRESS = "progress"
    }
}
