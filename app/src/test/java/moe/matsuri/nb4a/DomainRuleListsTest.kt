package moe.matsuri.nb4a

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `各字段保持输入顺序`() {
        val lists = DomainRuleLists(listOf("b.com", "full:x.com", "domain:a.com", "full:w.com", "c.com"))
        assertEquals(listOf("x.com", "w.com"), lists.domain)
        assertEquals(listOf("b.com", "a.com", "c.com"), lists.domainSuffix)
    }

    @Test
    fun `前缀只认开头的小写写法，其余一律按后缀`() {
        // geoip: 不是域名规则的前缀；大写的前缀、前面带空白的都不认
        val lists = DomainRuleLists(listOf("geoip:CN", "FULL:A.com", " full:b.com", "Geosite:cn"))
        assertEquals(listOf("geoip:cn", "full:a.com", " full:b.com", "geosite:cn"), lists.domainSuffix)
        assertTrue(lists.domain.isEmpty())
        assertTrue(lists.ruleSet.isEmpty())
    }

    @Test
    fun `空项与只有前缀的项照样分进去，由生成规则时去掉`() {
        val lists = DomainRuleLists(listOf("", "full:", "keyword:", "geosite:"))
        assertEquals(listOf(""), lists.domainSuffix)
        assertEquals(listOf(""), lists.domain)
        assertEquals(listOf(""), lists.domainKeyword)
        assertEquals(listOf("geosite:"), lists.ruleSet)
    }

    @Test
    fun `DNS 规则：去掉空项，空字段不输出`() {
        val rule = DNSRule_DefaultOptions().apply {
            makeSingBoxRule(listOf("full:", "geosite:cn", "", "Example.COM", "regexp:^A"))
        }
        assertEquals(listOf("geosite:cn"), rule.rule_set)
        assertNull(rule.domain)
        assertEquals(listOf("example.com"), rule.domain_suffix)
        assertEquals(listOf("^A"), rule.domain_regex)
        assertNull(rule.domain_keyword)
        assertFalse(rule.checkEmpty())
        // 全是空项：什么都不输出，规则为空
        val empty = DNSRule_DefaultOptions().apply { makeSingBoxRule(listOf("", "full:")) }
        assertNull(empty.domain)
        assertNull(empty.domain_suffix)
        assertTrue(empty.checkEmpty())
    }

    @Test
    fun `路由规则：域名与 IP 两段的 rule_set 累加，geoip private 单独成字段`() {
        val rule = Rule_DefaultOptions().apply {
            makeSingBoxRule(listOf("geosite:cn", "full:A.com"), false)
            makeSingBoxRule(listOf("geoip:private", "geoip:cn", "192.0.2.0/24"), true)
        }
        assertEquals(listOf("geosite:cn", "geoip:cn"), rule.rule_set)
        assertEquals(true, rule.ip_is_private)
        assertEquals(listOf("192.0.2.0/24"), rule.ip_cidr)
        assertEquals(listOf("a.com"), rule.domain)
        assertNull(rule.domain_suffix)
        // 只有 IP 段、只有 geoip:private
        val privateOnly = Rule_DefaultOptions().apply { makeSingBoxRule(listOf("geoip:private"), true) }
        assertNull(privateOnly.rule_set)
        assertNull(privateOnly.ip_cidr)
        assertFalse(privateOnly.checkEmpty())
    }

    @Test
    fun `规则集只为 geoip 与 geosite 生成本地文件`() {
        val sets = ArrayList<RuleSet>()
        generateRuleSet(listOf("geosite:cn", "geoip:us", "other"), sets)
        assertEquals(listOf("geosite:cn", "geoip:us"), sets.map { it.tag })
        for (set in sets) {
            assertEquals("local", set.type)
            assertEquals("binary", set.format)
            assertEquals(set.tag, set.path)
        }
    }
}
