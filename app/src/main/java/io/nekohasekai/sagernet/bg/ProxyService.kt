package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val service: Service get() = this
    override val wakeLockTag = "sagernet:proxy"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override fun onDestroy() {
        super.onDestroy()
        destroyRunner()
    }
}
