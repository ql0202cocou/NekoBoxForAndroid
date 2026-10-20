package io.nekohasekai.sagernet.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.moveUserOrder
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.insertClamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 配置页里一个分组页（[ProfileListFragment]）的页面级状态，旋转时留存、页面真正关闭时随 [onCleared] 清掉：
 * - 节点列表的全部数据：可见列表 [items]（differ 持有的那个工作副本）、缓存 [configurationList]、分组内的完整顺序、搜索词，
 *   以及「首次载入已做过」「载入完成」等标志。旋转后新的适配器直接用现成的列表，不重读库，
 *   RecyclerView 的布局状态（滚动位置）也就能恢复；只有进程重建（ViewModel 是新的）才重新读库；
 * - 对 [ProfileRepository] / [GroupRepository] 的监听器订阅：ViewModel 创建时订阅、清除时摘掉，
 *   旋转期间到达的通知也落在列表上，不会像「适配器随视图销毁而摘监听」那样漏掉；
 * - 分组 id 与排序方式来自 Fragment arguments（SavedStateHandle 以 arguments 为初值，进程重建后由系统带回）；
 *   「首次载入已定位过」在 SavedStateHandle 里，进程重建后不再把用户拽回选中节点，免得盖掉恢复出来的滚动位置。
 *
 * 列表与状态只在主线程读写。监听回调跑在调用方线程，读库在那里做，改列表一律切回主线程。
 * 界面刷新由当前的 [Host]（页面里的适配器）完成；没有 [Host]（旋转的空档）时只改列表，
 * 新适配器建好时直接取现成的列表。ViewModel 不碰 Activity 与 View。
 */
