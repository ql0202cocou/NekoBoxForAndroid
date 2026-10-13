package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.proto.TrafficAccountingTest.FakeStats
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 落库改成增量后的记账：基线、清零、失败回退、切换与并发。库用 SimDb 模拟（:bg 加增量，界面写绝对值）
class TrafficIncrementTest {

    /** 模拟 proxy_entities 的 tx / rx 两列：addTraffic 加增量，updateTraffic 写绝对值。 */
    private class SimDb(initial: Map<Long, TrafficTotals>) {
        val rows = initial.mapValuesTo(LinkedHashMap()) { it.value }

        fun apply(claim: TrafficAccounting.Claim) {
            for (inc in claim.increments) {
                val old = rows.getValue(inc.id)
                rows[inc.id] = TrafficTotals(rx = old.rx + inc.rx, tx = old.tx + inc.tx)
            }
        }

        fun clearFromUi(ids: Collection<Long>) {
            for (id in ids) rows[id] = TrafficTotals(0, 0)
        }
    }

    private fun base(id: Long) = TrafficTotals(rx = id * 10000, tx = id * 1000)

    private fun bindings(tags: Map<String, List<Long>>, initial: (Long) -> TrafficTotals = ::base) =
        TrafficBindings(tags, tags.values.flatten().associateWith(initial), emptySet())

    private class Env(b: TrafficBindings, selector: Map<Long, String>? = null, selected: Long = 1) {
        val stats = FakeStats(b.tags.keys + TAG_PROXY + TAG_BYPASS)
        val acc = TrafficAccounting(b, selector, selected, stats::query, 0)
        val db = SimDb(b.initial)
        var now = 0L
        fun sweep() = acc.sweep(++now * 1000)
        fun flush(ids: Collection<Long>) = acc.claim(ids).also(db::apply)
    }

    @Test
    fun `落库后基线推进：再取没有增量，之后只取新增的`() {
        val env = Env(bindings(mapOf(TAG_PROXY to listOf(1L, 2))))
        env.stats.route(TAG_PROXY, 100, 1000)
        env.sweep()
        val first = env.flush(listOf(1, 2))
        assertEquals(listOf(1L, 2L), first.increments.map { it.id })
        assertEquals(listOf(1000L), first.increments.map { it.rx }.distinct())
        assertEquals(listOf(100L), first.increments.map { it.tx }.distinct())
        assertEquals(base(1).rx + 1000, env.db.rows.getValue(1).rx)
        assertTrue(env.acc.claim(listOf(1, 2)).increments.isEmpty())

        env.stats.route(TAG_PROXY, 5, 50)
        env.sweep()
        val second = env.acc.claim(listOf(1, 2)).also(env.db::apply)
        assertEquals(50L, second.increments.first().rx)
        assertEquals(5L, second.increments.first().tx)
        // 库里的值始终等于界面显示的绝对值（初始 + 累计）
        assertEquals(env.acc.snapshot(), env.db.rows)
    }

    @Test
    fun `落库 → 清除 → 再落库：清掉的旧累计不会写回，库里只有清除之后新增的`() {
        val env = Env(bindings(mapOf(TAG_PROXY to listOf(1L, 2))))
        env.stats.route(TAG_PROXY, 100, 1000)
        env.sweep()
        env.flush(listOf(1, 2))
        env.stats.route(TAG_PROXY, 30, 300) // 清除前、还没落库的一段
        env.sweep()
        // 界面先把库清零，广播还没到 :bg
        env.db.clearFromUi(listOf(1, 2))
        // 这个窗口里落库：只写回上次落库之后的那一点（300 / 30），不是 1300 / 130 加初始值
        env.flush(listOf(1, 2))
        assertEquals(TrafficTotals(300, 30), env.db.rows.getValue(1))
        // 广播到了：累计与基线一起归零，之后的落库只加清零之后新增的
        env.acc.clear(longArrayOf(1, 2))
        assertEquals(TrafficTotals(0, 0), env.acc.snapshot().getValue(1))
        assertTrue(env.acc.claim(listOf(1, 2)).increments.isEmpty())
        env.stats.route(TAG_PROXY, 7, 70)
        env.sweep()
        env.flush(listOf(1, 2))
        assertEquals(TrafficTotals(370, 37), env.db.rows.getValue(1))
    }

    @Test
    fun `广播先到、库后清：窗口里没落库就没有残留`() {
        val env = Env(bindings(mapOf(TAG_PROXY to listOf(1L))))
        env.stats.route(TAG_PROXY, 100, 1000)
        env.sweep()
        env.flush(listOf(1))
        env.acc.clear(longArrayOf(1))
        env.db.clearFromUi(listOf(1))
        env.stats.route(TAG_PROXY, 7, 70)
        env.sweep()
        env.flush(listOf(1))
        assertEquals(TrafficTotals(70, 7), env.db.rows.getValue(1))
        assertEquals(env.acc.snapshot().getValue(1), env.db.rows.getValue(1))
    }

