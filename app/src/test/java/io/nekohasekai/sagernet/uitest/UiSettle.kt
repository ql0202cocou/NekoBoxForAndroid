package io.nekohasekai.sagernet.uitest

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 等界面「安静下来」：主 looper 是 PAUSED 模式，后台工作跑在真线程池上（协程 Default / IO 调度器、
 * Room 的查询执行器、WorkManager），所以交替做两件事：空转主 looper，并检查这些工作线程是否都已挂起
 * 等活。连续若干轮都稳定才算完成；不用固定 sleep，超时（10 s）时打出线程栈并失败。
 *
 * 局限：只认已知名字的线程池（见 [isWorkerThread]）；主 looper 上带延时的任务默认不推进，
 * 需要的用例传 advanceMs。
 */
object UiSettle {

    private const val STABLE_ROUNDS = 5
    private val TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10)

    // 轮询间隔：只是让出 CPU 给刚被唤醒的线程，不是在等某个固定时长后「应该好了」
    private const val POLL_NANOS = 1_000_000L

    /**
     * [advanceMs] > 0 时，先安静下来，再把主 looper 的虚拟时钟推进这么久（跑掉 delay / postDelayed 的任务），
     * 然后再安静一次。时钟是 Robolectric 的虚拟时钟，推进不耗真实时间；默认不推进。
     */
    fun settle(advanceMs: Long = 0) {
        settleOnce()
        if (advanceMs > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(advanceMs, TimeUnit.MILLISECONDS)
            settleOnce()
        }
    }

    private fun settleOnce() {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        var stable = 0
        while (stable < STABLE_ROUNDS) {
            looper.idle()
            val busy = busyThreads()
            if (busy.isEmpty() && looper.isIdle) stable++ else stable = 0
            if (System.nanoTime() > deadline) {
                throw AssertionError("settle() 超时 10 s，未挂起的工作线程：\n" + dump(busy))
            }
            LockSupport.parkNanos(POLL_NANOS)
        }
    }

    // 协程调度器（Default / IO）、java.util.concurrent 默认命名的线程池（Room 的单线程查询执行器等）、WorkManager
    private fun isWorkerThread(t: Thread): Boolean {
        val n = t.name
        return n.startsWith("DefaultDispatcher-worker-") ||
            (n.startsWith("pool-") && n.contains("-thread-")) ||
            n.startsWith("WM.task-") || n.startsWith("arch_disk_io_")
    }

    // 等活 = 栈上有线程池取任务 / 协程 worker 挂起的帧。其它状态（运行、阻塞在别处）都算忙
    private fun isParked(stack: Array<StackTraceElement>): Boolean = stack.any {
        (it.className == "java.util.concurrent.ThreadPoolExecutor" && it.methodName == "getTask") ||
            (it.className.startsWith("kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker") &&
                (it.methodName == "park" || it.methodName == "tryPark"))
    }

    private fun busyThreads(): List<Pair<Thread, Array<StackTraceElement>>> =
        Thread.getAllStackTraces().entries
            .filter { (t, _) -> t.isAlive && isWorkerThread(t) }
            .filter { (t, stack) -> t.state == Thread.State.RUNNABLE || !isParked(stack) }
            .map { it.key to it.value }

    private fun dump(busy: List<Pair<Thread, Array<StackTraceElement>>>): String =
        busy.joinToString("\n") { (t, stack) ->
            "  ${t.name} [${t.state}]\n" + stack.take(25).joinToString("\n") { "    at $it" }
        }
}
