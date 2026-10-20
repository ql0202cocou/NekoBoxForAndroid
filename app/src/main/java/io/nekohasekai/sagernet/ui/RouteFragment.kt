package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TextAppearanceSpan
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.database.RuleSummary
import io.nekohasekai.sagernet.databinding.LayoutRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.hostMainScrollable
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    // 首次访问在 onViewCreated 里（已附着），之后沿用同一个宿主 Activity
    val activity: MainActivity by lazy(LazyThreadSafetyMode.NONE) { requireActivity() as MainActivity }

    // 只在 onViewCreated 到 onDestroyView 之间有效
    private var _binding: LayoutRouteBinding? = null
    private val binding get() = _binding!!
    val ruleListView: RecyclerView get() = binding.routeList

    // 列表数据、出站节点名与仓库监听在 ViewModel 里：旋转后不重读库，滚动位置随之恢复
    val routeModel: RouteViewModel by viewModels()

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

        hostMainScrollable(ruleListView, appBar = binding.appbar.root)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)

        // onViewCreated can run again: 摘掉上一个适配器的观察者，数据留在 ViewModel 里
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        // ViewModel 已读过库（旋转）时列表是现成的，空列表也可以直接显示占位
        listLoaded = routeModel.loaded
        ruleAdapter = RuleAdapter()
        emptyObserverAdapter = ruleAdapter
        ruleAdapter.registerAdapterDataObserver(emptyObserver)
        ruleListView.adapter = ruleAdapter
        updateEmptyState()
        undoManager = UndoSnackbarManager(activity, ruleAdapter)
        // 撤销管理器建好之后再接上：ViewModel 的通知经适配器的 mutate 用到它
        routeModel.attach(ruleAdapter)

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
        if (::ruleAdapter.isInitialized) {
            ruleAdapter.discardPendingMoves()
            routeModel.detach(ruleAdapter)
        }
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        emptyObserverAdapter = null
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                val model = routeModel
                activity.confirm(R.string.reset_route_message) {
                    // 进程级：重置路由先清空再重建默认规则，确认后不随页面取消
                    runOnDefaultDispatcher {
                        // 走 RuleRepository 让 RuleListener 收到 onCleared；
                        // 本 adapter 的 onCleared 只清空列表，重建默认规则
                        // 并刷新仍由下面的手动 reload 完成（两者均幂等）
                        RuleRepository.clearRules()
                        DataStore.rulesFirstCreate = false
                        model.reload()
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
        val summary: RuleSummary,
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
    ), RouteViewModel.Host, UndoSnackbarManager.Interface<RuleEntity> {

        // 数据在 ViewModel 里，适配器只管显示。ViewModel 在适配器构造时取一次存着：
        // 撤销提交等后台回调晚于视图销毁时也不必再经 Fragment 去找它
        private val model = routeModel

        // 主线程上的工作副本，是列表内容的唯一来源（ViewModel 持有，旋转后新的适配器接着用）；ListAdapter 的
        // currentList 不可变，每次改动后由 submit() 按它重新生成行提交，差量事件由 DiffUtil 发出。
        // 拖动期间的换位也直接改它，随后同样 submit()（见 move）
        val ruleList: ArrayList<RuleEntity> get() = model.ruleList

        private fun rowOf(rule: RuleEntity) = RuleRow(
            id = rule.id,
            name = rule.displayName(),
            summary = rule.mkSummary(),
            outbound = rule.displayOutbound(model.outboundNames[rule.outbound]),
            enabled = rule.enabled,
            rule = rule,
        )

        // 把工作副本提交给 DiffUtil；只在主线程调用
        private fun submit() {
            submitList(ruleList.map(::rowOf))
        }

        fun reload() = model.reload()

        init {
            // 旋转后 ViewModel 里已有现成的列表，直接显示；还没读过库就等 ViewModel 的读库结果
            if (model.loaded) submit()
            // 进程重建后第一批规则到达时才有内容可恢复：滚动位置等布局状态等到列表非空再恢复，
            // 免得在空列表上布局时把已保存的位置丢掉
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        // RouteViewModel.Host
        override fun mutate(block: () -> Unit) = undoManager.mutate(block)

        override fun refresh() = submit()

        override fun needReload() = this@RouteFragment.needReload()

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

        /**
         * 视图销毁时还有没落库的换位（拖动中途旋转 / 离开，ItemTouchHelper 不会再回调 clearView）：
         * 放弃它们并按库重读，免得 ViewModel 里的顺序与库不一致
         */
        fun discardPendingMoves() {
            if (updated.isEmpty()) return
            updated.clear()
            model.reload()
        }

        fun remove(index: Int) {
            model.removeAt(index)
            submit()
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            model.restore(actions)
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            // 进程级：撤销窗口到期的规则删除落库，页面已关闭也要删完
            runOnDefaultDispatcher {
                RuleRepository.deleteRules(rules)
                // 落库之后在主线程记下确认时的序号：此后才开始的重读一定读不到这些规则
                onMainDispatcher { model.confirmRemoved(rules) }
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
                profileType.text = summaryText(profileType.context, row.summary)
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


    /**
     * 把摘要绑成一段文字（仍是同一个 TextView，TalkBack 按「标签 值」顺序整段朗读）：
     * 标签 Label medium + onSurfaceVariant，值 Body medium + onSurface，一项字段一行；
     * 超出的项数在末尾以「+N」收尾。
     */
    private fun summaryText(context: Context, summary: RuleSummary): CharSequence {
        val labelAppearance = textAppearanceRes(context, com.google.android.material.R.attr.textAppearanceLabelMedium)
        val valueAppearance = textAppearanceRes(context, com.google.android.material.R.attr.textAppearanceBodyMedium)
        val variant = context.getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val onSurface = context.getColorAttr(com.google.android.material.R.attr.colorOnSurface)
        val sb = SpannableStringBuilder()
        fun append(text: String, appearance: Int, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(TextAppearanceSpan(context, appearance), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (field in summary.fields) {
            if (sb.isNotEmpty()) sb.append('\n')
            append(context.getString(field.label), labelAppearance, variant)
            if (field.value.isNotEmpty()) {
                sb.append(' ')
                append(field.value, valueAppearance, onSurface)
                if (field.more > 0) {
                    append(", " + context.getString(R.string.route_summary_more, field.more), labelAppearance, variant)
                }
            }
        }
        if (summary.overflow > 0) {
            if (sb.isNotEmpty()) sb.append('\n')
            append(context.getString(R.string.route_summary_more, summary.overflow), labelAppearance, variant)
        }
        return sb
    }

    private fun textAppearanceRes(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

}
