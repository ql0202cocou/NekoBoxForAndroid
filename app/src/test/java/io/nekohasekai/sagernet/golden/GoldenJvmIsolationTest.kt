package io.nekohasekai.sagernet.golden

import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ConfigInput
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.fmt.buildConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

// 构建只消费采集好的输入：采集之后改数据源与调用方的对象不影响构建，构建也不改调用方的对象。
// 这些测试与 JVM 黄金测试本身都在普通 JVM 上跑纯构建入口（mockable android.jar，没有 Application、数据库与
// libcore），构建若碰到 DataStore、DAO、Logs 等会直接抛错
class GoldenJvmIsolationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseline by lazy { GoldenBaseline.load() }

    private fun scenario(id: String) = GoldenJvmScenario(baseline.input(id), GoldenAddressCorpus.of(baseline), tmp.root)

    // 同样的平台（端口从头分配）与凭据种子，同一份输入构建出的结果逐字相同
    private fun build(input: ConfigInput): ConfigBuildResult = buildConfig(input) { LocalSocksAuth.generate(Random(7)) }

    // 结果里可比较的部分：配置原文、外核跳实例（端口与拨号目标）、流量统计关联（按节点 id，含初始累计与规则出站）
    // 与选择器的映射
    private fun ConfigBuildResult.fingerprint(): String {
        val hops = ExternalRunPlan.from(this).hops.map { "${it.profileId}@${it.localPort}->${it.target}" }
        val traffic = listOf(traffic.tags.toSortedMap(), traffic.initial.toSortedMap(), traffic.ruleTags.sorted(), needsRootUidBypass)
        return listOf(config, hops, traffic, profileTagMap.toSortedMap(), selectorGroupId, diagnostics).joinToString("\n")
    }

    @Test
    fun `采集之后清空数据源、改调用方对象，构建产物不变`() {
        // 选择器 + 前置 + 落地，落地节点在每个成员的链里各出现一次
        val scenario = scenario("multi-selector-front-landing")
        val mode = ConfigBuildMode.RUN

        val referenceSource = baseline.input(scenario.id).dataSource()
        val referenceMain = scenario.main(referenceSource)
        val reference = build(scenario.configInput(mode, referenceMain, referenceSource, scenario.platform(tmp.newFolder())))

        val source = baseline.input(scenario.id).dataSource()
        val main = scenario.main(source)
        val input = scenario.configInput(mode, main, source, scenario.platform(tmp.newFolder()))
        source.clear()
        assertTrue(source.profilesByGroup(1).isEmpty())
        main.requireBean().apply {
            name = "changed"
            serverAddress = "changed.example.com"
            serverPort = 1
        }
        val after = build(input)

        assertEquals(reference.fingerprint(), after.fingerprint())
    }

    @Test
    fun `构建不改写调用方对象的 bean，也不改写它自己用的节点`() {
        // 单个 Xray 节点：最先拨号的一跳经映射
        val scenario = scenario("xray-vless-tls-tcp")
        val source = baseline.input(scenario.id).dataSource()
        val main = scenario.main(source)
        val bean = main.requireBean()
        val before = KryoConverters.serialize(bean)

        val result = build(scenario.configInput(ConfigBuildMode.RUN, main, source, scenario.platform(tmp.newFolder())))

        val hop = ExternalRunPlan.from(result).hops.single()
        assertTrue("最先拨号的一跳经映射", hop.target is ExternalDialTarget.Mapped)
        assertArrayEquals(before, KryoConverters.serialize(bean))
        // 构建结果不持有调用方的对象：统计关联只记主节点的 id 与构建时的累计，之后改调用方对象的计数不影响它；
        // 主节点那一跳的 bean 是拷贝（下面的 assertNotSame）
        val counted = TrafficTotals(rx = main.rx, tx = main.tx)
        assertEquals(listOf(main.id), result.traffic.tags.getValue(TAG_PROXY))
        assertEquals(main.id, hop.profileId)
        main.rx += 1000
        main.tx += 100
        assertEquals(counted, result.traffic.initial.getValue(main.id))
        // 映射只记在跳实例的拨号目标上：构建用的节点拷贝与采集时的字节相同
        assertNotSame(bean, hop.bean)
        assertArrayEquals(before, KryoConverters.serialize(hop.bean))
    }

    @Test
    fun `构建不改写它从快照取来的节点：每个跳实例的 bean 与输入的字节相同`() {
        // 选择器 + 前置 + 落地：同一个节点在多条链里各是一个跳实例
        val scenario = scenario("multi-selector-front-landing")
        val input = baseline.input(scenario.id)
        val source = input.dataSource()
        val result = build(scenario.configInput(ConfigBuildMode.RUN, scenario.main(source), source, scenario.platform(tmp.newFolder())))
        val hops = ExternalRunPlan.from(result).hops
        assertTrue("场景里应有外核跳实例", hops.isNotEmpty())
        for (hop in hops) {
            assertArrayEquals(
                "节点 ${hop.profileId}", KryoConverters.serialize(input.newBean(hop.profileId)), KryoConverters.serialize(hop.bean),
            )
        }
        // 同一个节点的不同跳实例是不同的对象
        hops.groupBy { it.profileId }.values.filter { it.size > 1 }.forEach { same ->
            assertEquals(same.size, same.map { System.identityHashCode(it.bean) }.distinct().size)
        }
    }
}
