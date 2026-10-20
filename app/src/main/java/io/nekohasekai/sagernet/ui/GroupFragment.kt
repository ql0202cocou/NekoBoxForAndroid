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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupBinding
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.util.*
import java.util.concurrent.Executor

// 只刷新订阅更新进度条的局部 bind 标记
private const val PAYLOAD_PROGRESS = GroupViewModel.PAYLOAD_PROGRESS

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

    // 首次访问在 onViewCreated 里（已附着），之后沿用同一个宿主 Activity
    val activity: MainActivity by lazy(LazyThreadSafetyMode.NONE) { requireActivity() as MainActivity }

    // 只在 onViewCreated 到 onDestroyView 之间有效
    private var _binding: LayoutGroupBinding? = null
    private val binding get() = _binding!!
    val groupListView: RecyclerView get() = binding.groupList

    // 列表数据、选中分组 id 与仓库监听在 ViewModel 里：旋转后不重读库，滚动位置随之恢复
    val groupModel: GroupViewModel by viewModels()

    // 适配器和撤销管理器每次建视图都重建，onDestroy 还要据此判断是否建过，所以保留 lateinit
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = LayoutGroupBinding.bind(view)

        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView.padForSystemBars(bottomExtra = mainBottomClearance())
        binding.appbar.root.liftOnScrollOf(groupListView)
        groupListView.layoutManager = FixedLinearLayoutManager(groupListView)

        // onViewCreated can run again: 数据留在 ViewModel 里，新适配器直接取现成的列表
        groupAdapter = GroupAdapter()
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)
        // 撤销管理器建好之后再接上：ViewModel 的通知经适配器的 mutate 用到它
        groupModel.attach(groupAdapter)

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
                // 选择器比本页面活得久：进程重建后结果送达一个全新的页面实例，点菜单的那次点击已不存在，
                // 分组 id 只能取 ViewModel 里（SavedStateHandle）保存的那份
                val groupId = groupModel.selectedGroupId
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
        GroupViewModel.Host,
        UndoSnackbarManager.Interface<ProxyGroup> {

        // 数据在 ViewModel 里，适配器只管显示。ViewModel 在适配器构造时取一次存着：
        // 撤销提交等后台回调晚于视图销毁时也不必再经 Fragment 去找它
        private val model = groupModel

        // 当前列表的可变工作副本（ViewModel 持有），同时就是 differ 持有的那一个实例（ListAdapter 的 currentList 是它的只读视图）。
        // 整表变化（重读、新增、库里删除、编辑器改动）一律由 ViewModel 经 submit 提交一份新副本，由 DiffUtil 发通知；
        // 只有三条主线程同步路径就地改它并紧跟精确的 notify：滑动删除、撤销插回、拖动换位
        // （ItemTouchHelper 要求当场可见，撤销下标只对删除当时的列表有效）。主线程之外不碰
        val groupList: ArrayList<ProxyGroup> get() = model.groupList

        fun reload() = model.reload()

        init {
            setHasStableIds(true)
            // 把 ViewModel 的工作副本交给 differ（旋转后是现成的列表，行直接出现），之后它们始终是同一个实例
            submitList(groupList)
            // 进程重建后第一批分组到达时才有内容可恢复：滚动位置等布局状态等到列表非空再恢复，
            // 免得在空列表上布局时把已保存的位置丢掉
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        // GroupViewModel.Host
        override fun mutate(block: () -> Unit) = undoManager.mutate(block)

        override fun submit(groups: ArrayList<ProxyGroup>) = submitList(groups)

        override fun notifyChanged(index: Int, payload: Any?) = notifyItemChanged(index, payload)

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
                model.beginDrag()
                notifyItemMoved(from, to)
            }
            return moved
        }

        fun commitMove() {
            // swap out the pending moves on the main thread: move() adds
            // to `updated` there while the write below iterates it
            val updated = HashSet(updated)
            this.updated.clear()
            val deferredReload = model.endDrag()
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
            // 进程级：撤销窗口到期的分组删除落库；其后的 reload 在 ViewModel 作用域里，页面真正关闭时什么也不做
            runOnDefaultDispatcher {
                GroupRepository.deleteGroups(groups)
                // 删完刷新列表
                reload()
            }
        }

    }

    override fun onDestroyView() {
        if (::groupAdapter.isInitialized) {
            // 拖动中途销毁时由 ViewModel 放弃这次拖动并按库重读，摘掉的适配器不再收通知
            groupModel.detach(groupAdapter)
        }
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
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
                groupModel.selectedGroupId = group.id

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