class ProfileListViewModel(private val handle: SavedStateHandle) : ViewModel(),
    ProfileManager.Listener,
    GroupManager.Listener {

    /** 页面里的适配器：ViewModel 把「改列表」的通知交给它，由它转成 RecyclerView 的变更事件 */
    interface Host {
        /** 列表变更的统一入口：先提交撤销窗口（UndoSnackbarManager.mutate），再执行 [block] */
        fun mutate(block: () -> Unit)

        /** 整表换成 [list]（就是 [items] 本身），由 DiffUtil 发通知 */
        fun submit(list: ArrayList<ProxyEntity>)

        fun notifyInserted(position: Int)
        fun notifyRemoved(position: Int)
        fun notifyMoved(from: Int, to: Int)

        /** 第 [position] 行的内容变了但差量看不出，就地通知 */
        fun notifyChanged(position: Int, payload: Any?)

        /** 实时流量只重画流量段，不重绑整行 */
        fun bindTraffic(data: TrafficData)

        /** 整表提交之后：差量可能一条通知也不发，载入完成与空状态仍要确认 */
        fun onListSubmitted()

        /** 首次载入后的定位：滚到 [index] 行 */
        fun scrollTo(index: Int)
    }

    val groupId: Long = handle[ARG_GROUP_ID] ?: NO_GROUP

    /** 分组的排序方式，来自 arguments，之后随监听器通知更新 */
    @Volatile
    var order: Int = handle[ARG_ORDER] ?: GroupOrder.ORIGIN
        private set

    /** 用户在菜单里改了排序方式：先记下新值（再点同一项不重复写），落库后监听器通知会触发整组重读 */
    fun changeOrder(newOrder: Int) {
        if (order == newOrder) return
        order = newOrder
        // 进程级：分组排序方式的落库，页面随后离开也要写完
        runOnDefaultDispatcher { GroupRepository.updateSortOrder(groupId, newOrder) }
    }

    // 这一页是否是「选中分组」所在页：首次载入后要定位到选中节点。选择模式下选中项来自配置页的 arguments
    private val selected: Boolean = handle[ARG_SELECTED] ?: false
    private var selectedItemId: Long? = null

    // 可见列表的可变工作副本，同时就是 differ 持有的那一个实例。
    // 整表变化（整组重读、搜索过滤）一律经 submitItems 提交一份新副本，由 DiffUtil 发通知；
    // 只有主线程同步的几条路径就地改它并紧跟精确的 notify：拖动换位、删除、撤销插回、新增追加、
    // 单行更新（ItemTouchHelper 要求当场可见，撤销下标只对删除当时的列表有效）。主线程之外不碰
    var items = ArrayList<ProxyEntity>()
        private set

    // 缓存里每个节点的当前实例（含被搜索过滤掉的）；items 里的项一定取自这里，两者始终在主线程
    // 同一个 post 里成对更新。取不到说明是陈旧回调（latestEntity 返回 null），而不是在主线程同步读库兜底
    val configurationList = HashMap<Long, ProxyEntity>()

    // every id in group order, unaffected by filter(): the HashMap's key
    // order used to scramble search results
    private val allProfileIds = mutableListOf<Long>()

    // Derived, not tracked: the visible list is always a subsequence of
    // allProfileIds, so a shorter one means a filter is hiding rows and its
    // positions no longer map to the search source. Dragging is disabled while
    // that holds (getDragDirs), which keeps move() in a single index space.
    val isFiltered get() = items.size != allProfileIds.size

    /** 当前搜索词（小写），只在主线程读写。整组重读回到主线程时按它重新过滤 */
    var query = ""
        private set

    /**
     * 列表是否已经载入过（含空分组）。为假时页面不显示空状态占位，免得刚建好的分组页闪一下；
     * 旋转后保持为真，新页面一开始就按真实内容显示
     */
    var listLoaded = false

    private var started = false
    private var host: Host? = null

    private val updated = HashSet<ProxyEntity>()

    // 拖动进行中（已有换位、尚未落下）；这期间不应用整组重读：库里还是拖动前的顺序，
    // 会把正在拖的列表拽回去。落下后补读一次
    private var dragging = false
    private var reloadAfterDrag = false

    // 首次载入定位只做一次：页面（每个分组一个）第一次载入，也就是冷启动与切到某个分组页；
    // 之后的整组重读（测速结果、订阅更新、改排序）是差量更新，不能再把用户拽回选中项。
    // 存在 SavedStateHandle 里：进程重建后 RecyclerView 恢复了滚动位置，不能再被盖掉
    private var initialScrollPending: Boolean
        get() = handle.get<Boolean>(KEY_INITIAL_SCROLL_DONE) != true
        set(value) {
            handle[KEY_INITIAL_SCROLL_DONE] = !value
        }

    init {
        ProfileRepository.addListener(this)
        GroupRepository.addListener(this)
    }

    override fun onCleared() {
        ProfileRepository.removeListener(this)
        GroupRepository.removeListener(this)
    }

    /** 页面配置：选中分组页要定位的选中项（选择模式下来自 arguments，否则为空，读库时取 DataStore.selectedProxy） */
    fun configure(selectedItemId: Long?) {
        this.selectedItemId = selectedItemId
    }

    fun attach(host: Host) {
        this.host = host
        // 载入已完成但这一页还没有机会定位（载入时没有页面）：补上
        if (listLoaded) scrollToInitialIfPending()
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
            updated.clear()
            reloadAsync()
        }
    }

    /**
     * 页面第一次可见时调用：还没读过库才读，旋转后的新页面不会再读。
     * [searchQuery] 是配置页搜索框里此刻的字（进程重建后搜索框已恢复、这一页的 ViewModel 是新的），读库前先带上
     */
    fun start(searchQuery: String) {
        if (started) return
        started = true
        query = searchQuery.lowercase()
        reloadAsync()
    }

    private fun reloadAsync() {
        viewModelScope.launch(Dispatchers.IO) { reloadProfiles() }
    }

    private fun mutateList(block: () -> Unit) {
        val host = host
        if (host == null) block() else host.mutate(block)
    }

    /**
     * 提交一份新的可见列表：传副本，differ 对同一实例直接返回、不做比对；此后的就地改动落在这份副本上。
     * 同步比对，返回时 RecyclerView 已收到全部通知
     */
    private fun submitItems(list: List<ProxyEntity>) {
        val next = ArrayList(list)
        items = next
        host?.submit(next)
    }

    /** 行位置；找不到返回 -1 */
    fun indexOfId(profileId: Long) = items.indexOfFirst { it.id == profileId }

    /** 缓存里某节点的最新实例：holder 的动作（分享、编辑、删除）取它，不用绑定那一刻的旧实例 */
    fun latestEntity(profileId: Long): ProxyEntity? = configurationList[profileId]

    private fun getItemAt(index: Int) = items.getOrNull(index)

    private fun visibleItems(): List<ProxyEntity> {
        val all = allProfileIds.mapNotNull { configurationList[it] }
        if (query.isEmpty()) return all
        return all.filter { matchesQuery(it) }
    }

    private fun matchesQuery(profile: ProxyEntity) = query.isEmpty() ||
            profile.displayName().contains(query, ignoreCase = true) ||
            profile.displayType().contains(query, ignoreCase = true) ||
            profile.displayAddress().contains(query, ignoreCase = true)

    // ------------------------------------------------------------- 搜索

    /** 主线程调用 */
    fun filter(name: String) {
        // 本来就没在搜索：清空会广播到所有分组页，不必每页都整组重读
        if (name.isEmpty() && query.isEmpty()) return
        query = name.lowercase()
        if (name.isEmpty()) {
            // 清空搜索时的整组重读带读库，放到 IO 线程；页面真正关闭时随作用域取消
            reloadAsync()
            return
        }
        // 与 onAdd / reloadProfiles 一样先提交撤销窗口：undo 按删除时的可见位置
        // 插回，过滤后列表变短就会越界
        mutateList {
            submitItems(visibleItems())
        }
        // 前后两次结果一样时差量不发任何通知，空状态（「没有匹配的节点」）仍要按新搜索词刷新
        host?.onListSubmitted()
    }

    // ------------------------------------------------------------- 拖动排序

    // dragging is disabled while filtering, so the visible list and the search
    // source share indices here and both can be permuted in one pass
    fun move(from: Int, to: Int): Boolean {
        val moved = moveUserOrder(from, to, ProxyEntity::userOrder, ::getItemAt) { i, profile ->
            items[i] = profile
            allProfileIds[i] = profile.id
            updated.add(profile)
        }
        if (moved) {
            dragging = true
            host?.notifyMoved(from, to)
        }
        return moved
    }

    fun commitMove() {
        // swap out the pending moves on the main thread: move() adds
        // to `updated` there while the write below iterates it
        val updated = HashSet(updated)
        this.updated.clear()
        dragging = false
        val deferredReload = reloadAfterDrag
        reloadAfterDrag = false
        // 进程级：拖动排序的落库，页面随后离开也要写完
        runOnDefaultDispatcher {
            ProfileRepository.updateUserOrders(updated)
            // 拖动期间被挡下的重读，要等新顺序落库后再读
            if (deferredReload) reloadProfiles()
        }
    }

    // ------------------------------------------------------------- 删除与撤销

    // 删除：就地改（见 items 的说明）
    fun remove(pos: Int) {
        if (pos < 0) return
        // drop it from the search source as well: until the undo snackbar
        // commits the deletion, filter() would still surface the removed profile
        allProfileIds.remove(items[pos].id)
        items.removeAt(pos)
        host?.notifyRemoved(pos)
    }

    // 撤销插回：就地改，下标夹在当前范围内
    fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        for ((index, item) in actions) {
            onMain {
                // 点撤销到这个 post 执行之间若有整组重读，它已经把这一行读回来了（库里还没删），不能再插一次
                if (item.id in allProfileIds) return@onMain
                configurationList[item.id] = item
                // 下标夹在当前范围内：窗口期间列表被改短也不越界
                val at = items.insertClamped(index, item)
                // back into the search source, anchored on the row it was
                // restored above: deleting is allowed while filtering, and
                // `index` is then a visible position the full list has never
                // shared
                val anchor = items.getOrNull(at + 1)?.id
                allProfileIds.add(
                    anchor?.let { allProfileIds.indexOf(it) }?.takeIf { it >= 0 }
                        ?: allProfileIds.size, item.id
                )
                host?.notifyInserted(at)
            }
        }
    }

    fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        // 同一分组的批量删除：一个事务，整组 rearrange 只做一次
        // 进程级：撤销窗口到期的批量删除落库，页面已关闭也要删完
        runOnDefaultDispatcher { ProfileRepository.deleteProfiles(profiles) }
    }

    // 回调跑在调用方线程：改列表一律投回主线程。用 Dispatchers.Main（不是 immediate），
    // 与原先的 view.post 一样总是排到主线程消息队列里，不会在回调线程就是主线程时插队
    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    // ------------------------------------------------------------- 仓库通知

    // 新增：就地追加到末尾。导入一批节点会逐个来到这里，每个都做一次整表差量是 O(n²)
    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId != groupId) return

        onMain {
            mutateList {
                // 并发的整组重读可能已经把这一行读进来了（先落库、重读先到、onAdd 后到）：
                // 重读的数据不旧于这次通知，按 id 去重，在就不再追加
                if (profile.id in allProfileIds) return@mutateList
                configurationList[profile.id] = profile
                allProfileIds.add(profile.id)
                // 搜索中只把命中的新节点放进可见列表，与 visibleItems() 的过滤一致
                if (!matchesQuery(profile)) return@mutateList
                val pos = items.size
                items.add(profile)
                host?.notifyInserted(pos)
            }
        }
    }

    // 单行更新（选中项切换、清除流量 / 测速结果的 postUpdate、编辑器保存）：内容可能没变
    // （选中态不在卡片字段里），差量看不出，所以走定点通知而不是整表提交
    override suspend fun onUpdated(profile: ProxyEntity) {
        if (profile.groupId != groupId) return
        onMain {
            // compute the index here: this callback runs on a background
            // dispatcher while the main thread mutates the list
            val index = indexOfId(profile.id)
            if (index < 0) return@onMain
            mutateList {
                // 库里的 tx/rx 可能落后于实时流量，重绑时 bind 会优先取
                // ProfileRepository.liveTraffic
                configurationList[profile.id] = profile
                items[index] = profile
                host?.notifyChanged(index, PAYLOAD_UPDATED)
            }
        }
    }

    override suspend fun onUpdated(data: TrafficData) {
        onMain {
            try {
                // 缓存的实体跟着实时流量走：停止时 liveTraffic 被清空，之后重绑的行
                // 回落到实体上的值，不能是列表加载时的旧值。在主线程改：后台回调期间
                // 主线程也在改这张表
                configurationList[data.id]?.apply {
                    rx = data.rx
                    tx = data.tx
                } ?: return@onMain
                host?.bindTraffic(data)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId != this.groupId) return

        onMain {
            // 别处删了节点（菜单批删、编辑器删除）：先提交撤销窗口。undo 按删除时的位置插回，列表变短会越界；
            // 删的若正是窗口里待删的那个，撤销还会插回一行库里已没有的节点
            mutateList {
                allProfileIds.remove(profileId)
                val index = indexOfId(profileId)
                if (index < 0) return@mutateList
                items.removeAt(index)
                configurationList.remove(profileId)
                host?.notifyRemoved(index)
            }
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit
    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != groupId) return
        order = group.order
        reloadProfiles()
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId != this.groupId) return
        // null when the group was deleted mid-update (e.g. subscription)
        order = GroupRepository.getGroup(groupId)?.order ?: return
        reloadProfiles()
    }

    // ------------------------------------------------------------- 整组重读

    /**
     * 阻塞读库，调用方在后台线程（页面首次可见 / 清空搜索走 IO，监听回调跑在调用方线程）；
     * 列表刷新回到主线程，随 ViewModel 清除而取消。
     * 刷新是差量：测速结果、订阅更新、改排序方式只动变化的行（变化的行带 payload 就地重绑）；
     * 只有首次载入才定位到选中节点
     */
    fun reloadProfiles() {
        var newProfiles = ProfileRepository.getProfilesByGroup(groupId)
        when (order) {
            GroupOrder.BY_NAME -> {
                newProfiles = newProfiles.sortedBy { it.displayName() }

            }

            GroupOrder.BY_DELAY -> {
                newProfiles =
                    newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            }
        }

        val newProfileIds = newProfiles.map { it.id }

        val selectedProxy = if (selected) {
            selectedItemId ?: DataStore.selectedProxy
        } else null

        viewModelScope.launch(Dispatchers.Main) {
            // 拖动中不应用：库里还是拖动前的顺序。落下后 commitMove 会补读
            if (dragging) {
                reloadAfterDrag = true
                return@launch
            }
            // 整组重读会把撤销窗口里待删的行重新放回列表，先把它提交掉。
            // mutate the lists on the main thread: this runs on a
            // background dispatcher while the main thread reads them
            mutateList {
                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                allProfileIds.clear()
                allProfileIds.addAll(newProfileIds)
                submitItems(visibleItems())
            }
            listLoaded = true
            // 差量可能一条通知也不发（空分组载入空列表、内容没变）：载入完成与空状态仍要在这里确认
            host?.onListSubmitted()

            scrollToInitialIfPending(selectedProxy, newProfiles.isNotEmpty())
        }
    }

    private fun scrollToInitialIfPending(selectedProxy: Long? = null, hasProfiles: Boolean = items.isNotEmpty()) {
        val host = host ?: return
        if (!initialScrollPending) return
        initialScrollPending = false
        // 在过滤后的可见列表里找选中行，列表位置才对得上
        val selectedProfileIndex = (selectedProxy ?: pendingSelectedProxy())?.let { indexOfId(it) } ?: -1
        if (selectedProfileIndex != -1) {
            host.scrollTo(selectedProfileIndex)
        } else if (hasProfiles) {
            host.scrollTo(0)
        }
    }

    // 载入时没有页面、之后才接上页面时用：选中节点此刻再取一次（主线程读 DataStore 与载入时在 IO 线程读同一个值）
    private fun pendingSelectedProxy(): Long? = if (selected) selectedItemId ?: DataStore.selectedProxy else null

    companion object {
        const val ARG_GROUP_ID = "groupId"
        const val ARG_ORDER = "order"
        const val ARG_SELECTED = "selected"
        const val NO_GROUP = -1L

        private const val KEY_INITIAL_SCROLL_DONE = "initialScrollDone"

        // 节点被更新（测试结果、编辑等）：就地重绑，不触发整卡的变更动画
        const val PAYLOAD_UPDATED = "updated"
    }
}
