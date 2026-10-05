package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals

/**
 * 一次运行的流量记账：节点累计、选择器当前选中的出站、每轮的速率与本次会话量。不碰 Android、DataStore、libcore，
 * 统计经 [query] 查询（生产上是 box.queryStats(tag, "uplink" | "downlink")，取走该出站自上次查询以来的增量）。
 *
 * 记账规则：
 * - 每个查询到的节点出站，增量记给它在 [TrafficBindings.tags] 里的节点集合，集合里每个节点加一次（列表里重复的 id
 *   只加一次）；同一节点在几个出站里，就各出站的增量都加给它。
 * - 选择器模式下 proxy 的增量记给当前选中成员所在出站的整个集合（成员、分组的前置 / 落地、链的各跳）。
 * - bypass 只进直连速率，不记给任何节点。
 * - 代理速率与本次会话量按出站汇总：每个查询到的非 bypass 出站计一次；会话量不因清除节点流量而减少。
 * - 速率 = 本轮字节 × 1000 / 距上一轮的毫秒数（第一轮从 startedAt 算起），间隔不大于 0 时为 0（字节照记）。
 *
 * 每轮查询的出站：非选择器模式是全部登记的节点出站加 bypass；选择器模式是 proxy、
 * [TrafficBindings.ruleTags]（规则直接指向的出站）与 bypass。每个出站上行、下行各查一次。
 *
 * 线程：一把私有锁覆盖 [sweep]、[select]、[clear]、[snapshot]，[query] 在锁内调用。查询是取走增量，每个字节只会被
 * 取走一次；锁要防的是切换插进「扫描取走 proxy 的增量」与「把它记给选中集合」之间，那样旧成员的字节会记给新成员。
 * 另外累计数组、变化项集合与会话量本身也不是线程安全的。落库与推送由调用方在锁外做。
 *
 * @param selector 选择器模式：构建结果的 profileTagMap（节点 id → 出站 tag，含选择器成员，也含路由规则目标）；
 *   非选择器模式为 null
 * @param selectedId 选择器模式下最初选中的成员（构建结果的主节点）
 * @param startedAt 单调时钟的毫秒数，第一轮速率的间隔从这里算起
 */
