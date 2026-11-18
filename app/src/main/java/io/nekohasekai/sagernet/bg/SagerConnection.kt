package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        const val SHORTCUT_CONNECT_TIMEOUT_MS = 10_000L

        // written on Dispatchers.Default (triggerFullRestart), read on binder threads
        @Volatile
        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbSelectorUpdate(previousId: Long, id: Long) {}

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

    // 注册请求失败时远端也可能已执行，断开时仍须尝试注销；binderDied 在 Binder 线程清除此标记。
    @Volatile
    private var callbackRegistrationAttempted = false
    private val registrationHandler = Handler(Looper.getMainLooper())
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private val serviceCallback = object : ISagerNetServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            // 越界 ordinal 由 fromOrdinal 回落 Stopped，无需在此拦截
            val s = BaseService.State.fromOrdinal(state)
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

        override fun cbSelectorUpdate(previousId: Long, id: Long) {
            runOnMainDispatcher {
                callback?.cbSelectorUpdate(previousId, id)
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
        registerCallback()
    }

    private fun registerCallback(retryDelayMillis: Long = 1000L) {
        registrationHandler.removeCallbacksAndMessages(null)
        val service = service ?: return
        callbackRegistrationAttempted = true
        try {
            service.registerCallback(serviceCallback, connectionId)
        } catch (e: Exception) {
            Logs.w("Failed to register service callback", e)
            // 小请求也可能因 Binder 缓冲区不足而失败，不能只等进程死亡或下次切页。
            // 注册按 callback binder 幂等；退避至最多 30 秒，每次重试都使用最新前后台状态。
            // 执行时再核对连接：断开或 binderDied 后入队的重试只会空跑一次。
            if (e is RemoteException) registrationHandler.postDelayed({
                if (connectionActive && this.service === service) {
                    registerCallback((retryDelayMillis * 2).coerceAtMost(30_000L))
                }
            }, retryDelayMillis)
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        if (!connectionActive) return
        connectionHandler.removeCallbacksAndMessages(null)
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            // 重复回连仍按同一个 callback binder 注册，不新增服务端回调；注册失败由内部退避重试，
            // 这里只会收到 linkToDeath 的失败（远端已死亡，不再注册）。
            registerCallback()
        } catch (e: RemoteException) {
            Logs.w("Failed to link to service death", e)
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
        callbackRegistrationAttempted = false
        registrationHandler.removeCallbacksAndMessages(null)
        if (!restartingApp) {
            runOnMainDispatcher { callback?.onBinderDied() }
        }
    }

    private fun unregisterCallback() {
        registrationHandler.removeCallbacksAndMessages(null)
        val service = service
        if (service != null && callbackRegistrationAttempted) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistrationAttempted = false
    }

    // 返回 false 表示绑定被拒或调用失败：不会再收到 onServiceConnected，
    // 无界面的调用方（QuickToggleShortcut）要据此自行收尾
    fun connect(context: Context, callback: Callback?, timeoutMillis: Long = 0): Boolean {
        if (connectionActive) return true
        check(this.callback == null)
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        connectionActive = true
        this.callback = callback
        val bound = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            // 系统服务通信失败也会被 ContextImpl 包装成 RuntimeException。
            Logs.w("Failed to bind service", e)
            false
        }
        if (!bound) {
            connectionActive = false
            this.callback = null
            // 重启绑定失败不能继续屏蔽其它连接的 binderDied。
            restartingApp = false
            // 框架先登记 ServiceConnection 再请求系统服务，返回 false 或抛异常也需解绑。
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // 异常发生在登记之前时没有可释放的连接。
            } catch (e: RuntimeException) {
                // 解绑也可能遇到系统通信失败，不能阻断已完成的本地回滚。
                Logs.w("Failed to unbind service after bind failure", e)
            }
        } else if (timeoutMillis > 0 && service == null) {
            // 无界面快捷方式不能无限等首次回连；解绑后框架会丢弃该请求的迟到回调。
            connectionHandler.postDelayed({
                val waiting = this.callback
                disconnect(context)
                waiting?.onServiceDisconnected()
            }, timeoutMillis)
        }
        return bound
    }

    fun disconnect(context: Context) {
        connectionHandler.removeCallbacksAndMessages(null)
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
            // 连接已由框架释放。
        } catch (e: RuntimeException) {
            Logs.w("Failed to unbind service", e)
        }
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
    get() = runCatching { BaseService.State.fromOrdinal(state) }
        .getOrDefault(BaseService.State.Stopped)
