package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appScope
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        // set on the looper coroutine's first dispatch; close() destroys only
        // guards whose looper never started, started loopers clean up in their
        // own finally (with the SIGTERM grace period on API < 24)
        @Volatile
        var looperStarted = false

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().use { it.forEachLine(logger) }
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        fun destroy() = process.destroy()

        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            looperStarted = true
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            val exitChannel = Channel<Int>(1) // buffered: cleanup may give up receiving, and a blocked send would leak the daemon thread
            // called for every process right after it starts, never across a
            // suspension point, so a live process always has a thread parked in
            // waitFor and the cleanup below can always receive its exit code
            fun spawnLoggers() {
                val child = process
                thread(name = "stderr-$cmdName", isDaemon = true) {
                    streamLogger(child.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                }
                thread(name = "stdout-$cmdName", isDaemon = true) {
                    streamLogger(child.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                }
                // Descendants can inherit stdout/stderr after this child exits.
                // Reaping must not wait for those pipes to reach EOF.
                thread(name = "wait-$cmdName", isDaemon = true) {
                    exitChannel.trySend(child.waitFor())
                    runCatching { child.outputStream.close() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                }
            }
            try {
                spawnLoggers()
                while (true) {
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    coroutineContext.ensureActive()
                    when {
                        SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                            "$cmdName exits too fast (exit code: $exitCode)"
                        )

                        exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: ${Commandline.toString(cmd)} (last exit code: $exitCode)")
                    start()
                    running = true
                    spawnLoggers() // before the suspending callback below
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                if (coroutineContext.isActive) {
                    appScope.launch(Dispatchers.Main) {
                        if (this@GuardedProcessPool.coroutineContext.isActive) onFatal(e)
                    }
                }
            } finally {
                if (running) withContext(NonCancellable) {  // clean-up cannot be cancelled
                    if (Build.VERSION.SDK_INT < 24) {
                        try {
                            Os.kill(pid.get(process) as Int, OsConstants.SIGTERM)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.ESRCH) Logs.w(e)
                        } catch (e: ReflectiveOperationException) {
                            Logs.w(e)
                        }
                        if (withTimeoutOrNull(500) { exitChannel.receive() } != null) return@withContext
                    }
                    process.destroy()                       // kill the process
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                        process.destroyForcibly()           // Force to kill the process if it's still alive
                    }
                    // don't hang forever if the process ignores destroy()
                    withTimeoutOrNull(5000) { exitChannel.receive() }
                }                                           // otherwise process already exited, nothing to be done
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    val processCount = AtomicInteger(0)

    // every successfully started guard, so close() can reap processes whose
    // looper coroutine never ran (pool cancelled after the isActive check)
    private val guards = CopyOnWriteArrayList<Guard>()

    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        Logs.i("start process: ${Commandline.toString(cmd)}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            guards.add(this)
            if (!coroutineContext.isActive) {
                // close() already cancelled this pool: the looper launch below
                // would never run and nothing else would kill this process
                destroy()
                return
            }
            launch { looper(onRestartCallback) }
        }
        processCount.incrementAndGet()
    }

    @MainThread
    fun close(scope: CoroutineScope): Job {
        cancel()
        // reap processes whose looper coroutine was cancelled before its
        // first dispatch; guards with a started looper clean up in their own
        // finally, so an unconditional destroy here would cut the graceful
        // shutdown short
        guards.forEach { if (!it.looperStarted) it.destroy() }
        // The returned Job completes once every guard looper — including its
        // process-killing finally — has exited; callers that must outlive the
        // guards hook onto it instead of joining (joining from the main
        // thread would deadlock the loopers' Main-dispatched cleanup).
        return scope.launch { this@GuardedProcessPool.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
