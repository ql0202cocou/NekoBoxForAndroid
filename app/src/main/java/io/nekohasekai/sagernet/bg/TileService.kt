package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.ProfileManager
import android.service.quicksettings.TileService as BaseTileService

class TileService : BaseTileService(), SagerConnection.Callback {
    // 磁贴用应用图标「盒」的剪影，停止状态带斜杠；主界面按钮的 ic_service_* 是另一套（纸飞机、带动画），
    // 动画矢量在磁贴里不会播放、只会停在错误的一帧，所以这里全是静态图，状态颜色由系统按 Tile.STATE_* 着色
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_box_off) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_box) }
    private val iconConnected by lazy { Icon.createWithResource(this, R.drawable.ic_box) }
    private var tapPending = false

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(service.stateOrStopped, runCatching { service.profileName }.getOrNull())
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = ProfileManager.getProfile(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        // 本次监听会话里没等到的 tap 不能留到下次 bind 成功时补发：
        // 那会在用户无操作的时刻启停 VPN
        tapPending = false
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            label = null
            when (serviceState) {
                // Idle means the :bg binder is up but its data was already
                // nulled (service dying); show it as Stopped.
                BaseService.State.Idle, BaseService.State.Stopped -> {
                    icon = iconIdle
                    state = Tile.STATE_INACTIVE
                }

                BaseService.State.Connecting -> {
                    icon = iconBusy
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Connected -> {
                    icon = iconConnected
                    label = profileName
                    state = Tile.STATE_ACTIVE
                }

                BaseService.State.Stopping -> {
                    icon = iconBusy
                    state = Tile.STATE_UNAVAILABLE
                }
            }
            label = label ?: getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service ?: run {
            tapPending = true
            return
        }
        val state = service.stateOrStopped
        when {
            state.canStop -> SagerNet.stopService()
            state == BaseService.State.Stopped -> SagerNet.startService()
        }
    }
}
