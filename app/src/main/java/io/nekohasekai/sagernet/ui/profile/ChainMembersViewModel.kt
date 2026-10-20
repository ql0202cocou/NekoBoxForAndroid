package io.nekohasekai.sagernet.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.EditorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 链式代理编辑器的成员列表，旋转时留存：[proxyList] 与缓存里的成员 id 保持同步，旋转后新的适配器直接用它，
 * 不再按 id 重读一遍库；进程重建后 ViewModel 是新的，从缓存里的 id 重新载入（[loaded] 为假）。
 *
 * 两个列表只在主线程改写（RecyclerView 也在主线程读）。依赖 SavedStateHandle，所以必须在
 * `super.onCreate()` 之后才能第一次取到。
 */
class ChainMembersViewModel(private val handle: SavedStateHandle) : ViewModel() {

    val proxyList = ArrayList<ProxyEntity>()

    // 悬空的成员 id（对应节点已被删除）渲染成不可操作的占位行，排在成员行之后；
    // 不放进 proxyList，move() / remove() 的下标算术与缓存回写语义才不变
    val missingIds = ArrayList<Long>()

    /** 列表是否已按缓存载入过；后台线程也会读 */
    @Volatile
    var loaded = false
        private set

    /** 正在替换的成员位置（1 起，0 表示追加）；进程死亡后仍在，免得待处理的替换变成追加 */
    var replacing: Int
        get() = handle[KEY_REPLACING] ?: 0
        set(value) {
            handle[KEY_REPLACING] = value
        }

    private val _revision = MutableStateFlow(0)

    /** 列表被整体重建的次数；Activity 订阅它来 notifyDataSetChanged（拖动、删除各自发细粒度通知，不计入） */
    val revision: StateFlow<Int> get() = _revision

    /**
     * 按 [readIds]（缓存里的成员 id）重建两个列表。[session] 是调用方所在的会话协程：每个挂起点之后先复查会话，
     * 再碰缓存与列表。在 [session] 的协程里整体重建，主线程完成后递增 [revision]。
     */
    suspend fun reload(session: EditorScope, readIds: () -> List<Long>) {
        while (true) {
            val idList = readIds()
            val profiles = if (idList.isNotEmpty()) {
                session.checked { ProfileRepository.getProfiles(idList) }.map { it.id to it }.toMap()
            } else emptyMap()
            // 进程重建后会触发两次（恢复出来的 Fragment 与重新初始化换上的 Fragment），所以整体重建而不是追加；
            // 在 RecyclerView 读取它的主线程上改写
            val rebuilt = session.onMain {
                // 载入期间选择器回调可能已经写了缓存：重试，而不是用过期的 id 重建列表、抹掉刚选的成员
                if (readIds() != idList) return@onMain false
                proxyList.clear()
                missingIds.clear()
                for (id in idList) {
                    val profile = profiles[id]
                    if (profile != null) proxyList.add(profile) else missingIds.add(id)
                }
                loaded = true
                _revision.value++
                true
            }
            if (rebuilt) return
        }
    }

    private companion object {
        const val KEY_REPLACING = "replacing"
    }
}
