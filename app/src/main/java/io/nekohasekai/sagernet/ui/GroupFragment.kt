package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.haveLink
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.liftOnScrollOf
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.insertClamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.util.*

private const val KEY_SELECTED_GROUP = "selectedGroupId"

// 只刷新订阅更新进度条的局部 bind 标记
private const val PAYLOAD_PROGRESS = "progress"

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity
        savedInstanceState?.let { selectedGroupId = it.getLong(KEY_SELECTED_GROUP) }

        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        groupListView.padForSystemBars(bottomExtra = mainBottomClearance())
        view.findViewById<AppBarLayout>(R.id.appbar).liftOnScrollOf(groupListView)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager

        // onViewCreated can run again (rotation): unregister the previous
        // adapter before replacing it
        if (::groupAdapter.isInitialized) {
            GroupRepository.removeListener(groupAdapter)
        }
        groupAdapter = GroupAdapter()
        GroupRepository.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        ItemTouchHelper(object : DragScaleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                // NO_POSITION 由 moveUserOrder 挡掉，返回 false
                return groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                requireContext().confirm(R.string.update_all_subscription) {
                    // getAllGroups 是阻塞读库，不能留在主线程
                    // 进程级：全部订阅更新（startUpdate）要在页面离开后继续，不碰界面
                    runOnDefaultDispatcher {
                        GroupRepository.getAllGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                }
            }
        }
        return true
    }

    // The picker outlives this fragment: after process death the result is
    // redelivered to a fresh instance whose menu click never ran, so the id has to
    // come from the saved state rather than a field set by that click.
    private var selectedGroupId = 0L

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_SELECTED_GROUP, selectedGroupId)
    }

    // 逐个节点生成链接。库里可能还留着入口加固前导入、或从备份恢复的坏节点：
    // 生成失败的跳过，不让一个坏节点拖垮整组导出。返回链接与给导出结果追加的
    // 跳过提示（没跳过为 null）；整体返回 null 表示读库失败、已经提示过
    private suspend fun stdLinksOfGroup(groupId: Long): Pair<String, String?>? {
        val profiles = try {
            ProfileRepository.getProfilesByGroup(groupId)
        } catch (e: Exception) {
            Logs.w(e)
            showSnackbar(e.readableMessage)
            return null
        }
        var skipped = 0
        val links = profiles.filter { it.haveLink() }.mapNotNull { profile ->
            runCatching { profile.toStdLink() }.onFailure {
                Logs.w(it)
                skipped++
            }.getOrNull()
        }.joinToString("\n")
        val note = if (skipped > 0) app.getString(R.string.share_links_skipped, skipped) else null
        return links to note
    }

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { data ->
            if (data != null) {
                val groupId = selectedGroupId
                // 进程级：把分组链接写进用户选的文件，页面重建或离开后也要写完（writeToDocument 自己判界面是否还在）
                runOnDefaultDispatcher {
                    val (links, note) = stdLinksOfGroup(groupId) ?: return@runOnDefaultDispatcher
                    // writeToDocument 拒绝空内容而不是把选中的文件截成 0 字节：
                    // 组里没有可分享的节点时什么都不写
                    writeToDocument(data, links, note)
                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        // 页面作用域：适配器在 onViewCreated 里建，此刻视图已存在；视图销毁时取消，
        // 读库结果与列表刷新一并作废。reload 可从任意线程调用（监听回调、撤销提交）
        private val viewScope = viewLifecycleOwner.lifecycleScope

        fun reload() {
            viewScope.launch {
                val groups = withContext(Dispatchers.IO) {
                    val groups = GroupRepository.getAllGroups().toMutableList()
                    val ungrouped = groups.find { it.ungrouped }
                    if (ungrouped != null && groups.size > 1 && ProfileRepository.countProfilesByGroup(ungrouped.id) == 0L) groups.removeAll { it.ungrouped }
                    groups
                }
                // 同节点列表的 reloadProfiles：整表重读会把撤销窗口里待删的分组读回来，先提交掉，
                // 否则撤销会按旧位置再插一份
                undoManager.mutate {
                    groupList.clear()
                    groupList.addAll(groups)
                    notifyDataSetChanged()
                }
            }
        }

        init {
            setHasStableIds(true)

            reload()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
                holder.bindProgress()
            } else {
                onBindViewHolder(holder, position)
            }
        }

        // 行被回收时取消它还在途的节点数查询
        override fun onViewRecycled(holder: GroupHolder) {
            holder.cancelPendingCount()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int): Boolean {
            val moved = moveUserOrder(from, to, ProxyGroup::userOrder, groupList::get) { i, group ->
                groupList[i] = group
                updated.add(group)
            }
            if (moved) notifyItemMoved(from, to)
            return moved
        }

        fun commitMove() {
            // swap out the pending moves on the main thread: move() adds
            // to `updated` there while the write below iterates it
            val updated = HashSet(updated)
            this.updated.clear()
            // 进程级：分组拖动排序的落库，页面随后离开也要写完
            runOnDefaultDispatcher {
                GroupRepository.updateUserOrders(updated)
            }
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                notifyItemInserted(groupList.insertClamped(index, item))
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            // 进程级：撤销窗口到期的分组删除落库；其后的 reload 自带页面作用域，页面已销毁时什么也不做
            runOnDefaultDispatcher {
                GroupRepository.deleteGroups(groups)
                // 删完刷新列表：页面作用域内的读取，页面已销毁时什么也不做
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            delay(300L)

            onMainDispatcher {
                undoManager.mutate {
                    groupList.add(group)
                    notifyItemInserted(groupList.size - 1)
                }

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            // 该回调跑在调用方的后台线程，读库在这里做，主线程只动视图
            val groupCount = GroupRepository.getAllGroups().size
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@onMainDispatcher
                undoManager.mutate {
                    if (groupCount <= 2) {
                        reload()
                    } else {
                        groupList.removeAt(index)
                        notifyItemRemoved(index)
                    }
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            // This callback runs on the caller's (background) dispatcher while
            // drag-sorting mutates groupList on the main thread; only touch the
            // list on the main thread.
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index == -1) {
                    reload()
                    return@onMainDispatcher
                }
                undoManager.mutate {
                    groupList[index] = group
                    notifyItemChanged(index)
                }
            }
        }

        override suspend fun groupUpdated(groupId: Long) = notifyGroupChanged(groupId, null)

        // 解析进度每前进一步都会到这里：只刷新进度条，不整卡重绑、不再查节点数
        override suspend fun groupProgress(groupId: Long) = notifyGroupChanged(groupId, PAYLOAD_PROGRESS)

        private suspend fun notifyGroupChanged(groupId: Long, payload: Any?) {
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) {
                    reload()
                    return@onMainDispatcher
                }
                notifyItemChanged(index, payload)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupRepository.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        // 页面作用域：holder 由适配器在视图存活时创建，视图销毁时取消；
        // 节点数查询每次 bind / 回收时先取消上一次的，复用后的 holder 不会收到旧分组的结果
        private val viewScope = viewLifecycleOwner.lifecycleScope
        private var countJob: Job? = null

        fun cancelPendingCount() {
            countJob?.cancel()
            countJob = null
        }

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    activity.snackbar(exportToClipboard(proxyGroup.toUniversalLink())).show()
                }

                R.id.action_export_clipboard -> {
                    // 页面内的操作：IO 线程生成链接，回主线程复制并提示；页面离开就不再弹
                    val groupId = proxyGroup.id
                    viewScope.launch {
                        val (links, note) = withContext(Dispatchers.IO) { stdLinksOfGroup(groupId) }
                            ?: return@launch
                        // 大分组的链接可能超过 binder 事务上限，按实际结果提示；
                        // 一条链接都没有时不复制空串，与导出到文件一致
                        val result = if (links.isEmpty()) R.string.action_export_err
                        else exportToClipboard(links)
                        activity.snackbar(listOfNotNull(activity.getString(result), note).joinToString("\n"))
                            .show()
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            // 进程级：确认后的清空分组落库，不随页面取消
                            runOnDefaultDispatcher {
                                GroupRepository.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bindProgress() {
            if (proxyGroup.id in GroupUpdater.updating) {
                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                // 进度条常驻占位（invisible），更新开始 / 结束时卡片高度不变
                subscriptionUpdateProgress.isInvisible = true
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }
        }

        fun bind(group: ProxyGroup) {
            cancelPendingCount()
            proxyGroup = group

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroupId = proxyGroup.id

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            bindProgress()

            // 流量与到期时间取 RawUpdater 从 Subscription-Userinfo 解析好的字段
            val subscription = proxyGroup.subscription
            val lines = mutableListOf<String>()
            if (subscription != null) {
                val used = subscription.bytesUsed
                val remain = subscription.bytesRemaining
                if (remain > 0L) {
                    lines += getString(
                        R.string.subscription_traffic, used.toBytesString(), remain.toBytesString()
                    )
                } else if (used > 0L) {
                    lines += getString(R.string.subscription_used, used.toBytesString())
                }
                if (subscription.expiryDate > 0) {
                    lines += getString(
                        R.string.subscription_expire,
                        Util.timeStamp2Text(subscription.expiryDate * 1000)
                    )
                }
            }
            if (lines.isNotEmpty()) {
                groupTraffic.isVisible = true
                groupTraffic.text = lines.joinToString("\n")
            } else {
                groupTraffic.isVisible = false
            }

            groupUser.text = subscription?.username ?: ""

            countJob = viewScope.launch {
                val size = withContext(Dispatchers.IO) { ProfileRepository.countProfilesByGroup(group.id) }
                when (group.type) {
                    GroupType.BASIC -> {
                        if (size == 0L) {
                            groupStatus.setText(R.string.group_status_empty)
                        } else {
                            groupStatus.text = getString(R.string.group_status_proxies, size)
                        }
                    }

                    GroupType.SUBSCRIPTION -> {
                        groupStatus.text = if (size == 0L) {
                            getString(R.string.group_status_empty_subscription)
                        } else {
                            val subscription = group.subscription
                            if (subscription == null) {
                                // corrupted state: a subscription group without
                                // the bean (GroupUpdater guards the same case)
                                getString(R.string.group_status_proxies, size)
                            } else {
                                val date = Date(subscription.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    DateFormat.getDateFormat(requireContext()).format(date)
                                )
                            }
                        }

                    }
                }
            }

        }
    }

}
