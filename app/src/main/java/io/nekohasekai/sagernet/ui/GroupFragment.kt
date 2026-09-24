package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.haveLink
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.util.*

private const val KEY_SELECTED_GROUP = "selectedGroupId"

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
        groupListView.padForSystemBars()
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

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
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
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
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
            onMainDispatcher { snackbar(e.readableMessage).show() }
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

        suspend fun reload() {
            val groups = GroupRepository.getAllGroups().toMutableList()
            val ungrouped = groups.find { it.ungrouped }
            if (ungrouped != null && groups.size > 1 && ProfileRepository.countProfilesByGroup(ungrouped.id) == 0L) groups.removeAll { it.ungrouped }
            groupListView.post {
                groupList.clear()
                groupList.addAll(groups)
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            moveUserOrder(from, to, ProxyGroup::userOrder, groupList::get) { i, group ->
                groupList[i] = group
                updated.add(group)
            }
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            // swap out the pending moves on the main thread: move() adds
            // to `updated` there while the write below iterates it
            val updated = HashSet(updated)
            this.updated.clear()
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
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupRepository.deleteGroups(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                groupList.add(group)
                notifyItemInserted(groupList.size - 1)

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
                undoManager.flush()
                if (groupCount <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
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
                    runOnDefaultDispatcher { reload() }
                    return@onMainDispatcher
                }
                undoManager.flush()

                groupList[index] = group
                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            onMainDispatcher {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) {
                    runOnDefaultDispatcher { reload() }
                    return@onMainDispatcher
                }
                notifyItemChanged(index)
            }
        }

        override suspend fun groupProgress(groupId: Long) = groupUpdated(groupId)

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
                    runOnDefaultDispatcher {
                        val (links, note) = stdLinksOfGroup(proxyGroup.id)
                            ?: return@runOnDefaultDispatcher
                        onMainDispatcher {
                            // 大分组的链接可能超过 binder 事务上限，按实际结果提示；
                            // 一条链接都没有时不复制空串，与导出到文件一致
                            val result = if (links.isEmpty()) R.string.action_export_err
                            else exportToClipboard(links)
                            activity.snackbar(listOfNotNull(activity.getString(result), note).joinToString("\n"))
                                .show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
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


        fun bind(group: ProxyGroup) {
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

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

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
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }

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
                groupStatus.setPadding(0)
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = ProfileRepository.countProfilesByGroup(group.id)
                onMainDispatcher {
                    // the holder may have been recycled while counting
                    if (proxyGroup.id != group.id) return@onMainDispatcher
                    @Suppress("DEPRECATION") when (group.type) {
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
                                        "${date.month + 1} - ${date.date}"
                                    )
                                }
                            }

                        }
                    }
                }

            }

        }
    }

}
