package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// REALITY 握手失败的提示：构建结果带出「REALITY 且跑在 sing-box 上」的节点（ConfigBuildResult.realityOnSingBox），
// withRealityHint 按它给测速报错补一句
class RealityHintTest {

    private fun result(vararg hops: RealityOnSingBox) = ConfigBuildResult(
        "", listOf(), 0L, TrafficBindings(mapOf(), mapOf(), setOf()), mapOf(), -1L,
        realityOnSingBox = hops.toList(),
    )

    private val failed = "Get \"https://www.example.com/generate_204\": reality verification failed"
    private val cause = "the server may require a newer REALITY client than sing-box provides"
    private val wsConflict = Conflict(DialCore.XRAY, CoreConflict.XRAY_REALITY_TRANSPORT)

    @Test
    fun `能改用 Xray 的节点建议改核心`() {
        val e = Exception(failed)
        val hinted = result(RealityOnSingBox(3, "香港", emptyList())).withRealityHint(e)
        assertTrue(hinted is RealityHintException)
        assertEquals("$failed. Hint: $cause; profile \"香港\" can run on Xray: set its core to Xray", hinted.message)
        assertSame(e, hinted.cause)
    }

    @Test
    fun `不能改用 Xray 的节点列出冲突字段与原因`() {
        val hinted = result(RealityOnSingBox(2, "东京", listOf(wsConflict))).withRealityHint(Exception(failed))
        assertEquals(
            "$failed. Hint: $cause; profile \"东京\" cannot run on Xray: " +
                "[type, realityPubKey] Xray only runs REALITY over TCP or gRPC",
            hinted.message,
        )
    }

    @Test
    fun `多个候选节点全部列出`() {
        val hinted = result(
            RealityOnSingBox(2, "东京", listOf(wsConflict, Conflict(DialCore.XRAY, CoreConflict.XRAY_SING_MUX, "smux"))),
            RealityOnSingBox(3, "香港", emptyList()),
        ).withRealityHint(Exception(failed))
        assertEquals(
            "$failed. Hint: $cause; it is not known which of these REALITY profiles on sing-box failed: " +
                "profile \"东京\" cannot run on Xray: [type, realityPubKey] Xray only runs REALITY over TCP or gRPC; " +
                "[enableMux, muxType] ${CoreConflict.XRAY_SING_MUX.reason} (smux) | " +
                "profile \"香港\" can run on Xray: set its core to Xray",
            hinted.message,
        )
    }

    @Test
    fun `不含这一句的报错原样返回`() {
        val e = Exception("Get \"https://www.example.com/generate_204\": context deadline exceeded")
        assertSame(e, result(RealityOnSingBox(3, "香港", emptyList())).withRealityHint(e))
    }

    @Test
    fun `构建里没有这样的节点时原样返回`() {
        val e = Exception(failed)
        assertSame(e, result().withRealityHint(e))
    }

    @Test
    fun `没有消息的异常原样返回`() {
        val e = Exception()
        assertSame(e, result(RealityOnSingBox(3, "香港", emptyList())).withRealityHint(e))
    }

    // ---- 构建结果带出的列表

    private fun reality(id: Long, name: String, core: Int? = null, configure: VMessBean.() -> Unit = {}) =
        ProxyEntity(id = id, groupId = 1, userOrder = id)
            .apply { if (core != null) this.core = core }
            .putBean(CoreTestNodes.vless {
                this.name = name
                serverAddress = "r$id.example.com"
                sni = "r$id.example.com"
                realityPubKey = CoreTestNodes.REALITY_KEY
                realityShortId = CoreTestNodes.SHORT_ID
                configure()
            })

    private fun socks(id: Long) = ProxyEntity(id = id, groupId = 1, userOrder = id).putBean(SOCKSBean().apply {
        name = "socks-$id"
        serverAddress = "192.0.2.$id"
        serverPort = 1080
        initializeDefaultValues()
    })

    // members 按用户填写的顺序：首个是最先拨号的一跳
    private fun chain(id: Long, vararg members: Long) =
        ProxyEntity(id = id, groupId = 1, userOrder = id).putBean(ChainBean().apply {
            name = "chain-$id"
            proxies = members.toList()
            initializeDefaultValues()
        })

    private fun build(main: ProxyEntity, profiles: List<ProxyEntity>, mode: ConfigBuildMode = ConfigBuildMode.RUN): ConfigBuildResult {
        val source = MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), profiles, emptyList())
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, mode)
        val input = ConfigInput(mode, record, testConfigSettings(), snapshot, emptyMap(), FakeConfigPlatform())
        return buildConfig(input)
    }

    @Test
    fun `构建带出 REALITY 且跑在 sing-box 上的节点与 Xray 的冲突`() {
        // 链 1：最先拨号的 4（REALITY + tcp，自动走 Xray）→ 3（REALITY + tcp，手动 sing-box）→ 2（REALITY + ws，
        // Xray 不支持 ws 上的 REALITY，自动走 sing-box）→ 出口 5 socks。按提交的次序（从出口起）列出，
        // 同一节点 2 在链里出现两次只记一项
        val ws = reality(2, "ws") { type = "ws" }
        val manual = reality(3, "manual", ProxyEntity.CORE_SING_BOX)
        val xray = reality(4, "xray")
        val main = chain(1, 4, 3, 2, 2, 5)
        for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST, ConfigBuildMode.EXPORT)) {
            val result = build(main, listOf(main, ws, manual, xray, socks(5)), mode)
            assertEquals(
                mode.toString(),
                listOf(
                    "2 ws [Conflict(core=XRAY, id=XRAY_REALITY_TRANSPORT, value=null)]",
                    "3 manual []",
                ),
                result.realityOnSingBox.map { "${it.profileId} ${it.name} ${it.xrayConflicts}" },
            )
            // 4 走 Xray，是外核跳，不在列表里
            assertEquals(listOf(4L), ExternalRunPlan.from(result).hops.map { it.profileId })
        }
    }

    @Test
    fun `没有 REALITY 的构建列表为空`() {
        val main = socks(1)
        assertEquals(emptyList<RealityOnSingBox>(), build(main, listOf(main)).realityOnSingBox)
    }
}
