package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import androidx.core.view.size
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

    val select by lazy {
        try {
            (parentFragment as ConfigurationFragment).select
        } catch (e: Exception) {
            Logs.e(e)
            false
        }
    }
    val noChain by lazy {
        try {
            (parentFragment as ConfigurationFragment).noChain
        } catch (e: Exception) {
            Logs.e(e)
            false
        }
    }
    val selectedItem by lazy {
        try {
            (parentFragment as ConfigurationFragment).selectedItem
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

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
        // The XML's fixed 48dp bottom padding predates edge-to-edge and already
        // clears the FAB; never let the gesture pill cover list content either.
        configurationListView.padForSystemBars(bottomAtLeast = true)
        layoutManager = FixedLinearLayoutManager(configurationListView)
        configurationListView.layoutManager = layoutManager
        adapter = ConfigurationAdapter(this)
        ProfileManager.addListener(adapter!!)
        GroupManager.addListener(adapter!!)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)

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
                    adapter?.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    return true
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
