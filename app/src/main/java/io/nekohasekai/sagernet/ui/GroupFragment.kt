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
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
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
import java.util.concurrent.Executor

private const val KEY_SELECTED_GROUP = "selectedGroupId"

// 只刷新订阅更新进度条的局部 bind 标记
private const val PAYLOAD_PROGRESS = "progress"

// 分组卡片显示的字段：差量比较只看这些，别的字段变了不重绑。进度条不在其中——
// 它的状态在 GroupUpdater.progress / updating，不在 ProxyGroup 上，走 PAYLOAD_PROGRESS 专用通知
private data class GroupCardState(
    val name: String?,
    val ungrouped: Boolean,
    val type: Int,
    val hasSubscription: Boolean,
    val bytesUsed: Long?,
    val bytesRemaining: Long?,
    val expiryDate: Long?,
    val username: String?,
    val lastUpdated: Long?,
)

private fun ProxyGroup.cardState(): GroupCardState {
    val subscription = subscription
    return GroupCardState(
        name = name.takeIf { !it.isNullOrBlank() },
        ungrouped = ungrouped,
        type = type,
        hasSubscription = subscription != null,
        bytesUsed = subscription?.bytesUsed,
        bytesRemaining = subscription?.bytesRemaining,
        expiryDate = subscription?.expiryDate,
        username = subscription?.username,
        lastUpdated = subscription?.lastUpdated,
    )
}

// 身份按 id；内容按卡片显示的字段。getChangePayload 不覆盖：内容变了就整卡重绑（null payload），
// 与原来 groupUpdated 的 notifyItemChanged(index) 一致
private val GROUP_DIFF = object : DiffUtil.ItemCallback<ProxyGroup>() {
    override fun areItemsTheSame(oldItem: ProxyGroup, newItem: ProxyGroup) = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: ProxyGroup, newItem: ProxyGroup) =
        oldItem.cardState() == newItem.cardState()
}

