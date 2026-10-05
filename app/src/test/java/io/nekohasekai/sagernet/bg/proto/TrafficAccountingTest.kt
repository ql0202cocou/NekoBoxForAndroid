package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// 流量聚合（TrafficAccounting）的记账规则；与构建对接的回归在 TrafficScenarioTest
class TrafficAccountingTest {

    /** 假的统计服务：只给登记的出站记账，查询取走增量（同 sing-box 的 GetStats(reset=true)），并记下每次查询。 */
    class FakeStats(tracked: Collection<String>) {
        private val counters = ConcurrentHashMap<String, Array<AtomicLong>>().apply {
            for (tag in tracked) put(tag, arrayOf(AtomicLong(), AtomicLong()))
        }
        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        fun route(tag: String, up: Long, down: Long) {
            val c = counters[tag] ?: return // 没登记的出站：sing-box 不建计数器
            c[0].addAndGet(up)
            c[1].addAndGet(down)
        }

        fun query(tag: String, direction: String): Long {
            log += "$tag/$direction"
            val c = counters[tag] ?: return 0
            return c[if (direction == TrafficAccounting.UPLINK) 0 else 1].getAndSet(0)
        }

        fun takeLog(): List<String> = synchronized(log) { log.toList().also { log.clear() } }
    }

    private fun bindings(
        tags: Map<String, List<Long>>,
        initial: Map<Long, TrafficTotals> = tags.values.flatten().associateWith { base(it) },
        ruleTags: Set<String> = emptySet(),
    ) = TrafficBindings(tags, initial, ruleTags)

    // 每个节点的初始累计：rx = id × 10000，tx = id × 1000，便于看出基数有没有带上
    private fun base(id: Long) = TrafficTotals(rx = id * 10000, tx = id * 1000)

    private fun delta(acc: TrafficAccounting, id: Long): TrafficTotals {
        val t = acc.snapshot().getValue(id)
        val b = base(id)
        return TrafficTotals(t.rx - b.rx, t.tx - b.tx)
    }

    private fun stats(b: TrafficBindings) = FakeStats(b.tags.keys + TAG_PROXY + TAG_BYPASS)

    private fun queries(vararg tags: String) = tags.flatMap { listOf("$it/uplink", "$it/downlink") }

