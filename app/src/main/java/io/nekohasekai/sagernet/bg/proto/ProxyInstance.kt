package io.nekohasekai.sagernet.bg.proto

import android.widget.Toast
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.configBuildNotices
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util

class ProxyInstance(profile: ProxyEntity, private val service: BaseService.Interface) :
    BoxInstance(profile) {

    // written on the serial dispatcher (NativeInterface selector callback),
    // read on binder threads (Binder.getProfileName)
    @Volatile
    var displayProfileName = ServiceNotification.genTitle(profile)

    // TrafficLooper：launch() 在 Default 上写入；BaseService.persistStats 在主线程、
    // close() 在 IO / Default（killProcesses / destroyRunner）上读取
    @Volatile
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        // 构建失败时也先提示已收集的诊断，再照常抛出
        try {
            super.buildConfig()
        } finally {
            showBuildNotices()
        }
        // 配置里有凭据，写进可导出的日志前脱敏：本次构建的本机 socks 凭据按值遮蔽，其余按键名。
        // 脱敏要对整份配置跑多遍正则，日志关闭时直接跳过
        if (Logs.enabled) Logs.d(Util.redactConfig(config.redactLocalAuth(config.config)))
        if (BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // init 在 :bg 进程的 IO 线程上跑，Toast 要投到主线程
    private fun showBuildNotices() {
        for (notice in configBuildNotices(buildDiagnostics) { id, arg -> app.getString(id, arg) }) {
            runOnMainDispatcher {
                Toast.makeText(app, notice.text, if (notice.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }

    override suspend fun init() {
        super.init()
        // init 跑在 IO 上，destroyRunner 的 close() 可能与它并发：close() 漏掉的 box 与临时文件已由
        // BoxInstance.init 补收，launch() 也会因 isClosed() 直接返回
        if (isClosed()) return
        // 外核配置同样先按值遮蔽本机 socks 凭据，再按键名脱敏
        if (Logs.enabled) externalProcesses.forEach { Logs.d(Util.redactSecrets(config.redactLocalAuth(it.config))) }
    }

    override suspend fun launch() {
        // same guard as BoxInstance.launch: a closed instance must not become the
        // main instance (and start the protect server) on its way out
        if (isClosed()) return
        box.setAsMain()
        // 流量统计服务在 box.start() 之前装上，见 TrafficLooper.statsTags
        if (TrafficLooper.enabled()) {
            box.setV2rayStats(TrafficLooper.statsTags(config))
        }
        super.launch() // start box
        runOnDefaultDispatcher {
            // The service may have stopped before this block runs; creating a
            // looper now would spin on an already closed box.
            if (isClosed()) return@runOnDefaultDispatcher
            val trafficLooper = TrafficLooper(service.data)
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
        // 顺序契约：调用方（killProcesses / destroyRunner）先挂起等 stopLoop() 再 close()——
        // 进行中的 queryStats 需要存活的 box，close() 不能阻塞等它。
        // 兜底 launch() 启动块整段落在调用方读 looper 与这里之间的竞态：先置 closed 再读
        // looper（与启动块「写 looper → 读 closed」相反），两边必有一方看到对方。只停循环
        // 不做最终推送：重启后 data.proxy 可能已是新实例，flushStats 会把计数写到新实例的
        // 节点上。box 已关时 queryStats 经 lockIfOpen 返回 0
        super.close()
        val trafficLooper = looper ?: return
        looper = null
        runOnDefaultDispatcher { trafficLooper.stopLoop() }
    }

}