// 差量计算与结果回主线程都在调用线程上同步完成：分组只有几行，同步算完，
// 滑动 / 拖动 / 撤销的就地改动才不会撞上一次还没落地的差量。submit 只在主线程调用
private val SYNC_EXECUTOR = Executor { it.run() }

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
                // 先取项再移除：移除后这个 holder 已不在任何位置
                val group = (viewHolder as GroupHolder).proxyGroup
                groupAdapter.remove(index)
                undoManager.remove(index to group)
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

    inner class GroupAdapter : ListAdapter<ProxyGroup, GroupHolder>(
        AsyncDifferConfig.Builder(GROUP_DIFF)
            .setBackgroundThreadExecutor(SYNC_EXECUTOR)
            .setMainThreadExecutor(SYNC_EXECUTOR)
            .build()
    ),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        // 当前列表的可变工作副本，同时就是 differ 持有的那一个实例（ListAdapter 的 currentList 是它的只读视图）。
        // 整表变化（重读、新增、库里删除、编辑器改动）一律经 submitGroups 提交一份新副本，由 DiffUtil 发通知；
        // 只有三条主线程同步路径就地改它并紧跟精确的 notify：滑动删除、撤销插回、拖动换位
        // （ItemTouchHelper 要求当场可见，撤销下标只对删除当时的列表有效）。主线程之外不碰
        var groupList = ArrayList<ProxyGroup>()
            private set

        // 页面作用域：适配器在 onViewCreated 里建，此刻视图已存在；视图销毁时取消，
        // 读库结果与列表刷新一并作废。reload 可从任意线程调用（监听回调、撤销提交）
        private val viewScope = viewLifecycleOwner.lifecycleScope

        // 拖动进行中（已有换位、尚未落下）；这期间不应用整表重读：库里还是拖动前的顺序，
        // 会把正在拖的列表拽回去。落下后补读一次
        private var dragging = false
        private var reloadAfterDrag = false

        /**
         * 提交一份新列表：传副本，differ 对同一实例直接返回、不做比对；此后的就地改动落在这份副本上。
         * 同步比对（见 SYNC_EXECUTOR），返回时 RecyclerView 已收到全部通知
         */
        private fun submitGroups(groups: List<ProxyGroup>) {
            val next = ArrayList(groups)
            groupList = next
            submitList(next)
        }

        fun reload() {
            viewScope.launch {
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
                undoManager.mutate {
                    submitGroups(groups)
                }
            }
        }

        init {
            setHasStableIds(true)
            // 先把空的工作副本交给 differ，之后它们始终是同一个实例
            submitList(groupList)

            reload()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
                holder.bindProgress(getItem(position))
            } else {
                onBindViewHolder(holder, position)
            }
        }

        // 行被回收时取消它还在途的节点数查询
        override fun onViewRecycled(holder: GroupHolder) {
            holder.cancelPendingCount()
            super.onViewRecycled(holder)
        }

        override fun getItemId(position: Int): Long {
            return getItem(position).id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int): Boolean {
            val moved = moveUserOrder(from, to, ProxyGroup::userOrder, groupList::get) { i, group ->
                groupList[i] = group
                updated.add(group)
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
            // 进程级：分组拖动排序的落库，页面随后离开也要写完
            runOnDefaultDispatcher {
                GroupRepository.updateUserOrders(updated)
                // 拖动期间被挡下的重读，要等新顺序落库后再读
                if (deferredReload) reload()
            }
        }

        // 滑动删除：就地改（见 groupList 的说明）
        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        // 撤销插回：就地改，下标夹在当前范围内
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
            // 这 300 ms 并不等任何动画：回调跑在保存方（分组编辑器）的协程里，延迟的实际效果是
            // 把编辑器的 finish() 与后面的监听器各推迟 300 ms，保留原样（去掉会改编辑器的关闭时机）
            delay(300L)

            onMainDispatcher {
                undoManager.mutate {
                    // 延迟期间另一次整表重读可能已经把这个分组读进列表：按 id 去重，在就不再追加
                    if (groupList.none { it.id == group.id }) {
                        submitGroups(groupList + group)
                    }
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
                if (groupList.none { it.id == groupId }) return@onMainDispatcher
                undoManager.mutate {
                    if (groupCount <= 2) {
                        reload()
                    } else {
                        submitGroups(groupList.filter { it.id != groupId })
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
                    if (groupList[index] === group) {
                        // 同一个实例被就地改过：差量比不出变化，按原来的做法整卡重绑
                        notifyItemChanged(index)
                    } else {
                        submitGroups(groupList.map { if (it.id == group.id) group else it })
                    }
                }
            }
        }

        // 节点数变了（清空分组、节点增删）：分组本身没变，差量看不出，整卡重绑重查节点数
        override suspend fun groupUpdated(groupId: Long) = notifyGroupChanged(groupId, null)

        // 解析进度每前进一步都会到这里：进度不在 ProxyGroup 上，差量看不到，也不该为它整卡重绑、重查节点数，
        // 所以保留带 payload 的定点通知，由 onBindViewHolder 只刷新进度条
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

        // 最近一次绑定的项，只在取不到当前位置时兜底
        private var boundGroup: ProxyGroup? = null

        // 动作（二维码、立即更新、滑动 / 拖动判定）取用的分组：每次取当前位置的最新项。
        // 差量认为内容没变时不会重绑，持有绑定那一刻的实例会让这些动作读到过期的订阅链接
        val proxyGroup: ProxyGroup
            get() = (bindingAdapter as? GroupAdapter)?.groupList?.getOrNull(bindingAdapterPosition) ?: boundGroup!!
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
                    // 对话框期间列表可能变动，先取下 id
                    val groupId = proxyGroup.id
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            // 进程级：确认后的清空分组落库，不随页面取消
                            runOnDefaultDispatcher {
                                GroupRepository.clearGroup(groupId)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bindProgress(group: ProxyGroup) {
            if (group.id in GroupUpdater.updating) {
                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(group.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[group.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                // 进度条常驻占位（invisible），更新开始 / 结束时卡片高度不变
                subscriptionUpdateProgress.isInvisible = true
                updateButton.isInvisible = group.type != GroupType.SUBSCRIPTION
                editButton.isGone = group.ungrouped
            }
        }

        fun bind(group: ProxyGroup) {
            cancelPendingCount()
            boundGroup = group

            editButton.isGone = group.ungrouped
            updateButton.isInvisible = group.type != GroupType.SUBSCRIPTION
            groupName.text = group.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroupId = group.id

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            bindProgress(group)

            // 流量与到期时间取 RawUpdater 从 Subscription-Userinfo 解析好的字段
            val subscription = group.subscription
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
