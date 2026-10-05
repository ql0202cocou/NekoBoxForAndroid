package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// 统计关联本身：构造时的校验与拷贝，规则出站的取法（逐场景的核对在 GoldenTrafficBindingsTest）
class TrafficBindingsTest {

    @Test
    fun `构造时复制传入的集合；缺初始累计或规则出站不在登记范围内时拒绝`() {
        val ids = mutableListOf(1L, 2)
        val tags = mutableMapOf("proxy" to ids)
        val b = TrafficBindings(tags, mapOf(1L to TrafficTotals(1, 2), 2L to TrafficTotals(3, 4)), emptySet())
        ids += 3
        tags["g-9"] = mutableListOf(9)
        assertEquals(mapOf("proxy" to listOf(1L, 2)), b.tags)
        assertThrows(IllegalArgumentException::class.java) {
            TrafficBindings(mapOf("proxy" to listOf(1L, 2)), mapOf(1L to TrafficTotals(0, 0)), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficBindings(mapOf("proxy" to listOf(1L)), mapOf(1L to TrafficTotals(0, 0)), setOf("g-2"))
        }
    }

    private fun rule(outbound: String?, vararg extra: Pair<String, Any?>) =
        (listOfNotNull(outbound?.let { "outbound" to it }) + extra).toMap()

    private fun config(rules: List<Map<String, Any?>>, final: String? = null): Map<String, Any?> =
        mapOf("route" to listOfNotNull("rules" to rules, final?.let { "final" to it }).toMap())

    private val bound = setOf("proxy", "g-2", "c-3-3", "m1", "m2")

    @Test
    fun `规则出站：用户规则直接指向的登记出站，去掉 proxy 与 bypass`() {
        val rules = listOf(
            rule(null, "action" to "sniff"),
            rule(null, "action" to "hijack-dns", "port" to listOf(53L)),
            rule("g-2", "domain_suffix" to listOf("a.example.org")),
            rule("proxy", "domain_suffix" to listOf("b.example.org")),
            rule("bypass", "ip_is_private" to true),
            rule("direct", "inbound" to listOf("c-0-mapping-2")),
            // 外核链规则：映射入站接下一跳，即使下一跳是登记过的出站也不算
            rule("m1", "inbound" to listOf("c-4-mapping-4")),
            rule("c-3-3", "inbound" to "c-3-mapping-7"),
            // 自定义 JSON 写的规则带别的入站：是用户规则
            rule("m2", "inbound" to listOf("tun-in")),
            // 没登记统计的出站（链内中间跳、复用的全局出站）：不在 bound 里，不算
            rule("c-5-4", "domain_suffix" to listOf("c.example.org")),
            // 再指向已算过的 g-2：结果里只出现一次
            rule("g-2", "domain_suffix" to listOf("d.example.org")),
            rule(null, "action" to "reject", "ip_cidr" to listOf("224.0.0.0/3")),
        )
        assertEquals(listOf("g-2", "m2"), ruleTrafficTags(config(rules), bound).toList())
    }

    @Test
    fun `规则出站：inbound 里既有映射入站又有别的入站时是用户规则，全部是映射入站才排除`() {
        val rules = listOf(
            rule("g-2", "inbound" to listOf("tun-in", "c-0-mapping-2")),
            rule("c-3-3", "inbound" to listOf("c-0-mapping-2", "c-3-mapping-7")),
        )
        assertEquals(setOf("g-2"), ruleTrafficTags(config(rules), bound))
    }

    @Test
    fun `规则出站：route final 指向登记出站时也算；没有 route 时为空`() {
        assertEquals(setOf("c-3-3"), ruleTrafficTags(config(emptyList(), final = "c-3-3"), bound))
        assertEquals(emptySet<String>(), ruleTrafficTags(config(emptyList(), final = "proxy"), bound))
        assertEquals(emptySet<String>(), ruleTrafficTags(mapOf("outbounds" to emptyList<Any>()), bound))
    }
}
