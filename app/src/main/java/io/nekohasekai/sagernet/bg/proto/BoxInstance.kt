package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.LocalPortTarget
import io.nekohasekai.sagernet.bg.awaitLocalPorts
import io.nekohasekai.sagernet.bg.localReadinessSummary
import io.nekohasekai.sagernet.bg.probeWrongPassword
import io.nekohasekai.sagernet.bg.runCheckProcess
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.recordPluginConflict
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ExternalCheckResult
import io.nekohasekai.sagernet.fmt.ExternalCoreCheck
import io.nekohasekai.sagernet.fmt.ExternalCoreGroup
import io.nekohasekai.sagernet.fmt.ExternalCoreProcess
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.HopReadiness
import io.nekohasekai.sagernet.fmt.assemble
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.externalAuthOutcome
import io.nekohasekai.sagernet.fmt.externalReadyOutcome
import io.nekohasekai.sagernet.fmt.localAuthCheckHops
import io.nekohasekai.sagernet.fmt.requireLocalAuth
import io.nekohasekai.sagernet.fmt.withBoxErrorProfileName
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.plus
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.Util
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    // 与 closed 组成双向交接：init 发布 box 后检查 closed，close 先置 closed 再读 box。
    // 只把 closed 设为原子变量不能发布另一个线程写的 box，两边仍可能都跳过回收。
    @Volatile
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()

    // 外核运行计划与按它组装出的外核进程（Xray 每组一个，插件核心每个跳实例一个）。
    // init 生成并校验，launch 逐个启动
    lateinit var externalPlan: ExternalRunPlan
        private set
    var externalProcesses: List<ExternalCoreProcess> = emptyList()
        private set
    open lateinit var processes: GuardedProcessPool

    private val cacheFiles = InstanceConfigFiles()
    private val redactExternalOutput by lazy {
        config.externalOutputRedactor(externalProcesses.map { it.config })
    }

    private fun writeCacheFile(prefix: String, ext: String, content: String): File =
        cacheFiles.write(app.externalConfigDir) { newFile -> newFile(prefix, ext).apply { writeText(content) } }

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    // 报错文本按本次构建的凭据脱敏（ConfigBuildResult.redactErrorText，外核配置取已组装的这几份）；
    // 构建没完成时没有可按值遮的配置，只按键名脱敏
    fun redactErrorText(text: String): String =
        if (::config.isInitialized) config.redactErrorText(text, externalProcesses.map { it.config })
        else Util.redactSecrets(text)

    // 异常链与 suppressed 也可能带配置值；传给日志的只有处理过的字符串。
    internal suspend fun reportServiceError(error: Throwable): String = onIoDispatcher {
        runCatching {
            val redact = if (::config.isInitialized) redactExternalOutput else {
                val values = Util.secretValueRedactor(listOfNotNull(profile.configBean?.config))
                fun(text: String) = values(Util.redactSecrets(text))
            }
            val reason = redact(error.readableMessage)
            if (Logs.enabled) {
                Logs.w(if (error.javaClass.name.endsWith("proxyerror")) reason else redact(error.stackTraceToString()))
            }
            reason
        }.getOrElse {
            "Service error (details unavailable)".also { message -> Logs.w(message) }
        }
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name, buildDiagnostics::recordPluginConflict)!! }
    }

    // 构建诊断的收集器：构建抛异常时这里仍有已收集的部分。只有运行路径
    // （ProxyInstance）把它显示给用户
    protected val buildDiagnostics = ArrayList<ConfigBuildDiagnostic>()

    protected open fun buildConfig() {
        config = buildConfig(profile, diagnostics = buildDiagnostics)
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    // 每跑完一组的启动前校验调用一次（含没过与没有结论的），在没过时抛出之前。debug 采集入口据此计数
    protected open fun onExternalCheck(group: ExternalCoreGroup, result: ExternalCheckResult) {}

    open suspend fun init() {
        try {
            buildConfig()
            val plan = ExternalRunPlan.from(config)
            externalPlan = plan
            // 设置用构建时采集的那份
            externalProcesses = if (plan.hops.isEmpty()) emptyList() else cacheFiles.write(app.externalConfigDir) { newFile ->
                plan.assemble(newFile, config.requireExternalCoreSettings(), beforeHop = { initPlugin(it.pluginId) })
            }
            // ProxyInstance / TestInstance 的 loadConfig 都在这里收口
            try {
                loadConfig()
            } catch (e: Exception) {
                throw config.withBoxErrorProfileName(e)
            }
            checkExternalCores()
        } finally {
            // close() 可能早于 box 赋值；init 结束时再接手这个较晚创建的核心。
            if (isClosed() && ::box.isInitialized) runCatching { box.close() }
        }
    }

    // 只有内置的 Xray 有核实过的校验入口与就绪握手；外部插件 app 提供的版本与插件核心返回 null
    private fun verifiedCheck(process: ExternalCoreProcess): ExternalCoreCheck? =
        process.group.core.check?.takeIf { initPlugin(process.group.pluginId).builtin }

    // 启动前校验：起任何外核进程之前，用核心的校验入口把每组合并配置加载一遍，
    // 没过就不启动，报错经运行计划对回节点。这一轮不剔除坏节点：选中的节点与未选中的成员都是整体失败。
    // 校验用的配置文件校验完即删；实例被关闭或协程被取消时校验进程立即被杀
    private suspend fun checkExternalCores() {
        for (process in externalProcesses) {
            val check = verifiedCheck(process) ?: continue
            val group = process.group
            val startedAt = SystemClock.elapsedRealtime()
            val files = ArrayList<File>()
            val result = try {
                val launch = check.command(initPlugin(group.pluginId).path, process.config) { prefix, ext, content ->
                    writeCacheFile(prefix, ext, content).also { files.add(it) }
                }
                val run = runCheckProcess(launch, app.noBackupFilesDir, CHECK_TIMEOUT_MS, ::isClosed, redactExternalOutput)
                check.result(group, run.exitCode, run.output)
            } catch (e: IOException) {
                // 进程起不来：启动时同样会失败，照旧交给启动路径报错
                ExternalCheckResult.Inconclusive("${check.coreName} config check could not run: ${redactExternalOutput(e.readableMessage)}")
            } finally {
                files.forEach(cacheFiles::delete)
            }
            onExternalCheck(group, result)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when (result) {
                is ExternalCheckResult.Passed ->
                    Logs.i("${group.pluginId}: config of ${group.hops.size} hops checked in $elapsed ms")

                is ExternalCheckResult.Inconclusive ->
                    Logs.w("${result.reason} after $elapsed ms; starting without the check")

                is ExternalCheckResult.Failed -> throw result.error
            }
        }
    }

    override suspend fun launch() {
        // 已被取消的 TestInstance 可能在 close() 之后才走到这里：
        // 此时再启动 box 和插件会泄漏它们，所以直接返回。
        if (isClosed()) return

        val targets = ArrayList<Pair<ExternalHop, LocalPortTarget>>()
        val context = currentCoroutineContext()
        for (process in externalProcesses) {
            context.ensureActive()
            if (isClosed()) return
            val launch = process.launch(
                initPlugin(process.group.pluginId).path, ::writeCacheFile, config.requireExternalCoreSettings()
            )
            context.ensureActive()
            if (isClosed()) return
            val check = verifiedCheck(process)
            val processState = processes.start(launch.commands, launch.env) { line ->
                val safe = redactExternalOutput(line)
                check?.logLine(safe) ?: safe
            }
            val strict = check != null
            for (hop in process.group.hops) {
                targets += hop to LocalPortTarget(hop.localPort, strict, hop.localAuth, processState)
            }
        }
        awaitExternalCores(targets)
        // 等待刚结束时被关闭：外核进程已由 close() 停掉，不再启动 box
        if (isClosed()) return
        checkExternalAuth()
        context.ensureActive()
        if (isClosed()) return

        try {
            box.start()
        } catch (e: Exception) {
            throw config.withBoxErrorProfileName(e)
        }
    }

    // 启动就绪：外核进程起来之后、box.start() 之前，等计划里每个跳实例的本机入站就绪。
    // 内置 Xray 逐个端口用跳实例的本机 socks 凭据完成 SOCKS5 用户名 / 密码握手，等不到或守护放弃
    // 进程就失败（进程退出后被守护重启不算失败，等监听回来）；其余核心只等端口能连上，等不到记警告照常启动。
    // 实例被关闭或协程被取消时立即停止等待（抛 CancellationException）
    private suspend fun awaitExternalCores(targets: List<Pair<ExternalHop, LocalPortTarget>>) {
        if (targets.isEmpty()) return
        val startedAt = SystemClock.elapsedRealtime()
        val statuses = awaitLocalPorts(targets.map { it.second }, READY_TIMEOUT_MS, ::isClosed)
        val outcome = externalReadyOutcome(
            targets.zip(statuses) { (hop, target), status ->
                HopReadiness(hop, target.strict, status.ready, status.exitCode, status.lastError, status.abandoned)
            },
            READY_TIMEOUT_MS,
        )
        outcome.warnings.forEach { Logs.w(it) }
        outcome.failure?.let { throw it }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Logs.i(localReadinessSummary(statuses, elapsed))
    }

    // 认证检查（K0b）：入站全部就绪之后，对每个内置 Xray 进程抽第一个跳实例，用正确的用户名与错误的
    // 密码握手一次。错误的密码被接受就不启动（入站的认证没有生效）；没有明确拒绝的记警告照常启动。
    // 实例被关闭或协程被取消时立即停止（抛 CancellationException）
    private suspend fun checkExternalAuth() {
        val hops = localAuthCheckHops(externalProcesses) { verifiedCheck(it) != null }
        if (hops.isEmpty()) return
        val startedAt = SystemClock.elapsedRealtime()
        val results = hops.map { hop ->
            hop to probeWrongPassword(hop.localPort, hop.requireLocalAuth(), AUTH_CHECK_TIMEOUT_MS, ::isClosed)
        }
        val outcome = externalAuthOutcome(results)
        outcome.warnings.forEach { Logs.w(it) }
        outcome.failure?.let { throw it }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Logs.i("external cores: local authentication of ${hops.size} processes checked in $elapsed ms")
    }

    private val closed = AtomicBoolean(false)

    protected fun isClosed(): Boolean {
        return closed.get()
    }

    // 在 close() 之后调用：join 会在守护的收尾逻辑运行期间挂起 Main。
    suspend fun awaitProcessesClosed() {
        if (::processes.isInitialized) processes.coroutineContext[Job]?.join()
    }

    // 从不抛出：killProcesses / destroyRunner / TestInstance 的取消回调都不能
    // 让关停中途的异常逃出去（分别会崩溃 :bg、带上 onDestroy、违反取消回调契约）
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        // 先停插件进程再删它们的配置文件：onClosed 在每个 guard looper 退出后
        // 才在 IO 上跑，此刻才不会有 guard 重启一个配置文件已被删掉的插件。
        // processes.close() 只有 OOM 级的抛异常路径，抛了回调没挂上，直接补删
        val deleteAfterClose = ::processes.isInitialized && runCatching {
            processes.close(appScope + Dispatchers.IO, cacheFiles::close)
        }.onFailure { Logs.w(it) }.isSuccess
        if (!deleteAfterClose) cacheFiles.close()

        // gomobile 把 Go 侧错误（DeferPanicToError）转成异常；在
        // stopRunner 的协程里不接住会让 :bg 在关停途中崩溃
        if (::box.isInitialized) runCatching { box.close() }.onFailure { Logs.w(it) }
    }

    companion object {
        // 校验 200 个节点的合并配置在模拟器上不到 30 毫秒；留足低端机冷启动二进制的余量。超时不算配置有错，
        // 照旧启动（与没有校验时相同）
        private const val CHECK_TIMEOUT_MS = 10_000L

        // 模拟器上外核起来后几十毫秒内各入站就绪；5 秒约为百倍余量，覆盖低端机冷启动。进程退出会立即报错，
        // 这个时限只在进程活着却不监听（例如端口被占用但只记错误日志）时才会等满
        private const val READY_TIMEOUT_MS = 5_000L

        // 认证检查一次握手的读时限：Xray 拒绝时立即应答并断开，只有异常情况才会等满；比就绪探测宽，
        // 免得负载高时把还没来得及的应答当成没有被接受
        private const val AUTH_CHECK_TIMEOUT_MS = 1_000L
    }

}
