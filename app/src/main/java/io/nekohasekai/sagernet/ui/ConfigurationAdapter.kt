package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.beanForType
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import java.util.concurrent.Executor

// 节点被更新（测试结果、编辑等）：就地重绑，不触发整卡的变更动画
private const val PAYLOAD_UPDATED = ProfileListViewModel.PAYLOAD_UPDATED

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
    ProfileListViewModel.Host,
    UndoSnackbarManager.Interface<ProxyEntity> {

    // 数据在 ViewModel 里，适配器只管显示。ViewModel 在适配器构造时取一次存着：
    // 撤销提交等后台回调晚于视图销毁时也不必再经 Fragment 去找它
    private val model = groupFragment.profileModel

    // 可见列表的可变工作副本（ViewModel 持有），同时就是 differ 持有的那一个实例（ListAdapter 的 currentList 是它的只读视图）。
    // 整表变化（整组重读、搜索过滤）一律由 ViewModel 经 submit 提交一份新副本，由 DiffUtil 发通知；
    // 只有主线程同步的几条路径就地改它并紧跟精确的 notify：拖动换位、删除、撤销插回、新增追加、
    // 单行更新（ItemTouchHelper 要求当场可见，撤销下标只对删除当时的列表有效）。主线程之外不碰
    val items: ArrayList<ProxyEntity> get() = model.items

    // 缓存里每个节点的当前实例（含被搜索过滤掉的）
    val configurationList: HashMap<Long, ProxyEntity> get() = model.configurationList

    // 可见行的 id 视图（只读，不拷贝）；ConfigurationFragment 的标题栏点击按它找选中行的位置
    val configurationIdList: List<Long>
        get() = object : AbstractList<Long>() {
            override val size get() = items.size
            override fun get(index: Int) = items[index].id
        }

    init {
        setHasStableIds(true)
        // 把 ViewModel 的工作副本交给 differ（旋转后是现成的列表，行直接出现），之后它们始终是同一个实例
        submitList(items)
        // 进程重建后第一批节点到达时才有内容可恢复：滚动位置等布局状态等到列表非空再恢复，
        // 免得在空列表上布局时把已保存的位置丢掉
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    /** 行位置；找不到返回 -1 */
    fun indexOfId(profileId: Long) = model.indexOfId(profileId)

    /** 缓存里某节点的最新实例：holder 的动作（分享、编辑、删除）取它，不用绑定那一刻的旧实例 */
    fun latestEntity(profileId: Long): ProxyEntity? = model.latestEntity(profileId)

    val isFiltered get() = model.isFiltered

    // ProfileListViewModel.Host
    override fun mutate(block: () -> Unit) = groupFragment.mutateList(block)

    override fun submit(list: ArrayList<ProxyEntity>) = submitList(list)

    override fun notifyInserted(position: Int) = notifyItemInserted(position)

    override fun notifyRemoved(position: Int) = notifyItemRemoved(position)

    override fun notifyMoved(from: Int, to: Int) = notifyItemMoved(from, to)

    override fun notifyChanged(position: Int, payload: Any?) = notifyItemChanged(position, payload)

    override fun bindTraffic(data: TrafficData) {
        // 按 stable id 找 holder：按位置找在增删 / 拖动尚未重新布局时会拿到
        // 别的行，而流量只推变化项，画错的行不会被下一次推送纠正
        (groupFragment.listViewOrNull?.findViewHolderForItemId(data.id) as ConfigurationHolder?)?.bindTraffic(data)
    }

    override fun onListSubmitted() = groupFragment.onListSubmitted()

    override fun scrollTo(index: Int) {
        groupFragment.listViewOrNull?.scrollTo(index, true)
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

    // 以下都转给 ViewModel（状态与逻辑在那里）
    fun filter(name: String) = model.filter(name)

    fun move(from: Int, to: Int) = model.move(from, to)

    fun commitMove() = model.commitMove()

    fun remove(pos: Int) = model.remove(pos)

    override fun undo(actions: List<Pair<Int, ProxyEntity>>) = model.undo(actions)

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) = model.commit(actions)

    suspend fun onAdd(profile: ProxyEntity) = model.onAdd(profile)

    fun reloadProfiles() = model.reloadProfiles()
}
