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
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.ConfigurationFragment.SelectCallback
import io.nekohasekai.sagernet.ui.profile.settingIntent
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import io.nekohasekai.sagernet.bg.ServiceRegistry

class ConfigurationHolder(
    private val groupFragment: ProfileListFragment, val view: View
) : RecyclerView.ViewHolder(view),
    PopupMenu.OnMenuItemClickListener {

    lateinit var entity: ProxyEntity

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

    fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
        val pf = groupFragment.parentFragment as? ConfigurationFragment ?: return

        entity = proxyEntity

        if (groupFragment.select) {
            view.setOnClickListener {
                (groupFragment.requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
            }
        } else {
            view.setOnClickListener {
                runOnDefaultDispatcher {
                    var update: Boolean
                    var lastSelected: Long
                    groupFragment.profileAccess.withLock {
                        update = DataStore.selectedProxy != proxyEntity.id
                        lastSelected = DataStore.selectedProxy
                        DataStore.selectedProxy = proxyEntity.id
                        onMainDispatcher {
                            selectedView.visibility = View.VISIBLE
                        }
                    }

                    if (update) {
                        ProfileManager.postUpdate(lastSelected)
                        if (ServiceRegistry.state.canStop && groupFragment.reloadAccess.tryLock()) {
                            SagerNet.reloadService()
                            groupFragment.reloadAccess.unlock()
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
        profileType.setTextColor(groupFragment.requireContext().getProtocolColor(proxyEntity.type))

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
        (trafficText.parent as View).isGone =
            (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

        if (proxyEntity.status <= 0) {
            if (showTraffic) {
                profileStatus.text = trafficText.text
                profileStatus.setTextColor(groupFragment.requireContext().getColorAttr(android.R.attr.textColorSecondary))
                trafficText.text = ""
            } else {
                profileStatus.text = ""
            }
        } else if (proxyEntity.status == 1) {
            profileStatus.text = groupFragment.getString(R.string.available, proxyEntity.ping)
            profileStatus.setTextColor(groupFragment.requireContext().getColour(R.color.material_green_500))
        } else {
            profileStatus.setTextColor(groupFragment.requireContext().getColour(R.color.material_red_500))
            if (proxyEntity.status == 2) {
                profileStatus.text = proxyEntity.error
            }
        }

        if (proxyEntity.status == 3) {
            val err = proxyEntity.error ?: "<?>"
            val msg = Protocols.genFriendlyMsg(err)
            profileStatus.text = if (msg != err) msg else groupFragment.getString(R.string.unavailable)
            profileStatus.setOnClickListener {
                groupFragment.alert(err).tryToShow()
            }
        } else {
            profileStatus.setOnClickListener(null)
        }

        editButton.setOnClickListener {
            it.context.startActivity(
                proxyEntity.settingIntent(
                    it.context, groupFragment.proxyGroup.type == GroupType.SUBSCRIPTION
                )
            )
        }

        removeButton.setOnClickListener {
            groupFragment.adapter?.let {
                val index = it.configurationIdList.indexOf(proxyEntity.id)
                // a stale holder can outlive its row: -1 would queue an
                // undo entry that inserts at index -1 on restore, while
                // still deleting the profile on commit
                if (index < 0) return@let
                it.remove(index)
                groupFragment.undoManager.remove(index to proxyEntity)
            }
        }

        val selectOrChain = groupFragment.select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
        shareLayout.isGone = selectOrChain
        editButton.isGone = groupFragment.select
        removeButton.isGone = groupFragment.select

        proxyEntity.nekoBean?.apply {
            shareLayout.isGone = true
        }

        runOnDefaultDispatcher {
            val selected = (groupFragment.selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
            val started =
                selected && ServiceRegistry.state.started && DataStore.currentProfile == proxyEntity.id
            onMainDispatcher {
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }

            fun showShare(anchor: View) {
                val popup = PopupMenu(groupFragment.requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                when {
                    !proxyEntity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }

                    !proxyEntity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }
                }

                if (proxyEntity.nekoBean != null) {
                    popup.menu.removeItem(R.id.action_group_configuration)
                }

                popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                popup.show()
            }

            if (!(groupFragment.select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                onMainDispatcher {
                    shareLayer.setBackgroundColor(Color.TRANSPARENT)
                    shareButton.setImageResource(R.drawable.ic_social_share)
                    shareButton.setColorFilter(Color.GRAY)
                    shareButton.isVisible = true

                    shareLayout.setOnClickListener {
                        showShare(it)
                    }
                }
            }
        }

    }

    var currentName = ""
    fun showCode(link: String) {
        QRCodeDialog(link, currentName).showAllowingStateLoss(groupFragment.parentFragmentManager)
    }

    fun export(link: String) {
        val success = SagerNet.trySetPrimaryClip(link)
        (groupFragment.activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
            .show()
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

                R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                R.id.action_config_export_file -> {
                    val cfg = entity.exportConfig()
                    (groupFragment.parentFragment as ConfigurationFragment).pendingExportConfig =
                        cfg.first
                    groupFragment.startFilesForResult(
                        (groupFragment.parentFragment as ConfigurationFragment).exportConfig, cfg.second
                    )
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            (groupFragment.activity as MainActivity).snackbar(e.readableMessage).show()
            return true
        }
        return true
    }
}
