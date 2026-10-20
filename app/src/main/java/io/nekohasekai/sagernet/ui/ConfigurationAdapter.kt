package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.beanForType
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.moveUserOrder
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.insertClamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

// 节点被更新（测试结果、编辑等）：就地重绑，不触发整卡的变更动画
private const val PAYLOAD_UPDATED = "updated"

// 节点卡片显示的字段：差量比较只看这些。选中态不在其中（它在 DataStore，bind 时异步查），
// 选中项变化走 onUpdated(profile) 的定点通知；实时流量也不在其中（走 onUpdated(TrafficData) 直接重画流量段）
private data class ProfileCardState(
    val name: String?,
    val type: String,
    val chain: Boolean,
    val neko: Boolean,
    val nameBlank: Boolean,
    val address: String?,
    val rx: Long,
    val tx: Long,
    val status: Int,
    val ping: Int,
    val error: String?,
)

private fun ProxyEntity.cardState(): ProfileCardState {
    // 取不到 bean 的坏行：bind 本身会抛，这里不抢着抛，免得一行坏数据让整次差量失败
    val bean = beanForType()
    return ProfileCardState(
        name = bean?.displayName(),
        type = displayType(),
        chain = type == ProxyEntity.TYPE_CHAIN,
        neko = nekoBean != null,
        nameBlank = bean?.name.isNullOrBlank(),
        address = bean?.displayAddress(),
        rx = rx,
        tx = tx,
        status = status,
        ping = ping,
        error = error,
    )
}

// 身份按 id；内容按卡片显示的字段；内容变了一律带 PAYLOAD_UPDATED，让 ItemAnimator 复用同一个 holder 就地重绑
// （M6：测速结果逐行刷新，状态行淡入，不整卡交叉淡变）
private val PROFILE_DIFF = object : DiffUtil.ItemCallback<ProxyEntity>() {
    override fun areItemsTheSame(oldItem: ProxyEntity, newItem: ProxyEntity) = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: ProxyEntity, newItem: ProxyEntity) =
        oldItem.cardState() == newItem.cardState()

    override fun getChangePayload(oldItem: ProxyEntity, newItem: ProxyEntity): Any = PAYLOAD_UPDATED
}

// 差量计算与结果回主线程都在调用线程上同步完成：拖动 / 撤销 / 删除的就地改动与整表提交
// 才不会撞上一次还没落地的差量。submit 只在主线程调用；节点数在数百到数千的量级，主线程算得过来
private val SYNC_EXECUTOR = Executor { it.run() }

