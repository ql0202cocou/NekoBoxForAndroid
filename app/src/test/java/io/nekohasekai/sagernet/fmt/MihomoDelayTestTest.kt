package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 测速时是否让 mihomo 自己测延迟（ConfigBuildResult.delayTestOnMihomo）：含义与以前 TestInstance 读 DataStore、另查分组
// 时完全相同，只是事实取自本次构建（快照里的主分组行、构建时采集的设置）。每个用例走一次真实的测速构建（快照 → 纯构建）
class MihomoDelayTestTest {

    private fun anytls(id: Long, groupId: Long = 1, core: Int? = null) =
        ProxyEntity(id = id, groupId = groupId, userOrder = id)
            .apply { if (core != null) this.core = core }
            .putBean(AnyTLSBean().apply {
                name = "anytls-$id"
                serverAddress = "a$id.example.com"
                serverPort = 8443
                password = "fake-password-$id"
                initializeDefaultValues()
            })

    private fun socks(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(SOCKSBean().apply {
            name = "socks-$id"
            serverAddress = "192.0.2.$id"
            serverPort = 1080
            initializeDefaultValues()
        })

    private fun vless(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, userOrder = id)
        .putBean(VMessBean().apply {
            name = "vless-$id"
            serverAddress = "x$id.example.com"
            serverPort = 443
            uuid = "00000000-0000-0000-0000-0000000000%02x".format(id)
            alterId = -1
            initializeDefaultValues()
        })

    private fun chain(id: Long, vararg members: Long) = ProxyEntity(id = id, groupId = 1, userOrder = id)
        .putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    // 测速模式构建主节点 main；groups 里没有主节点的分组表示分组的行不存在
    private fun testBuild(
        main: ProxyEntity,
        groups: List<ProxyGroup>,
        others: List<ProxyEntity> = emptyList(),
        globalAllowInsecure: Boolean = false,
        afterCollect: (MemoryConfigDataSource) -> Unit = {},
    ): ConfigBuildResult {
        val source = MemoryConfigDataSource(groups, listOf(main) + others, emptyList())
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, ConfigBuildMode.TEST)
        afterCollect(source)
        val settings = testConfigSettings(globalAllowInsecure = globalAllowInsecure)
        return buildConfig(ConfigInput(ConfigBuildMode.TEST, record, settings, snapshot, emptyMap(), FakeConfigPlatform()))
    }

    private fun measures(main: ProxyEntity, groups: List<ProxyGroup>, others: List<ProxyEntity> = emptyList()) =
        testBuild(main, groups, others).delayTestOnMihomo

    // 以前的条件（TestInstance 里的 mihomoMeasuresDelay）原样抄来作对照：类型是 AnyTLS 且 needExternal，取得到分组行，
    // 前置与落地都不大于 0。group 是测速时读到的分组行
    private fun oldCondition(profile: ProxyEntity, globalAllowInsecure: Boolean, group: () -> ProxyGroup?): Boolean {
        if (profile.type != ProxyEntity.TYPE_ANYTLS || !profile.needExternal(globalAllowInsecure)) return false
        val found = group() ?: return false
        return found.frontProxy <= 0 && found.landingProxy <= 0
    }

    @Test
    fun `单个 AnyTLS 节点走 mihomo、分组没有前置与落地时由 mihomo 测`() {
        assertTrue(measures(anytls(1), listOf(ProxyGroup(id = 1))))
    }

    @Test
    fun `主节点所在分组的行不存在时不开`() {
        val result = testBuild(anytls(1, groupId = 5), emptyList())
        // 构建照样建出只有主节点的单跳链，但条件按分组行判断
        assertEquals(listOf(1L), ExternalRunPlan.from(result).hops.map { it.profileId })
        assertFalse(result.delayTestOnMihomo)
    }

    @Test
    fun `前置或落地 id 大于 0 却指向已删除的节点时不开`() {
        val front = testBuild(anytls(1), listOf(ProxyGroup(id = 1, frontProxy = 99)))
        // 构建忽略悬空的前置，链上只有主节点；条件照旧按分组行里的 id 判断
        assertEquals(listOf(1L), ExternalRunPlan.from(front).hops.map { it.profileId })
        assertFalse(front.delayTestOnMihomo)
        assertFalse(measures(anytls(1), listOf(ProxyGroup(id = 1, landingProxy = 99))))
    }

