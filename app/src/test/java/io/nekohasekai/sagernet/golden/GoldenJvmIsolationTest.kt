package io.nekohasekai.sagernet.golden

import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ConfigInput
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.buildConfig
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

    // 结果里可比较的部分：配置原文、外核跳实例（端口与拨号目标）、流量与选择器的映射（按节点 id）
    private fun ConfigBuildResult.fingerprint(): String {
        val hops = ExternalRunPlan.from(this).hops.map { "${it.profileId}@${it.localPort}->${it.target}" }
        val traffic = trafficMap.entries.sortedBy { it.key }.map { (tag, list) -> "$tag=${list.map { it.id }}" }
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
    fun `构建不改写调用方对象的 bean`() {
        // 单个 Xray 节点：最先拨号的一跳经映射，映射目标写成本机与映射端口
        val scenario = scenario("xray-vless-tls-tcp")
        val source = baseline.input(scenario.id).dataSource()
        val main = scenario.main(source)
        val bean = main.requireBean()
        val before = Triple(bean.serverAddress, bean.finalAddress, bean.finalPort)
        assertEquals("采集前的映射目标就是服务器本身", bean.serverAddress, bean.finalAddress)

        val result = build(scenario.configInput(ConfigBuildMode.RUN, main, source, scenario.platform(tmp.newFolder())))

        val hop = ExternalRunPlan.from(result).hops.single()
        assertTrue("最先拨号的一跳经映射", hop.target is ExternalDialTarget.Mapped)
        assertEquals(before, Triple(bean.serverAddress, bean.finalAddress, bean.finalPort))
        // 构建结果里的主节点是拷贝，不是调用方的对象
        assertNotSame(main, result.trafficMap.getValue(TAG_PROXY).single())
        assertEquals(main.id, result.trafficMap.getValue(TAG_PROXY).single().id)
    }
}
