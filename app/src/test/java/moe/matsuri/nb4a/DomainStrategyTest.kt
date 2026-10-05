package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Test

// SingBoxOptionsUtil.domainStrategy 的取值逻辑（domainStrategyOf）：按 tag 选键，auto 换成该 tag 的默认
class DomainStrategyTest {

    private val stored = mapOf(
        "domain_strategy_for_remote" to "auto",
        "domain_strategy_for_direct" to "ipv4_only",
        "domain_strategy_for_server" to "auto",
    )

    @Test
    fun `各 tag 读自己的键，auto 换成默认`() {
        assertEquals("", domainStrategyOf("dns-remote", stored::get))
        assertEquals("ipv4_only", domainStrategyOf("dns-direct", stored::get))
        assertEquals("prefer_ipv4", domainStrategyOf("server", stored::get))
    }

    @Test
    fun `分组 DNS 等其余 tag 都按 server 的键取`() {
        val server = mapOf("domain_strategy_for_server" to "prefer_ipv6")
        assertEquals("prefer_ipv6", domainStrategyOf("dns-group-0", server::get))
        assertEquals("prefer_ipv6", domainStrategyOf("dns-group-3", server::get))
    }

    @Test
    fun `没存过的键按空串处理`() {
        val none = emptyMap<String, String>()
        assertEquals("", domainStrategyOf("dns-remote", none::get))
        assertEquals("", domainStrategyOf("dns-direct", none::get))
        assertEquals("", domainStrategyOf("server", none::get))
    }
}
