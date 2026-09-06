package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StrictMode
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isOss
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
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
            assetsDir.mkdirs()
            // before initCore: its asset extraction fills an empty new location from the
            // APK, and the user's own files must win over that
            migrateLegacyAssets()
            Seq.setContext(this)
            Libcore.initCore(
                process,
                cacheDir.absolutePath + "/",
                filesDir.absolutePath + "/",
                assetsDir.absolutePath + "/",
                DataStore.logBufSize,
                DataStore.logLevel > 0,
                nativeInterface, nativeInterface, LocalResolverImpl
            )

            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

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

    // One-time move of the external assets dir used up to 1.7.3. Both processes run this at
    // start, so copy through a per-process temp file and rename; the legacy copy
    // overwrites whatever initCore may have extracted on a start where external storage
    // was not mounted yet (then the flag stays unset and the move is retried).
    private fun migrateLegacyAssets() {
        if (DataStore.legacyAssetsMigrated) return
        val legacy = getExternalFilesDir(null) ?: return
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
                val tmp = File(assetsDir, "${file.name}.migrating-${Process.myPid()}")
                file.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(File(assetsDir, file.name))) {
                    tmp.delete()
                    error("rename failed")
                }
                file.delete()
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

        fun startService() = ContextCompat.startForegroundService(
            application, Intent(application, SagerConnection.serviceClass)
        )

        fun reloadService() =
            application.sendBroadcast(Intent(Action.RELOAD).setPackage(application.packageName))

        fun stopService() =
            application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        // see TrafficLooper.clearStats; a no-op when nothing is running
        fun clearTrafficStatistics(profileIds: LongArray) = application.sendBroadcast(
            Intent(Action.CLEAR_TRAFFIC_STATISTICS).setPackage(application.packageName)
                .putExtra(Action.EXTRA_PROFILE_IDS, profileIds)
        )

        @Volatile
        var underlyingNetwork: Network? = null

        var appVersionNameForDisplay = {
            var n = BuildConfig.VERSION_NAME
            if (isPreview) {
                n += " " + BuildConfig.PRE_VERSION_NAME
            } else if (!isOss) {
                n += " ${BuildConfig.FLAVOR}"
            }
            if (BuildConfig.DEBUG) {
                n += " DEBUG"
            }
            n
        }()
    }

}
