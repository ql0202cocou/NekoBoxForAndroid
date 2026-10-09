package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.core.view.isGone
import androidx.core.view.size
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.sync.Mutex
import io.nekohasekai.sagernet.bg.ServiceRegistry

class ProfileListFragment : Fragment() {

    lateinit var proxyGroup: ProxyGroup
    var selected = false

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::proxyGroup.isInitialized) {
            outState.putParcelable("proxyGroup", proxyGroup)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState?.let {
            BundleCompat.getParcelable(it, "proxyGroup", ProxyGroup::class.java)
        }?.also {
            proxyGroup = it
            // The system onViewCreated call may already have run the
            // setup; only redo it when it bailed out on the then
            // uninitialized proxyGroup.
            if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
        }
    }

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
    // 之后才允许显示，免得每个分组页刚建好时占位闪一下
    private var listLoaded = false
    private var emptyObserverAdapter: ConfigurationAdapter? = null
    private val emptyObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onListChanged()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onListChanged()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onListChanged()
    }

    private fun onListChanged() {
        listLoaded = true
        updateEmptyState()
        (parentFragment as? ConfigurationFragment)?.updateAppBarLift()
    }

    private fun updateEmptyState() {
        val root = view ?: return
        val holder = root.findViewById<View>(R.id.profile_list_empty) ?: return
        val listAdapter = adapter
        if (!listLoaded || listAdapter == null || listAdapter.itemCount != 0) {
            holder.isGone = true
            return
        }
        val filtered = listAdapter.isFiltered
        root.findViewById<TextView>(R.id.empty_title).setText(
            if (filtered) R.string.profile_list_no_match else R.string.profile_list_empty
        )
        // 选择模式页没有 + 号，不显示添加提示
        root.findViewById<View>(R.id.empty_hint).isGone = filtered || select
        holder.isGone = false
    }

    // 列表滚动时通知父级更新顶部栏抬升；onViewCreated 会再次执行，换新前先摘掉旧的
    private val liftScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            (parentFragment as? ConfigurationFragment)?.updateAppBarLift()
        }
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

        if (::configurationListView.isInitialized && configurationListView.size == 0) {
            configurationListView.adapter = adapter
            runOnDefaultDispatcher {
                adapter?.reloadProfiles()
            }
        } else if (!::configurationListView.isInitialized) {
            onViewCreated(requireView(), null)
        }
        checkOrderMenu()
        configurationListView.requestFocus()
        (parentFragment as? ConfigurationFragment)?.updateAppBarLift()
    }

    fun checkOrderMenu() {
        if (select) return

        val pf = requireParentFragment() as? ToolbarFragment ?: return
        val menu = pf.toolbar.menu
        val origin = menu.findItem(R.id.action_order_origin)
        val byName = menu.findItem(R.id.action_order_by_name)
        val byDelay = menu.findItem(R.id.action_order_by_delay)
        when (proxyGroup.order) {
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

        fun updateTo(order: Int) {
            if (proxyGroup.order == order) return
            runOnDefaultDispatcher {
                proxyGroup.order = order
                GroupManager.updateSortOrder(proxyGroup.id, order)
            }
        }

        origin.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.ORIGIN)
            true
        }
        byName.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_NAME)
            true
        }
        byDelay.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_DELAY)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::proxyGroup.isInitialized) return

        // onViewCreated can run again (onViewStateRestored / onResume):
        // unregister the previous adapter before replacing it
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
        }

        configurationListView = view.findViewById(R.id.configuration_list)
        if (select) {
            // 选择节点页没有 FAB：保留 XML 的 48dp 底部留白，只保证不低于导航栏。
            // 这一页不加载工具栏菜单，没有搜索框，不需要让开键盘
            configurationListView.padForSystemBars(bottomAtLeast = true)
        } else {
            // 主界面：底部留白与顶部相同（XML 的 48dp 只给选择节点页），再加导航栏与悬浮的
            // Dock、状态卡片所占高度（mainBottomClearance）。
            // 工具栏的节点搜索会弹键盘，键盘弹出时改加键盘高度，搜索结果才能滚到最后一项
            configurationListView.updatePadding(bottom = configurationListView.paddingTop)
            configurationListView.padForSystemBars(ime = true, bottomExtra = mainBottomClearance())
        }
        configurationListView.removeOnScrollListener(liftScrollListener)
        configurationListView.addOnScrollListener(liftScrollListener)
        layoutManager = FixedLinearLayoutManager(configurationListView)
        configurationListView.layoutManager = layoutManager
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        listLoaded = false
        adapter = ConfigurationAdapter(this)
        emptyObserverAdapter = adapter
        adapter!!.registerAdapterDataObserver(emptyObserver)
        ProfileManager.addListener(adapter!!)
        GroupManager.addListener(adapter!!)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)
        updateEmptyState()

        if (!select) {

            // Replace any previous instances before creating new ones
            // (onViewCreated can re-run, see onViewStateRestored /
            // onResume): flush pending undo actions, and detach the old
            // helper or two helpers would handle the same drag and swap
            // items twice.
            if (::undoManager.isInitialized) undoManager.flush()
            undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

            itemTouchHelper?.attachToRecyclerView(null)
            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
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

    }

    override fun onDestroy() {
        emptyObserverAdapter?.unregisterAdapterDataObserver(emptyObserver)
        emptyObserverAdapter = null
        adapter?.let {
            ProfileManager.removeListener(it)
            GroupManager.removeListener(it)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()
    val isUndoManagerInitialized get() = ::undoManager.isInitialized

}
