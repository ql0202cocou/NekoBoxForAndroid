package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSummaryTest {

    @Test
    fun emptyRuleHasNoFields() {
        val s = RuleSummary.of(RuleEntity())
        assertTrue(s.fields.isEmpty())
        assertEquals(0, s.overflow)
    }

    @Test
    fun fieldOrderAndValues() {
        val s = RuleSummary.of(RuleEntity(domains = "a.com\nb.com", port = "443", network = "tcp"))
        assertEquals(
            listOf(
                RuleSummary.Field(R.string.route_field_domain, "a.com, b.com"),
                RuleSummary.Field(R.string.route_field_dst_port, "443"),
                RuleSummary.Field(R.string.network, "tcp"),
            ),
            s.fields,
        )
        assertEquals(0, s.overflow)
    }

    @Test
    fun limitsToThreeFieldsAndCountsOverflow() {
        val s = RuleSummary.of(
            RuleEntity(
                config = "{}", domains = "a.com", ip = "1.1.1.1", source = "10.0.0.1",
                sourcePort = "1", port = "2", network = "udp", protocol = "dns",
                packages = setOf("x", "y"),
            )
        )
        assertEquals(
            listOf(R.string.custom_config, R.string.route_field_domain, R.string.route_field_dst_ip),
            s.fields.map { it.label },
        )
        assertEquals(6, s.overflow)
    }

    @Test
    fun appsShowCountAndBlankFieldsAreSkipped() {
        val s = RuleSummary.of(RuleEntity(domains = "  ", packages = setOf("x", "y")))
        assertEquals(listOf(RuleSummary.Field(R.string.apps, "2")), s.fields)
    }

    @Test
    fun longListIsCappedWithRemainingCount() {
        val s = RuleSummary.of(RuleEntity(domains = "a\nb\n\nc\nd\ne"))
        assertEquals(listOf(RuleSummary.Field(R.string.route_field_domain, "a, b, c", 2)), s.fields)
    }

    @Test
    fun threeEntriesHaveNoMore() {
        val s = RuleSummary.of(RuleEntity(ip = "1\n2\n3"))
        assertEquals(listOf(RuleSummary.Field(R.string.route_field_dst_ip, "1, 2, 3", 0)), s.fields)
    }
}
