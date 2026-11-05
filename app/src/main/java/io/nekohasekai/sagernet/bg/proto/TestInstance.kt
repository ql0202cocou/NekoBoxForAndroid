package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.withRealityHint
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.Continuation

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int {
        return suspendCancellableCoroutine { c ->
            // CancellableContinuation hides the ktx tryResume extensions behind
            // its internal members; view it as a plain Continuation instead.
            val cont = c as Continuation<Int>
            processes = GuardedProcessPool {
                Logs.w(it)
                cont.tryResumeWithException(redacted(it))
            }
            c.invokeOnCancellation {
                // The test below runs on appScope and outlives the caller;
                // close the instance so its box and plugin processes don't
                // linger after the caller gave up. close() is idempotent.
                runCatching { close() }
            }
            // Dispatchers.IO, not Default: Libcore.urlTest blocks for seconds, and
            // connectionTestConcurrent would otherwise saturate Default — where
            // TrafficLooper and most of the app's background work also live.
            runOnIoDispatcher {
                try {
                    use {
                        try {
                            // If cancellation won the race before this block ran,
                            // closed is already set and the use {} close() below
                            // is a no-op — starting the box and plugins now would leak
                            // them, so bail out instead.
                            if (isClosed()) throw CancellationException("test cancelled")
                            init()
                            // close() 在 init() 期间跑过：它漏掉的 box 与临时文件已由
                            // BoxInstance.init 补收，这里只需不再启动
                            if (isClosed()) throw CancellationException("test cancelled")
                            // 外核的本机入站就绪后才启动 box（BoxInstance.launch）
                            launch()
                            if (isClosed()) throw CancellationException("test cancelled")
                            cont.tryResume(singBoxUrlTest())
                        } catch (e: Exception) {
                            cont.tryResumeWithException(redacted(e))
                        }
                    }
                } catch (e: Exception) {
                    // use {} rethrows close() failures; the continuation must
                    // still be resumed (a no-op if it already was).
                    cont.tryResumeWithException(redacted(e))
                }
            }
        }
    }

    // 测速的报错会存进节点的测速结果、显示在卡片上，先按本次构建的凭据脱敏（redactErrorText）。文本没变时原样返回；
    // 取消与缺插件保留原类型（调用方按类型识别，消息里没有凭据）。脱敏后的异常不带原异常，免得原文经 cause 流出
    private fun redacted(e: Throwable): Throwable {
        if (e is CancellationException || e is PluginManager.PluginNotFoundException) return e
        val message = e.readableMessage
        val text = redactErrorText(message)
        return if (text == message) e else Exception(text)
    }

    // 经 sing-box 测速；REALITY 握手失败时按构建结果补一句提示（ConfigBuildResult.withRealityHint）
    private fun singBoxUrlTest(): Int = try {
        Libcore.urlTest(box, link, timeout)
    } catch (e: Exception) {
        throw config.withRealityHint(e)
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // 配置里有凭据，写进可导出的日志前脱敏：本次构建的本机 socks 凭据按值遮蔽，其余按键名
        if (BuildConfig.DEBUG) Logs.d(config.loggableConfig())
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
