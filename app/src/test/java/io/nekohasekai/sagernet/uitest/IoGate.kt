package io.nekohasekai.sagernet.uitest

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 占满 Dispatchers.IO 的并行度：门开着的时候，页面代码提交到 IO 的读库任务只能排队，不会运行，
 * 「读库在途时销毁页面」因此是确定的，不取决于读库和销毁谁先到。
 * 关闭（[close]）后排队的任务照常跑完。门开着时不能 settle（占着的线程在等门闩，settle 会超时），
 * 所以一律用 `IoGate().use { ... }` 包住「触发读库 → 销毁页面」，出了块再 settle。
 * Robolectric 的 ActivityController.pause()/stop()/destroy() 每一步都会把主 looper 空转一遍，
 * 没有这道门时，已读完的结果会在销毁前的空转里被应用——那是正常行为，不是缺陷。
 */
@OptIn(DelicateCoroutinesApi::class)
internal class IoGate : AutoCloseable {
    private val release = CountDownLatch(1)

    init {
        // kotlinx.coroutines 的 IO 并行度：系统属性，缺省 max(64, CPU 数)
        val slots = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        val occupied = CountDownLatch(slots)
        repeat(slots) {
            GlobalScope.launch(Dispatchers.IO) {
                occupied.countDown()
                release.await()
            }
        }
        try {
            check(occupied.await(10, TimeUnit.SECONDS)) { "IO 调度器没能在 10 s 内被占满" }
            // 自检：门关上后再提交的 IO 任务必须跑不起来，否则下面的「在途」不成立
            val probe = CountDownLatch(1)
            GlobalScope.launch(Dispatchers.IO) { probe.countDown() }
            check(!probe.await(100, TimeUnit.MILLISECONDS)) { "IO 门没有挡住后续的 IO 任务" }
        } catch (t: Throwable) {
            release.countDown()
            throw t
        }
    }

    override fun close() = release.countDown()
}
