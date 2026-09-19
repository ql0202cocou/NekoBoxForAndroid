package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
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

        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")

        fun writeCacheFile(prefix: String, ext: String, content: String): File {
            return newCacheFile(prefix, ext, cacheDir).apply { writeText(content) }
        }

        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                val core = externalCore(profile.requireBean()) ?: continue
                val config = pluginConfigs[port]?.second ?: ""
                val launch = core.launch(initPlugin(core.pluginId).path, config, ::writeCacheFile)
                processes.start(launch.commands, launch.env.toMutableMap())
            }
        }

        box.start()
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        // Stop the plugin processes before deleting their config files: the
        // Job returned by GuardedProcessPool.close completes once every guard
        // looper has exited, so only then is no guard left to restart a
        // plugin whose config file is already gone. Hook the deletion onto it
        // instead of joining here — close() may run on the main thread, where
        // joining would deadlock the loopers' Main-dispatched cleanup.
        if (::processes.isInitialized) {
            processes.close(appScope + Dispatchers.IO).invokeOnCompletion {
                deleteCacheFiles()
            }
        } else {
            deleteCacheFiles()
        }

        // gomobile turns a Go-side error (DeferPanicToError) into an exception;
        // uncaught in stopRunner's coroutine it would crash :bg mid-teardown
        if (::box.isInitialized) {
            runCatching { box.close() }.onFailure { Logs.w(it) }
        }
    }

}
