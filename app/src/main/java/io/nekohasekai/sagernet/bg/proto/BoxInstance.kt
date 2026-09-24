package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.externalCore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    open lateinit var processes: GuardedProcessPool

    // Written by init/launch on one thread while close() may purge it from
    // another (TestInstance cancellation); a plain ArrayList can CME there and
    // the runCatching in close() would silently skip box.close().
    private val cacheFiles = CopyOnWriteArrayList<File>()

    // Concurrent TestInstances share these directories, so let the filesystem
    // pick the name — a timestamp only makes collisions rarer, not impossible.
    private fun newCacheFile(prefix: String, ext: String, dir: File): File {
        dir.mkdirs()
        return File.createTempFile(prefix + "_", ".$ext", dir).also { cacheFiles.add(it) }
    }

    private fun deleteCacheFiles() {
        cacheFiles.forEach { it.delete() }
        cacheFiles.clear()
    }

    // Plugin configs get their own directory; the files init() hands out via
    // newCacheFile (the hysteria CA) land in cacheDir itself.
    private val pluginConfigDir = File(app.cacheDir, "tmpcfg")

    private fun writeCacheFile(prefix: String, ext: String, content: String): File =
        newCacheFile(prefix, ext, pluginConfigDir).apply { writeText(content) }

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    // TestInstance overrides this to enable mihomo's Clash API for delay self-test.
    protected open fun mihomoTestController(): Pair<Int, String>? = null

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                val core = externalCore(profile.requireBean()) ?: continue
                initPlugin(core.pluginId)
                pluginConfigs[port] = profile.type to core.config(
                    port, { prefix, ext -> newCacheFile(prefix, ext, app.cacheDir) }, mihomoTestController()
                )
            }
        }
        loadConfig()
    }

    override fun launch() {
        // A cancelled TestInstance may reach here after close(): starting the
        // box and plugins now would leak them, so bail out instead.
        if (isClosed()) return

        try {
            for ((chain) in config.externalIndex) {
                for ((port, profile) in chain) {
                    val core = externalCore(profile.requireBean()) ?: continue
                    val config = pluginConfigs[port]?.second ?: ""
                    val launch = core.launch(initPlugin(core.pluginId).path, config, ::writeCacheFile)
                    processes.start(launch.commands, launch.env.toMutableMap())
                }
            }

            box.start()
        } finally {
            // 上面的 isClosed() 守卫与 writeCacheFile/processes.start 不是原子的：
            // TestInstance 的取消落在 launch() 中途时 close() 已清过 cacheFiles，
            // 之后加进来的插件配置文件无人再删（进程与 box 由 close() 兜底，
            // 仅 cacheDir/tmpcfg 残留），这里补清一次。放在 finally：那时 box 已被
            // 关闭，box.start() 会抛错，写在它后面就执行不到
            if (isClosed()) deleteCacheFiles()
        }
    }

    private val closed = AtomicBoolean(false)

    protected fun isClosed(): Boolean {
        return closed.get()
    }

    // TestInstance cancellation can run close() while init() is still
    // working: box is not yet assigned (box.close() skipped) and cache
    // files created afterwards survive deleteCacheFiles. The CAS in
    // close() makes a second close() a no-op, so a caller that finds
    // itself closed right after init() finishes the cleanup here.
    protected fun closeAfterLateInit() {
        deleteCacheFiles()
        if (::box.isInitialized) runCatching { box.close() }
    }

    // Called after close(): joining suspends Main while guard finalizers run.
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
            processes.close(appScope + Dispatchers.IO, ::deleteCacheFiles)
        }.onFailure { Logs.w(it) }.isSuccess
        if (!deleteAfterClose) deleteCacheFiles()

        // gomobile 把 Go 侧错误（DeferPanicToError）转成异常；在
        // stopRunner 的协程里不接住会让 :bg 在关停途中崩溃
        if (::box.isInitialized) runCatching { box.close() }.onFailure { Logs.w(it) }
    }

}
