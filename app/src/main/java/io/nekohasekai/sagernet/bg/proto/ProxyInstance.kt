package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
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
        super.buildConfig()
        // configs contain credentials; redact them before writing to the exportable log.
        // 脱敏要对整份配置跑多遍正则，日志关闭时直接跳过
        if (Logs.enabled) Logs.d(Util.redactSecrets(Util.redactDnsServerPaths(config.config)))
        if (BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    override suspend fun init() {
        super.init()
        // init 跑在 IO 上，destroyRunner 的 close() 可能与它并发：box 还没赋值时
        // close() 跳过了它，这里补收，launch() 也会因 isClosed() 直接返回
        if (isClosed()) {
            closeAfterLateInit()
            return
        }
        if (Logs.enabled) pluginConfigs.values.forEach { Logs.d(Util.redactSecrets(it)) }
    }

    override fun launch() {
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
        // looper 由调用方在此之前停掉：进行中的 queryStats 需要存活的 box，而
        // close() 不能挂起等它；在这里阻塞等待会让调用线程卡在进入 JNI 后无法
        // 取消的统计上。
        // 顺序契约：调用方必须先停 looper 再 close()——killProcesses 先挂起等
        // stopLoop()，destroyRunner 在后台协程里同样先等 stopLoop()。新调用方
        // 不得绕过这一顺序直接调 close()
        // 竞态兜底：launch() 的启动块可能整段落在调用方读 looper 之后、这里置 closed
        // 之前——调用方读到 null 没停，块内的 isClosed() 复查也还是 false。所以先置
        // closed 再读 looper（与启动块「写 looper → 读 closed」相反），两边必有一方看到
        // 对方。正常路径读到的是调用方已停的 looper，stopLoop 的 CAS 直接返回。
        // 只停循环不做最终推送：重启后 data.proxy 可能已是新实例，flushStats 会把
        // 泄漏循环的计数写到新实例的节点上。box 此时已关，queryStats 经 lockIfOpen
        // 返回 0，不需要存活的 box
        super.close()
        val trafficLooper = looper ?: return
        looper = null
        runOnDefaultDispatcher { trafficLooper.stopLoop() }
    }

}
