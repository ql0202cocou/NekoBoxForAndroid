package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainRuleListsTest {

    @Test
    fun regexpKeepsOriginalCase() {
        val lists = DomainRuleLists(listOf("regexp:^\\D+\\W[\\p{Lu}]+$"))
        assertEquals(listOf("^\\D+\\W[\\p{Lu}]+$"), lists.domainRegex)
    }

    @Test
    fun otherPrefixesAreLowercased() {
        val lists = DomainRuleLists(
            listOf("full:A.Com", "domain:B.Com", "keyword:FOO", "C.Com", "geosite:CN")
        )
        assertEquals(listOf("a.com"), lists.domain)
        assertEquals(listOf("b.com", "c.com"), lists.domainSuffix)
        assertEquals(listOf("foo"), lists.domainKeyword)
        assertEquals(listOf("geosite:CN"), lists.ruleSet)
    }
}
