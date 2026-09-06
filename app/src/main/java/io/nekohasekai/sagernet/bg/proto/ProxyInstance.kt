package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
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

    override suspend fun loadConfig() {
        super.loadConfig()
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
            val trafficLooper = service?.let { TrafficLooper(it.data, this) }
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
        // stop the looper first: its in-flight queryStats needs a live box. This
        // blocks the caller, which is :bg's main thread (stopRunner -> killProcesses),
        // so the wait has to stay bounded: one stats sweep to cancel the loop, then a
        // single transaction for the final counters and a oneway binder broadcast.
        try {
            runBlocking {
                looper?.stop()
            }
        } finally {
            looper = null
            super.close()
        }
    }

}
