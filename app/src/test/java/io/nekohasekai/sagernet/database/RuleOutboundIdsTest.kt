package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

// 路由页在后台一次查齐规则出站指定的节点名（RuleRepository.outboundProfileNames），要查的 id 由 profileOutboundIds 给出
class RuleOutboundIdsTest {

    private fun rule(id: Long, outbound: Long) = RuleEntity(id = id, outbound = outbound)

    @Test
    fun `只取指定节点的出站，代理直连阻断不查`() {
        val rules = listOf(rule(1, 0), rule(2, -1), rule(3, -2), rule(4, 7))
        assertEquals(listOf(7L), profileOutboundIds(rules))
    }

    @Test
    fun `同一节点只查一次并保持出现顺序`() {
        val rules = listOf(rule(1, 9), rule(2, 7), rule(3, 9), rule(4, 8))
        assertEquals(listOf(9L, 7L, 8L), profileOutboundIds(rules))
    }

    @Test
    fun `没有指定节点时为空`() {
        assertEquals(emptyList<Long>(), profileOutboundIds(listOf(rule(1, 0))))
        assertEquals(emptyList<Long>(), profileOutboundIds(emptyList()))
    }
}
