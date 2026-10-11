package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

class ProxyService : Service(), BaseService.Interface {
    companion object {
        // 模式守卫的占位通知 id，与 VpnService 的授权通知（NOTIFICATION_ID_VPN_REQUEST）分开：
        // 占位是挂上就摘的，复用那个 id 会把正在显示的授权通知一起摘掉
        private const val NOTIFICATION_ID_MODE_GUARD = 1003
    }

    override val data = BaseService.Data(this)
    override val service: Service get() = this
    override val wakeLockTag = "sagernet:proxy"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override fun onBind(intent: Intent) = super.onBind(intent)

    // 与 VpnService 对称的模式守卫：只在仅代理模式下启动。Stopping 窗口内记下的 pendingStart 重放、
    // 系统在模式切换时重启的服务，都可能在 VPN 模式下到达这里；不拦的话会真的拉起代理核心，与 VpnService
    // 抢同一个本地端口。不发权限通知（这里不缺任何权限，只是模式不对）
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_PROXY) {
            return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        Logs.w("ProxyService cannot run (mode=${DataStore.serviceMode}), stopping")
        // startForegroundService 启动的服务不 startForeground 会被系统杀掉（连带 :bg）：先挂一个占位前台通知，
        // 立刻摘掉（同 VpnService 的占位写法）
        val placeholder = NotificationCompat.Builder(this, "service-proxy")
            .setContentTitle(getString(R.string.service_proxy))
            .setSmallIcon(R.drawable.ic_box)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID_MODE_GUARD,
                    placeholder,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID_MODE_GUARD, placeholder)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Logs.w(e)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyRunner()
    }
}