    @Test
    fun `分组有前置或落地时不开`() {
        assertFalse(measures(anytls(1), listOf(ProxyGroup(id = 1, frontProxy = 9)), listOf(socks(9))))
        assertFalse(measures(anytls(1), listOf(ProxyGroup(id = 1, landingProxy = 9)), listOf(socks(9))))
        assertFalse(measures(anytls(1), listOf(ProxyGroup(id = 1, frontProxy = 9)), listOf(anytls(9))))
    }

    @Test
    fun `主节点是只有一个 AnyTLS 成员的链时不开`() {
        val result = testBuild(chain(1, 2), listOf(ProxyGroup(id = 1)), listOf(anytls(2)))
        assertEquals(listOf(2L), ExternalRunPlan.from(result).hops.map { it.profileId })
        assertFalse(result.delayTestOnMihomo)
    }

    @Test
    fun `AnyTLS 选了 sing-box 核心或主节点不是 AnyTLS 时不开`() {
        assertFalse(measures(anytls(1, core = ProxyEntity.CORE_SING_BOX), listOf(ProxyGroup(id = 1))))
        assertFalse(measures(vless(1), listOf(ProxyGroup(id = 1))))
        assertFalse(measures(socks(1), listOf(ProxyGroup(id = 1))))
    }

    @Test
    fun `完整配置节点与手工拼出的构建结果不开`() {
        assertFalse(ConfigBuildResult("{}", emptyList(), 1L, TrafficBindings(emptyMap(), emptyMap(), emptySet()), emptyMap(), -1L).delayTestOnMihomo)
    }

    @Test
    fun `条件只取决于构建结果：采集之后删掉分组行或改了节点，结果不变`() {
        // 采集之后把数据源清空：以前测速时另查分组会查不到（为否），现在用的是快照里的那一行
        val result = testBuild(anytls(1), listOf(ProxyGroup(id = 1)), afterCollect = { it.clear() })
        assertTrue(result.delayTestOnMihomo)
        // 反过来：采集时分组有前置，之后改成没有，构建结果仍为否
        val withFront = testBuild(
            anytls(1), listOf(ProxyGroup(id = 1, frontProxy = 9)), listOf(socks(9)), afterCollect = { it.clear() },
        )
        assertFalse(withFront.delayTestOnMihomo)
    }

    @Test
    fun `对各种主节点、分组与设置都与以前的条件结果相同`() {
        val mains = listOf(
            anytls(1),
            anytls(1, core = ProxyEntity.CORE_SING_BOX),
            anytls(1, core = ProxyEntity.CORE_MIHOMO),
            vless(1),
            socks(1),
            chain(1, 2),
        )
        val groups = listOf(
            null,
            ProxyGroup(id = 1),
            ProxyGroup(id = 1, frontProxy = 9),
            ProxyGroup(id = 1, landingProxy = 9),
            ProxyGroup(id = 1, frontProxy = 99),
            ProxyGroup(id = 1, landingProxy = 99),
            ProxyGroup(id = 1, frontProxy = 0, landingProxy = -1),
        )
        val others = listOf(anytls(2), socks(9))
        var cases = 0
        var positive = 0
        for (main in mains) for (group in groups) for (allowInsecure in listOf(false, true)) {
            val groupRows = listOfNotNull(group)
            val result = testBuild(main, groupRows, others, globalAllowInsecure = allowInsecure)
            val old = oldCondition(main, allowInsecure) { group?.copy() }
            assertEquals("主节点 ${main.displayName()}（core ${main.core}），分组 $group，允许不安全 $allowInsecure",
                old, result.delayTestOnMihomo)
            assertEquals(old, mihomoDelayTestApplies(main, group, allowInsecure))
            cases++
            if (old) positive++
        }
        assertEquals(mains.size * groups.size * 2, cases)
        // 两种 AnyTLS 走 mihomo 的主节点 × 两种没有前置 / 落地的分组 × 两种设置
        assertEquals(8, positive)
    }
}
