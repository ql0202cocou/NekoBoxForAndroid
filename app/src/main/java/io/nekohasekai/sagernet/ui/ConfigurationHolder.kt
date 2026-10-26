package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.exportToClipboard
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.motionDuration
import io.nekohasekai.sagernet.ktx.onLiveView
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.ConfigurationFragment.SelectCallback
import io.nekohasekai.sagernet.ui.StatusColors.latencyColor
import io.nekohasekai.sagernet.ui.StatusColors.statusErrorColor
import io.nekohasekai.sagernet.ui.profile.settingIntent
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols.getProtocolColor
import io.nekohasekai.sagernet.bg.ServiceRegistry

class ConfigurationHolder(
    private val groupFragment: ProfileListFragment, val view: View
) : RecyclerView.ViewHolder(view),
    PopupMenu.OnMenuItemClickListener {

    // 最近一次绑定的实例，只在适配器缓存里取不到时兜底
    private lateinit var boundEntity: ProxyEntity

    // 动作（分享、编辑、删除、导出）与流量重画取用的节点：每次取缓存里的最新实例。
    // 差量认为卡片字段没变时不会重绑，持有绑定那一刻的实例会让这些动作读到过期的节点内容（例如旧密码的分享链接）
    val entity: ProxyEntity
        get() = groupFragment.adapter?.latestEntity(boundEntity.id) ?: boundEntity

    val profileName: TextView = view.findViewById(R.id.profile_name)
    val profileType: TextView = view.findViewById(R.id.profile_type)
    val profileAddress: TextView = view.findViewById(R.id.profile_address)
    val profileStatus: TextView = view.findViewById(R.id.profile_status)

    val trafficText: TextView = view.findViewById(R.id.traffic_text)
    val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
    val editButton: ImageView = view.findViewById(R.id.edit)
    val shareLayout: LinearLayout = view.findViewById(R.id.share)
    val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
    val shareButton: ImageView = view.findViewById(R.id.shareIcon)
    val removeButton: ImageView = view.findViewById(R.id.remove)

    // 上次绑定的节点 id 与本次绑定是否要让状态行淡入：只有「同一个 holder 上同一个节点」
    // 的测试结果文字变化才淡入（整组重读的差量变化与 onUpdated 都带 payload 就地重绑，会走到这里），
    // 首次绑定、滚动复用、换节点都不动画
    private var boundId = Long.MIN_VALUE
    private var fadeStatus = false

    // 页面作用域：holder 由适配器在视图存活时创建，视图销毁时取消。
    // 行内的读取与回填挂在这里，每次 bind / 回收时先取消上一次的，复用后的 holder 不会收到旧节点的结果
    private val viewScope = groupFragment.viewLifecycleOwner.lifecycleScope
    private var bindJob: Job? = null

    fun cancelPendingBind() {
        bindJob?.cancel()
        bindJob = null
    }

    fun bind(proxyEntity: ProxyEntity) {
        cancelPendingBind()
        if (groupFragment.parentFragment !is ConfigurationFragment) return

        fadeStatus = boundId == proxyEntity.id
        boundId = proxyEntity.id
        if (!fadeStatus) {
            profileStatus.animate().cancel()
            profileStatus.alpha = 1f
        }
        boundEntity = proxyEntity

        if (groupFragment.select) {
            view.setOnClickListener {
                (groupFragment.requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
            }
        } else {
            view.setOnClickListener {
                // 进程级：点选节点要写 selectedProxy 并按需重载服务，页面离开也必须完成；界面回填只在页面存活时做
                runOnDefaultDispatcher {
                    var update: Boolean
                    var lastSelected: Long
                    groupFragment.profileAccess.withLock {
                        update = DataStore.selectedProxy != proxyEntity.id
                        lastSelected = DataStore.selectedProxy
                        DataStore.selectedProxy = proxyEntity.id
                        // 页面已销毁则不回填；与 bind 末尾同一防护：协程返回时 holder 可能已复用
                        groupFragment.onLiveView {
                            if (entity.id != proxyEntity.id) return@onLiveView
                            selectedView.visibility = View.VISIBLE
                        }
                    }

                    if (update) {
                        ProfileRepository.postUpdate(lastSelected)
                        if (ServiceRegistry.state.canStop && groupFragment.reloadAccess.tryLock()) {
                            // reloadService 抛异常时也要还锁，否则锁永远挂着，
                            // 之后点选节点都不再触发 reload
                            try {
                                SagerNet.reloadService()
                            } finally {
                                groupFragment.reloadAccess.unlock()
                            }
                        }
                    } else if (SagerNet.isTv) {
                        if (ServiceRegistry.state.started) {
                            SagerNet.stopService()
                        } else {
                            SagerNet.startService()
                        }
                    }
                }

            }
        }

        profileName.text = proxyEntity.displayName()
        profileType.text = proxyEntity.displayType()
        profileType.setTextColor(groupFragment.requireContext().getProtocolColor())

        bindTraffic(ProfileRepository.liveTraffic[proxyEntity.id])
        fadeStatus = false

        editButton.setOnClickListener {
            it.context.startActivity(entity.settingIntent(it.context))
        }

        removeButton.setOnClickListener {
            groupFragment.adapter?.let {
                val index = it.indexOfId(proxyEntity.id)
                // a stale holder can outlive its row: -1 would queue an
                // undo entry that inserts at index -1 on restore, while
                // still deleting the profile on commit
                if (index < 0) return@let
                // 先取最新实例再移除：撤销与到期删除用的是这一份
                val latest = entity
                it.remove(index)
                groupFragment.undoManager.remove(index to latest)
            }
        }

        val selectOrChain = groupFragment.select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
        shareLayout.isGone = selectOrChain
        editButton.isGone = groupFragment.select
        removeButton.isGone = groupFragment.select

        proxyEntity.nekoBean?.apply {
            shareLayout.isGone = true
        }

        // 选中态要读库（DataStore 键值表），读完回到主线程回填按钮；
        // bindJob 被下一次 bind / 回收 / 视图销毁取消，所以回填时 holder 一定还绑着这个节点
        val selectedItemId = groupFragment.selectedItem?.id
        bindJob = viewScope.launch {
            val (selected, started) = withContext(Dispatchers.IO) {
                val selected = (selectedItemId ?: DataStore.selectedProxy) == proxyEntity.id
                val started =
                    selected && ServiceRegistry.state.started && DataStore.currentProfile == proxyEntity.id
                selected to started
            }
            editButton.isEnabled = !started
            removeButton.isEnabled = !started
            selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE

            if (!selectOrChain) {
                shareLayer.setBackgroundColor(Color.TRANSPARENT)
                shareButton.setImageResource(R.drawable.ic_social_share)
                shareButton.setColorFilter(shareButton.context.getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                shareButton.isVisible = true

                shareLayout.setOnClickListener {
                    showShare(it, entity)
                }
            }
        }

    }

    private fun showShare(anchor: View, proxyEntity: ProxyEntity) {
        val popup = PopupMenu(groupFragment.requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

        if (!proxyEntity.haveStandardLink()) {
            popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
            popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                R.id.action_standard_clipboard
            )
        }

        if (proxyEntity.nekoBean != null) {
            popup.menu.removeItem(R.id.action_group_configuration)
        }

        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
        popup.show()
    }

    // 流量、地址与状态这一段：TrafficLooper 前台时每秒推一次流量，只重画这里，
    // 不重跑 bind 的监听器设置和选中状态查询
    fun bindTraffic(trafficData: TrafficData?) {
        val pf = groupFragment.parentFragment as? ConfigurationFragment ?: return
        val proxyEntity = entity

        var rx = proxyEntity.rx
        var tx = proxyEntity.tx
        if (trafficData != null) {
            // use new data
            tx = trafficData.tx
            rx = trafficData.rx
        }

        val showTraffic = rx + tx != 0L
        trafficText.isVisible = showTraffic
        if (showTraffic) {
            trafficText.text = view.context.getString(
                R.string.traffic,
                Formatter.formatFileSize(view.context, tx),
                Formatter.formatFileSize(view.context, rx)
            )
        }

        var address = proxyEntity.displayAddress()
        if (showTraffic && address.length >= 30) {
            address = address.substring(0, 27) + "..."
        }

        if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
            address = ""
        }

        profileAddress.text = address
        val previousStatus = profileStatus.text.toString()
        (trafficText.parent as View).isGone =
            (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

        if (proxyEntity.status <= 0) {
            if (showTraffic) {
                profileStatus.text = trafficText.text
                profileStatus.setTextColor(groupFragment.requireContext().getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
                trafficText.text = ""
            } else {
                profileStatus.text = ""
            }
        } else if (proxyEntity.status == 1) {
            profileStatus.text = groupFragment.getString(R.string.available, proxyEntity.ping)
            profileStatus.setTextColor(groupFragment.requireContext().latencyColor(proxyEntity.ping))
        } else {
            profileStatus.setTextColor(groupFragment.requireContext().statusErrorColor())
            if (proxyEntity.status == 2) {
                profileStatus.text = proxyEntity.error
            }
        }

        if (proxyEntity.status == 3) {
            val err = proxyEntity.error ?: "<?>"
            profileStatus.text = groupFragment.requireContext().unavailableText(err)
            profileStatus.setOnClickListener {
                groupFragment.alert(err).tryToShow()
            }
        } else {
            profileStatus.setOnClickListener(null)
        }

        if (fadeStatus && proxyEntity.status > 0 && profileStatus.text.toString() != previousStatus) {
            profileStatus.animate().cancel()
            profileStatus.alpha = 0f
            profileStatus.animate().alpha(1f).setDuration(
                view.context.motionDuration(com.google.android.material.R.attr.motionDurationShort4, 200)
            ).start()
        }
    }

    var currentName = ""
    fun showCode(link: String) {
        QRCodeDialog(link, currentName).showAllowingStateLoss(groupFragment.parentFragmentManager)
    }

    fun export(link: String) {
        (groupFragment.activity as MainActivity).snackbar(exportToClipboard(link)).show()
    }

    // 导出配置走构建流程，要读节点库（buildConfig），不能在主线程做：IO 线程生成，回主线程复制或选文件。
    // 页面作用域：生成期间页面离开就不再弹提示 / 选文件。失败的提示与菜单里其它导出项相同
    private fun exportConfig(toFile: Boolean) {
        val entity = entity
        viewScope.launch {
            val cfg = try {
                withContext(Dispatchers.IO) { entity.exportConfig() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                (groupFragment.activity as MainActivity).snackbar(e.readableMessage).show()
                return@launch
            }
            if (toFile) {
                val configuration = groupFragment.parentFragment as ConfigurationFragment
                configuration.pendingExportConfig = cfg.first
                groupFragment.startFilesForResult(configuration.exportConfig, cfg.second)
            } else {
                export(cfg.first)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        try {
            currentName = entity.displayName()!!
            when (item.itemId) {
                R.id.action_standard_qr -> showCode(entity.toStdLink())
                R.id.action_standard_clipboard -> export(entity.toStdLink())
                R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                R.id.action_universal_clipboard -> export(
                    entity.requireBean().toUniversalLink()
                )

                R.id.action_config_export_clipboard -> exportConfig(toFile = false)
                R.id.action_config_export_file -> exportConfig(toFile = true)
            }
        } catch (e: Exception) {
            Logs.w(e)
            (groupFragment.activity as MainActivity).snackbar(e.readableMessage).show()
            return true
        }
        return true
    }
}
