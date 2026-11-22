package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import android.widget.Toast
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RunGeneration
import io.nekohasekai.sagernet.database.ServiceRestoreLock
import io.nekohasekai.sagernet.fmt.configBuildNotices
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.utils.JavaUtil
import java.io.Closeable

// title：ServiceNotification.genTitle(profile) 的结果。它可能查分组名（读库），由调用方在后台算好传进来，
// 构造本身不查库（BaseService 在 :bg 主线程上构造）
class ProxyInstance(profile: ProxyEntity, private val service: BaseService.Interface, title: String, internal val generation: RunGeneration) :
    BoxInstance(profile) {

    // written on the serial dispatcher (NativeInterface selector callback),
    // read on binder threads (Binder.getProfileName)
    @Volatile
    var displayProfileName = title

    // 选择回调可能早于启动后的首次采样。每个实例从构造起接收切换，launch 成功后才启动循环。
    @Volatile
    var looper: TrafficLooper? = TrafficLooper(service.data, this)
        private set

    internal fun acceptsSelectorCallback(): Boolean = isInitialized() && !isClosed()

    override fun buildConfig() {
        super.buildConfig()
        // 配置里有凭据，写进可导出的日志前脱敏：本次构建的本机 socks 凭据按值遮蔽，其余按键名。
        // 脱敏要对整份配置跑多遍正则，日志关闭时直接跳过
        if (Logs.enabled) Logs.d(config.loggableConfig())
        // 统计关联只记 tag → 节点 id，不含节点数据
        if (BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.traffic.tags))
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
        // 插件冲突也可能在组装外核时发现。即使初始化失败，已收集的诊断也只在运行入口提示一次。
        try {
            super.init()
        } finally {
            showBuildNotices()
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
        // 在外核校验前记录当次组装的配置，校验拒绝时也能用同一份脱敏日志定位。
        if (Logs.enabled && !isClosed()) externalProcesses.forEach { Logs.d(config.loggableExternalConfig(it.config)) }
    }

    override suspend fun launch() {
        // same guard as BoxInstance.launch: a closed instance must not become the
        // main instance (and start the protect server) on its way out
        if (isClosed()) return
        box.setAsMain()
        // 先安装核心计数器并建账。选择器开始接收切换时，就能把已有流量结算给旧成员。
        if (TrafficLooper.enabled()) {
            box.setV2rayStats(TrafficLooper.statsTags(config), TrafficLooper.ignoredInbounds(config))
            looper?.initializeAccounting(config, box::queryStats, SystemClock.elapsedRealtime())
        }
        super.launch() // start box
        if (isClosed()) return
        val trafficLooper = looper ?: return
        trafficLooper.start()
        if (isClosed()) trafficLooper.stop()
    }

    private val shutdownMutex = Mutex()

    private var runLease: Closeable? = null
    private var stopping = false

    @Synchronized
    internal fun acquireRunLease(): Boolean {
        if (stopping || isClosed()) return false
        if (runLease == null) runLease = ServiceRestoreLock.default.tryAcquire(forService = true)
        return runLease != null
    }

    // 首个关停入口接手文件锁；直接关闭和带流量结算的关闭都可能先到。
    @Synchronized
    private fun takeRunLeaseForClose(): Closeable? {
        stopping = true
        return runLease.also { runLease = null }
    }

    suspend fun closeWithTraffic(): Unit = onIoDispatcher {
        shutdownMutex.withLock {
            takeRunLeaseForClose().use {
                looper?.let { traffic ->
                    try {
                        traffic.stopLoop()
                        traffic.collectFinal()
                    } catch (e: Exception) {
                        Logs.w("Final traffic collection failed", e)
                    } finally {
                        close()
                    }
                    // 最终查询失败时，先前采样的增量仍应在释放恢复锁之前落库。
                    try {
                        traffic.postFinalTraffic()
                    } catch (e: Exception) {
                        Logs.w("Final traffic persistence failed", e)
                    }
                }
                awaitProcessesClosed()
            }
        }
    }

    override fun close() {
        // 服务关停由 closeWithTraffic 等采样完成；直接 close 时也要兜底停止循环。
        takeRunLeaseForClose().use {
            super.close()
            val trafficLooper = looper ?: return
            looper = null
            trafficLooper.stop()
        }
    }

}
