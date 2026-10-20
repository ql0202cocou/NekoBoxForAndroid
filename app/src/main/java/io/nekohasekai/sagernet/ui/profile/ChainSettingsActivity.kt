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
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.fmt.displayType
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.launch
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.padForSystemBars
import moe.matsuri.nb4a.Protocols.getProtocolColor
import io.nekohasekai.sagernet.database.EditorCache

class ChainSettingsActivity : ProfileSettingsActivity<ChainBean>(R.layout.layout_chain_settings) {

    override fun createEntity() = ChainBean()

    override val preferenceListReachesBottom = false

    // 偏好部分只有「Profile Name」一行，顶栏正下方的内容（跳列表）不随它滚动，抬升没有对应的滚动源
    override val liftAppBarWithPreferenceList = false

    // 成员列表在 ViewModel 里，旋转时留存、不重读库。第一次取要在 super.onCreate() 之后
    private val members: ChainMembersViewModel by viewModels()

    val proxyList: ArrayList<ProxyEntity> get() = members.proxyList

    // 让缓存与 proxyList 保持同步：进程重建（reload() 按 EditorCache.serverProtocol 重建 proxyList）
    // 后才不会丢掉未保存的成员改动
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

    val configurationAdapter = ProxiesAdapter()

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 编辑会话被接管时基类已 finish() 并提前返回，toolbar 未安装，直接退出
        if (!ownsEditorSession) return

        supportActionBar!!.setTitle(R.string.chain_settings)
        val configurationList = findViewById<RecyclerView>(R.id.configuration_list)
        configurationList.padForSystemBars()
        configurationList.layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.adapter = configurationAdapter
        // 成员列表整体重建（载入、选中后重载）时刷新；旋转后第一次回调就是当前列表。
        // 保留整表刷新：首行「添加」和末尾的缺失成员占位行不在 proxyList 里，同一配置可重复出现，
        // 没有稳定的行身份可比；重建只发生在载入与选中后，拖动 / 删除已走针对性通知
        lifecycleScope.launch {
            members.revision.collect { configurationAdapter.notifyDataSetChanged() }
        }

        ItemTouchHelper(object : DragScaleCallback(
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

        // 恢复出来的 Fragment 可能在 super.onCreate 里就创建视图，那时本 Activity 的 onCreate 还没走完：
        // 先切到主线程再碰成员列表，让 onCreate 先走完。
        // 旋转后列表已在 ViewModel 里，不再重载；进程重建后（loaded 为假）按缓存里的 id 载入
        editor.launchInSession {
            checked { awaitEditorReady() }
            if (!onMain { members.loaded }) members.reload(this, ::cachedProxyIds)
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun move(from: Int, to: Int) {
            // 先取出再插入：ItemTouchHelper 一次可能跨多行，交换会让列表顺序与界面不一致；
            // 适配器位置比 proxyList 下标大 1（第 0 行是别的条目）
            proxyList.add(to - 1, proxyList.removeAt(from - 1))
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
                else -> -members.missingIds[position - 1 - proxyList.size]
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
                holder.bind(members.missingIds[position - 1 - proxyList.size])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + members.missingIds.size + 1
        }

    }

    // memberSnapshot 须为 proxyList 的主线程快照：本函数跑在 Default 调度器上，
    // 直接迭代 proxyList 会与主线程的拖拽/删除并发
    fun testProfileAllowed(profile: ProxyEntity, memberSnapshot: List<ProxyEntity>): Boolean {
        if (profile.id == EditorCache.editingId) return false

        for (entity in memberSnapshot) {
            if (testProfileContains(entity, profile)) return false
        }

        // reverse check: the candidate's own subtree must not contain the chain
        // being edited, or adding it would close a loop (chain A holds chain B
        // while B is being edited to add A)
        val editing = ProfileRepository.getProfile(EditorCache.editingId)
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
        // 防已损坏数据里的链循环（A 含 B、B 含 A）：再次进入时停止下钻，免得栈溢出。
        // 与 ConfigBuilder.kt 里 expandChainInOrder 的 visiting 集合同理
        if (!visiting.add(profile.id)) return false
        try {
            val proxies = profile.chainBean!!.proxies
            if (proxies.contains(anotherProfile.id)) return true
            if (proxies.isNotEmpty()) {
                for (entity in ProfileRepository.getProfiles(proxies)) {
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

    // 进程死亡后仍在（SavedStateHandle），待处理的替换才不会变成追加
    var replacing: Int
        get() = members.replacing
        set(value) {
            members.replacing = value
        }

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) launchInSession {
                checked { awaitEditorReady() }

                val profileId = data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
                    ?: return@launchInSession
                val profile = checked { ProfileRepository.getProfile(profileId) } ?: return@launchInSession

                // proxyList 只由主线程改写（拖拽/删除/重载），快照必须在主线程取
                val snapshot = onMain { proxyList.toList() }
                if (!checked { testProfileAllowed(profile, snapshot) }) {
                    onMain {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    // onMain 先复查会话再写：查库与环路检查期间缓存可能已被别的编辑器接管，迟到的结果丢弃；
                    // 查完到写完之间没有挂起点
                    onMain {
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
                        editor.launchInSession { members.reload(this, ::cachedProxyIds) }
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
            profileType.setTextColor(getProtocolColor())

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