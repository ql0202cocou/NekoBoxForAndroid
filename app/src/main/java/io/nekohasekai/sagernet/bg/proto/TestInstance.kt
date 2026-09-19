package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
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
import moe.matsuri.nb4a.proxy.anytls.MIHOMO_PROXY_NAME
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

    // Single-node AnyTLS-on-mihomo: enable mihomo's Clash API so mihomo measures
    // the delay through the proxy itself. Chained profiles keep the sing-box path.
    private val mihomoController: Pair<Int, String>? by lazy {
        if (profile.type != ProxyEntity.TYPE_ANYTLS || !profile.needExternal()) return@lazy null
        val group = GroupManager.getGroup(profile.groupId) ?: return@lazy null
        if (group.frontProxy > 0 || group.landingProxy > 0) return@lazy null
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
                            if (isClosed()) {
                                // close() ran during init(); launch() would bail
                                // out and leak what init() built, so finish the
                                // cleanup close() could not do
                                closeAfterLateInit()
                                throw CancellationException("test cancelled")
                            }
                            launch()
                            val controller = mihomoController
                            if (controller != null) {
                                try {
                                    cont.tryResume(mihomoDelay(controller))
                                } catch (e: Exception) {
                                    // mihomo collapses every dial failure into one opaque
                                    // message; re-test through the same tunnel via sing-box,
                                    // whose errors name the actual cause.
                                    Logs.w("mihomo delay test failed, retry via sing-box: ${e.message}")
                                    cont.tryResume(Libcore.urlTest(box, link, timeout))
                                }
                            } else {
                                if (processes.processCount.get() > 0) {
                                    // wait for plugin start
                                    delay(500)
                                }
                                cont.tryResume(Libcore.urlTest(box, link, timeout))
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

    // Poll mihomo's Clash API until the core is up (replaces a fixed startup delay),
    // then let mihomo measure the delay through the proxy:
    // mihomo -> sing-box mapping inbound -> real server.
    private suspend fun mihomoDelay(controller: Pair<Int, String>): Int {
        val (port, secret) = controller
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

        val url = "$base/proxies/$MIHOMO_PROXY_NAME/delay" +
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
        // don't call destroyAllJsi here
        // configs contain credentials; redact them before writing to the exportable log
        if (BuildConfig.DEBUG) Logs.d(Util.redactSecrets(config.config))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
