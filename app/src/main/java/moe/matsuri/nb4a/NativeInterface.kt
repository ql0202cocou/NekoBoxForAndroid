package moe.matsuri.nb4a

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnSerialDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import java.net.InetSocketAddress
import io.nekohasekai.sagernet.bg.ServiceRegistry

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    // :bg 进程里每次出站拨号都会走到这里（libcore 的 UsePlatformAutoDetectInterfaceControl 恒为 true）。
    // 按注册表里正在跑的服务的类型分流，不看 DataStore.serviceMode（设置里的模式可能已被改而旧服务还在跑）：
    //  - 有 VpnService：照旧 protect，失败照旧抛。VPN 模式下 startProcesses 先登记再启动 box，拨号总能看到它
    //  - 没有 VpnService，运行的是 ProxyService（仅代理模式）：没有 tun，套接字本来就走物理网络，没有东西
    //    需要 protect，直接返回
    //  - 两者都没有：照旧抛。服务还没登记（VPN 模式下 VpnService 尚未登记）、或已被注销时，放行会让套接字
    //    进入 tun 回环，所以绝不能 fail-open；失败必须抛，Go 侧才不会把未保护的套接字当已保护
    override fun autoDetectInterfaceControl(fd: Int) {
        val registry = ServiceRegistry
        when (dialProtectAction(registry.vpnService != null, registry.baseService is ProxyService)) {
            DialProtectAction.PROTECT -> {
                val vpn = registry.vpnService ?: throw Exception("no VpnService")
                if (!vpn.protect(fd)) throw Exception("VpnService.protect failed")
            }

            DialProtectAction.SKIP -> return
            DialProtectAction.FAIL -> throw Exception("no VpnService")
        }
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        val vpn = ServiceRegistry.vpnService ?: throw Exception("no VpnService")
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
        // 不能把异常抛过 gomobile 边界：加载失败按未知 uid 处理
        try {
            PackageCache.awaitLoadSync()
        } catch (e: Exception) {
            Logs.w(e)
            return if (uid <= 1000L) "android" else ""
        }

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
        Libcore.resetAllConnections()
        ServiceRegistry.baseService?.apply {
            // 串行调度器：快速连续切换（A→B→C）要按事件顺序结算与落库，Default 池可能先跑后到的那个协程。
            // TrafficLooper.selectMain 在本协程里等落库完成才返回，落库的先后由这里的串行保证
            runOnSerialDispatcher {
                // proxy can be nulled on service stop before this coroutine runs
                val proxy = data.proxy ?: return@runOnSerialDispatcher
                val id = proxy.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = ProfileManager.getProfile(id) ?: return@runOnSerialDispatcher
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

// 出站拨号的 protect 分流（autoDetectInterfaceControl）：已登记 VpnService 一律 protect（VPN 模式，含停止
// 与启动重叠的窗口），否则登记的是 ProxyService 才跳过，其余一律失败
internal enum class DialProtectAction { PROTECT, SKIP, FAIL }

internal fun dialProtectAction(vpnRegistered: Boolean, baseIsProxyService: Boolean): DialProtectAction = when {
    vpnRegistered -> DialProtectAction.PROTECT
    baseIsProxyService -> DialProtectAction.SKIP
    else -> DialProtectAction.FAIL
}
