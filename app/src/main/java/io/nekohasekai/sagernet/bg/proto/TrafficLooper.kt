package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    // loop() fills these (under statsLock) while selectMain() (via
    // NativeInterface.selector_OnProxySelected) reads/writes them on another thread
    private val idMap = ConcurrentHashMap<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = ConcurrentHashMap<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    suspend fun stop() {
        if (stopLoop()) postFinalTraffic()
    }

    // Cancel the loop, and only that: an in-flight queryStats needs a live box, so this
    // is the one part the teardown has to await before closing it. Both
    // ProxyInstance.launch's post-close recheck and BaseService.killProcesses can get
    // here; true goes to the caller that actually stopped it, which then owns the final
    // post below.
    suspend fun stopLoop(): Boolean {
        if (!stopped.compareAndSet(false, true)) return false
        job?.cancelAndJoin()
        return true
    }

    // Final counters and UI post. Reads in-memory state and writes the DB, so it needs
    // no live box and must not sit between the loop and box.close().
    suspend fun postFinalTraffic() {
        if (!DataStore.profileTrafficStatistics) return
        // one entity per id: two chains can carry the same profile under different tags
        val traffic = flushStats().distinctBy { it.id }
            .map { TrafficData(id = it.id, rx = it.rx, tx = it.tx) }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t)
            }
        }
        Logs.d("finally traffic post done")
    }

    // ACTION_SHUTDOWN kills the process without stopRunner, so stop() never
    // runs; persist the counters without stopping the loop. Reads race the
    // loop's TrafficUpdater writes, but a slightly stale counter beats
    // losing it all.
    suspend fun persistStats() {
        if (!DataStore.profileTrafficStatistics) return
        flushStats()
    }

    // copy the live counters onto the entities and write them all in one transaction
    private suspend fun flushStats(): List<ProxyEntity> = withContext(Dispatchers.IO) {
        val updated = mutableListOf<ProxyEntity>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                // only skip this ent, not the rest of the tag's entries
                val item = idMap[ent.id] ?: continue
                ent.rx = item.rx
                ent.tx = item.tx
                updated.add(ent)
            }
        }
        ProfileManager.updateTraffic(updated) // update DB
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

    @Volatile
    var selectorNowId = -114514L

    @Volatile
    var selectorNowFakeTag = ""

    // Shared by selectMain and the stats sweep in loop(): an interleaved
    // selector switch would otherwise add the same TAG_PROXY diff to both the
    // old and the new item (once in updateOne, once via the per-tag diff
    // cache), double-counting it. A private lock, so the exclusion is not at
    // the mercy of anyone holding this public object's monitor.
    private val statsLock = Any()

    // The UI cleared the tx/rx columns of these profiles in the DB: drop the
    // counters this loop carries for them too, or the next persistStats/stop
    // writes the pre-clear totals straight back. Scoped to the ids the UI
    // actually cleared — it clears one group, the loop spans every profile in
    // the running config. Under statsLock like every other read-modify-write here.
    fun clearStats(profileIds: LongArray) {
        synchronized(statsLock) {
            for (id in profileIds) {
                idMap[id]?.apply {
                    rx = 0
                    tx = 0
                    rxBase = 0
                    txBase = 0
                }
            }
        }
    }

    // NativeInterface.selector_OnProxySelected serializes the selector events,
    // but this read-modify-write still races the loop's stats sweep
    fun selectMain(id: Long) {
        synchronized(statsLock) {
            Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
            val oldData = idMap[selectorNowId]
            val newData = idMap[id] ?: return
            oldData?.apply {
                tag = selectorNowFakeTag
                ignore = true
                // post traffic when switch
                if (DataStore.profileTrafficStatistics) {
                    // find by id, not firstOrNull(): a chained/grouped tag maps to
                    // several entities in an unordered set; selectorNowId still
                    // holds the OLD id here (updated below), which is the one we want
                    data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull { it.id == selectorNowId }?.let {
                        it.rx = rx
                        it.tx = tx
                        runOnDefaultDispatcher {
                            ProfileManager.updateTraffic(it) // update DB
                        }
                    }
                }
            }
            selectorNowFakeTag = newData.tag
            selectorNowId = id
            newData.apply {
                tag = TAG_PROXY
                ignore = false
            }
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        // speedInterval 0 (Disable) turns off the speed display only: keep a
        // low-frequency counting loop so per-profile traffic statistics still
        // accumulate and can persist on stop
        val countingOnly = delayMs == 0L
        if (countingOnly && !profileTrafficStatistics) return
        val loopDelay = if (countingOnly) 1000L else delayMs

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (coroutineContext.isActive) {
            proxy = data.proxy
            if (proxy == null) {
                delay(loopDelay)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) {
                    delay(loopDelay)
                    continue
                }
                // under statsLock: a selectMain sneaking in mid-fill would see a
                // half-populated idMap and drop the switch (id lookup fails)
                synchronized(statsLock) {
                    idMap.clear()
                    idMap[-1] = itemBypass
                    //
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    proxy.config.trafficMap.forEach { (tag, ents) ->
                        tags.add(tag)
                        for (ent in ents) {
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = ent.rx,
                                tx = ent.tx,
                                rxBase = ent.rx,
                                txBase = ent.tx,
                                ignore = proxy.config.selectorGroupId >= 0L,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${ent.id}")
                        }
                    }
                    if (proxy.config.selectorGroupId >= 0L) {
                        selectMain(proxy.config.mainEntId)
                    }
                    //
                    trafficUpdater = TrafficUpdater(
                        box = proxy.box, items = idMap.values.toList()
                    )
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }
            }

            // mutually exclusive with selectMain, see statsLock
            synchronized(statsLock) {
                trafficUpdater?.updateAll()
            }
            if (!coroutineContext.isActive) return

            if (countingOnly) {
                delay(loopDelay)
                continue
            }

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // speed
            val speed = SpeedDisplayData(
                mainTxRate,
                mainRxRate,
                if (showDirectSpeed) itemBypass.txRate else 0L,
                if (showDirectSpeed) itemBypass.rxRate else 0L,
                mainTx,
                mainRx
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b.asBinder()] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            idMap.forEach { (id, item) ->
                                b.cbTrafficUpdate(
                                    TrafficData(id = id, rx = item.rx, tx = item.tx) // display
                                )
                            }
                        }
                    }
                }
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(loopDelay)
        }
    }
}