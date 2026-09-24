package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.fmt.displayType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.padForSystemBars
import moe.matsuri.nb4a.Protocols.getProtocolColor
import io.nekohasekai.sagernet.database.EditorCache

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(R.layout.layout_chain_settings) {

    override fun createEntity() = ChainBean()

    override val preferenceListReachesBottom = false

    val proxyList = ArrayList<ProxyEntity>()

    // Keep the cache in sync with proxyList so a rotation (which rebuilds
    // proxyList from EditorCache.serverProtocol in reload()) does not lose
    // unsaved member edits.
    fun updateProxiesCache() {
        EditorCache.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
    }

    // Inverse of updateProxiesCache().
    fun cachedProxyIds(): List<Long> =
        EditorCache.serverProtocol.split(",").filter { it.isNotBlank() }.map { it.toLong() }

    override fun ChainBean.init() {
        EditorCache.profileName = name
        EditorCache.serverProtocol = proxies.joinToString(",")
    }

    override fun ChainBean.serialize() {
        name = EditorCache.profileName
        proxies = cachedProxyIds()
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 编辑会话被接管时基类已 finish() 并提前返回，toolbar 未安装，直接退出
        if (!ownsEditorSession) return

        supportActionBar!!.setTitle(R.string.chain_settings)
        replacing = savedInstanceState?.getInt("replacing") ?: 0
        configurationList = findViewById(R.id.configuration_list)
        configurationList.padForSystemBars()
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                // 适配器有待处理的更新时位置可能是 NO_POSITION（-1），拿它算下标会越界
                if (target !is ProfileHolder || from == RecyclerView.NO_POSITION ||
                    to == RecyclerView.NO_POSITION
                ) return false
                configurationAdapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) configurationAdapter.remove(position)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            awaitEditorReady()
            // Restored fragments can create their view inside super.onCreate,
            // before this Activity has installed its adapter. Queue on Main
            // before accessing it so Activity.onCreate can finish first.
            val adapter = onMainDispatcher { configurationAdapter }
            adapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // Dangling member ids (their profiles were deleted) render as
        // non-interactive placeholder rows after the member rows; keeping them
        // out of proxyList preserves the index arithmetic in move()/remove()
        // and the existing cache-rewrite semantics.
        private val missingIds = ArrayList<Long>()

        suspend fun reload() {
            awaitEditorReady()
            val idList = cachedProxyIds()
            val profiles = if (idList.isNotEmpty()) {
                ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
            } else emptyMap()
            // A process-death restore fires this twice (the restored fragment
            // and the re-init replacement), so rebuild the list instead of
            // appending; mutate it on the main thread where the RecyclerView
            // reads it.
            onMainDispatcher {
                // A select-profile callback may have written the cache while
                // the profiles were loading; retry instead of rebuilding the
                // list from stale ids and wiping that selection.
                val currentIds = cachedProxyIds()
                if (currentIds != idList) {
                    lifecycleScope.launch(Dispatchers.Default) { reload() }
                    return@onMainDispatcher
                }
                proxyList.clear()
                missingIds.clear()
                for (id in idList) {
                    val profile = profiles[id]
                    if (profile != null) proxyList.add(profile) else missingIds.add(id)
                }
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            updateProxiesCache()
            EditorCache.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            updateProxiesCache()
            EditorCache.dirty = true
        }

        override fun getItemId(position: Int): Long {
            return when {
                position == 0 -> 0
                position <= proxyList.size -> proxyList[position - 1].id
                else -> -missingIds[position - 1 - proxyList.size]
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when {
                position == 0 -> 0
                position <= proxyList.size -> 1
                else -> 2
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
                1 -> ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
                else -> MissingHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            } else if (holder is MissingHolder) {
                holder.bind(missingIds[position - 1 - proxyList.size])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + missingIds.size + 1
        }

    }

    // members 须为 proxyList 的主线程快照：本函数跑在 Default 调度器上，
    // 直接迭代 proxyList 会与主线程的拖拽/删除并发
    fun testProfileAllowed(profile: ProxyEntity, members: List<ProxyEntity>): Boolean {
        if (profile.id == EditorCache.editingId) return false

        for (entity in members) {
            if (testProfileContains(entity, profile)) return false
        }

        // reverse check: the candidate's own subtree must not contain the chain
        // being edited, or adding it would close a loop (chain A holds chain B
        // while B is being edited to add A)
        val editing = ProfileManager.getProfile(EditorCache.editingId)
        if (editing != null && testProfileContains(profile, editing)) return false

        return true
    }

    fun testProfileContains(
        profile: ProxyEntity,
        anotherProfile: ProxyEntity,
        visiting: MutableSet<Long> = mutableSetOf(),
    ): Boolean {
        if (profile.type != ProxyEntity.TYPE_CHAIN || anotherProfile.type != ProxyEntity.TYPE_CHAIN) return false
        if (profile.id == anotherProfile.id) return true
        // Guard against chain loops in already-corrupted data (A contains B,
        // B contains A): stop descending on re-entry instead of overflowing
        // the stack. Mirrors the visiting set in
        // ConfigBuilder.resolveChainInternal.
        if (!visiting.add(profile.id)) return false
        try {
            val proxies = profile.chainBean!!.proxies
            if (proxies.contains(anotherProfile.id)) return true
            if (proxies.isNotEmpty()) {
                for (entity in ProfileManager.getProfiles(proxies)) {
                    if (testProfileContains(entity, anotherProfile, visiting)) {
                        return true
                    }
                }
            }
            return false
        } finally {
            visiting.remove(profile.id)
        }
    }

    // survives process death so a pending replace does not turn into an append
    var replacing = 0

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("replacing", replacing)
    }

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) lifecycleScope.launch(Dispatchers.Default) {
                awaitEditorReady()

                val profile = ProfileManager.getProfile(
                    data?.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    ) ?: return@launch
                ) ?: return@launch

                // proxyList 只由主线程改写（拖拽/删除/重载），快照必须在主线程取
                val members = onMainDispatcher { proxyList.toList() }
                if (!testProfileAllowed(profile, members)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    onMainDispatcher {
                        // reload() rebuilds proxyList from EditorCache.serverProtocol
                        // asynchronously, so after a process-death restore the
                        // list may still be empty here. Write the selection
                        // through the same cache instead of indexing proxyList
                        // (replacing can also be out of range then); a pending
                        // reload picks the change up via its stale-cache retry.
                        val ids = cachedProxyIds().toMutableList()
                        if (replacing in 1..ids.size) {
                            ids[replacing - 1] = profile.id
                        } else {
                            ids.add(profile.id)
                        }
                        EditorCache.serverProtocol = ids.joinToString(",")
                        EditorCache.dirty = true
                        lifecycleScope.launch(Dispatchers.Default) { configurationAdapter.reload() }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                // 布局刷新间隙点击会拿到 NO_POSITION，此时启动会让「替换」静默变「追加」
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                replacing = position
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            shareLayout.isVisible = false
        }

    }

    // A chain member whose profile was deleted: read-only row, no listeners,
    // and ItemTouchHelper gates drag/swipe on ProfileHolder, so it can neither
    // be selected, replaced nor moved.
    inner class MissingHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share

        fun bind(id: Long) {
            profileName.text = itemView.context.getString(R.string.deleted_profile)
            profileType.text = "#$id"
            trafficText.isVisible = false
            editButton.isVisible = false
            shareLayout.isVisible = false
        }

    }

}