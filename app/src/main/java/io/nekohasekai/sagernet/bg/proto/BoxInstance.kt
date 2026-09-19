package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildMihomoConfig
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
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            newCacheFile("hysteria", "ca", app.cacheDir)
                        }
                    }

                    is VMessBean -> {
                        initPlugin("xray-plugin")
                        pluginConfigs[port] = profile.type to buildXrayConfig(bean, port)
                    }

                    is AnyTLSBean -> {
                        initPlugin("mihomo-plugin")
                        val controller = mihomoTestController()
                        pluginConfigs[port] = profile.type to buildMihomoConfig(
                            bean, port, controller?.first, controller?.second ?: ""
                        )
                    }
                }
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
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    bean is TrojanGoBean -> {
                        val configFile = writeCacheFile("trojan_go", "json", config)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = writeCacheFile("mieru", "json", config)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = writeCacheFile("naive", "json", config)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = writeCacheFile("naive", "crt", bean.certificates)
                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = writeCacheFile("hysteria", "json", config)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }

                    bean is VMessBean -> {
                        val configFile = writeCacheFile("xray", "json", config)

                        val commands = mutableListOf(
                            initPlugin("xray-plugin").path, "run", "-c", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is AnyTLSBean -> {
                        val configFile = writeCacheFile("mihomo", "yaml", config)

                        val commands = mutableListOf(
                            initPlugin("mihomo-plugin").path,
                            "-d", app.noBackupFilesDir.absolutePath,
                            "-f", configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                }
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
