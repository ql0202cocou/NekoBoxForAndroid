package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appScope
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.CancellationException
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
import kotlin.concurrent.thread

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        // 在 looper 协程第一次被调度时置位；close() 只销毁 looper 从未启动的守护，
        // 已启动的 looper 在自己的 finally 里清理（API < 24 时带 SIGTERM 的宽限期）
        @Volatile
        var looperStarted = false

        // 进程的运行状态，只供启动就绪等待发现进程退出与守护放弃（见 start() 的返回值与 ProcessState），
        // 守护与重启逻辑不看它
        val state = GuardedProcessState()

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().use { it.forEachLine(logger) }
        } catch (_: IOException) {
        }    // 忽略

        private val cmdName = File(cmd.first()).nameWithoutExtension

        private fun closeStreams(child: Process) {
            runCatching { child.outputStream.close() }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        }

        fun start() {
            process = ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        // 只在 looper 从未跑起来的路径调用（start() 里池已取消、close() 里
        // looperStarted 仍为 false），此时不存在任何 waitFor 线程：只 destroy
        // 不回收，子进程会变僵尸、管道 fd 也会残留，故补一个回收线程。
        // waitFor 可能阻塞，必须在线程里做，绝不占用调用线程
        fun destroy() {
            process.destroy()
            val child = process
            thread(name = "reap-$cmdName", isDaemon = true) {
                runCatching { child.waitFor() }
                closeStreams(child)
            }
        }

        suspend fun looper() {
            looperStarted = true
            var running = true
            val exitChannel = Channel<Int>(1) // 带缓冲：清理处可能放弃接收，而被阻塞的 send 会泄漏守护线程
            // 每个进程一启动就调用，中间不跨挂起点，所以活着的进程总有一个线程
            // 停在 waitFor 上，下面的清理也就总能收到它的退出码
            fun spawnLoggers() {
                val child = process
                thread(name = "stderr-$cmdName", isDaemon = true) {
                    streamLogger(child.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                }
                thread(name = "stdout-$cmdName", isDaemon = true) {
                    streamLogger(child.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                }
                // 子进程退出后，它的后代进程可能仍继承着 stdout/stderr。
                // 回收（reap）不能等这些管道到达 EOF。
                thread(name = "wait-$cmdName", isDaemon = true) {
                    val exitCode = child.waitFor()
                    // 先更新状态再通知 looper：looper 重启进程后才会置 running，不会被这里晚到的写覆盖
                    state.onExited(exitCode)
                    exitChannel.trySend(exitCode)
                    closeStreams(child)
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
                    state.onStarted()
                    running = true
                    spawnLoggers()
                }
            } catch (e: CancellationException) {
                throw e // 池关闭/服务停止的正常拆毁路径
            } catch (e: Exception) {
                // IOException 是进程退得太快 / 重启失败；其余意外异常同样按守护失败
                // 处理：逃逸出协程会崩溃 :bg（池没有 CoroutineExceptionHandler），
                // 吞掉则 start() 已失败的 guard 会永远挂在 exitChannel.receive() 上不再守护
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                if (coroutineContext.isActive) {
                    appScope.launch(Dispatchers.Main) {
                        if (this@GuardedProcessPool.coroutineContext.isActive) {
                            onFatal(e as? IOException ?: IOException(e))
                        }
                    }
                }
            } finally {
                // 先标记放弃再清理（清理最长要等几秒）：就绪等待据此知道不会再重启
                state.onAbandoned()
                if (running) withContext(NonCancellable) {  // 清理不可取消
                    process.destroy()                       // 杀掉进程
                    if (Build.VERSION.SDK_INT >= 26) {
                        if (withTimeoutOrNull(1000) { exitChannel.receive() } != null) return@withContext
                        process.destroyForcibly()           // 进程还活着就强杀
                    }
                    // 进程不理会 destroy() 时不能永远挂着
                    withTimeoutOrNull(5000) { exitChannel.receive() }
                }                                           // 否则进程已经退出，无事可做
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()

    // 每个成功启动的守护都登记在这里，这样 close() 能回收那些 looper 协程
    // 从未运行的进程（进程池在 isActive 检查之后被取消）
    private val guards = CopyOnWriteArrayList<Guard>()

    // 返回值：这个进程的运行状态（退出码、是否在跑、守护是否放弃、退出代次）。启动就绪等待据它区分
    // 「守护已放弃」与「退出后已被守护重启」，只有前者才立即判启动失败
    fun start(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
    ): ProcessState {
        Logs.i("start process: ${Commandline.toString(cmd)}")
        val guard = Guard(cmd, env).apply {
            start() // 启动失败会直接抛出 IOException
            guards.add(this)
            if (!coroutineContext.isActive) {
                // close() 已经取消了这个进程池：下面的 looper 启动不会运行，
                // 也没有别的地方会杀掉这个进程
                destroy()
                state.onAbandoned()
                return state
            }
            launch { looper() }
        }
        return guard.state
    }

    // 任意线程可调（BoxInstance.close 在主线程、TestInstance 的
    // invokeOnCancellation 在取消发生的线程）：cancel()、
    // CopyOnWriteArrayList 遍历、Process.destroy 都线程安全。
    // onClosed 在每个 guard looper（含其杀进程的 finally）退出后于 scope 的
    // 调度器上执行；调用方把「进程全部停掉之后」的收尾放在这里而不是 join
    //（在主线程 join 会死锁 looper 的 Main 分发清理）。返回的 Job 在 onClosed
    // 跑完后完成，供能安全等待的调用方（测试）join
    fun close(scope: CoroutineScope, onClosed: () -> Unit = {}): Job {
        cancel()
        // 回收 looper 协程在第一次被调度前就被取消的进程；
        // looper 已启动的守护会在自己的 finally 里清理，
        // 所以这里无条件 destroy 会把它的优雅退出截断
        // 没跑起来的 looper 没有 finally 可标记放弃，这里一并标记
        guards.forEach {
            it.state.onAbandoned()
            if (!it.looperStarted) it.destroy()
        }
        return scope.launch {
            this@GuardedProcessPool.coroutineContext[Job]!!.cancelAndJoin()
            onClosed()
        }
    }
}
