package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.padForSystemBars()
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)

        // onViewCreated can run again (rotation): unregister the previous
        // adapter before replacing it
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
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

    override fun onDestroy() {
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                activity.confirm(R.string.clear_profiles_message) {
                    runOnDefaultDispatcher {
                        // 走 ProfileManager 让 RuleListener 收到 onCleared；
                        // 本 adapter 的 onCleared 只清空列表，重建默认规则
                        // 并刷新仍由下面的手动 reload 完成（两者均幂等）
                        ProfileManager.clearRules()
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

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder as RuleHolder).bind(ruleList[position])
        }

        override fun getItemCount(): Int {
            return ruleList.size
        }

        override fun getItemId(position: Int): Long {
            return ruleList[position].id
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            moveUserOrder(from, to, RuleEntity::userOrder, ruleList::get) { i, rule ->
                ruleList[i] = rule
                updated.add(rule)
            }
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            // swap out the pending moves on the main thread: move() adds
            // to `updated` there while the write below iterates it
            val updated = HashSet(updated)
            this.updated.clear()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                if (updated.isNotEmpty()) {
                    ProfileManager.updateRuleOrders(updated)
                    onMainDispatcher { needReload() }
                }
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                ruleList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.size - 1)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            // These callbacks run on the caller's (background) dispatcher while
            // drag-sorting mutates ruleList on the main thread; only touch the
            // list in post.
            ruleListView.post {
                val index = ruleList.indexOfFirst { it.id == rule.id }
                if (index == -1) return@post
                ruleList[index] = rule
                ruleAdapter.notifyItemChanged(index)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            ruleListView.post {
                val index = ruleList.indexOfFirst { it.id == ruleId }
                if (index == -1) {
                    needReload()
                } else {
                    ruleList.removeAt(index)
                    ruleAdapter.notifyItemRemoved(index)
                    needReload()
                }
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
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

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                profileName.text = ruleEntity.displayName()
                profileType.text = ruleEntity.mkSummary()
                routeOutbound.text = ruleEntity.displayOutbound()
                itemView.setOnClickListener {
                    enableSwitch.performClick()
                }
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = ruleEntity.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val ruleId = ruleEntity.id
                    ruleEntity.enabled = isChecked
                    runOnDefaultDispatcher {
                        ProfileManager.updateRuleEnabled(ruleId, isChecked)
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
