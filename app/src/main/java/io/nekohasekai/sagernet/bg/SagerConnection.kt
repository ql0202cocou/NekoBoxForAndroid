package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) : ServiceConnection, IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4

        // written on Dispatchers.Default (triggerFullRestart), read on binder threads
        @Volatile
        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbSelectorUpdate(id: Long) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false

    // binderDied clears these from a binder thread; the main thread reads them
    @Volatile
    private var callbackRegistered = false
    private var callback: Callback? = null
    private val serviceCallback = object : ISagerNetServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            ServiceRegistry.state = s
            runOnMainDispatcher {
                // re-read at execution time: disconnect() may have run while
                // this lambda was queued, and a stale copy would still fire
                callback?.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            runOnMainDispatcher {
                callback?.cbSpeedUpdate(stats)
            }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            runOnMainDispatcher {
                callback?.cbTrafficUpdate(stats)
            }
        }

        override fun cbSelectorUpdate(id: Long) {
            runOnMainDispatcher {
                callback?.cbSelectorUpdate(id)
            }
        }

        override fun missingPlugin(profileName: String, pluginName: String) {
            runOnMainDispatcher {
                callback?.missingPlugin(profileName, pluginName)
            }
        }

    }

    private var binder: IBinder? = null

    @Volatile
    var service: ISagerNetService? = null

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            service?.registerCallback(serviceCallback, id)
        } catch (e: Exception) {
            Logs.w("Failed to update connection id", e)
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            // a second onServiceConnected without a disconnect in between must not
            // throw out of a system callback; re-registering is harmless
            if (callbackRegistered) Logs.w("callback already registered, registering again")
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
        } catch (e: RemoteException) {
            Logs.w("Failed to register service callback", e)
        }
        callback?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() {
        service = null
        callbackRegistered = false
        if (!restartingApp) {
            runOnMainDispatcher { callback?.onBinderDied() }
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
            // bind refused: no onServiceConnected/Disconnected will ever
            // arrive, so roll back to allow a later retry
            connectionActive = false
            this.callback = null
            // a failed restart connect must not leave this stuck, or every
            // later binderDied would be swallowed
            restartingApp = false
        }
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        if (listenForDeath) try {
            binder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
        binder = null
        service = null
        callback = null
    }
}

// The :bg binder may already be dying; a failed read must not crash the caller's
// process, so treat it as Stopped.
val ISagerNetService.stateOrStopped: BaseService.State
    get() = runCatching { BaseService.State.values()[state] }
        .getOrDefault(BaseService.State.Stopped)
