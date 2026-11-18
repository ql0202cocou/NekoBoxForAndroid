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
import kotlin.coroutines.coroutineContext

class TrafficLooper(private val data: BaseService.Data, private val owner: ProxyInstance) {

    // 关停取得任务后，start 不能再发布新的采样任务。
    private val jobLock = Any()
    private var job: Job? = null
    private var stopped = false

    // 首轮建账与早期切换、清零共用这把锁。发布后由记账自己的锁保护计数与 JNI，落库在锁外。
    private val startupLock = Any()
    private var earlySelection: Long? = null
    private var pendingClears: MutableMap<Long, Long>? = HashMap()
    private var nextCheckpointAt = 0L
    @Volatile
    private var accounting: TrafficAccounting? = null

    // 只在 loop 协程里用：前台回调收过哪些推送
    private val posts = TrafficPosts<IBinder>()

    private fun markStopped(): Job? = synchronized(jobLock) {
        stopped = true
        job
    }

    fun stop() {
        markStopped()?.cancel()
    }

    suspend fun stopLoop() {
        markStopped()?.cancelAndJoin()
    }

    // 停采样之后、关核心之前，把最后一段字节记给本次运行。快速停止时可能还没跑到首轮建账。
    fun collectFinal() {
        if (!DataStore.profileTrafficStatistics) return
        val now = SystemClock.elapsedRealtime()
        val active = accounting ?: run {
            if (!owner.isInitialized()) return
            initializeAccounting(owner.config, owner.box::queryStats, now)
        }
        active.sweep(now)
    }

    suspend fun postFinalTraffic() {
        val traffic = flushStats()
        if (traffic.isEmpty() || data.proxy !== owner) return
        data.binder.broadcast { b ->
            for (t in traffic) b.cbTrafficUpdate(t)
        }
    }

    // ACTION_SHUTDOWN 不走正常关停，只在有限时间内保存已采样的增量。
    suspend fun persistStats(): Unit = withContext(Dispatchers.IO) {
        val active = accounting ?: return@withContext
        if (DataStore.profileTrafficStatistics) persistIncrements(active)
    }

    // 销毁期间 data.proxy 可能已清空或换代，节点绑定与恢复世代始终取自这次运行。
    private suspend fun flushStats(): List<TrafficData> = withContext(Dispatchers.IO) {
        val accounting = accounting ?: return@withContext emptyList()
        if (!DataStore.profileTrafficStatistics) return@withContext emptyList()
        persistIncrements(accounting)
        TrafficAccounting.rowsToPersist(owner.config.traffic.tags, accounting.snapshot()).toTrafficData()
    }

    // 取走这些节点的增量并加到库里；库写失败把基线退回去再抛出，下次落库补上
    private fun persistIncrements(accounting: TrafficAccounting, ids: Collection<Long>? = null) {
        val increments = accounting.claim(ids)
        if (increments.isEmpty()) return
        val stale = try {
            ProfileManager.persistTrafficIncrements(increments, owner.generation)
        } catch (e: Throwable) {
            accounting.release(increments)
            throw e
        }
        if (stale.isNotEmpty()) accounting.clear(stale)
    }

    // 由采样循环串行调用，关闭速度显示时也经过这里。失败或长时间休眠后不会密集补写。
    internal suspend fun sampleTraffic(accounting: TrafficAccounting, now: Long): TrafficAccounting.Round {
        val round = accounting.sweep(now)
        if (now >= nextCheckpointAt) {
            nextCheckpointAt = now + CHECKPOINT_INTERVAL_MS
            try {
                withContext(Dispatchers.IO) {
                    if (DataStore.profileTrafficStatistics) persistIncrements(accounting)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 本轮速率与变化项照常推送，尚未写入的增量留给下一次结算。
                Logs.w(e)
            }
        }
        return round
    }

    fun start() = synchronized(jobLock) {
        if (!stopped && job == null) job = runOnDefaultDispatcher { loop() }
    }

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 60_000L

        // loop() 会不会跑：速度显示关闭且不统计节点流量时直接返回
        fun enabled() = DataStore.speedInterval != 0 || DataStore.profileTrafficStatistics

        // 要统计的 outbound tag。ProxyInstance 在 box.start() 之前用它装上统计服务：
        // 上游 trackers 无锁，libcore 的 SetV2rayStats 在启动后直接忽略
        fun statsTags(config: ConfigBuildResult): String =
            (setOf(TAG_PROXY, TAG_BYPASS) + config.traffic.tags.keys).joinToString("\n")

        // 不计入统计的映射入站 tag，与 statsTags 同样按行传给 SetV2rayStats
        fun ignoredInbounds(config: ConfigBuildResult): String =
            config.traffic.excludedInbounds.joinToString("\n")

        private fun TrafficTotals.toTrafficData(id: Long) = TrafficData(id = id, rx = rx, tx = tx)

        private fun Map<Long, TrafficTotals>.toTrafficData() = map { (id, t) -> t.toTrafficData(id) }
    }

