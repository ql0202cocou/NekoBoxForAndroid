package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    // 写于 buildConfig（主线程），读于 canReloadSelector（binder/Default 线程）
    @Volatile
    var lastSelectorGroupId = -1L

    // written on the serial dispatcher (NativeInterface selector callback),
    // read on binder threads (Binder.getProfileName)
    @Volatile
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper (written on the Default dispatcher in launch(),
    // read on the main thread in BaseService.persistStats and close())
    @Volatile
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        // configs contain credentials; redact them before writing to the exportable log
        if (notTmp) Logs.d(Util.redactSecrets(config.config))
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(Util.redactSecrets(content))
        }
    }

    override fun launch() {
        // same guard as BoxInstance.launch: a closed instance must not become the
        // main instance (and start the protect server) on its way out
        if (isClosed()) return
        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            // The service may have stopped before this block runs; creating a
            // looper now would spin on an already closed box.
            if (isClosed()) return@runOnDefaultDispatcher
            val trafficLooper = service?.let { TrafficLooper(it.data) }
                ?: return@runOnDefaultDispatcher
            looper = trafficLooper
            trafficLooper.start()
            if (isClosed()) {
                // close() ran between the check and start(); it saw a null
                // looper and skipped stopping it, so stop it here.
                looper = null
                trafficLooper.stop()
            }
        }
    }

    override fun close() {
        // The looper is cancelled by BaseService.killProcesses before this runs: its
        // in-flight queryStats needs a live box, and close() cannot suspend to wait for
        // one. Blocking here instead would park :bg's main thread on a stats sweep that
        // cancellation cannot interrupt once it is inside its JNI calls.
        // 顺序契约：调用方必须先停 looper 再 close()——killProcesses 先挂起等
        // stopLoop()，destroyRunner 先 post stopLoop()。新调用方不得绕过这一
        // 顺序直接调 close()
        looper = null
        super.close()
    }

}
