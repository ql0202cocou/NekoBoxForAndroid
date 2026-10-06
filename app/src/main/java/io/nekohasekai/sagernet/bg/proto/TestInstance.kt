package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.withRealityHint
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.Util
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    // mihomo measures the full handshake (TCP + TLS + auth + HEAD, plus the
    // server-domain DNS), while the sing-box path measures a warm RTT. Give it
    // double the budget so slow-but-working nodes don't report a timeout.
    private val mihomoTimeout = timeout * 2

    // 单节点的 AnyTLS 走 mihomo 时开 mihomo 的 Clash API，让 mihomo 自己经代理测延迟；链上的节点仍走 sing-box。
    // 条件由构建按它采集的分组行与设置算出（ConfigBuildResult.delayTestOnMihomo），所以只能在 buildConfig 之后取
    private val mihomoController: Pair<Int, String>? by lazy {
        if (!config.delayTestOnMihomo) return@lazy null
        mkPort() to UUID.randomUUID().toString().replace("-", "")
    }

    override fun mihomoTestController(): Pair<Int, String>? = mihomoController

    companion object {
        // One dispatcher and connection pool for every test: a fresh OkHttpClient per
        // node keeps its own idle threads and pooled sockets alive for a minute.
        // newBuilder() below shares both while overriding the timeouts.
        private val sharedHttpClient by lazy { OkHttpClient() }
    }

    suspend fun doTest(): Int {
        return suspendCancellableCoroutine { c ->
            // CancellableContinuation hides the ktx tryResume extensions behind
            // its internal members; view it as a plain Continuation instead.
            val cont = c as Continuation<Int>
            processes = GuardedProcessPool {
                Logs.w(it)
                cont.tryResumeWithException(it)
            }
            c.invokeOnCancellation {
                // The test below runs on appScope and outlives the caller;
                // close the instance so its box and plugin processes don't
                // linger after the caller gave up. close() is idempotent.
                runCatching { close() }
            }
            // Dispatchers.IO, not Default: Libcore.urlTest and mihomoDelay block for
            // seconds, and connectionTestConcurrent of them would saturate Default —
            // where TrafficLooper and most of the app's background work also live.
            runOnIoDispatcher {
                try {
                    use {
                        try {
                            // If cancellation won the race before this block ran,
                            // closed is already set and the use {} close() below is
                            // a no-op — starting the box and plugins now would leak
                            // them, so bail out instead.
                            if (isClosed()) throw CancellationException("test cancelled")
                            init()
                            // close() 在 init() 期间跑过：它漏掉的 box 与临时文件已由
                            // BoxInstance.init 补收，这里只需不再启动
                            if (isClosed()) throw CancellationException("test cancelled")
                            // 外核的本机入站就绪后才启动 box（BoxInstance.launch）
                            launch()
                            if (isClosed()) throw CancellationException("test cancelled")
                            val controller = mihomoController
                            if (controller != null) {
                                try {
                                    cont.tryResume(mihomoDelay(controller))
                                } catch (e: Exception) {
                                    // mihomo collapses every dial failure into one opaque
                                    // message; re-test through the same tunnel via sing-box,
                                    // whose errors name the actual cause.
                                    Logs.w("mihomo delay test failed, retry via sing-box: ${e.message}")
                                    cont.tryResume(singBoxUrlTest())
                                }
                            } else {
                                cont.tryResume(singBoxUrlTest())
                            }
                        } catch (e: Exception) {
                            cont.tryResumeWithException(e)
                        }
                    }
                } catch (e: Exception) {
                    // use {} rethrows close() failures; the continuation must
                    // still be resumed (a no-op if it already was).
                    cont.tryResumeWithException(e)
                }
            }
        }
    }

    // 经 sing-box 测速；REALITY 握手失败时按构建结果补一句提示（ConfigBuildResult.withRealityHint）
    private fun singBoxUrlTest(): Int = try {
        Libcore.urlTest(box, link, timeout)
    } catch (e: Exception) {
        throw config.withRealityHint(e)
    }

    // Poll mihomo's Clash API until the core is up (replaces a fixed startup delay),
    // then let mihomo measure the delay through the proxy:
    // mihomo -> sing-box mapping inbound -> real server.
    private suspend fun mihomoDelay(controller: Pair<Int, String>): Int {
        val (port, secret) = controller
        // 代理名取运行计划里这个节点的出站标识；有 Clash API 时计划里只有这一个跳实例
        val proxyName = externalPlan.hops.singleOrNull { it.profileId == profile.id }?.outboundTag
            ?: throw IOException("profile ${profile.id} is not in the external core plan")
        val client = sharedHttpClient.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(mihomoTimeout + 3000L, TimeUnit.MILLISECONDS)
            .build()
        val base = "http://127.0.0.1:$port"

        fun newRequest(url: String) = Request.Builder().url(url)
            .header("Authorization", "Bearer $secret")
            .build()

        val deadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                client.newCall(newRequest("$base/version")).execute().use { resp ->
                    ready = resp.isSuccessful
                }
            } catch (_: IOException) {
            }
            if (ready) break
            delay(100)
        }
        if (!ready) throw IOException("mihomo controller not ready")

        val url = "$base/proxies/$proxyName/delay" +
            "?url=${URLEncoder.encode(link, "UTF-8")}&timeout=$mihomoTimeout"
        client.newCall(newRequest(url)).execute().use { resp ->
            val body = resp.body.string()
            if (resp.isSuccessful) return JSONObject(body).getInt("delay")
            val message = runCatching { JSONObject(body).getString("message") }.getOrNull()
            throw IOException(message ?: "mihomo delay test failed: HTTP ${resp.code}")
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // 配置里有凭据，写进可导出的日志前脱敏：本次构建的本机 socks 凭据按值遮蔽，其余按键名
        if (BuildConfig.DEBUG) Logs.d(Util.redactConfig(config.redactLocalAuth(config.config)))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