    @Test
    fun `单出站多节点：每个节点都加同一份增量，基数带上`() {
        val b = bindings(mapOf(TAG_PROXY to listOf(11L, 1, 10)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        assertEquals(mapOf(11L to base(11), 1L to base(1), 10L to base(10)), acc.snapshot())
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(1000)
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(2000)
        for (id in listOf(11L, 1, 10)) assertEquals(TrafficTotals(2000, 200), delta(acc, id))
    }

    @Test
    fun `同一节点在多个出站里：各出站的增量都记给它，与出站的遍历顺序无关`() {
        val tags = linkedMapOf(TAG_PROXY to listOf(2L, 3, 1), "c-5-4" to listOf(4L, 3, 5), "g-6" to listOf(6L, 3))
        val results = listOf(tags.keys.toList(), tags.keys.reversed(), listOf("c-5-4", TAG_PROXY, "g-6")).map { order ->
            val b = bindings(order.associateWithTo(LinkedHashMap()) { tags.getValue(it) })
            val s = stats(b)
            val acc = TrafficAccounting(b, null, 1, s::query, 0)
            repeat(3) { round ->
                s.route(TAG_PROXY, 100, 1000)
                s.route("c-5-4", 7, 70)
                s.route("g-6", 2, 20)
                acc.sweep((round + 1) * 1000L)
            }
            acc.snapshot()
        }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
        val acc = results[0]
        fun d(id: Long) = acc.getValue(id).let { TrafficTotals(it.rx - base(id).rx, it.tx - base(id).tx) }
        assertEquals(TrafficTotals(3000 + 210 + 60, 300 + 21 + 6), d(3))
        assertEquals(TrafficTotals(3000, 300), d(2))
        assertEquals(TrafficTotals(210, 21), d(5))
        assertEquals(TrafficTotals(60, 6), d(6))
    }

    @Test
    fun `列表里重复的 id 只加一次`() {
        val b = bindings(mapOf(TAG_PROXY to listOf(1L, 2, 1)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(1000)
        assertEquals(TrafficTotals(1000, 100), delta(acc, 1))
        assertEquals(TrafficTotals(1000, 100), delta(acc, 2))
    }

    @Test
    fun `非选择器模式每轮查全部登记的出站加 bypass，各上下行一次`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(1L), "g-2" to listOf(2L), "c-3-3" to listOf(10L, 3)), ruleTags = setOf("g-2"))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        acc.sweep(1000)
        assertEquals(queries(TAG_PROXY, "g-2", "c-3-3", TAG_BYPASS), s.takeLog())
        // 非选择器模式不切换，也不查询
        assertNull(acc.select(1))
        assertEquals(emptyList<String>(), s.takeLog())
    }

    // 选择器分组：成员 1、2 带前置 10、落地 11；规则另指向组外节点 20（g-20）
    private fun selectorBindings(ruleTags: Set<String> = emptySet()) = bindings(
        linkedMapOf("m1" to listOf(11L, 1, 10), "m2" to listOf(11L, 2, 10), "g-20" to listOf(20L)),
        ruleTags = ruleTags,
    )

    private val members = mapOf(1L to "m1", 2L to "m2", 20L to "g-20")

    @Test
    fun `选择器：proxy 记给选中成员所在出站的整个集合，未选中的成员不计`() {
        val b = selectorBindings()
        val s = stats(b)
        val acc = TrafficAccounting(b, members, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        val round = acc.sweep(1000)
        assertEquals(queries(TAG_PROXY, TAG_BYPASS), s.takeLog())
        for (id in listOf(11L, 1, 10)) assertEquals(TrafficTotals(1000, 100), delta(acc, id))
        assertEquals(TrafficTotals(0, 0), delta(acc, 2))
        assertEquals(TrafficTotals(0, 0), delta(acc, 20))
        assertEquals(setOf(11L, 1, 10), round.changed.keys)
        assertEquals(100, round.txRateProxy)
        assertEquals(1000, round.rxRateProxy)
    }

    @Test
    fun `选择器切换：先结算旧成员再切换，返回旧集合的累计，只多查一次 proxy`() {
        val b = selectorBindings()
        val s = stats(b)
        val acc = TrafficAccounting(b, members, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(1000)
        // 上一轮之后、切换之前的 proxy 字节属于旧成员
        s.route(TAG_PROXY, 40, 400)
        s.takeLog()
        val old = acc.select(2)
        assertEquals(queries(TAG_PROXY), s.takeLog())
        assertEquals(listOf(11L, 1, 10), old!!.keys.toList())
        assertEquals(TrafficTotals(base(1).rx + 1400, base(1).tx + 140), old.getValue(1))
        s.route(TAG_PROXY, 100, 1000)
        val round = acc.sweep(2000)
        assertEquals(TrafficTotals(1400, 140), delta(acc, 1))
        assertEquals(TrafficTotals(1000, 100), delta(acc, 2))
        // 前置、落地两边都计
        assertEquals(TrafficTotals(2400, 240), delta(acc, 10))
        assertEquals(TrafficTotals(2400, 240), delta(acc, 11))
        // 切换时结算的字节算进下一轮的速率与会话量
        assertEquals(140, round.txRateProxy)
        assertEquals(1400, round.rxRateProxy)
        assertEquals(240, round.txSession)
        assertEquals(2400, round.rxSession)
        // 切换时结算给旧成员的变化在下一轮的变化项里
        assertEquals(setOf(11L, 1, 10, 2), round.changed.keys)
    }

    @Test
    fun `两轮之间多次切换：每次切换前结算的字节都算进下一轮的代理速率`() {
        val b = bindings(linkedMapOf("m1" to listOf(1L), "m2" to listOf(2L)))
        val s = stats(b)
        val acc = TrafficAccounting(b, mapOf(1L to "m1", 2L to "m2"), 1, s::query, 0)
        acc.sweep(1000)
        s.route(TAG_PROXY, 10, 100)
        acc.select(2)
        s.route(TAG_PROXY, 20, 200)
        acc.select(1)
        s.route(TAG_PROXY, 30, 300)
        val round = acc.sweep(2000)
        // 两次切换各结算一份，加上本轮扫描的一份
        assertEquals(60, round.txRateProxy)
        assertEquals(600, round.rxRateProxy)
        assertEquals(TrafficTotals(400, 40), delta(acc, 1))
        assertEquals(TrafficTotals(200, 20), delta(acc, 2))
    }

    @Test
    fun `选择器切换到非成员不查询、不切换；切到同一成员照常结算`() {
        val b = selectorBindings()
        val s = stats(b)
        val acc = TrafficAccounting(b, members, 1, s::query, 0)
        assertNull(acc.select(99))
        assertEquals(emptyList<String>(), s.takeLog())
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(1000)
        assertEquals(TrafficTotals(1000, 100), delta(acc, 1))
        s.route(TAG_PROXY, 5, 50)
        assertEquals(setOf(11L, 1, 10), acc.select(1)!!.keys)
        assertEquals(TrafficTotals(1050, 105), delta(acc, 1))
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(2000)
        assertEquals(TrafficTotals(2050, 205), delta(acc, 1))
        assertEquals(TrafficTotals(0, 0), delta(acc, 2))
    }

    @Test
    fun `选择器模式只多查规则直接指向的出站，记给该出站的集合并计入代理速率`() {
        val b = selectorBindings(ruleTags = setOf("g-20", "m2"))
        val s = stats(b)
        val acc = TrafficAccounting(b, members, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        s.route("g-20", 7, 70)
        s.route("m2", 3, 30)
        s.route("m1", 9, 90) // 没有规则指向 m1：不查询，字节留在计数器里
        s.route(TAG_BYPASS, 1, 10)
        val round = acc.sweep(1000)
        // 每轮 2 ×（规则出站数 + 2）次
        assertEquals(queries(TAG_PROXY, "g-20", "m2", TAG_BYPASS), s.takeLog())
        assertEquals(TrafficTotals(70, 7), delta(acc, 20))
        assertEquals(TrafficTotals(30, 3), delta(acc, 2))
        // 规则指向未选中的成员 2 的出站：前置 / 落地也计
        assertEquals(TrafficTotals(1030, 103), delta(acc, 10))
        assertEquals(TrafficTotals(1000, 100), delta(acc, 1))
        assertEquals(110, round.txRateProxy)
        assertEquals(1100, round.rxRateProxy)
        assertEquals(1, round.txRateDirect)
        assertEquals(10, round.rxRateDirect)
        // 切换只多查 proxy
        acc.select(2)
        assertEquals(queries(TAG_PROXY), s.takeLog())
        acc.sweep(2000)
        assertEquals(queries(TAG_PROXY, "g-20", "m2", TAG_BYPASS), s.takeLog())
    }

    @Test
    fun `规则指向选中成员自己的出站：与 proxy 记给同一集合，各算一次`() {
        val b = selectorBindings(ruleTags = setOf("m1"))
        val s = stats(b)
        val acc = TrafficAccounting(b, members, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        s.route("m1", 1, 10)
        val round = acc.sweep(1000)
        assertEquals(TrafficTotals(1010, 101), delta(acc, 1))
        assertEquals(TrafficTotals(1010, 101), delta(acc, 10))
        assertEquals(101, round.txRateProxy)
    }

    @Test
    fun `选中成员的出站没有登记时只记给它自己；初始选中不是成员时 proxy 不记给节点`() {
        // 成员 3 复用了成员 1 链里的出站（g-3 没有登记），它在 m1 的集合里有初始累计
        val b = bindings(linkedMapOf("m1" to listOf(3L, 1)))
        val s = stats(b)
        val acc = TrafficAccounting(b, mapOf(1L to "m1", 3L to "g-3"), 3, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        acc.sweep(1000)
        assertEquals(TrafficTotals(1000, 100), delta(acc, 3))
        assertEquals(TrafficTotals(0, 0), delta(acc, 1))

        val s2 = stats(b)
        val acc2 = TrafficAccounting(b, mapOf(1L to "m1"), 99, s2::query, 0)
        s2.route(TAG_PROXY, 100, 1000)
        val round = acc2.sweep(1000)
        assertEquals(mapOf(3L to base(3), 1L to base(1)), acc2.snapshot())
        assertEquals(1000, round.rxSession)
    }

    @Test
    fun `bypass 只进直连速率，不记给任何节点，也不出现 -1`() {
        val b = bindings(mapOf(TAG_PROXY to listOf(1L)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        s.route(TAG_BYPASS, 3, 30)
        val round = acc.sweep(1000)
        assertEquals(3, round.txRateDirect)
        assertEquals(30, round.rxRateDirect)
        assertEquals(0, round.txRateProxy)
        assertEquals(0, round.rxSession)
        assertEquals(TrafficTotals(0, 0), delta(acc, 1))
        assertTrue(round.changed.isEmpty())
        assertEquals(setOf(1L), acc.snapshot().keys)
    }

    @Test
    fun `速率按轮间隔：首轮从起点算，间隔不大于 0 为 0 但字节照记，多节点链不放大`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(3L, 2, 1), "g-4" to listOf(4L)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 10_000)
        s.route(TAG_PROXY, 100, 1000)
        s.route("g-4", 10, 100)
        val first = acc.sweep(12_000)
        // 两秒的字节：每个出站计一次，三跳链不乘三
        assertEquals(55, first.txRateProxy)
        assertEquals(550, first.rxRateProxy)
        s.route(TAG_PROXY, 100, 1000)
        val same = acc.sweep(12_000)
        assertEquals(0, same.txRateProxy)
        assertEquals(0, same.rxRateProxy)
        assertEquals(TrafficTotals(2000, 200), delta(acc, 1))
        val back = acc.sweep(11_000)
        assertEquals(0, back.rxRateProxy)
    }

    @Test
    fun `会话量是出站字节之和，清除节点流量不减；清零后继续累计`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(1L, 10), "c-5-4" to listOf(4L, 10)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        s.route(TAG_PROXY, 100, 1000)
        s.route("c-5-4", 7, 70)
        assertEquals(1070, acc.sweep(1000).rxSession)
        acc.clear(longArrayOf(10, 99))
        assertEquals(TrafficTotals(0, 0), acc.snapshot().getValue(10))
        s.route(TAG_PROXY, 100, 1000)
        s.route("c-5-4", 7, 70)
        val round = acc.sweep(2000)
        assertEquals(2140, round.rxSession)
        assertEquals(214, round.txSession)
        // 共享节点清零后两个出站照常累加
        assertEquals(TrafficTotals(1070, 107), acc.snapshot().getValue(10))
        assertEquals(TrafficTotals(2000, 200), delta(acc, 1))
        assertEquals(setOf(1L, 10, 4), acc.snapshot().keys)
    }

    @Test
    fun `快照是拷贝；变化项只含上一轮之后变化的节点（含清零）`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(1L), "g-2" to listOf(2L), "g-3" to listOf(3L)))
        val s = stats(b)
        val acc = TrafficAccounting(b, null, 1, s::query, 0)
        val before = acc.snapshot()
        s.route(TAG_PROXY, 100, 1000)
        val first = acc.sweep(1000)
        assertEquals(mapOf(1L to TrafficTotals(base(1).rx + 1000, base(1).tx + 100)), first.changed)
        assertEquals(base(1), before.getValue(1))
        (before as MutableMap<Long, TrafficTotals>).clear()
        assertEquals(3, acc.snapshot().size)
        s.route("g-2", 1, 10)
        acc.clear(longArrayOf(3))
        val second = acc.sweep(2000)
        assertEquals(setOf(2L, 3L), second.changed.keys)
        assertEquals(TrafficTotals(0, 0), second.changed.getValue(3))
        assertTrue(acc.sweep(3000).changed.isEmpty())
    }

    @Test
    fun `多线程同时扫描与切换：proxy 与规则出站的字节一个不多一个不少`() {
        // 成员集合各只有自己，规则出站 g-20 只有节点 20：每个字节恰好记给一个节点
        val b = bindings(linkedMapOf("m1" to listOf(1L), "m2" to listOf(2L), "g-20" to listOf(20L)), ruleTags = setOf("g-20"))
        val s = stats(b)
        val acc = TrafficAccounting(b, mapOf(1L to "m1", 2L to "m2"), 1, s::query, 0)
        val pool = Executors.newFixedThreadPool(6)
        val barrier = CyclicBarrier(6)
        val done = CountDownLatch(6)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val injectedProxy = AtomicLong()
        val injectedRule = AtomicLong()
        fun task(body: (Int) -> Unit) = pool.execute {
            try {
                barrier.await()
                repeat(2000) { body(it) }
            } catch (t: Throwable) {
                errors += t
            } finally {
                done.countDown()
            }
        }
        repeat(2) { task { s.route(TAG_PROXY, 1, 2); injectedProxy.addAndGet(1) } }
        task { s.route("g-20", 3, 4); injectedRule.addAndGet(3) }
        task { i -> acc.sweep(i.toLong()) }
        task { i -> acc.select(if (i % 2 == 0) 2L else 1L) }
        task { acc.snapshot() }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(emptyList<Throwable>(), errors)
        val last = acc.sweep(1_000_000)
        val snap = acc.snapshot()
        fun tx(id: Long) = snap.getValue(id).tx - base(id).tx
        fun rx(id: Long) = snap.getValue(id).rx - base(id).rx
        assertEquals(injectedProxy.get(), tx(1) + tx(2))
        assertEquals(injectedProxy.get() * 2, rx(1) + rx(2))
        assertEquals(injectedRule.get(), tx(20))
        assertEquals(injectedRule.get() / 3 * 4, rx(20))
        assertEquals(injectedProxy.get() + injectedRule.get(), last.txSession)
    }

    @Test
    fun `扫描取走 proxy 的增量之后、记给选中集合之前到达的切换等扫描做完：字节记给旧成员`() {
        val b = bindings(linkedMapOf("m1" to listOf(1L), "m2" to listOf(2L)))
        val pending = AtomicLong(1000)
        var hook: (() -> Unit)? = null
        val query: (String, String) -> Long = { tag, direction ->
            if (tag == TAG_PROXY && direction == TrafficAccounting.DOWNLINK) {
                val taken = pending.getAndSet(0)
                // 增量已取走、还没记账：此时另一线程切换到成员 2
                hook?.invoke()
                taken
            } else 0L
        }
        val acc = TrafficAccounting(b, mapOf(1L to "m1", 2L to "m2"), 1, query, 0)
        var switcher: Thread? = null
        hook = {
            hook = null
            // 有锁时切换线程阻塞在锁上，等满 300 ms 后扫描继续；没有锁时切换在这段时间里做完
            switcher = Thread { acc.select(2) }.apply { start(); join(300) }
        }
        acc.sweep(1000)
        switcher!!.join()
        assertEquals(TrafficTotals(1000, 0), delta(acc, 1))
        assertEquals(TrafficTotals(0, 0), delta(acc, 2))
    }

    @Test
    fun `多线程同时扫描、切换与清零：无异常，会话量守恒，清零只影响节点累计`() {
        val b = bindings(linkedMapOf("m1" to listOf(1L, 10), "m2" to listOf(2L, 10)))
        val s = stats(b)
        val acc = TrafficAccounting(b, mapOf(1L to "m1", 2L to "m2"), 1, s::query, 0)
        val pool = Executors.newFixedThreadPool(5)
        val barrier = CyclicBarrier(5)
        val done = CountDownLatch(5)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val injected = AtomicLong()
        fun task(body: (Int) -> Unit) = pool.execute {
            try {
                barrier.await()
                repeat(2000) { body(it) }
            } catch (t: Throwable) {
                errors += t
            } finally {
                done.countDown()
            }
        }
        task { s.route(TAG_PROXY, 1, 1); injected.addAndGet(1) }
        task { i -> acc.sweep(i.toLong()) }
        task { i -> acc.select(if (i % 2 == 0) 2L else 1L) }
        task { acc.clear(longArrayOf(1, 10)) }
        task { acc.snapshot() }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(injected.get(), acc.sweep(1_000_000).txSession)
        // 清零之后的字节照记：节点 10 在两个集合里，累计不超过注入总量
        assertFalse(acc.snapshot().getValue(10).tx > injected.get())
    }
}