class ConfigurationAdapter(private val groupFragment: ProfileListFragment) :
    ListAdapter<ProxyEntity, ConfigurationHolder>(
        AsyncDifferConfig.Builder(PROFILE_DIFF)
            .setBackgroundThreadExecutor(SYNC_EXECUTOR)
            .setMainThreadExecutor(SYNC_EXECUTOR)
            .build()
    ),
    ProfileManager.Listener,
    GroupManager.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    // 页面作用域：适配器在 ProfileListFragment.onViewCreated 里建，此刻视图已存在；
    // 视图销毁时取消，整组重读的读库结果与随后的列表刷新一并作废
    private val viewScope = groupFragment.viewLifecycleOwner.lifecycleScope

    // 可见列表的可变工作副本，同时就是 differ 持有的那一个实例（ListAdapter 的 currentList 是它的只读视图）。
    // 整表变化（整组重读、搜索过滤）一律经 submitItems 提交一份新副本，由 DiffUtil 发通知；
    // 只有主线程同步的几条路径就地改它并紧跟精确的 notify：拖动换位、删除、撤销插回、新增追加、
    // 单行更新（ItemTouchHelper 要求当场可见，撤销下标只对删除当时的列表有效）。主线程之外不碰
    var items = ArrayList<ProxyEntity>()
        private set

    // 缓存里每个节点的当前实例（含被搜索过滤掉的）；items 里的项一定取自这里，两者始终在主线程
    // 同一个 post 里成对更新。取不到说明是陈旧回调（latestEntity 返回 null），而不是在主线程同步读库兜底
    val configurationList = HashMap<Long, ProxyEntity>()

    // 可见行的 id 视图（只读，不拷贝）；ConfigurationFragment 的标题栏点击按它找选中行的位置
    val configurationIdList: List<Long>
        get() = object : AbstractList<Long>() {
            override val size get() = items.size
            override fun get(index: Int) = items[index].id
        }

    init {
        setHasStableIds(true)
        // 先把空的工作副本交给 differ，之后它们始终是同一个实例
        submitList(items)
    }

    private fun getItemAt(index: Int) = items.getOrNull(index)

    /** 行位置；找不到返回 -1 */
    fun indexOfId(profileId: Long) = items.indexOfFirst { it.id == profileId }

    /** 缓存里某节点的最新实例：holder 的动作（分享、编辑、删除）取它，不用绑定那一刻的旧实例 */
    fun latestEntity(profileId: Long): ProxyEntity? = configurationList[profileId]

    /**
     * 提交一份新的可见列表：传副本，differ 对同一实例直接返回、不做比对；此后的就地改动落在这份副本上。
     * 同步比对（见 SYNC_EXECUTOR），返回时 RecyclerView 已收到全部通知
     */
    private fun submitItems(list: List<ProxyEntity>) {
        val next = ArrayList(list)
        items = next
        submitList(next)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ConfigurationHolder {
        return ConfigurationHolder(
            groupFragment,
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_profile, parent, false)
        )
    }

    override fun getItemId(position: Int): Long {
        return items[position].id
    }

    override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
        // group 删除等竞态下 RecyclerView 会按旧 itemCount 回调越界 position；
        // 显式判空，而不是吞异常把 bind 内部的真实 bug 一起盖掉
        holder.bind(items.getOrNull(position) ?: return)
    }

    // 带 payload 的更新（差量的内容变化、onUpdated）：ItemAnimator 复用同一个 holder 就地重绑，不再整张卡片交叉淡变，
    // 状态行的文字变化由 holder 自己判断并淡入
    override fun onBindViewHolder(
        holder: ConfigurationHolder, position: Int, payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        holder.bind(items.getOrNull(position) ?: return)
    }

    // 行被回收时取消它还在途的选中态查询，免得结果落到池里的 holder 上
    override fun onViewRecycled(holder: ConfigurationHolder) {
        holder.cancelPendingBind()
        super.onViewRecycled(holder)
    }

    private val updated = HashSet<ProxyEntity>()

    // 拖动进行中（已有换位、尚未落下）；这期间不应用整组重读：库里还是拖动前的顺序，
    // 会把正在拖的列表拽回去。落下后补读一次
    private var dragging = false
    private var reloadAfterDrag = false

    // 这个适配器还没做过「首次载入定位」：载入后把列表滚到选中节点（没有就回顶部），只做这一次。
    // 页面（每个分组一个）第一次载入，也就是冷启动与切到某个分组页；之后的整组重读（测速结果、
    // 订阅更新、改排序）是差量更新，不能再把用户拽回选中项
    private var initialScrollPending = true

    // every id in group order, unaffected by filter(): the HashMap's key
    // order used to scramble search results
    private val allProfileIds = mutableListOf<Long>()

    // Derived, not tracked: the visible list is always a subsequence of
    // allProfileIds, so a shorter one means a filter is hiding rows and its
    // positions no longer map to the search source. Dragging is disabled while
    // that holds (getDragDirs), which keeps move() in a single index space.
    val isFiltered get() = items.size != allProfileIds.size

    // 当前搜索词（小写），只在主线程读写。reloadProfiles 回到主线程时按它重新
    // 过滤：清空搜索的整组重读在后台读库，期间用户可能又输入了字符
    private var query = ""

    private fun visibleItems(): List<ProxyEntity> {
        val all = allProfileIds.mapNotNull { configurationList[it] }
        if (query.isEmpty()) return all
        return all.filter { matchesQuery(it) }
    }

    private fun matchesQuery(profile: ProxyEntity) = query.isEmpty() ||
            profile.displayName().contains(query, ignoreCase = true) ||
            profile.displayType().contains(query, ignoreCase = true) ||
            profile.displayAddress().contains(query, ignoreCase = true)

    fun filter(name: String) {
        // 本来就没在搜索：清空会广播到所有分组页，不必每页都整组重读
        if (name.isEmpty() && query.isEmpty()) return
        query = name.lowercase()
        if (name.isEmpty()) {
            // 清空搜索时的整组重读带读库，放到 IO 线程；页面销毁时随作用域取消
            viewScope.launch(Dispatchers.IO) {
                reloadProfiles()
            }
            return
        }
        // 与 onAdd / reloadProfiles 一样先提交撤销窗口：undo 按删除时的可见位置
        // 插回，过滤后列表变短就会越界
        groupFragment.mutateList {
            submitItems(visibleItems())
        }
        // 前后两次结果一样时差量不发任何通知，空状态（「没有匹配的节点」）仍要按新搜索词刷新
        groupFragment.onListSubmitted()
    }

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
            notifyItemMoved(from, to)
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

    // 删除：就地改（见 items 的说明）
    fun remove(pos: Int) {
        if (pos < 0) return
        // drop it from the search source as well: until the undo snackbar
        // commits the deletion, filter() would still surface the removed profile
        allProfileIds.remove(items[pos].id)
        items.removeAt(pos)
        notifyItemRemoved(pos)
    }

    // 撤销插回：就地改，下标夹在当前范围内
    override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        for ((index, item) in actions) {
            groupFragment.configurationListView.post {
                // 点撤销到这个 post 执行之间若有整组重读，它已经把这一行读回来了（库里还没删），不能再插一次
                if (item.id in allProfileIds) return@post
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
                notifyItemInserted(at)
            }
        }
    }

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        // 同一分组的批量删除：一个事务，整组 rearrange 只做一次
        // 进程级：撤销窗口到期的批量删除落库，页面已关闭也要删完
        runOnDefaultDispatcher { ProfileRepository.deleteProfiles(profiles) }
    }

    // 新增：就地追加到末尾。导入一批节点会逐个来到这里，每个都做一次整表差量是 O(n²)
    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId != groupFragment.proxyGroup.id) return

        groupFragment.configurationListView.post {
            groupFragment.mutateList {
                // 并发的整组重读可能已经把这一行读进来了（先落库、重读先到、onAdd 后到）：
                // 重读的数据不旧于这次通知，按 id 去重，在就不再追加
                if (profile.id in allProfileIds) return@post
                configurationList[profile.id] = profile
                allProfileIds.add(profile.id)
                // 搜索中只把命中的新节点放进可见列表，与 visibleItems() 的过滤一致
                if (!matchesQuery(profile)) return@post
                val pos = items.size
                items.add(profile)
                notifyItemInserted(pos)
            }
        }
    }

    // 单行更新（选中项切换、清除流量 / 测速结果的 postUpdate、编辑器保存）：内容可能没变
    // （选中态不在卡片字段里），差量看不出，所以走定点通知而不是整表提交
    override suspend fun onUpdated(profile: ProxyEntity) {
        if (profile.groupId != groupFragment.proxyGroup.id) return
        groupFragment.configurationListView.post {
            // compute the index here: this callback runs on a background
            // dispatcher while the main thread mutates the list
            val index = indexOfId(profile.id)
            if (index < 0) return@post
            groupFragment.mutateList {
                // 库里的 tx/rx 可能落后于实时流量，重绑时 bind 会优先取
                // ProfileRepository.liveTraffic
                configurationList[profile.id] = profile
                items[index] = profile
                notifyItemChanged(index, PAYLOAD_UPDATED)
            }
        }
    }

    override suspend fun onUpdated(data: TrafficData) {
        onMainDispatcher {
            try {
                // 缓存的实体跟着实时流量走：停止时 liveTraffic 被清空，之后重绑的行
                // 回落到实体上的值，不能是列表加载时的旧值。在主线程改：后台回调期间
                // 主线程也在改这张表
                configurationList[data.id]?.apply {
                    rx = data.rx
                    tx = data.tx
                } ?: return@onMainDispatcher
                // 按 stable id 找 holder：按位置找在增删 / 拖动尚未重新布局时会拿到
                // 别的行，而流量只推变化项，画错的行不会被下一次推送纠正
                (groupFragment.configurationListView.findViewHolderForItemId(data.id)
                        as ConfigurationHolder?)?.bindTraffic(data)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId != groupFragment.proxyGroup.id) return

        groupFragment.configurationListView.post {
            // 别处删了节点（菜单批删、编辑器删除）：先提交撤销窗口。undo 按删除时的位置插回，列表变短会越界；
            // 删的若正是窗口里待删的那个，撤销还会插回一行库里已没有的节点
            groupFragment.mutateList {
                allProfileIds.remove(profileId)
                val index = indexOfId(profileId)
                if (index < 0) return@post
                items.removeAt(index)
                configurationList.remove(profileId)
                notifyItemRemoved(index)
            }
        }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit
    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != groupFragment.proxyGroup.id) return
        groupFragment.proxyGroup = group
        reloadProfiles()
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId != groupFragment.proxyGroup.id) return
        // null when the group was deleted mid-update (e.g. subscription)
        groupFragment.proxyGroup = GroupRepository.getGroup(groupId) ?: return
        reloadProfiles()
    }

    // 阻塞读库，调用方在后台线程（onResume / 清空搜索走 IO，监听回调跑在调用方线程）；
    // 列表刷新回到主线程，挂在页面作用域上，视图销毁后不再触碰 View。
    // 刷新是差量：测速结果、订阅更新、改排序方式只动变化的行（变化的行带 payload 就地重绑）；
    // 只有首次载入才定位到选中节点
    fun reloadProfiles() {
        var newProfiles = ProfileRepository.getProfilesByGroup(groupFragment.proxyGroup.id)
        when (groupFragment.proxyGroup.order) {
            GroupOrder.BY_NAME -> {
                newProfiles = newProfiles.sortedBy { it.displayName() }

            }

            GroupOrder.BY_DELAY -> {
                newProfiles =
                    newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
            }
        }

        val newProfileIds = newProfiles.map { it.id }

        val selectedProxy = if (groupFragment.selected) {
            groupFragment.selectedItem?.id ?: DataStore.selectedProxy
        } else null

        viewScope.launch(Dispatchers.Main) {
            // 拖动中不应用：库里还是拖动前的顺序。落下后 commitMove 会补读
            if (dragging) {
                reloadAfterDrag = true
                return@launch
            }
            // 整组重读会把撤销窗口里待删的行重新放回列表，先把它提交掉。
            // mutate the lists on the main thread: this runs on a
            // background dispatcher while the main thread reads them
            groupFragment.mutateList {
                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                allProfileIds.clear()
                allProfileIds.addAll(newProfileIds)
                submitItems(visibleItems())
            }
            // 差量可能一条通知也不发（空分组载入空列表、内容没变）：载入完成与空状态仍要在这里确认
            groupFragment.onListSubmitted()

            if (initialScrollPending) {
                initialScrollPending = false
                // 在过滤后的可见列表里找选中行，列表位置才对得上
                val selectedProfileIndex = selectedProxy?.let { indexOfId(it) } ?: -1
                if (selectedProfileIndex != -1) {
                    groupFragment.configurationListView.scrollTo(selectedProfileIndex, true)
                } else if (newProfiles.isNotEmpty()) {
                    groupFragment.configurationListView.scrollTo(0, true)
                }
            }
        }
    }
}
