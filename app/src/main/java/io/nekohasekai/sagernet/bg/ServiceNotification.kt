package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags = PendingIntent.FLAG_IMMUTABLE

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    // written on the main thread, read by TrafficLooper on Dispatchers.Default
    @Volatile
    var listenPostSpeed = true

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        useBuilder {
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setContentText(speedSimple)
            }
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            it.setContentTitle(newTitle)
        }
        update()
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        }
        update()
    }

    private val showDirectSpeed = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_service_active)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(androidx.appcompat.R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        useBuilder {
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 0, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.reset_connections),
                PendingIntent.getBroadcast(
                    service, 0,
                    // explicit like Action.CLOSE above: an implicit broadcast would
                    // also reach any other app listening for this action
                    Intent(Action.RESET_UPSTREAM_CONNECTIONS).setPackage(service.packageName),
                    flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (service.data.state == BaseService.State.Connected) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
        }
    }


    private suspend fun show() =
        useBuilder {
            val notification = it.build()
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // systemExempted is only granted to a VPN app through the OP_ACTIVATE_VPN
                    // app-op, which ProxyService never holds: the platform would answer it with
                    // a SecurityException and the service would silently degrade to a background one
                    val type = if (service is VpnService) FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    else FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    (service as Service).startForeground(notificationId, notification, type)
                } else {
                    (service as Service).startForeground(notificationId, notification)
                }
            } catch (e: Exception) {
                // the service cannot survive in the background without this;
                // keep it out of the log-less Toast-only path
                Logs.w(e)
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // stopRunner destroys the notification while Go callback threads may
    // still be queueing title/speed posts; re-posting after destroy would
    // revive it as a ghost notification
    private val destroyed = AtomicBoolean(false)

    // POST_NOTIFICATIONS is requested in MainActivity; when denied, notify() is
    // silently dropped by the system — no SecurityException to handle
    @SuppressLint("MissingPermission")
    private suspend fun update() = useBuilder {
        if (destroyed.get()) return@useBuilder
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        // update() checks `destroyed` under buildLock on Go callback threads;
        // take the same lock so a notify cannot slip in between the flag
        // check and stopForeground. Called on the main thread; the lock is
        // never held across a suspension, so runBlocking cannot deadlock.
        val service = service as Service
        runBlocking {
            buildLock.withLock {
                destroyed.set(true)
                listenPostSpeed = false
                ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
        service.unregisterReceiver(this)
    }
}