class TrafficAccounting(
    bindings: TrafficBindings,
    private val selector: Map<Long, String>?,
    selectedId: Long,
    private val query: (tag: String, direction: String) -> Long,
    startedAt: Long,
) {

    /**
     * 一轮的结果：代理与直连的速率（字节 / 秒）、本次会话经代理出站的字节，以及上一轮之后累计有变化的节点
     * （含切换时结算与清零带来的变化）。
     */
    class Round(
        val txRateProxy: Long,
        val rxRateProxy: Long,
        val txRateDirect: Long,
        val rxRateDirect: Long,
        val txSession: Long,
        val rxSession: Long,
        val changed: Map<Long, TrafficTotals>,
    )

    private val lock = Any()

    // 出站 tag → 节点 id（去重，保持列表顺序）
    private val sets: Map<String, LongArray> =
        bindings.tags.mapValues { (_, ids) -> ids.distinct().toLongArray() }

    // 节点 id → [rx, tx]
    private val totals = LinkedHashMap<Long, LongArray>().apply {
        for (ids in sets.values) for (id in ids) {
            getOrPut(id) { bindings.initial.getValue(id).let { longArrayOf(it.rx, it.tx) } }
        }
    }

    private val queryTags: List<String> = buildList {
        if (selector != null) {
            add(TAG_PROXY)
            bindings.ruleTags.filterTo(this) { it != TAG_PROXY && it != TAG_BYPASS }
        } else {
            bindings.tags.keys.filterTo(this) { it != TAG_BYPASS }
        }
        add(TAG_BYPASS)
    }

    // 选择器模式下 proxy 的增量记给的集合
    private var selectedSet = EMPTY

    private var lastRound = startedAt

    // 切换前结算、尚未计入哪一轮速率的 proxy 字节
    private var pendingTx = 0L
    private var pendingRx = 0L
    private var sessionTx = 0L
    private var sessionRx = 0L

    // 上一轮之后累计有变化的节点
    private val dirty = LinkedHashSet<Long>()

    init {
        selector?.get(selectedId)?.let { selectedSet = memberSet(selectedId, it) }
    }

    /** 一轮查询与记账；[now] 是单调时钟的毫秒数。 */
    fun sweep(now: Long): Round = synchronized(lock) {
        var proxyTx = pendingTx
        var proxyRx = pendingRx
        pendingTx = 0
        pendingRx = 0
        var directTx = 0L
        var directRx = 0L
        for (tag in queryTags) {
            val tx = query(tag, UPLINK)
            val rx = query(tag, DOWNLINK)
            if (tag == TAG_BYPASS) {
                directTx += tx
                directRx += rx
                continue
            }
            credit(if (selector != null && tag == TAG_PROXY) selectedSet else sets[tag] ?: EMPTY, rx, tx)
            sessionTx += tx
            sessionRx += rx
            proxyTx += tx
            proxyRx += rx
        }
        val interval = now - lastRound
        lastRound = now
        fun rate(bytes: Long) = if (interval <= 0) 0L else bytes * 1000 / interval
        val changed = dirty.associateWithTo(LinkedHashMap()) { totals.getValue(it).toTotals() }
        dirty.clear()
        Round(rate(proxyTx), rate(proxyRx), rate(directTx), rate(directRx), sessionTx, sessionRx, changed)
    }

    /**
     * 选择器切换到成员 [id]：先查一次 proxy，把上一轮之后的增量记给旧成员的集合（计入会话量，速率算进下一轮），
     * 再切换。返回旧成员集合各节点的累计，供立即落库（之前没有选中成员时为空）；非选择器模式或 [id] 不在 [selector]
     * 里时返回 null，不查询、不切换。
     */
    fun select(id: Long): Map<Long, TrafficTotals>? = synchronized(lock) {
        val tag = selector?.get(id) ?: return null
        val tx = query(TAG_PROXY, UPLINK)
        val rx = query(TAG_PROXY, DOWNLINK)
        credit(selectedSet, rx, tx)
        sessionTx += tx
        sessionRx += rx
        pendingTx += tx
        pendingRx += rx
        val old = selectedSet
        selectedSet = memberSet(id, tag)
        old.associateWithTo(LinkedHashMap()) { totals.getValue(it).toTotals() }
    }

    /** 界面清除了这些节点的流量：累计归零，之后照常累计。不在本次运行里的 id 忽略；会话量不变。 */
    fun clear(ids: LongArray): Unit = synchronized(lock) {
        for (id in ids) {
            val t = totals[id] ?: continue
            t.fill(0)
            dirty += id
        }
    }

    /** 全部节点当前累计的拷贝。 */
    fun snapshot(): Map<Long, TrafficTotals> = synchronized(lock) {
        totals.entries.associateTo(LinkedHashMap()) { (id, t) -> id to t.toTotals() }
    }

    // 选中成员的集合：它所在出站的集合。成员的出站没有登记统计时（单跳、复用了别的链已建的出站），
    // 那个出站上只有它自己，记给它一个；它不在任何集合里时（没有初始累计）不记给节点
    private fun memberSet(id: Long, tag: String): LongArray =
        sets[tag] ?: if (id in totals) longArrayOf(id) else EMPTY

    private fun credit(ids: LongArray, rx: Long, tx: Long) {
        if (rx == 0L && tx == 0L) return
        for (id in ids) {
            val t = totals.getValue(id)
            t[0] += rx
            t[1] += tx
            dirty += id
        }
    }

    private fun LongArray.toTotals() = TrafficTotals(rx = this[0], tx = this[1])

    companion object {
        const val UPLINK = "uplink"
        const val DOWNLINK = "downlink"
        private val EMPTY = LongArray(0)

        /**
         * 按构建结果建一次运行的记账（TrafficLooper 用的就是它）：主节点所在分组是选择器时（selectorGroupId >= 0）
         * 按 profileTagMap 记 proxy、最初选中构建的主节点，否则是非选择器模式。
         */
        fun of(config: ConfigBuildResult, query: (tag: String, direction: String) -> Long, startedAt: Long) =
            TrafficAccounting(
                config.traffic,
                config.profileTagMap.takeIf { config.selectorGroupId >= 0L },
                config.mainEntId,
                query,
                startedAt,
            )

        /**
         * 停止 / 持久化时写库的行：[bound]（当前运行实例的统计关联，tag → 节点 id）里的每个节点一行，按 id 去重、保持
         * 列表顺序，值取记账快照 [totals]；快照里没有的节点（运行实例已换成新的、节点不在本次记账里）不写。
         */
        fun rowsToPersist(bound: Map<String, List<Long>>, totals: Map<Long, TrafficTotals>): Map<Long, TrafficTotals> =
            bound.values.flatten().distinct().mapNotNull { id -> totals[id]?.let { id to it } }.toMap(LinkedHashMap())
    }
}
