package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.ProfileManager
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    // ic_service_connected is an animated vector for ServiceButton; tiles cannot
    // animate it and would show a wrong frame, so the static airplane is used here.
    private val iconConnected by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
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
