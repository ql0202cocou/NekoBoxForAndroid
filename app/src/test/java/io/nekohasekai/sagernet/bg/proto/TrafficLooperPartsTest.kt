package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.proto.TrafficAccountingTest.FakeStats
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// TrafficLooper 接线里不依赖 Android 的几块：从构建结果建记账、停止时写库的行、每轮推送给前台回调的内容
class TrafficLooperPartsTest {

    private fun zero(tags: Map<String, List<Long>>) =
        TrafficBindings(tags, tags.values.flatten().associateWith { TrafficTotals(0, 0) }, emptySet())

    // 选择器分组：成员 1（带落地 11）、成员 2；主节点是 2
    private fun selectorResult(selectorGroupId: Long) = ConfigBuildResult(
        "{}", emptyList(), 2L,
        zero(linkedMapOf("m1" to listOf(11L, 1), "m2" to listOf(2L))),
        mapOf(1L to "m1", 2L to "m2"),
        selectorGroupId,
    )

    @Test
    fun `从构建结果建记账：选择器分组按成员记 proxy，最初选中主节点`() {
        val config = selectorResult(selectorGroupId = 7)
        val stats = FakeStats(listOf(TAG_PROXY, TAG_BYPASS, "m1", "m2"))
        val acc = TrafficAccounting.of(config, stats::query, 0)
        stats.route(TAG_PROXY, 10, 100)
        acc.sweep(1000)
        assertEquals(TrafficTotals(100, 10), acc.snapshot().getValue(2))
        assertEquals(TrafficTotals(0, 0), acc.snapshot().getValue(1))
        assertEquals(TrafficTotals(0, 0), acc.snapshot().getValue(11))
        // 切换到成员 1：返回旧集合（主节点 2 所在出站的）
        assertEquals(setOf(2L), acc.select(1)?.keys)
        stats.route(TAG_PROXY, 5, 50)
        acc.sweep(2000)
        assertEquals(TrafficTotals(50, 5), acc.snapshot().getValue(1))
        assertEquals(TrafficTotals(50, 5), acc.snapshot().getValue(11))
    }

    @Test
    fun `从构建结果建记账：不是选择器分组时按出站记账，不切换`() {
        val config = selectorResult(selectorGroupId = -1)
        val stats = FakeStats(listOf(TAG_PROXY, TAG_BYPASS, "m1", "m2"))
        val acc = TrafficAccounting.of(config, stats::query, 0)
        assertNull(acc.select(1))
        stats.route("m1", 3, 30)
        acc.sweep(1000)
        assertEquals(TrafficTotals(30, 3), acc.snapshot().getValue(1))
        assertEquals(TrafficTotals(30, 3), acc.snapshot().getValue(11))
    }

    @Test
    fun `停止时写库的行：统计关联里每个节点一行，按 id 去重，快照里没有的不写`() {
        val bound = linkedMapOf(TAG_PROXY to listOf(2L, 3, 1), "c-5-4" to listOf(4L, 3, 5, 7))
        val totals = (1L..6L).associateWith { TrafficTotals(rx = it * 10, tx = it) }
        val rows = TrafficAccounting.rowsToPersist(bound, totals)
        // 7 不在快照里（运行实例已换成新的），6 不在统计关联里
        assertEquals(listOf(2L, 3, 1, 4, 5), rows.keys.toList())
        for ((id, t) in rows) assertEquals(totals.getValue(id), t)
        assertEquals(emptyMap<Long, TrafficTotals>(), TrafficAccounting.rowsToPersist(emptyMap(), totals))
    }

    @Test
    fun `推送：新出现的前台回调先收全量，上一轮收过的只收变化项，没收到推送的下一轮收全量`() {
        val posts = TrafficPosts<String>()
        var allTaken = 0
        fun all() = lazy { allTaken++; listOf("all") }
        val changed = listOf("changed")

        // 第一轮：a、b 都是新出现的，全量只取一次
        val all1 = all()
        assertEquals(listOf("all"), posts.itemsFor("a", changed, all1))
        assertEquals(listOf("all"), posts.itemsFor("b", changed, all1))
        assertEquals(1, allTaken)
        posts.endRound(setOf("a", "b"))

        // 第二轮：a、b 只收变化项，不取全量；只有 a 收到了推送
        val all2 = all()
        assertEquals(changed, posts.itemsFor("a", changed, all2))
        assertEquals(changed, posts.itemsFor("b", changed, all2))
        assertEquals(1, allTaken)
        posts.endRound(setOf("a"))

        // 第三轮：b 上一轮没收到推送，收全量
        assertEquals(changed, posts.itemsFor("a", changed, all()))
        assertEquals(listOf("all"), posts.itemsFor("b", changed, all()))

        // 一轮没有前台回调：之后都收全量
        posts.endRound(emptySet())
        assertEquals(listOf("all"), posts.itemsFor("a", changed, all()))
    }
}
