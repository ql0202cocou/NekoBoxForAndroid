package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.ktx.DragScaleCallback
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.setPlaceholderVisible
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.hostMainScrollable
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.sync.Mutex
import io.nekohasekai.sagernet.bg.ServiceRegistry

class ProfileListFragment : Fragment() {

    companion object {
        /**
         * 分组 id、排序方式与「是否是选中分组页」都走 arguments：进程被杀后系统只用无参构造恢复 Fragment 并带回 arguments，
         * 页面不再依赖父级在创建后赋值的字段
         */
        fun newInstance(group: ProxyGroup, selected: Boolean) = ProfileListFragment().apply {
            arguments = Bundle().apply {
                putLong(ProfileListViewModel.ARG_GROUP_ID, group.id)
                putInt(ProfileListViewModel.ARG_ORDER, group.order)
                putBoolean(ProfileListViewModel.ARG_SELECTED, selected)
            }
        }
    }

    // 没有分组 id 的页面（不是经 GroupPagerAdapter 建出来）没有列表可显示
    private val groupId get() = arguments?.getLong(ProfileListViewModel.ARG_GROUP_ID, ProfileListViewModel.NO_GROUP)
        ?: ProfileListViewModel.NO_GROUP
    private val hasGroup get() = groupId > 0L

    // 节点列表的数据、搜索词、载入标志与仓库监听在 ViewModel 里：旋转后不重读库，滚动位置随之恢复
    val profileModel: ProfileListViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return LayoutProfileListBinding.inflate(inflater).root
    }

    lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
    var adapter: ConfigurationAdapter? = null
    var itemTouchHelper: ItemTouchHelper? = null

    private val isEnabled: Boolean
        get() {
            return ServiceRegistry.state.let { it.canStop || it == BaseService.State.Stopped }
        }

    lateinit var layoutManager: LinearLayoutManager
    lateinit var configurationListView: RecyclerView

    // 视图还没建出来（ViewPager 预载间隙）时为空
    val listViewOrNull: RecyclerView?
        get() = if (::configurationListView.isInitialized) configurationListView else null

    // 空状态占位：空分组显示「还没有节点」，搜索无结果显示「没有匹配的节点」。
    // 列表首次载入前适配器也是空的，等第一次数据变化（reloadProfiles / filter 都以它收尾）
    // 之后才允许显示，免得每个分组页刚建好时占位闪一下。载入标志在 ViewModel 里，旋转后仍为真
    private var emptyObserverAdapter: ConfigurationAdapter? = null

    // 适配器是 ListAdapter：整表刷新由 DiffUtil 拆成范围事件（插入 / 删除 / 变更 / 移动）发出，
    // 不再是一条 onChanged，所以五类事件都要接住。变更与移动不改条数，但载入完成、入场动画触发
    // 与顶部栏抬升的判断仍照旧跑一遍（都是幂等的）
    private val emptyObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onListChanged()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onListChanged()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onListChanged()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onListChanged()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onListChanged()
    }

    /**
     * 适配器每次整表提交（整组重读、搜索过滤）之后调用。差量在前后两份列表一样时不发任何通知
     * （空分组载入空列表、搜索结果不变），observer 就不会响，载入完成标志与空状态要在这里补上
     */
    fun onListSubmitted() = onListChanged()

    private fun onListChanged() {
        profileModel.listLoaded = true
        updateEmptyState()
        playEnterAnimationOnce()
        (parentFragment as? ConfigurationFragment)?.updateAppBarLift()
    }

    private fun updateEmptyState() {
        val root = view ?: return
        val holder = root.findViewById<View>(R.id.profile_list_empty) ?: return
        val listAdapter = adapter
        if (!profileModel.listLoaded || listAdapter == null || listAdapter.itemCount != 0) {
            holder.setPlaceholderVisible(false)
            return
        }
        val filtered = listAdapter.isFiltered
        root.findViewById<TextView>(R.id.empty_title).setText(
            if (filtered) R.string.profile_list_no_match else R.string.profile_list_empty
        )
        // 选择模式页没有 + 号，不显示添加提示
        root.findViewById<View>(R.id.empty_hint).isGone = filtered || select
        holder.setPlaceholderVisible(true)
    }

    // 这一页的节点卡片还没播过错位入场：第一次拿到非空数据并且正可见时播一次
    // （onListChanged 与 onResume 两条路径，先到先播）；滚动、刷新状态、搜索都不再触发。
    // 只在冷启动与用户切分组时播：经 Dock 淡变回到配置页时父级在转场中，这一页按已播处理。
    // 「哪些分组播过」记在配置页的 ViewModel 里，旋转后视图重建不会再播一次。
    // view 动画不服从系统动画时长缩放，所以关闭动画时不播
    private fun playEnterAnimationOnce() {
        val config = parentFragment as? ConfigurationFragment ?: return
        if (groupId in config.model.enterAnimationPlayed || select || !isResumed) return
        val list = listViewOrNull ?: return
        val listAdapter = adapter ?: return
        if (!profileModel.listLoaded || listAdapter.itemCount == 0 || !list.isShown) return
        config.model.enterAnimationPlayed.add(groupId)
        if (!config.allowsEnterAnimation()) return
        if (!requireContext().animatorsEnabled()) return
        list.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            requireContext(), R.anim.layout_profile_list_enter
        )
        list.setLayoutAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) {
                list.layoutAnimation = null
            }
        })
        list.scheduleLayoutAnimation()
    }

    private val parentConfiguration by lazy {
        try {
            parentFragment as ConfigurationFragment
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }
    val select by lazy { parentConfiguration?.select ?: false }
    val selectedItem by lazy { parentConfiguration?.selectedItem }

    override fun onResume() {
        super.onResume()

        // 没有分组 id 的页面（不是经 GroupPagerAdapter 建出来）onViewCreated 没建列表，没有可显示的
        if (!hasGroup || listViewOrNull == null) return
        // 第一次可见才读库；旋转后的新页面里 ViewModel 已有列表，不会再读
        profileModel.start(parentConfiguration?.model?.searchQuery.orEmpty())
        checkOrderMenu()
        if (parentConfiguration?.takeSearchFocusGuard() != true) configurationListView.requestFocus()
        playEnterAnimationOnce()
        (parentFragment as? ConfigurationFragment)?.updateAppBarLift()
    }

    fun checkOrderMenu() {
        if (select || !hasGroup) return

        val pf = parentFragment as? ToolbarFragment ?: return
        val menu = pf.toolbar.menu
        val origin = menu.findItem(R.id.action_order_origin)
        val byName = menu.findItem(R.id.action_order_by_name)
        val byDelay = menu.findItem(R.id.action_order_by_delay)
        when (profileModel.order) {
            GroupOrder.ORIGIN -> {
                origin.isChecked = true
            }

            GroupOrder.BY_NAME -> {
                byName.isChecked = true
            }

            GroupOrder.BY_DELAY -> {
                byDelay.isChecked = true
            }
        }

        origin.setOnMenuItemClickListener {
            it.isChecked = true
            profileModel.changeOrder(GroupOrder.ORIGIN)
            true
        }
        byName.setOnMenuItemClickListener {
            it.isChecked = true
            profileModel.changeOrder(GroupOrder.BY_NAME)
            true
        }
        byDelay.setOnMenuItemClickListener {
            it.isChecked = true
            profileModel.changeOrder(GroupOrder.BY_DELAY)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!hasGroup) return

        configurationListView = view.findViewById(R.id.configuration_list)
        if (select) {
            // 选择节点页没有 FAB：保留 XML 的 48dp 底部留白，只保证不低于导航栏。
            // 这一页不加载工具栏菜单，没有搜索框，不需要让开键盘
            configurationListView.padForSystemBars(bottomAtLeast = true)
            // 没有 Dock，不经 hostMainScrollable；顶部栏抬升仍由父级的控制器按当前页判断
            parentConfiguration?.appBarLift?.follow(configurationListView)
        } else {
            // 主界面：底部留白与顶部相同（XML 的 48dp 只给选择节点页），再加导航栏与悬浮的
            // Dock、状态卡片所占高度（hostMainScrollable 里的 bottomClearance）。
            // 工具栏的节点搜索会弹键盘，键盘弹出时改加键盘高度，搜索结果才能滚到最后一项
            configurationListView.updatePadding(bottom = configurationListView.paddingTop)
            hostMainScrollable(configurationListView, lift = parentConfiguration?.appBarLift, ime = true)
        }
        layoutManager = FixedLinearLayoutManager(configurationListView)
        configurationListView.layoutManager = layoutManager
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        profileModel.configure(selectedItem?.id)
        adapter = ConfigurationAdapter(this)
        emptyObserverAdapter = adapter
        adapter!!.registerAdapterDataObserver(emptyObserver)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)
        updateEmptyState()

        if (!select) {

            // 视图重建时先换掉旧的：提交待撤销的删除，并摘掉旧的 ItemTouchHelper，
            // 否则两个 helper 会处理同一次拖动、把项换两次
            if (::undoManager.isInitialized) undoManager.flush()
            undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

            itemTouchHelper?.attachToRecyclerView(null)
            itemTouchHelper = ItemTouchHelper(object : DragScaleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
            ) {
                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) = if (isEnabled && adapter?.isFiltered != true) {
                    super.getDragDirs(recyclerView, viewHolder)
                } else 0

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    // NO_POSITION 由 moveUserOrder 挡掉，返回 false
                    return adapter?.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    ) == true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter?.commitMove()
                }
            }).apply { attachToRecyclerView(configurationListView) }

        }

        // 撤销管理器建好之后再接上：ViewModel 的通知经适配器的 mutate 用到它。
        // 接上之后 ViewModel 里现成的列表已在适配器里，布局状态（滚动位置）在第一次布局时恢复
        profileModel.attach(adapter!!)
    }

    override fun onDestroyView() {
        // 拖动中途销毁时由 ViewModel 放弃这次拖动并按库重读，摘掉的适配器不再收通知
        adapter?.let { profileModel.detach(it) }
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        emptyObserverAdapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()
    // 节点列表改动的统一入口（转给 UndoSnackbarManager.mutate）；undoManager 未初始化时直接执行
    inline fun <R> mutateList(block: () -> R): R {
        val manager = undoManagerOrNull() ?: return block()
        return manager.mutate(block)
    }

    // lateinit 的 isInitialized 不能写在 inline 函数里，单独取一次
    fun undoManagerOrNull(): UndoSnackbarManager<ProxyEntity>? =
        if (::undoManager.isInitialized) undoManager else null

}
