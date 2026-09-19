package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import java.io.IOException
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"

        // separate from ServiceNotification (1), SubscriptionUpdater (2) and
        // ConnectionTestNotification (1001) so none of them replaces it
        const val NOTIFICATION_ID_VPN_REQUEST = 1002

    }

    // written by gomobile Go threads in startVpn, read/closed by the main
    // thread in killProcesses
    @Volatile
    var conn: ParcelFileDescriptor? = null

    // ParcelFileDescriptor.close throws IOException; don't let it escape and
    // skip the rest of the caller's cleanup.
    private fun ParcelFileDescriptor.closeQuietly() = try {
        close()
    } catch (e: IOException) {
        Logs.w(e)
    }

    private var metered = false

    override suspend fun startProcesses() {
        ServiceRegistry.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        conn?.closeQuietly()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val service: Service get() = this
    override val wakeLockTag = "sagernet:vpn"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    // POST_NOTIFICATIONS is requested in MainActivity; when denied, notify() is
    // silently dropped by the system — no SecurityException to handle
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val vpnMode = DataStore.serviceMode == Key.MODE_VPN
        if (vpnMode && prepare(this) == null) {
            return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        // Reaching here means the VPN cannot run: permission was revoked, or the
        // service was started while not in VPN mode (a restart racing a mode
        // switch). Log it — this stop is silent otherwise.
        Logs.w("VpnService cannot run (mode=${DataStore.serviceMode}), stopping")
        val notification = if (vpnMode) {
            // VPN permission was revoked; starting an activity from the
            // background is silently blocked since Android 10, so ask
            // via a notification instead.
            NotificationCompat.Builder(this, "service-vpn-request")
                .setContentTitle(getString(R.string.service_vpn))
                .setContentText(getString(R.string.vpn_permission_required))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, VpnRequestActivity::class.java),
                        ServiceNotification.flags
                    )
                )
                .setSmallIcon(R.drawable.ic_service_active)
                .setAutoCancel(true)
                .build()
        } else {
            // placeholder, removed again below
            NotificationCompat.Builder(this, "service-vpn")
                .setContentTitle(getString(R.string.service_vpn))
                .setSmallIcon(R.drawable.ic_service_active)
                .build()
        }
        // Started via startForegroundService(): not calling startForeground()
        // gets :bg killed at the stopSelf() of the stopRunner() below, and an
        // activity still holding a binding does not clear that. Post the request
        // as the foreground notification, then detach it so it outlives us; the
        // placeholder is removed instead. specialUse, not systemExempted: the
        // VPN app-op may be missing here.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID_VPN_REQUEST,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID_VPN_REQUEST, notification)
            }
            ServiceCompat.stopForeground(
                this,
                if (vpnMode) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE
            )
        } catch (e: Exception) {
            Logs.w(e)
            if (vpnMode) NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID_VPN_REQUEST, notification
            )
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException() {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        // read once: proxy is cleared on the main thread during shutdown
        val proxy = data.proxy ?: error("proxy not started")
        val needBypassRootUid = workaroundSYSTEM || proxy.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        val c = builder.establish() ?: throw NullConnectionException()
        // killProcesses (main thread) may null conn between establish() and
        // the fd read below; keep a local reference
        conn?.closeQuietly() // a previous openTun lost the race against killProcesses
        conn = c
        // killProcesses may already have run while establish() blocked: the
        // service is stopping and nothing would ever close this fd. Don't
        // hand the dead fd number back to the Go side either.
        if (!data.state.started) {
            c.closeQuietly()
            conn = null
            throw IOException("service is stopping")
        }

        // the VPN is up, so the permission-request notification is obsolete
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_VPN_REQUEST)

        return c.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        SagerNet.underlyingNetwork?.let {
            builder?.setUnderlyingNetworks(arrayOf(it))
                ?: setUnderlyingNetworks(arrayOf(it))
        }
    }

    // A system-side revoke (another VPN taking the slot, OEM VPN features,
    // permission withdrawal) is otherwise a zero-trace stop: the exported log
    // shows a clean teardown with no cause, like a user-initiated stop.
    override fun onRevoke() {
        Logs.w("VPN revoked by system")
        stopRunner(false, getString(R.string.vpn_revoked))
    }

    override fun onDestroy() {
        ServiceRegistry.vpnService = null
        super.onDestroy()
        destroyRunner()
    }
}