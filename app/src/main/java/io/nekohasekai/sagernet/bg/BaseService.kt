package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RestoreJournal
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        // written on the main thread, read on binder threads and by gomobile
        // Go threads (NativeInterface selector_OnProxySelected)
        @Volatile
        var state = State.Stopped

        @Volatile
        var proxy: ProxyInstance? = null

        @Volatile
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    // box is lateinit: during Connecting data.proxy is
                    // already set while proxy.init() has not finished
                    val p = proxy
                    if (p != null && p.isInitialized()) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            p.box.sleep()
                        } else {
                            p.box.wake()
                            if (DataStore.wakeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }

                Action.CLEAR_TRAFFIC_STATISTICS -> {
                    val ids = intent.getLongArrayExtra(Action.EXTRA_PROFILE_IDS)
                    // off the receiver's main thread: clearStats contends with
                    // the looper's stats sweep, which holds statsLock across
                    // its JNI queryStats calls
                    if (ids != null) runOnDefaultDispatcher {
                        proxy?.looper?.clearStats(ids)
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                // Action.CLOSE (notification button / tile / UI): log it, or an
                // exported log shows a clean teardown with no identifiable cause
                else -> {
                    Logs.i("Broadcast ${intent.action}: stopping service")
                    service.stopRunner()
                }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(@Volatile private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                if (callback != null) callbackIdMap.remove(callback.asBinder())
            }
        }

        // Keyed by the binder, not the interface: every registerCallback transaction
        // deserializes a fresh Stub.Proxy and the generated proxy has no equals(), so
        // an object-keyed map would never dedup and never remove — it would just grow
        // one stale entry per foreground/background switch.
        // written on binder threads, read by TrafficLooper on Dispatchers.Default
        val callbackIdMap = ConcurrentHashMap<IBinder, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            val key = cb.asBinder()
            if (!callbackIdMap.containsKey(key)) {
                callbacks.register(cb)
            }
            callbackIdMap[key] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb.asBinder())
            callbacks.unregister(cb)
        }

        override fun urlTest(): Int {
            // close() nulls data on the main thread while this runs on a binder
            // thread; box is lateinit, and a restart in progress has data.proxy
            // set before loadConfig() assigns it
            val box = data?.proxy?.takeIf { it.isInitialized() }?.box
                ?: error("core not started")
            try {
                return Libcore.urlTest(
                    box, DataStore.connectionTestURL, 3000
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            callbackIdMap.clear()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            // canReloadSelector() builds a whole config, DB reads included, and
            // reload() runs on :bg's main thread from onReceive — an ANR risk on a
            // large group. data.proxy can be nulled by a concurrent stop now that
            // this is off-thread, so read it defensively.
            runOnDefaultDispatcher {
                try {
                    if (canReloadSelector()) {
                        val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                        val tag = data.proxy?.config?.profileTagMap?.get(ent?.id) ?: ""
                        if (tag.isNotBlank() && ent != null) {
                            // select from GUI
                            data.proxy?.box?.selectOutbound(tag)
                            // or select from webui
                            // => selector_OnProxySelected
                            return@runOnDefaultDispatcher
                        }
                        // no outbound of its own (e.g. only a middle hop of another
                        // member's chain): fall through to a full restart, which
                        // rebuilds the config around it, instead of doing nothing
                        Logs.w("No outbound tag for profile ${ent?.id}, restarting")
                    }
                } catch (e: Throwable) {
                    // bad profile data (e.g. a chain loop) or a JNI error must not
                    // crash :bg from an uncaught coroutine exception;
                    // fall back to a full restart below
                    Logs.w(e)
                }
                onMainDispatcher {
                    val s = data.state
                    when {
                        s == State.Stopped -> startRunner()
                        s.canStop -> stopRunner(true)
                        else -> Logs.w("Illegal state $s when invoking use")
                    }
                }
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses() {
            // only the loop has to stop before the box does — its queryStats needs a
            // live box. The final counters and broadcast touch neither, so they run
            // after the close instead of holding up the teardown.
            val looper = data.proxy?.looper
            val postFinalTraffic = looper?.stopLoop() == true
            data.proxy?.let {
                it.close()
                it.awaitProcessesClosed()
            }
            if (postFinalTraffic) {
                // A database write failure must not strand the service in
                // Stopping with its wake lock and foreground notification held.
                runCatching { looper?.postFinalTraffic() }
                    .onFailure { Logs.w("Final traffic persistence failed", it) }
            }
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            // post from the main queue like the preInit start send, so on a
            // restart the Stop always reaches the listener actor before the
            // new Start (a Default-dispatcher send could overtake it)
            runOnMainDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null

            if (data.state == State.Stopping) return
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null
                }
                // Stay in the foreground until here: a start arriving while
                // Stopping is dropped by onStartCommand, and with the
                // notification already gone that startForegroundService() would
                // have no startForeground() to pair with — the platform kills
                // :bg for it at the stopSelf() below.
                data.notification?.destroy()
                data.notification = null

                // change the state
                data.changeState(State.Stopped, msg)
                // stop the service if nothing has bound to it
                if (restart) startRunner() else {
                    stopSelf()
                }
            }
        }

        fun persistStats() {
            // ACTION_SHUTDOWN: the process is killed without stopRunner, so
            // the looper never persists its counters; block until they are
            // written. proxy/looper are null when not fully started.
            runBlocking {
                data.proxy?.looper?.persistStats()
            }
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            DefaultNetworkListener.start(this) { network ->
                // resetAllConnections is a blocking gomobile call; keep it off
                // the DefaultNetworkListener actor, whose queue is fed from
                // ConnectivityThread via runBlocking. The serial dispatcher
                // keeps the callbacks ordered.
                runOnSerialDispatcher {
                    // Lost is reported with a null network; drop the stale
                    // reference like the main-process listener does
                    if (network == null) {
                        SagerNet.underlyingNetwork = null
                        upstreamInterfaceName = null
                        return@runOnSerialDispatcher
                    }
                    SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                        SagerNet.underlyingNetwork = network
                        DataStore.vpnService?.updateUnderlyingNetwork()
                        //
                        val oldName = upstreamInterfaceName
                        if (oldName != link.interfaceName) {
                            upstreamInterfaceName = link.interfaceName
                        }
                        if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                            Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                            if (DataStore.networkChangeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (RestoreJournal.default.isPending()) {
                // a restore interrupted between its two commits could not be replayed at
                // startup: profiles and settings do not match yet
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.restore_pending))
                return Service.START_NOT_STICKY
            }
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.CLEAR_TRAFFIC_STATISTICS)
                }
                ContextCompat.registerReceiver(
                    this,
                    data.receiver,
                    filter,
                    "$packageName.SERVICE",
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = runOnMainDispatcher {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    onIoDispatcher { Executable.killAll() }    // clean up old processes (/proc IO off the main thread)
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
