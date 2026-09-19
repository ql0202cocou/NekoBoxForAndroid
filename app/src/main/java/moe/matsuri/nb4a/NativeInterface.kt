package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnSerialDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        // failure must throw so the Go side (bg dial / protect server) doesn't
        // treat an unprotected socket as protected
        val vpn = DataStore.vpnService ?: throw Exception("no VpnService")
        if (!vpn.protect(fd)) throw Exception("VpnService.protect failed")
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        val vpn = DataStore.vpnService ?: throw Exception("no VpnService")
        return vpn.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        // unknown uid (isolated process, or a race before PackageCache loads):
        // the Go side ignores errors anyway, so don't throw across the gomobile boundary
        return ""
    }

    // connectionInfo is deprecated since API 31 in favour of a NetworkCallback with
    // FLAG_INCLUDE_LOCATION_INFO; sing-box pulls WIFIState() synchronously, so the
    // getter is kept until the callback-based replacement is wired up
    @Suppress("DEPRECATION")
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // null when Wi-Fi is off; the Go side treats empty SSID/BSSID as "not connected"
        val connectionInfo = wifiManager.connectionInfo ?: return ","
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            // serial dispatcher: rapid switches A->B must persist in event
            // order, the Default pool could run B's coroutine before A's
            runOnSerialDispatcher {
                // proxy can be nulled on service stop before this coroutine runs
                val proxy = data.proxy ?: return@runOnSerialDispatcher
                val id = proxy.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnSerialDispatcher
                // persist here too: the binder broadcast below only reaches a
                // bound MainActivity, and an unpersisted selection is rolled
                // back to the stale selectedProxy on the next service reload
                DataStore.selectedProxy = id
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
