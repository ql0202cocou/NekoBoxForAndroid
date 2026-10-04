package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnauthHttpGuardTest {

    private fun http(user: String? = "") = HttpBean().apply { username = user }
    private fun socks() = SOCKSBean()

    private fun reject(
        new: List<AbstractBean>, old: List<AbstractBean>, clash: Boolean = false
    ) = shouldRejectUnauthHttpOnly(new, old, clash)

    @Test
    fun `空分组放行`() = assertFalse(reject(listOf(http()), listOf()))

    @Test
    fun `全是无认证 HTTP 的分组放行`() =
        assertFalse(reject(listOf(http()), listOf(http(null), http(" "))))

    @Test
    fun `含其它类型节点的分组拒绝`() =
        assertTrue(reject(listOf(http()), listOf(http(), socks())))

    @Test
    fun `含带认证 HTTP 的分组拒绝`() =
        assertTrue(reject(listOf(http()), listOf(http("u"))))

    @Test
    fun `新结果含带认证 HTTP 放行`() =
        assertFalse(reject(listOf(http(), http("u")), listOf(socks())))

    @Test
    fun `新结果含其它协议放行`() =
        assertFalse(reject(listOf(http(), socks()), listOf(socks())))

    @Test
    fun `Clash 来源不受约束`() =
        assertFalse(reject(listOf(http()), listOf(socks()), clash = true))
}
