package io.nekohasekai.sagernet.bg.proto

import android.os.IBinder
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class TrafficLooper(val data: BaseService.Data) {

    // The loop's own job on appScope (loop() checks its own context, the one
    // stopLoop's cancel turns inactive). Written by start() on the Default
    // dispatcher, read by stopLoop() on main; the stopped CAS alone gives that
    // read no happens-before.
    @Volatile
    private var job: Job? = null
    private val stopped = AtomicBoolean(false)

    // 本次运行的记账，循环第一轮在 Default 上建好后发布。selectMain（NativeInterface 的串行调度器）、
    // clearStats（BaseService 的 Default）与 flushStats（IO）在别的线程上读；记账自己的锁管互斥，
    // 建好之前到达的切换与清零照旧丢弃
    @Volatile
    private var accounting: TrafficAccounting? = null

    // 只在 loop 协程里用：前台回调收过哪些推送
    private val posts = TrafficPosts<IBinder>()

    suspend fun stop() {
        if (stopLoop()) postFinalTraffic()
    }

    // 只停循环：进行中的 queryStats 需要存活的 box，这是关停时唯一要在关 box 前等完的
    // 部分。killProcesses / destroyRunner、ProxyInstance.launch 的关闭后复查和
    // ProxyInstance.close 的兜底都会走到这里；返回 true 的那个调用方真正停了循环，
    // 由它负责下面的最终推送
    suspend fun stopLoop(): Boolean {
        if (!stopped.compareAndSet(false, true)) return false
        job?.cancelAndJoin()
        return true
    }

    // Final counters and UI post. Reads in-memory state and writes the DB, so it needs
    // no live box and must not sit between the loop and box.close().
    suspend fun postFinalTraffic() {
        if (!DataStore.profileTrafficStatistics) return
        val traffic = flushStats()
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t)
            }
        }
        Logs.d("finally traffic post done")
    }

    // ACTION_SHUTDOWN 直接杀进程、不走 stopRunner，stop() 不会运行：不停循环，先把累计落库。
    // 快照在记账的锁内取，与进行中的一轮互斥；之后的那一轮来不及落库，少一点总好过全丢
    suspend fun persistStats() {
        if (!DataStore.profileTrafficStatistics) return
        flushStats()
    }

    // 当前累计写库，每个节点一行、一个事务。写哪些节点仍按当前 data.proxy 的统计关联取（同以前：data.proxy
    // 已置空时不写，已换成新实例时只写两边都有的节点），值取本循环的记账
    private suspend fun flushStats(): List<TrafficData> = withContext(Dispatchers.IO) {
        val bound = data.proxy?.config?.traffic?.tags ?: return@withContext emptyList()
        val totals = accounting?.snapshot() ?: return@withContext emptyList()
        val updated = TrafficAccounting.rowsToPersist(bound, totals).toTrafficData()
        ProfileManager.persistTraffic(updated)
        updated
    }

    fun start() {
        // stop() may have already won the CAS (close() raced ProxyInstance.launch);
        // launching now would leave a loop on a closed box that stop() can never cancel
        if (stopped.get()) return
        job = runOnDefaultDispatcher { loop() }
        // stop() won the CAS between the check above and the job assignment, saw a
        // null job and skipped cancelAndJoin; cancel here so the loop cannot leak
        if (stopped.get()) job?.cancel()
    }

    companion object {
        // loop() 会不会跑：速度显示关闭且不统计节点流量时直接返回
        fun enabled() = DataStore.speedInterval != 0 || DataStore.profileTrafficStatistics

        // 要统计的 outbound tag。ProxyInstance 在 box.start() 之前用它装上统计服务：
        // 上游 trackers 无锁，libcore 的 SetV2rayStats 在启动后直接忽略
        fun statsTags(config: ConfigBuildResult): String =
            (setOf(TAG_PROXY, TAG_BYPASS) + config.traffic.tags.keys).joinToString("\n")

        private fun TrafficTotals.toTrafficData(id: Long) = TrafficData(id = id, rx = rx, tx = tx)

        private fun Map<Long, TrafficTotals>.toTrafficData() = map { (id, t) -> t.toTrafficData(id) }
    }

    // 界面清除了这些节点在库里的流量列：本循环的累计也要清，否则下一次 persistStats / stop 会把清除前的
    // 累计写回去。只清界面清除的那些 id（界面按分组清，本循环覆盖运行配置里的全部节点）；会话量不变
    fun clearStats(profileIds: LongArray) {
        accounting?.clear(profileIds)
    }

    // NativeInterface.selector_OnProxySelected 在串行调度器的协程里按事件顺序调用。切换前先把 proxy 上的字节结算给
    // 旧成员，旧成员所在出站的整个集合就在调用方的协程里落库（一个事务），写完才返回：前后两次切换写到同一节点
    // （共享的前置 / 落地）时，库里留下的是后一次的累计
    suspend fun selectMain(id: Long) {
        val accounting = accounting ?: return
        Logs.d("select traffic count $TAG_PROXY to $id")
        val old = accounting.select(id) ?: return
        if (old.isNotEmpty() && DataStore.profileTrafficStatistics) {
            ProfileManager.persistTraffic(old.toTrafficData())
        }
    }

    private suspend fun loop() {
        if (!enabled()) return
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        // speedInterval 0 (Disable) turns off the speed display only: keep a
        // low-frequency counting loop so per-profile traffic statistics still
        // accumulate and can persist on stop
        val countingOnly = delayMs == 0L
        val loopDelay = if (countingOnly) 1000L else delayMs

        // 单轮统计；每条提前 return 都落到下方 while 里的 delay
        suspend fun loopOnce() {
            val proxy = data.proxy ?: return

            val accounting = this@TrafficLooper.accounting ?: run {
                if (!proxy.isInitialized()) return
                val config = proxy.config
                if (Logs.enabled) config.traffic.tags.forEach { (tag, ids) -> Logs.d("traffic count $tag to $ids") }
                // 这一轮只建记账、不查询：速率按轮间隔算，从这里起算；box 启动以来的字节留在计数器里，下一轮取走
                this@TrafficLooper.accounting =
                    TrafficAccounting.of(config, proxy.box::queryStats, SystemClock.elapsedRealtime())
                return
            }

            // 记账的锁覆盖整轮查询，与 selectMain / clearStats 互斥
            val round = accounting.sweep(SystemClock.elapsedRealtime())
            if (!coroutineContext.isActive) return

            if (countingOnly) return

            // speed
            val speed = SpeedDisplayData(
                round.txRateProxy,
                round.rxRateProxy,
                if (showDirectSpeed) round.txRateDirect else 0L,
                if (showDirectSpeed) round.rxRateDirect else 0L,
                round.txSession,
                round.rxSession
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                // 每个节点的流量只推变化项，见 TrafficPosts
                val changed = if (profileTrafficStatistics) round.changed.toTrafficData() else emptyList()
                val all = lazy { if (profileTrafficStatistics) accounting.snapshot().toTrafficData() else emptyList() }
                val seen = HashSet<IBinder>()
                data.binder.broadcast { b ->
                    val binder = b.asBinder()
                    if (data.binder.callbackIdMap[binder] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        for (t in posts.itemsFor(binder, changed, all)) b.cbTrafficUpdate(t)
                        seen.add(binder)
                    }
                }
                posts.endRound(seen)
            } else {
                // 没有前台回调：下一个前台回调视为新出现，收全量
                posts.endRound(emptySet())
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }
        }

        while (coroutineContext.isActive) {
            try {
                loopOnce()
            } catch (e: CancellationException) {
                throw e // stopLoop() 的取消是循环的正常退出路径，必须重抛不吞
            } catch (e: Exception) {
                // 通知更新、DataStore 读取等意外异常不能逃出循环：本协程跑在
                // appScope 上且没有 CoroutineExceptionHandler，逃逸即崩溃 :bg
                Logs.w(e)
            }
            delay(loopDelay)
        }
    }
}

/**
 * 前台回调的节点流量推送：逐个 binder 调用、前台时每秒 N 次，全量太多，所以上一轮收过推送的回调只收本轮的变化项，
 * 新出现的先收一次全量。只在循环协程里用。
 */
internal class TrafficPosts<K> {
    // 上一轮收到推送的回调
    private var postedTo: Set<K> = emptySet()

    /** 回调 [key] 本轮要收的节点流量；[all] 只在有回调要收全量时取。 */
    fun <T> itemsFor(key: K, changed: List<T>, all: Lazy<List<T>>): List<T> = if (key in postedTo) changed else all.value

    /** 一轮推送结束：本轮收到推送的回调（[delivered]）就是下一轮的 postedTo；没有前台回调时传空集。 */
    fun endRound(delivered: Set<K>) {
        postedTo = delivered.toSet()
    }
}