    // 界面清除了这些节点在库里的流量列：本循环的累计与已落库基线也要清，否则清除前的累计还会按增量继续写
    // 进库。只清界面清除的那些 id（界面按分组清，本循环覆盖运行配置里的全部节点）；会话量不变
    fun clearStats(profileIds: LongArray) {
        val epochs = ProfileManager.trafficClearEpochs(profileIds, owner.generation)
        applyClears(epochs)
    }

    internal fun applyClears(epochs: Map<Long, Long>) {
        val active = synchronized(startupLock) {
            accounting ?: run {
                pendingClears?.let { pending ->
                    for ((id, epoch) in epochs) pending[id] = maxOf(epoch, pending[id] ?: 0L)
                }
                return
            }
        }
        active.clear(epochs)
    }

    // NativeInterface.selector_OnProxySelected 在串行调度器的协程里按事件顺序调用。切换前先把 proxy 上的字节结算给
    // 旧成员，旧成员所在出站的整个集合的增量就在调用方的协程里落库（一个事务），写完才返回。落库是加增量，前后
    // 两次切换写到同一节点（共享的前置 / 落地）也各加各的一段，不会互相覆盖
    suspend fun selectMain(id: Long) {
        val accounting = synchronized(startupLock) {
            accounting ?: run {
                earlySelection = id
                return
            }
        }
        Logs.d("select traffic count $TAG_PROXY to $id")
        val old = accounting.select(id) ?: return
        if (old.isNotEmpty() && DataStore.profileTrafficStatistics) {
            persistIncrements(accounting, old.keys)
        }
    }

    internal fun initializeAccounting(config: ConfigBuildResult, query: (String, String) -> Long, startedAt: Long) =
        synchronized(startupLock) {
            TrafficAccounting.of(config, query, startedAt, earlySelection ?: config.mainEntId).also { active ->
                pendingClears?.let { if (it.isNotEmpty()) active.clear(it) }
                pendingClears = null
                nextCheckpointAt = startedAt + CHECKPOINT_INTERVAL_MS
                accounting = active
            }
        }

    private suspend fun loop() {
        if (!enabled()) {
            synchronized(startupLock) { pendingClears = null }
            return
        }
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        // 关闭测速显示时仍每秒采样，节点累计与周期落库继续运行。
        val countingOnly = delayMs == 0L
        val loopDelay = if (countingOnly) 1000L else delayMs

        // 单轮统计；每条提前 return 都落到下方 while 里的 delay
        suspend fun loopOnce() {
            val proxy = data.proxy?.takeIf { it.looper === this@TrafficLooper } ?: return

            val accounting = this@TrafficLooper.accounting ?: run {
                if (!proxy.isInitialized()) return
                val config = proxy.config
                if (Logs.enabled) config.traffic.tags.forEach { (tag, ids) -> Logs.d("traffic count $tag to $ids") }
                // 首轮建立基线。已收到清零的出站先结算，其余计数留给下一轮采样。
                initializeAccounting(config, proxy.box::queryStats, SystemClock.elapsedRealtime())
                return
            }

            // 记账的锁覆盖整轮查询，与 selectMain / clearStats 互斥
            val round = sampleTraffic(accounting, SystemClock.elapsedRealtime())
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
