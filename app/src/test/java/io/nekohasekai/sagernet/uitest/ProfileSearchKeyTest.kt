package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.ui.searchFold
import io.nekohasekai.sagernet.ui.searchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 节点搜索的缓存键：「折叠后的三段键 contains 折叠后的搜索词」必须与原先「名称、类型、地址各自
 * contains(搜索词小写, ignoreCase = true)」逐一同结果，包括 lowercase() 会改变结果的字符，且命中不会跨段。纯 JVM
 */
class ProfileSearchKeyTest {

    private val names = listOf(
        "Tokyo 01 VMess", "ΟΔΟΣ", "İstanbul", "Straße", "ſecret", "KELVIN K", "ǅungla", "Ꭰ cherokee", "",
    )
    private val types = listOf("VMess", "Shadowsocks", "Naïve", "Hysteria2")
    private val addresses = listOf("192.0.2.10:443", "[2001:db8::1]:8443", "example.COM:80")
    private val queries = listOf(
        "tokyo", "VMESS", "σ", "ς", "Σ", "i", "İ", "I", "ss", "ß", "s", "S", "k", "K", "ǆ", "Ǆ", "ꭰ", "Ꭰ",
        "naÏve", "com:80", "443", "vmessshadow", "0 vm", "a", "A", "2", "]:84",
    )

    private fun original(name: String, type: String, address: String, query: String): Boolean {
        val q = query.lowercase()
        return q.isEmpty() || name.contains(q, ignoreCase = true) || type.contains(q, ignoreCase = true) ||
            address.contains(q, ignoreCase = true)
    }

    @Test
    fun cachedKeyMatchesExactlyLikeTheThreeIgnoreCaseContains() {
        for (name in names) for (type in types) for (address in addresses) {
            val key = searchKey(name, type, address)
            for (query in queries) {
                assertEquals(
                    "name=$name type=$type address=$address query=$query",
                    original(name, type, address, query),
                    key.contains(searchFold(query.lowercase())),
                )
            }
        }
    }

    @Test
    fun matchNeverSpansTwoFields() {
        // 名称结尾与类型开头拼起来才像搜索词：原先三段分开比较，不算命中
        val key = searchKey("Tokyo", "VMess", "192.0.2.10:443")
        assertFalse(key.contains(searchFold("tokyovmess")))
        assertFalse(key.contains(searchFold("vmess192")))
    }
}