    @Test
    fun `库写失败 release 后下次补上；取走之后清过零的节点不退基线`() {
        val env = Env(bindings(mapOf(TAG_PROXY to listOf(1L, 2))))
        env.stats.route(TAG_PROXY, 100, 1000)
        env.sweep()
        val failed = env.acc.claim(listOf(1, 2))
        assertEquals(2, failed.increments.size)
        env.acc.release(failed)
        // 失败的那段下次照常取到，不丢也不重
        env.flush(listOf(1, 2))
        assertEquals(env.acc.snapshot(), env.db.rows)

        // 取走后清过零：release 不能把基线退成比累计还大
        env.stats.route(TAG_PROXY, 10, 100)
        env.sweep()
        val failed2 = env.acc.claim(listOf(1))
        env.acc.clear(longArrayOf(1))
        env.acc.release(failed2)
        assertTrue(env.acc.claim(listOf(1)).increments.isEmpty())
        env.stats.route(TAG_PROXY, 1, 2)
        env.sweep()
        assertEquals(listOf(TrafficTotals(2, 1)), env.acc.claim(listOf(1)).increments.map { TrafficTotals(it.rx, it.tx) })
    }

    @Test
    fun `服务重启：新记账的基线等于库里的初始值，第一次落库没有增量`() {
        val env = Env(bindings(mapOf(TAG_PROXY to listOf(1L))))
        env.stats.route(TAG_PROXY, 100, 1000)
        env.sweep()
        env.flush(listOf(1))
        val env2 = Env(bindings(mapOf(TAG_PROXY to listOf(1L))) { env.db.rows.getValue(it) })
        assertTrue(env2.acc.claim(listOf(1)).increments.isEmpty())
        env2.stats.route(TAG_PROXY, 1, 10)
        env2.sweep()
        assertEquals(10L, env2.acc.claim(listOf(1)).increments.single().rx)
    }

    @Test
    fun `选择器切换：只取旧成员集合的增量，共享节点前后两次切换各加一段，合起来等于累计`() {
        // 成员 1、2 共享前置 10 与落地 11
        val b = bindings(linkedMapOf("m1" to listOf(11L, 1, 10), "m2" to listOf(11L, 2, 10)))
        val env = Env(b, mapOf(1L to "m1", 2L to "m2"), 1)
        env.sweep()
        env.stats.route(TAG_PROXY, 10, 100)
        val old1 = env.acc.select(2)!!
        val c1 = env.acc.claim(old1.keys).also(env.db::apply)
        assertEquals(setOf(11L, 1L, 10L), c1.increments.map { it.id }.toSet())
        // 新成员 2 还没有增量
        assertTrue(env.acc.claim(listOf(2)).increments.isEmpty())
        env.stats.route(TAG_PROXY, 20, 200)
        val old2 = env.acc.select(1)!!
        env.acc.claim(old2.keys).also(env.db::apply)
        env.stats.route(TAG_PROXY, 40, 400)
        env.sweep()
        env.flush(b.initial.keys)
        // 前置 / 落地 10、11：三段（100 + 200 + 400）都进了库
        assertEquals(base(10).rx + 700, env.db.rows.getValue(10).rx)
        assertEquals(env.acc.snapshot(), env.db.rows)
    }

    @Test
    fun `同一节点在多条链里：只有一个节点条目，增量是各出站之和，每个 id 一项`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(2L, 3, 1), "c-5-4" to listOf(4L, 3, 5)))
        val env = Env(b)
        env.stats.route(TAG_PROXY, 100, 1000)
        env.stats.route("c-5-4", 7, 70)
        env.sweep()
        val claim = env.acc.claim(b.tags.values.flatten()) // 列表里有重复的 3
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L), claim.increments.map { it.id })
        assertEquals(1070L, claim.increments.single { it.id == 3L }.rx)
        assertEquals(107L, claim.increments.single { it.id == 3L }.tx)
        // 不在本次运行里的 id 忽略
        assertTrue(env.acc.claim(listOf(99L)).increments.isEmpty())
    }

    @Test
    fun `并发的扫描与取走：每一段只落库一次，库里的总量等于累计`() {
        val b = bindings(linkedMapOf(TAG_PROXY to listOf(1L, 2), "g-3" to listOf(3L)))
        val env = Env(b)
        val ids = b.initial.keys
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val dbLock = Any()
        repeat(2) {
            pool.execute {
                start.await()
                repeat(500) {
                    val claim = env.acc.claim(ids)
                    synchronized(dbLock) { env.db.apply(claim) }
                }
                done.countDown()
            }
        }
        repeat(2) {
            pool.execute {
                start.await()
                repeat(500) {
                    env.stats.route(TAG_PROXY, 1, 3)
                    env.stats.route("g-3", 2, 5)
                    env.acc.sweep(System.nanoTime() / 1_000_000)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        synchronized(dbLock) { env.db.apply(env.acc.claim(ids)) }
        assertEquals(env.acc.snapshot(), env.db.rows)
    }
}
