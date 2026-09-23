package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
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
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RestoreJournal
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class BaseService {

    // 警告：ordinal 即跨进程 IPC 线格式——Binder.getState() 返回 ordinal，
    // 主进程用 State.fromOrdinal 还原。
    // 顺序即 IPC 契约：新值只准追加在末尾，不准插入/重排既有值。
    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        // Idle 不由 BaseService 主动上报：Binder.getState() 在 data == null
        //（服务正在退出）时返回它，UI（如 TileService）按 Stopped 处理
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped;

        companion object {
            // 唯一的跨进程解码点：:bg 版本更新后可发来本版本不存在的 ordinal
            //（State 只准追加新值），越界回落 Stopped 而不是在 binder 线程上 AIOOBE
            fun fromOrdinal(ordinal: Int) = values().getOrElse(ordinal) { Stopped }
        }
    }

    class Data internal constructor(private val service: Interface) {
        // written on the main thread, read on binder threads and by gomobile
        // Go threads (NativeInterface selector_OnProxySelected)
        @Volatile
        var state = State.Stopped

        @Volatile
        var proxy: ProxyInstance? = null

        @Volatile
        var notification: ServiceNotification? = null

        // main thread only: lateInit acquires, killProcesses releases
        var wakeLock: PowerManager.WakeLock? = null

        // serial dispatcher only, see Interface.preInit
        var upstreamInterfaceName: String? = null

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
                                Libcore.resetAllConnections()
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
                    Libcore.resetAllConnections()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                // Action.CLOSE (notification button / tile / UI): log it, or an
                // exported log shows a clean teardown with no identifiable cause
                // 警告：兜底分支收到即停服务。IntentFilter 新增 action 时必须在
                // 上面的 when 里加对应分支，否则该广播一落到这里就误停服务
                else -> {
                    Logs.i("Broadcast ${intent.action}: stopping service")
                    service.stopRunner()
                }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        // 停止完成后要重放的启动意图：stopRunner(restart) 的显式重启，以及
        // Stopping 窗口（killProcesses 挂起等待 looper/box/插件进程池，可达数百
        // 毫秒）内到达的 onStartCommand / reload——以前后者被静默丢弃，用户只能
        // 再点一次。只在主线程读写（onStartCommand、stopRunner 及其尾部的
        // runOnMainDispatcher 块都在主线程），无需 volatile
        var pendingStart = false

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            ServiceRegistry.state = s
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
                        } catch (e: Exception) {
                            // a bug in the callback body, not a dead client: keep it visible
                            Logs.w(e)
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

        // Every implementer is a Service; an interface cannot say so in its
        // type, so the defaults below reach the Context through this instead
        // of casting `this`.
        val service: Service
        val wakeLockTag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, service.getString(R.string.profile_empty))
                return
            }
            // canReloadSelector() builds a whole config, DB reads included, and
            // reload() runs on :bg's main thread from onReceive — an ANR risk on a
            // large group. data.proxy can be nulled by a concurrent stop now that
            // this is off-thread, so read it defensively.
            runOnDefaultDispatcher {
                try {
                    if (canReloadSelector()) {
                        val ent = ProfileManager.getProfile(DataStore.selectedProxy)
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
                        // Stopping：stopRunner 只把重启意图记入 pendingStart
                        s.canStop || s == State.Stopping -> stopRunner(true)
                        else -> Logs.w("Illegal state $s when invoking use")
                    }
                }
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = ProfileManager.getProfile(DataStore.selectedProxy) ?: return false
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

        // 与 SagerNet.startService 同一层防御：Android 12+ 在后台抛
        // ForegroundServiceStartNotAllowedException（IllegalStateException
        // 子类，单个 catch 即可覆盖，无需按 API 引用该类），8-11 抛普通
        // IllegalStateException。丢掉这次启动好过让进程崩溃
        fun startRunner() {
            val intent = Intent(service, service.javaClass)
            try {
                ContextCompat.startForegroundService(service, intent)
            } catch (e: IllegalStateException) {
                Logs.w(e)
            }
        }

        // 子类私有的运行期资源（VpnService 的 tun fd）。运行期资源有两条释放
        // 路径——正常停止走 killProcesses，框架直接 destroy 走 destroyRunner
        // ——两条都在最前面调这个钩子，子类因此只需覆盖这一处
        fun releaseSubclassResources() {}

        // wakeLock 与网络监听：两条释放路径里时序无差别的部分，共用一份。
        // looper 与 box 不放进来，它们的时序两条路径本就不同：正常停止要等
        // （stopLoop 的返回值决定是否落最后一笔流量，close 之后还要
        // awaitProcessesClosed），而 onDestroy 不能阻塞，只能 detach。
        // release() 包 runCatching：teardown 中途抛异常会让服务卡在
        // Stopping，wakeLock 与前台通知一直握着
        fun releaseWakeLockAndNetworkListener() {
            runCatching { data.wakeLock?.release() }.onFailure { Logs.w(it) }
            data.wakeLock = null
            // post from the main queue like the preInit start send, so on a
            // restart the Stop always reaches the listener actor before the
            // new Start (a Default-dispatcher send could overtake it)
            runOnMainDispatcher {
                DefaultNetworkListener.stop(this)
            }
        }

        suspend fun killProcesses() {
            releaseSubclassResources()
            // only the loop has to stop before the box does — its queryStats needs a
            // live box. The final counters and broadcast touch neither, so they run
            // after the close instead of holding up the teardown.
            val looper = data.proxy?.looper
            val postFinalTraffic = looper?.stopLoop() == true
            data.proxy?.let {
                it.close() // 从不抛出，见 BoxInstance.close
                it.awaitProcessesClosed()
            }
            if (postFinalTraffic) {
                // A database write failure must not strand the service in
                // Stopping with its wake lock and foreground notification held.
                runCatching { looper.postFinalTraffic() }
                    .onFailure { Logs.w("Final traffic persistence failed", it) }
            }
            // wakeLock 撑到这里再放：上面的等待期间设备不应休眠
            releaseWakeLockAndNetworkListener()
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            ServiceRegistry.baseService = null
            ServiceRegistry.vpnService = null

            // 记在 pendingStart 上再判重：已在 Stopping 时本次调用只留下重启意图
            if (restart) data.pendingStart = true
            if (data.state == State.Stopping) return

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        service.unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null
                }
                // 前台状态一直保持到这里才解除：Stopping 期间到达的
                // startForegroundService 记入 pendingStart 待重放，若通知提前销毁，
                // 那次启动就没有 startForeground() 与之配对——平台会因此在下面的
                // stopSelf() 处杀死 :bg
                data.notification?.destroy()
                data.notification = null

                // change the state
                data.changeState(State.Stopped, msg)
                // 重放停止期间记下的启动意图，否则停掉服务（没有谁绑定着它）
                val start = data.pendingStart
                data.pendingStart = false
                if (start) startRunner() else service.stopSelf()
            }
        }

        // onDestroy：正常路径 stopRunner 已跑过（它最后 stopSelf），运行期资源
        // 由 killProcesses 释放完毕，下面全是幂等 no-op；框架直接 destroy 时
        // stopRunner 没跑过，receiver、notification 和 box / 插件进程 / wakeLock
        // 都会残留，这里 best-effort 兜底释放。子类私有的资源由
        // releaseSubclassResources 钩子覆盖，与 killProcesses 共用
        // Job.cancel() 的成员与同名扩展在本文件（CoroutineScope.cancel 扩展被
        // Binder.close 使用，通配 import 引入）同时可见：编译期恒解析到成员，
        // lint 的跨环境歧义警告在此不适用
        @Suppress("MemberExtensionConflict")
        fun destroyRunner() {
            val data = data
            releaseSubclassResources()
            // stopRunner 之外的另一条出口：注册表同样要清，否则
            // NativeInterface.selector_OnProxySelected 会拿到已销毁的服务
            ServiceRegistry.baseService = null
            // 框架 destroy 可能落在 connectingJob 的挂起点之间：不取消的话它
            // 恢复后会在已关闭的实例上 init 出无人回收的 box
            data.connectingJob?.cancel()
            if (data.closeReceiverRegistered) {
                service.unregisterReceiver(data.receiver)
                data.closeReceiverRegistered = false
            }
            data.notification?.destroy()
            data.notification = null
            // 流量循环也是 killProcesses 负责的运行期资源，这里同样兜底。
            // looper 要先于 box 关闭停下（其 queryStats 需要存活 box），且必须
            // 先捕获引用：close() 会把 looper 字段置 null。onDestroy 不能阻塞，
            // 所以只 detach 不等；stopLoop / Stop 消息都是幂等的，正常路径
            // 重复执行无害
            val looper = data.proxy?.looper
            if (looper != null) runOnDefaultDispatcher { looper.stopLoop() }
            data.proxy?.close() // CAS 保证幂等，且从不抛出（见 BoxInstance.close）
            data.proxy = null
            releaseWakeLockAndNetworkListener()
            data.binder.close()
        }

        fun persistStats() {
            // ACTION_SHUTDOWN: the process is killed without stopRunner, so
            // the looper never persists its counters; block until they are
            // written. proxy/looper are null when not fully started.
            runBlocking {
                // 该广播在主线程上处理，DB 极端慢时无限阻塞会拖出广播 ANR；
                // 超时即放弃——丢最后一笔统计好过关机时 ANR
                withTimeoutOrNull(3000) {
                    data.proxy?.looper?.persistStats()
                }
            }
        }

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
                        data.upstreamInterfaceName = null
                        return@runOnSerialDispatcher
                    }
                    SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                        SagerNet.underlyingNetwork = network
                        ServiceRegistry.vpnService?.updateUnderlyingNetwork()
                        //
                        val oldName = data.upstreamInterfaceName
                        val newName = link.interfaceName
                        if (oldName != newName) {
                            data.upstreamInterfaceName = newName
                        }
                        if (oldName != null && newName != null && oldName != newName) {
                            Logs.d("Network changed: $oldName -> $newName")
                            if (DataStore.networkChangeResetConnections) {
                                Libcore.resetAllConnections()
                            }
                        }
                    }
                }
            }
        }

        @SuppressLint("WakelockTimeout")
        fun acquireWakeLock() {
            data.wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
                .apply { acquire() }
        }

        suspend fun lateInit() {
            data.wakeLock?.apply {
                release()
                data.wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            ServiceRegistry.baseService = this

            val data = data
            if (data.state != State.Stopped) {
                // Stopping 期间的启动意图记下来，由 stopRunner 尾部重放
                if (data.state == State.Stopping) data.pendingStart = true
                return Service.START_NOT_STICKY
            }
            val profile = try {
                ProfileManager.getProfile(DataStore.selectedProxy)
            } catch (e: IOException) {
                // 数据库打不开时 getProfile 抛 IOException（见 ProfileManager 的
                // guardedRead 契约）。与下方空 profile 路径一样优雅停止：未捕获异常
                // 会杀死 :bg 进程，而崩溃后的重启兜底会再次走到这里，形成崩溃循环
                Logs.w(e)
                data.notification = createNotification("")
                stopRunner(false, "${service.getString(R.string.service_failed)}: ${e.readableMessage}")
                return Service.START_NOT_STICKY
            }
            if (RestoreJournal.default.isPending()) {
                // a restore interrupted between its two commits could not be replayed at
                // startup: profiles and settings do not match yet
                data.notification = createNotification("")
                stopRunner(false, service.getString(R.string.restore_pending))
                return Service.START_NOT_STICKY
            }
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, service.getString(R.string.profile_empty))
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
                    service,
                    data.receiver,
                    filter,
                    "${service.packageName}.SERVICE",
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
                    stopRunner(false, service.getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(service, e.readableMessage, Toast.LENGTH_SHORT).show()
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
                        false, "${service.getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
