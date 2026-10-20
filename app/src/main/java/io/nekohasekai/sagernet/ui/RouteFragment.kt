package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.databinding.LayoutRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.liftOnScrollOf
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.insertClamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

// removedIds 里「删除落库尚未确认」的标记：大于任何重读序号，永不被清掉
private const val UNCONFIRMED = Long.MAX_VALUE

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    // 首次访问在 onViewCreated 里（已附着），之后沿用同一个宿主 Activity
    val activity: MainActivity by lazy(LazyThreadSafetyMode.NONE) { requireActivity() as MainActivity }

    // 只在 onViewCreated 到 onDestroyView 之间有效
    private var _binding: LayoutRouteBinding? = null
    private val binding get() = _binding!!
    val ruleListView: RecyclerView get() = binding.routeList

    // 适配器和撤销管理器每次建视图都重建，onDestroy 还要据此判断是否建过，所以保留 lateinit
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    // 适配器第一次数据变化之前也是空的，等它之后才允许显示占位，免得刚建好页面时闪一下
    private var listLoaded = false
    private var emptyObserverAdapter: RuleAdapter? = null
    private val emptyObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onListChanged()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onListChanged()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onListChanged()
    }

    private fun onListChanged() {
        listLoaded = true
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val holder = _binding?.routeListEmpty ?: return
        holder.setPlaceholderVisible(listLoaded && ::ruleAdapter.isInitialized && ruleAdapter.itemCount == 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = LayoutRouteBinding.bind(view)

        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView.padForSystemBars(bottomExtra = mainBottomClearance())
        binding.appbar.root.liftOnScrollOf(ruleListView)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)

        // onViewCreated can run again (rotation): unregister the previous
        // adapter before replacing it
        if (::ruleAdapter.isInitialized) {
            RuleRepository.removeListener(ruleAdapter)
        }
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        listLoaded = false
        ruleAdapter = RuleAdapter()
        emptyObserverAdapter = ruleAdapter
        ruleAdapter.registerAdapterDataObserver(emptyObserver)
        RuleRepository.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        updateEmptyState()
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : DragScaleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                // NO_POSITION 由 moveUserOrder 挡掉，返回 false
                return ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        emptyObserverAdapter = null
        if (::ruleAdapter.isInitialized) {
            RuleRepository.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                activity.confirm(R.string.reset_route_message) {
                    // 进程级：重置路由先清空再重建默认规则，确认后不随页面取消
                    runOnDefaultDispatcher {
                        // 走 RuleRepository 让 RuleListener 收到 onCleared；
                        // 本 adapter 的 onCleared 只清空列表，重建默认规则
                        // 并刷新仍由下面的手动 reload 完成（两者均幂等）
                        RuleRepository.clearRules()
                        DataStore.rulesFirstCreate = false
                        ruleAdapter.reload()
                    }
                }
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
        }
        return true
    }

    /**
     * 路由行的显示快照：列表项的内容由这几个字段决定，DiffUtil 只比较它们。
     * 文字在建行时就算好（出站节点名也合在里面，节点改名即内容变化），绑定时不再查别处；
     * 拖动排序与开关会就地改 [rule]（同一个对象），所以不能拿实体本身做比较。
     */
    class RuleRow(
        val id: Long,
        val name: String,
        val summary: String,
        val outbound: String,
        val enabled: Boolean,
        // 绑定、滑动删除与撤销要用的实体；不参与差量比较
        val rule: RuleEntity,
    )

    private object RuleDiff : DiffUtil.ItemCallback<RuleRow>() {
        // 仅开关状态变化时带这个 payload：就地重绑，不触发整张卡片的交叉淡变（与点开关时一致）
        const val PAYLOAD_ENABLED = "enabled"

        override fun areItemsTheSame(oldItem: RuleRow, newItem: RuleRow) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RuleRow, newItem: RuleRow) =
            oldItem.name == newItem.name && oldItem.summary == newItem.summary &&
                oldItem.outbound == newItem.outbound && oldItem.enabled == newItem.enabled

        override fun getChangePayload(oldItem: RuleRow, newItem: RuleRow): Any? =
            if (oldItem.name == newItem.name && oldItem.summary == newItem.summary &&
                oldItem.outbound == newItem.outbound
            ) PAYLOAD_ENABLED else null
    }

    /**
     * 列表只有几十行，差量计算就在调用线程（主线程）上同步做：提交后 [currentList] 立刻是新内容，
     * 滑动、拖动、撤销用的位置与 [ruleList] 始终一致，不会有「后台算完才换」的空档。
     */
    private val syncExecutor = Executor { it.run() }

    inner class RuleAdapter : ListAdapter<RuleRow, RuleAdapter.RuleHolder>(
        AsyncDifferConfig.Builder(RuleDiff)
            .setBackgroundThreadExecutor(syncExecutor)
            .setMainThreadExecutor(syncExecutor)
            .build()
    ), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        // 监听器在后台线程回调，视图销毁后也可能到达：构造时（视图存活）取一次，之后不再经过 binding
        private val listView = ruleListView

        // 主线程上的工作副本，是列表内容的唯一来源；ListAdapter 的 currentList 不可变，
        // 每次改动后由 submit() 按它重新生成行提交，差量事件由 DiffUtil 发出。
        // 拖动期间的换位也直接改它，随后同样 submit()（见 move）
        val ruleList = ArrayList<RuleEntity>()

        // 出站指定节点的规则要显示的节点名，键是节点 id；只在主线程读写，名字在后台查好再放进来，绑定时不查库
        private val outboundNames = HashMap<Long, String>()

        // 页面作用域：适配器在 onViewCreated 里建，此刻视图已存在；视图销毁时取消，
        // 读库结果与列表刷新一并作废。reload 可从任意线程调用（含「重置路由」的进程级协程）
        private val viewScope = viewLifecycleOwner.lifecycleScope

        private fun rowOf(rule: RuleEntity) = RuleRow(
            id = rule.id,
            name = rule.displayName(),
            summary = rule.mkSummary(),
            outbound = rule.displayOutbound(outboundNames[rule.outbound]),
            enabled = rule.enabled,
            rule = rule,
        )

        // 把工作副本提交给 DiffUtil；只在主线程调用
        private fun submit() {
            submitList(ruleList.map(::rowOf))
        }

        // 滑掉的规则：id → 「落库已确认」时的 reloadGen，未确认为 UNCONFIRMED。整表重读开始得早于删除落库时，
        // 读到的结果里仍有这些规则，应用结果时按它过滤，滑掉的行才不会回来、撤销也不会插出重复项。
        // id 是自增主键、不复用，过滤在确认之后也不会误伤别的规则
        private val removedIds = HashMap<Long, Long>()

        // 整表重读的序号，每次在主线程上、读库之前递增
        private var reloadGen = 0L

        fun reload() {
            viewScope.launch {
                val gen = ++reloadGen
                val (rules, names) = withContext(Dispatchers.IO) {
                    val rules = RuleRepository.getRules()
                    rules to RuleRepository.outboundProfileNames(rules)
                }
                // 列表变更的统一入口：先提交撤销窗口，再换列表（撤销下标只对删除当时的列表有效）
                undoManager.mutate {
                    // 提交只是把删除交给后台，读库可能早于它，所以过滤不能省
                    ruleList.clear()
                    rules.filterTo(ruleList) { it.id !in removedIds }
                    outboundNames.clear()
                    outboundNames.putAll(names)
                    // 删除确认于本次读库之前（gen 大于确认时的序号），读到的结果已不含它们，可以不再过滤
                    removedIds.values.removeAll { it < gen }
                    submit()
                }
            }
        }

        init {
            reload()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RuleHolder {
            return RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RuleHolder, position: Int) {
            holder.bind(getItem(position))
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int): Boolean {
            val moved = moveUserOrder(from, to, RuleEntity::userOrder, ruleList::get) { i, rule ->
                ruleList[i] = rule
                updated.add(rule)
            }
            // 拖动中的换位也走 DiffUtil：同一批行只是顺序变了，差量只会是一次移动事件
            if (moved) submit()
            return moved
        }

        fun commitMove() {
            // swap out the pending moves on the main thread: move() adds
            // to `updated` there while the write below iterates it
            val updated = HashSet(updated)
            this.updated.clear()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                if (updated.isNotEmpty()) {
                    RuleRepository.updateRuleOrders(updated)
                    onMainDispatcher { needReload() }
                }
            }
        }

        fun remove(index: Int) {
            removedIds[ruleList.removeAt(index).id] = UNCONFIRMED
            submit()
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                removedIds.remove(item.id)
                ruleList.insertClamped(index, item)
            }
            submit()
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            // 进程级：撤销窗口到期的规则删除落库，页面已关闭也要删完
            runOnDefaultDispatcher {
                RuleRepository.deleteRules(rules)
                // 落库之后在主线程记下确认时的序号：此后才开始的重读一定读不到这些规则
                onMainDispatcher {
                    for (rule in rules) {
                        if (removedIds.containsKey(rule.id)) removedIds[rule.id] = reloadGen
                    }
                }
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            // 监听器跑在调用方的后台线程上，节点名在这里查
            val names = RuleRepository.outboundProfileNames(listOf(rule))
            listView.post {
                undoManager.mutate {
                    outboundNames.putAll(names)
                    ruleList.add(rule)
                    submit()
                }
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            // These callbacks run on the caller's (background) dispatcher while
            // drag-sorting mutates ruleList on the main thread; only touch the
            // list in post.
            val names = RuleRepository.outboundProfileNames(listOf(rule))
            listView.post {
                undoManager.mutate {
                    outboundNames.putAll(names)
                    val index = ruleList.indexOfFirst { it.id == rule.id }
                    if (index == -1) return@post
                    ruleList[index] = rule
                    submit()
                }
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            listView.post {
                // 同节点列表：别处删了规则先提交撤销窗口，免得 undo 按旧位置插回越界或插回幽灵行
                undoManager.mutate {
                    val index = ruleList.indexOfFirst { it.id == ruleId }
                    if (index != -1) {
                        ruleList.removeAt(index)
                        submit()
                    }
                }
                needReload()
            }
        }

        override suspend fun onCleared() {
            listView.post {
                // 「重置路由」清空了全部规则：窗口里待删的规则已不在库里，撤销只会插回幽灵行
                undoManager.mutate {
                    ruleList.clear()
                    submit()
                }
                needReload()
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable

            fun bind(row: RuleRow) {
                val ruleEntity = row.rule
                rule = ruleEntity
                profileName.text = row.name
                profileType.text = row.summary
                routeOutbound.text = row.outbound
                itemView.setOnClickListener {
                    enableSwitch.performClick()
                }
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = row.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val ruleId = ruleEntity.id
                    ruleEntity.enabled = isChecked
                    // 同步快照：之后的提交不再把这一行当作变化（开关本身已是新状态）
                    submit()
                    // 进程级：规则开关的落库，页面随后离开也要写完；提示只在页面还在时弹
                    runOnDefaultDispatcher {
                        RuleRepository.updateRuleEnabled(ruleId, isChecked)
                        onMainDispatcher {
                            if (isAdded) needReload()
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, ruleEntity.id)
                    })
                }
            }
        }

    }

}
