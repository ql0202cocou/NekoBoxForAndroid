package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.proto.TrafficAccountingTest.FakeStats
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.golden.GoldenAddressCorpus
import io.nekohasekai.sagernet.golden.GoldenBaseline
import io.nekohasekai.sagernet.golden.GoldenJvmScenario
import io.nekohasekai.sagernet.golden.GoldenScenarioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

// 流量聚合跑在真实的构建结果上：构建走纯入口 buildConfig(input)（基线 input.json，个别场景在 JSON 上改动），统计服务
// 换成 FakeStats，脚本直接说每轮有多少字节被 sing-box 记到哪个出站（sing-box 只按连接被路由到的那个出站计数，见
// TrafficBindings）。断言的口径：选择器的 proxy 记给选中成员所在出站的整个集合；选择器模式只多查规则直接指向的出站；
// 切换前先结算；清零不减会话量。注释里的「旧」是改为按统计关联记账之前 TrafficLooper 在同一场景下的结果。
// 每轮：proxy 上 100 / 下 1000，bypass 3 / 30，另加场景指定的出站；时钟每轮 +1000 ms
class TrafficScenarioTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseline by lazy { GoldenBaseline.load() }

    // 每个节点的初始累计：rx = id × 10000，tx = id × 1000
    private fun base(id: Long) = TrafficTotals(rx = id * 10000, tx = id * 1000)

    private fun build(id: String, edit: (JsonObject) -> Unit = {}): ConfigBuildResult {
        val orig = baseline.input(id)
        val json = JsonParser.parseString(File(orig.dir, "input.json").readText()).asJsonObject
        for (p in json.getAsJsonArray("profiles")) {
            val o = p.asJsonObject
            val pid = o["id"].asLong
            o.addProperty("rx", base(pid).rx)
            o.addProperty("tx", base(pid).tx)
        }
        edit(json)
        val input = GoldenScenarioInput(id, orig.dir, json)
        val scenario = GoldenJvmScenario(input, GoldenAddressCorpus.of(baseline), tmp.root)
        val source = input.dataSource()
        return buildConfig(
            scenario.configInput(ConfigBuildMode.RUN, scenario.main(source), source, scenario.platform(tmp.newFolder()))
        ) { LocalSocksAuth.generate(Random(7)) }
    }

    private fun JsonObject.group(id: Long): JsonObject =
        getAsJsonArray("groups").map { it.asJsonObject }.single { it["id"].asLong == id }

    // 加一条指向 target 的路由规则（取 rules-to-main 的那一条，只改目标）
    private fun withRuleTo(target: Long): (JsonObject) -> Unit = { json ->
        val rules = JsonParser.parseString(File(baseline.input("rules-to-main").dir, "input.json").readText())
            .asJsonObject.getAsJsonArray("rules")
        rules[0].asJsonObject.addProperty("outbound", target)
        json.add("rules", rules)
    }

    private fun selectorPlain() = build("selector-front-landing") {
        it.group(1).apply { addProperty("frontProxy", -1); addProperty("landingProxy", -1) }
    }

    private fun selectorFront() = build("selector-front-landing") { it.group(1).addProperty("landingProxy", -1) }
    private fun selectorLanding() = build("selector-front-landing") { it.group(1).addProperty("frontProxy", -1) }

    /** 一次运行：构建结果 + 假统计服务 + 聚合，经 TrafficLooper 用的同一个入口（statsTags、TrafficAccounting.of）。 */
    private inner class Run(val config: ConfigBuildResult) {
        val stats = FakeStats(TrafficLooper.statsTags(config).split("\n"))
        private var now = 3_600_000L
        val acc = TrafficAccounting.of(config, stats::query, now)
        val rounds = ArrayList<TrafficAccounting.Round>()
        var queriesLastRound = 0

        fun round(vararg extra: Pair<String, Pair<Long, Long>>): TrafficAccounting.Round {
            stats.route(TAG_PROXY, 100, 1000)
            stats.route(TAG_BYPASS, 3, 30)
            for ((tag, bytes) in extra) stats.route(tag, bytes.first, bytes.second)
            now += 1000
            stats.takeLog()
            return acc.sweep(now).also {
                queriesLastRound = stats.takeLog().size
                rounds += it
            }
        }

        fun rounds(n: Int, vararg extra: Pair<String, Pair<Long, Long>>) = repeat(n) { round(*extra) }

        fun delta(id: Long): TrafficTotals {
            val t = acc.snapshot().getValue(id)
            return TrafficTotals(t.rx - base(id).rx, t.tx - base(id).tx)
        }

        // 末轮的速度：代理上下行速率、直连上下行速率、会话上下行
        fun speed() = rounds.last().let {
            listOf(it.txRateProxy, it.rxRateProxy, it.txRateDirect, it.rxRateDirect, it.txSession, it.rxSession)
        }

        fun ruleTag(id: Long) = if (id == config.mainEntId) TAG_PROXY else config.profileTagMap.getValue(id)
    }

    // 同一构建结果，统计关联里出站的先后反过来
    private fun ConfigBuildResult.reversedTraffic() = ConfigBuildResult(
        config, externalChains, mainEntId,
        TrafficBindings(traffic.tags.entries.reversed().associate { it.key to it.value }, traffic.initial, traffic.ruleTags),
        profileTagMap, selectorGroupId,
    )

    private fun d(rx: Long, tx: Long) = TrafficTotals(rx, tx)
    private val zero = d(0, 0)
    private val normalSpeed = listOf(100L, 1000, 3, 30, 300, 3000)

    @Test
    fun `非选择器、没有共享节点：各节点与旧结果相同，首轮速率不再为 0`() {
        val cases = mapOf(
            "sb-vmess-tcp" to listOf(1L),
            "chain-2-internal" to listOf(1L, 2, 3),
            "group-front" to listOf(1L, 10),
            "group-landing" to listOf(1L, 11),
            "group-front-landing" to listOf(1L, 10, 11),
        )
        for ((id, nodes) in cases) {
            val run = Run(build(id))
            run.rounds(3)
            for (n in nodes) assertEquals("$id 节点 $n", d(3000, 300), run.delta(n))
            assertEquals(id, nodes.toSet(), run.acc.snapshot().keys)
            assertEquals(id, normalSpeed, run.speed())
            // 旧：第一轮速率恒为 0
            assertEquals(id, 100L, run.rounds.first().txRateProxy)
            // 每轮 JNI 查询次数不变：2 ×（出站数 + 1）
            assertEquals(id, 4, run.queriesLastRound)
        }
    }

    @Test
    fun `选择器：前置、落地随选中成员计；带前置时速度不再恒为 0`() {
        val plain = Run(selectorPlain()).apply { rounds(3) }
        assertEquals(d(3000, 300), plain.delta(1))
        assertEquals(zero, plain.delta(2))
        assertEquals(normalSpeed, plain.speed())
        assertEquals("选择器、没有规则：2 ×（0 + 2）", 4, plain.queriesLastRound)

        // 只带前置：旧 10 为 0、速度 [0, 0, 3, 30, 0, 0]
        val front = Run(selectorFront()).apply { rounds(3) }
        assertEquals(d(3000, 300), front.delta(1))
        assertEquals(d(3000, 300), front.delta(10))
        assertEquals(zero, front.delta(2))
        assertEquals(normalSpeed, front.speed())

        // 前置、落地都带（selector-front-landing 原样）：旧 10、11 为 0、速度恒为 0
        val both = Run(build("selector-front-landing")).apply { rounds(3) }
        for (n in listOf(1L, 10, 11)) assertEquals("节点 $n", d(3000, 300), both.delta(n))
        assertEquals(zero, both.delta(2))
        assertEquals(normalSpeed, both.speed())

        // 只带落地：旧 11 为 0
        val landing = Run(selectorLanding()).apply { rounds(3) }
        assertEquals(d(3000, 300), landing.delta(11))
        assertEquals(d(3000, 300), landing.delta(1))
    }

    @Test
    fun `同一节点在主链与规则链里：两边都计，与出站的遍历顺序无关`() {
        val config = build("chain-shared-node")
        val rule = Run(config).ruleTag(5)
        val results = listOf(config, config.reversedTraffic()).map { Run(it).apply { rounds(3, rule to (7L to 70L)) } }
        for (run in results) {
            // 旧：3 只得 210 / 21（统计关联原序）或 3000 / 300（反序）
            assertEquals(d(3210, 321), run.delta(3))
            assertEquals(d(3000, 300), run.delta(2))
            assertEquals(d(3000, 300), run.delta(1))
            assertEquals(d(210, 21), run.delta(4))
            assertEquals(d(210, 21), run.delta(5))
            assertEquals(listOf(107L, 1070, 3, 30, 321, 3210), run.speed())
            assertEquals(6, run.queriesLastRound)
        }
        assertEquals(results[0].acc.snapshot(), results[1].acc.snapshot())
    }

    @Test
    fun `前置在三条规则链里：三条的字节都计，规则出站的速率与会话量不再漏`() {
        val config = build("multi-rules-front-groups")
        val probe = Run(config)
        val extra = arrayOf(probe.ruleTag(2) to (7L to 70L), probe.ruleTag(3) to (5L to 50L), probe.ruleTag(4) to (2L to 20L))
        val results = listOf(config, config.reversedTraffic()).map { Run(it).apply { rounds(3, *extra) } }
        for (run in results) {
            // 旧：10 只得 60 / 6（原序）或 210 / 21（反序），速度 102 / 1020 或 107 / 1070
            assertEquals(d(420, 42), run.delta(10))
            assertEquals(d(210, 21), run.delta(2))
            assertEquals(d(150, 15), run.delta(3))
            assertEquals(d(60, 6), run.delta(4))
            assertEquals(d(3000, 300), run.delta(1))
            assertEquals(listOf(114L, 1140), run.speed().take(2))
            assertEquals(10, run.queriesLastRound)
        }
        assertEquals(results[0].acc.snapshot(), results[1].acc.snapshot())
    }

    @Test
    fun `选择器链成员：选中出站的整个集合都计，选 1 时速度不再为 0`() {
        val config = build("selector-chain-members")
        val run = Run(config)
        run.rounds(2)
        // 旧：选 1 的两轮速度与会话量为 0
        assertEquals(listOf(100L, 1000), run.speed().take(2))
        assertEquals(setOf(1L, 4L), run.acc.select(3)!!.keys)
        run.rounds(2)
        run.acc.select(4)
        run.rounds(2)
        // 旧：1 2000 / 200，2 为 0
        assertEquals(d(6000, 600), run.delta(1))
        assertEquals(d(2000, 200), run.delta(2))
        assertEquals(d(2000, 200), run.delta(3))
        assertEquals(d(4000, 400), run.delta(4))
        assertEquals(listOf(100L, 1000, 3, 30, 600, 6000), run.speed())
    }

    @Test
    fun `非选择器的规则目标：与旧结果相同；规则指向主节点时经 proxy 计，不另查`() {
        val config = build("rules-two-to-same-node")
        val m = Run(config).apply { rounds(3, ruleTag(2) to (7L to 70L)) }
        assertEquals(d(210, 21), m.delta(2))
        assertEquals(d(3000, 300), m.delta(1))
        assertEquals(listOf(107L, 1070), m.speed().take(2))

        // 规则指向主节点自己，出站是 proxy，不另建、不另查
        val r = Run(build("group-front", withRuleTo(1))).apply { rounds(3) }
        assertEquals(emptySet<String>(), r.config.traffic.ruleTags)
        assertEquals(d(3000, 300), r.delta(1))
        assertEquals(d(3000, 300), r.delta(10))
        assertEquals(4, r.queriesLastRound)
    }

    @Test
    fun `选择器下规则直接指向的出站要查、要计，计入代理速度；只多查规则出站`() {
        val config = build("selector-rule-targets")
        val run = Run(config)
        assertEquals(setOf(run.ruleTag(2), run.ruleTag(10), run.ruleTag(11)), config.traffic.ruleTags)
        run.rounds(
            3,
            run.ruleTag(10) to (7L to 70L), run.ruleTag(11) to (5L to 50L), run.ruleTag(2) to (2L to 20L),
            // 选中成员 1 自己的出站：没有用户规则路由到这里（指向 1 的规则出站是 proxy），选择器模式只查规则出站，
            // 所以不查、这里的字节不计（每轮查全部出站的话，1 会是 3030 / 303）
            config.profileTagMap.getValue(1) to (1L to 10L),
        )
        // 旧：10、11、2 都为 0，速度不含规则
        assertEquals(d(210, 21), run.delta(10))
        assertEquals(d(150, 15), run.delta(11))
        assertEquals(d(60, 6), run.delta(2))
        assertEquals(d(3000, 300), run.delta(1))
        assertEquals(listOf(114L, 1140, 3, 30, 342, 3420), run.speed())
        // 2 ×（规则出站数 3 + 2）
        assertEquals(10, run.queriesLastRound)
    }

    @Test
    fun `选择器切换：切换前的字节记给旧成员，切换后一轮速率按间隔算`() {
        val run = Run(selectorPlain())
        run.rounds(2)
        run.stats.route(TAG_PROXY, 40, 400)
        run.stats.takeLog()
        val persisted = run.acc.select(2)
        // 切换多查 2 次；旧成员所在集合立即落库
        assertEquals(2, run.stats.takeLog().size)
        assertEquals(mapOf(1L to TrafficTotals(base(1).rx + 2400, base(1).tx + 240)), persisted)
        run.rounds(2)
        // 旧：1 2000 / 200，2 2400 / 240，切换后一轮速率 0
        assertEquals(d(2400, 240), run.delta(1))
        assertEquals(d(2000, 200), run.delta(2))
        assertEquals(listOf(140L, 1400), run.rounds[2].let { listOf(it.txRateProxy, it.rxRateProxy) })
        assertEquals(listOf(100L, 1000, 3, 30, 440, 4400), run.speed())
    }

    @Test
    fun `运行中清除流量：节点从 0 继续累计，会话量不减`() {
        val p = Run(build("group-front"))
        p.rounds(2)
        p.acc.clear(longArrayOf(1))
        p.rounds(2)
        assertEquals(d(2000, 200), p.acc.snapshot().getValue(1))
        assertEquals(d(base(10).rx + 4000, base(10).tx + 400), p.acc.snapshot().getValue(10))
        assertEquals(listOf(400L, 4000), p.speed().drop(4))

        // 清前置 10：旧会话量从 200 / 2000 掉回 100 / 1000（清的是代表条目）
        val p2 = Run(build("group-front"))
        p2.rounds(2)
        p2.acc.clear(longArrayOf(10))
        p2.rounds(2)
        assertEquals(d(2000, 200), p2.acc.snapshot().getValue(10))
        assertEquals(listOf(400L, 4000), p2.speed().drop(4))
    }

    @Test
    fun `选择器初始选中构建的主节点：没有切换时 proxy 只记给它`() {
        // 初始选中来自 TrafficAccounting.of（构建结果的主节点）
        val run = Run(selectorPlain()).apply { rounds(3) }
        assertEquals(d(3000, 300), run.delta(1))
        assertEquals(zero, run.delta(2))
    }

    @Test
    fun `规则目标复用别的链已建的出站时，那个出站不登记统计：路由到它的字节不计给节点，也不进速度`() {
        // 规则 → 主链的首跳 2，复用 g-2，不在统计关联里，sing-box 不给它建计数器
        val s = Run(build("chain-2-internal", withRuleTo(2)))
        val sTag = s.ruleTag(2)
        assertFalse(sTag in s.config.traffic.tags)
        assertEquals(emptySet<String>(), s.config.traffic.ruleTags)
        s.rounds(3, sTag to (7L to 70L))
        assertEquals(d(3000, 300), s.delta(2))
        assertEquals(listOf(100L, 1000), s.speed().take(2))

        // 选择器 + 前置，规则 → 前置 10（复用 g-10）；10 只经选中成员的集合计 proxy 的字节（旧：10 为 0、速度 0）
        val s2 = Run(build("selector-front-landing") { j -> j.group(1).addProperty("landingProxy", -1); withRuleTo(10)(j) })
        val s2Tag = s2.ruleTag(10)
        assertFalse(s2Tag in s2.config.traffic.tags)
        s2.rounds(3, s2Tag to (7L to 70L))
        assertEquals(d(3000, 300), s2.delta(10))
        assertEquals(normalSpeed, s2.speed())
    }

    @Test
    fun `推送与落库按节点：没有 -1，每个 id 一项`() {
        val config = build("chain-shared-node")
        val run = Run(config).apply { rounds(1, ruleTag(5) to (7L to 70L)) }
        val ids = config.traffic.tags.values.flatten()
        assertTrue("共享节点在两个出站里", ids.size > ids.toSet().size)
        assertEquals(ids.toSet(), run.acc.snapshot().keys)
        assertFalse(-1L in run.acc.snapshot())
        assertFalse(-1L in run.rounds.last().changed)
        // 停止时写库：两个出站里的节点都写，共享节点只写一行
        val rows = TrafficAccounting.rowsToPersist(config.traffic.tags, run.acc.snapshot())
        assertEquals(ids.distinct(), rows.keys.toList())
        assertEquals(run.acc.snapshot(), rows)
    }
}
