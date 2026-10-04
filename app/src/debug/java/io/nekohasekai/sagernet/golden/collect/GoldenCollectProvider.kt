package io.nekohasekai.sagernet.golden.collect

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.ktx.Logs
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 旧配置输出的采集入口，只编进 debug 包，由 buildScript/golden/collect.sh 经
//   adb shell content call --uri content://<applicationId>.golden --method collect --extra …
// 触发。call() 同步跑完整次采集再返回，结果放在返回的 Bundle 里（status=ok / error 与原因）；
// shell 持有 provider 期间进程按前台优先级对待，不会被冻结或回收。
// 不启动 VPN / 代理服务，不启动任何外核进程，不发起网络连接
class GoldenCollectProvider : ContentProvider() {

    private val running = AtomicBoolean(false)

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // 只给 adb shell（2000）与 root（0）用：provider 必须 exported 才能被 shell 调到
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("golden collect is for adb shell only")
        if (method != METHOD_COLLECT) return failure("unknown method $method")
        if (!running.compareAndSet(false, true)) return failure("a collection is already running")
        return try {
            awaitApplicationCreated()
            val args = parseArgs(extras ?: Bundle.EMPTY)
            // 在独立线程上跑：binder 线程上的 Binder.getCallingUid() 是 shell 的
            var summary: CollectSummary? = null
            var failure: Throwable? = null
            val worker = Thread({
                try {
                    summary = GoldenCollector(args).collect()
                } catch (e: Throwable) {
                    failure = e
                }
            }, "golden-collect")
            worker.start()
            worker.join()
            failure?.let { throw it }
            val result = summary!!
            Bundle().apply {
                putString("status", "ok")
                putString("outDir", result.outDir.absolutePath)
                putInt("scenarios", result.scenarios)
                for ((key, value) in result.counts) putInt(key, value)
            }
        } catch (e: CollectRefusedException) {
            failure("refused: ${e.message}")
        } catch (e: Throwable) {
            Logs.e(e)
            failure(e.stackTraceToString())
        } finally {
            running.set(false)
        }
    }

    // 进程由这次调用拉起时，provider 在 Application.onCreate 之前就已发布，binder 调用可能
    // 赶在它前面。往主线程投一个任务并等它执行：轮到它时 bindApplication（含 onCreate）已跑完
    private fun awaitApplicationCreated() {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        check(latch.await(60, TimeUnit.SECONDS)) { "main thread did not become idle" }
    }

    private fun parseArgs(extras: Bundle): CollectArgs {
        fun required(key: String) = extras.getString(key)?.takeIf { it.isNotBlank() }
            ?: throw CollectRefusedException("missing extra $key")
        // 路径列表：换行分隔后整体 Base64，"-" 表示没有
        fun paths(key: String) = required(key).let { encoded ->
            if (encoded == "-") emptyList() else String(Base64.getDecoder().decode(encoded))
                .lines().filter { it.isNotEmpty() }
        }
        fun count(key: String) = extras.getInt(key, -1).takeIf { it >= 0 }
            ?: throw CollectRefusedException("missing or negative integer extra $key")
        fun core(name: String) = CoreArgs(
            version = required("${name}Version"),
            pinnedSha256 = required("${name}PinnedSha256"),
            packagedSha256 = required("${name}PackagedSha256"),
        )
        return CollectArgs(
            commit = required("commit"),
            dirty = DirtyArgs(
                count = count("dirtyCount"),
                top = paths("dirtyTop"),
                codeCount = count("codeDirtyCount"),
                codeTop = paths("codeDirtyTop"),
            ),
            xray = core("xray"),
            mihomo = core("mihomo"),
            allowWipe = extras.getBoolean("allowWipe", false),
        )
    }

    private fun failure(message: String) = Bundle().apply {
        putString("status", "error")
        putString("message", message)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val METHOD_COLLECT = "collect"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }
}
