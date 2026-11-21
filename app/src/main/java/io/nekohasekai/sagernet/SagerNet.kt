package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.InstallMarker
import io.nekohasekai.sagernet.database.RestoreJournal
import io.nekohasekai.sagernet.bg.proto.prepareExternalConfigDirectory
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.deleteSharedBackups
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.cleanWebview
import java.io.File
import androidx.work.Configuration as WorkConfiguration
import okhttp3.OkHttp

class SagerNet : Application(),
    WorkConfiguration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    private val nativeInterface = NativeInterface()

    // Replaceable assets (geoip/geosite, rule-set files, ca.pem) live in internal storage.
    // Releases up to 1.7.3 used getExternalFilesDir(), which any app holding
    // WRITE_EXTERNAL_STORAGE can write to on Android 10 and below — enough to plant a
    // trusted CA or swap the routing databases. migrateLegacyAssets moves it over once.
    val assetsDir: File by lazy { File(filesDir, "assets") }
    val process: String = JavaUtil.getProcessName()
    private val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")

    // 同一进程只清理一次，上次进程死亡留下的配置归该进程回收。
    internal val externalConfigDir by lazy {
        prepareExternalConfigDirectory(cacheDir, isBgProcess)
    }

    // CrashHandler deliberately replaces the platform handler: it logs the crash and
    // restarts the process into the log-sharing screen via ProcessPhoenix, which never
    // returns, so there is no point at which the previous handler could be delegated to
    @SuppressLint("DefaultUncaughtExceptionDelegation")
    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)
        // okhttp-android 5.5 reads the application Context (public-suffix list from its
        // assets); its App Startup initializer is dropped together with the
        // InitializationProvider and would only ever run in the main process anyway
        OkHttp.initialize(this)

        if (isMainProcess || isBgProcess) {
            externalConfigDir
            // Finish a restore interrupted between its two database commits before
            // anything reads either database; the same lock then settles the
            // per-install marker so both processes agree on the Clash secret.
            // 重放与安装标记都要读写节点库，而节点库不许在主线程查（SagerDatabase 不开 allowMainThreadQueries）：
            // 确实要做时交给 IO 线程，主线程在这里等它做完——两个库在此之前本来就不能被读，等待照旧。
            // 平常两项都只是一次文件检查，不切线程
            val restoreReplayed = try {
                val journal = RestoreJournal.default
                val result = if (!journal.isPending()) RestoreJournal.Result.NONE else runBlocking(Dispatchers.IO) {
                    BackupRestore.replayPending()
                }
                if (result == RestoreJournal.Result.PENDING && isMainProcess) {
                    Toast.makeText(this, R.string.restore_replay_failed, Toast.LENGTH_LONG).show()
                }
                if (InstallMarker.isMissing()) runBlocking(Dispatchers.IO) {
                    journal.withLock { InstallMarker.ensure() }
                }
                result == RestoreJournal.Result.REPLAYED
            } catch (e: Exception) {
                Logs.w(e)
                false
            }

            assetsDir.mkdirs()
            // before initCore: its asset extraction fills an empty new location from the
            // APK, and the user's own files must win over that
            migrateLegacyAssets()
            Seq.setContext(this)
            val logEnable = DataStore.logLevel > 0
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                assetsDir.absolutePath + "/",
                DataStore.logBufSize,
                logEnable,
                nativeInterface, nativeInterface, LocalResolverImpl
            )
            Logs.enabled = logEnable

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            // both processes edit groups: the UI here, GroupUpdater in :bg
            GroupManager.addListener(SubscriptionUpdater)

            // 恢复重放直接写库、绕过 GroupManager 事件：分组集合可能已变
            // （新增或删光了自动更新订阅），在此补一次调度重排
            if (restoreReplayed) runOnDefaultDispatcher {
                SubscriptionUpdater.reconfigureUpdater()
            }

            // 渠道创建一次即被系统持久化，但全新安装/云恢复后 :bg 可能先于主
            // 进程运行（开机广播、添加 QS tile 都不经过主进程），渠道缺失时
            // 订阅更新进度与 FGS 通知会被系统静默丢弃。:bg 要在 onStartCommand
            // 可能发通知之前建好，只能同步做；主进程仍走下面那次异步调用，
            // 不往冷启动关键路径上加 binder 往返
            if (isBgProcess) updateNotificationChannels()

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        if (isMainProcess) {
            Theme.apply(this)
            Theme.applyNightTheme()
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }

                updateNotificationChannels()

                // 分享出去的备份 JSON 含全部节点凭证，平时由 BackupFragment.onDestroyView
                // 和下一次分享清掉；进程在后台被杀时两者都不会发生，冷启动时补清
                deleteSharedBackups()
            }
        }

        if (BuildConfig.DEBUG) {
            System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    // 一次性迁移 1.7.3 及以前的外部资源。两个进程都可能启动迁移，使用独立临时文件再原子替换。
    // 外部存储未挂载时不置完成标记；以后迁移的旧资源仍覆盖 initCore 曾解压的默认副本。
    private fun migrateLegacyAssets() {
        if (DataStore.legacyAssetsMigrated) return
        val legacy = getExternalFilesDir(null) ?: return
        cleanupStaleLegacyAssetTemps(assetsDir)
        var failed = false
        for (file in legacy.listFiles { it.isFile } ?: emptyArray()) {
            // Two kinds of file do not cross the boundary, because any app holding
            // WRITE_EXTERNAL_STORAGE can write the old directory on Android 10 and
            // below: ca.pem, which would promote a planted root CA into the new trusted
            // location, and the *.version.txt pins, whose "Custom" marker stops libcore
            // from ever re-extracting the APK copy over the file they pin — migrating a
            // planted geoip.db together with its pin would make it permanent. Without
            // the pin the official assets re-extract themselves; a genuinely custom
            // asset is re-imported from AssetsActivity.
            if (file.name == CA_FILE_NAME || file.name.endsWith(".version.txt")) {
                Logs.w("Not migrating ${file.name}: re-import it in route assets if needed")
                continue
            }
            try {
                migrateLegacyAsset(file, File(assetsDir, file.name))
            } catch (e: Exception) {
                // keep the source: the next start retries
                failed = true
                Logs.w("migrate asset ${file.name} failed", e)
            }
        }
        if (!failed) DataStore.legacyAssetsMigrated = true
    }

    // Kept so WorkManager can initialize itself on demand in :bg, where
    // RemoteWorkerService hosts the subscription worker. The schedulers stay in
    // the main process: WorkManager's SystemJobService and ForceStopRunnable are
    // declared there, so naming :bg as the default process only stopped the main
    // process from rescheduling work after a force-stop.
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        // the one file name libcore appends to the root store (nb4a.go InitCore)
        const val CA_FILE_NAME = "ca.pem"

        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    PendingIntent.FLAG_IMMUTABLE
                )
            }
        }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
                notification.createNotificationChannels(
                    listOf(
                        NotificationChannel(
                            "service-vpn",
                            application.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW
                        ),   // #1355
                        NotificationChannel(
                            "service-proxy",
                            application.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW
                        ), NotificationChannel(
                            "service-subscription",
                            application.getText(R.string.service_subscription),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "connection-test",
                            application.getText(R.string.connection_test),
                            NotificationManager.IMPORTANCE_DEFAULT
                        ), NotificationChannel(
                            "service-vpn-request",
                            application.getText(R.string.vpn_permission_required),
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                )
            }
        }

        // Android 12+ throws ForegroundServiceStartNotAllowedException — an
        // IllegalStateException subclass, so the single catch below covers it
        // without an API-gated class reference — when called from the
        // background (BootReceiver on boot), and 8-11 throw a plain
        // IllegalStateException. Losing the start is better than crashing.
        fun startService() = try {
            ContextCompat.startForegroundService(
                application, Intent(application, SagerConnection.serviceClass)
            )
        } catch (e: IllegalStateException) {
            Logs.w(e)
            null
        }

        // manifest 声明了 excludeFromRecents 的界面（alias 随目标）。系统按同一份
        // ActivityInfo.flags 给它们起头的任务打隐藏标志；安装期内不变，进程内只查一次
        private val manifestExcludedActivities by lazy {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
            ).activities.orEmpty()
                .filter { it.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0 }
                .mapTo(HashSet()) { it.name }
        }

        // 作用于本应用的全部任务；标记记在系统的任务记录里，进程重启后仍在。
        // 写 false 时跳过上面那些界面起头的任务：manifest 声明与这里写的是 base intent
        // 上的同一个标志，写 false 会把它们放回最近任务
        fun setExcludeFromRecents(exclude: Boolean) {
            activity.appTasks.forEach {
                try {
                    if (!exclude &&
                        it.taskInfo?.baseIntent?.component?.className in manifestExcludedActivities
                    ) return@forEach
                    it.setExcludeFromRecents(exclude)
                } catch (e: RuntimeException) {
                    // 任务在取列表后已被移除
                    Logs.w(e)
                }
            }
        }

        // fullRestart 见 Action.EXTRA_FULL_RESTART：设置改动要用它，切换节点不用
        fun reloadService(fullRestart: Boolean = false) =
            application.sendBroadcast(
                Intent(Action.RELOAD).setPackage(application.packageName)
                    .putExtra(Action.EXTRA_FULL_RESTART, fullRestart)
            )

        fun stopService() =
            application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        fun notifyTrafficCleared(profileIds: List<Long>) {
            // 每个广播最多携带 32 KiB 的 id，避免大订阅超过 Binder 事务限制。
            profileIds.chunked(4096) { ids ->
                application.sendBroadcast(
                    Intent(Action.CLEAR_TRAFFIC_STATISTICS).setPackage(application.packageName)
                        .putExtra(Action.EXTRA_PROFILE_IDS, ids.toLongArray())
                )
            }
        }

        @Volatile
        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            // 只有 oss 一个 flavor，不再拼 flavor 名
            var n = BuildConfig.VERSION_NAME
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
