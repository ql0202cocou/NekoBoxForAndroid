package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// BUG-012：订阅更新结果只在订阅身份未变时才写库
class SubscriptionIdentityTest {

    private fun group(
        link: String = "https://a.example/sub",
        type: Int = GroupType.SUBSCRIPTION,
        forceResolve: Boolean = false,
        nameserver: String = "",
        edit: SubscriptionBean.() -> Unit = {},
    ) = ProxyGroup(
        id = 1, type = type, proxyServerNameserver = nameserver,
        subscription = SubscriptionBean().apply {
            initializeDefaultValues()
            this.link = link
            this.forceResolve = forceResolve
            edit()
        },
    )

    private fun superseded(started: ProxyGroup, current: ProxyGroup) =
        isSubscriptionSuperseded(started.subscriptionIdentity(), current)

    @Test
    fun `链接从 A 改成 B 后，A 的结果过期`() {
        assertTrue(superseded(group(link = "https://a.example/sub"), group(link = "https://b.example/sub")))
    }

    @Test
    fun `链接只差首尾空白不算换了订阅`() {
        // 旧版本保存时不去空白，编辑器现在保存的是去空白后的链接
        assertFalse(superseded(group(link = "https://a.example/sub \n"), group(link = "https://a.example/sub")))
    }

    @Test
    fun `分组类型改变后结果过期`() {
        assertTrue(superseded(group(), group(type = GroupType.BASIC)))
    }

    @Test
    fun `自定义 UA 与强制解析 去重开关改变后结果过期`() {
        val started = group()
        assertTrue(superseded(started, group { customUserAgent = "other" }))
        assertTrue(superseded(started, group(forceResolve = true)))
        assertTrue(superseded(started, group { deduplication = !(deduplication ?: false) }))
    }

    @Test
    fun `节点解析 DNS 只在强制解析开着时参与身份`() {
        assertTrue(
            superseded(group(forceResolve = true, nameserver = "1.1.1.1"), group(forceResolve = true, nameserver = "8.8.8.8"))
        )
        assertFalse(superseded(group(nameserver = "1.1.1.1"), group(nameserver = "8.8.8.8")))
    }

    @Test
    fun `不影响下载内容的设置改变不让进行中的更新作废`() {
        val started = group()
        val current = group {
            autoUpdate = !(autoUpdate ?: false)
            autoUpdateDelay = 999
            updateWhenConnectedOnly = !(updateWhenConnectedOnly ?: false)
            lastUpdated = 123L
            bytesUsed = 5L
            subscriptionUserinfo = "upload=1"
        }.apply { name = "renamed"; isSelector = true; userOrder = 9 }
        assertFalse(superseded(started, current))
    }

    @Test
    fun `身份相同的分组不过期`() {
        assertFalse(superseded(group(), group()))
        assertEquals(group().subscriptionIdentity(), group().subscriptionIdentity())
    }

    @Test
    fun `订阅链接只接受 http 与 https，不区分大小写`() {
        assertTrue(isHttpSubscriptionLink("https://a.example/sub"))
        assertTrue(isHttpSubscriptionLink("HTTP://a.example/sub"))
        assertFalse(isHttpSubscriptionLink("content://docs/x"))
        assertFalse(isHttpSubscriptionLink("ftp://a.example/sub"))
        assertFalse(isHttpSubscriptionLink("a.example/sub"))
        assertFalse(isHttpSubscriptionLink("httpx://a.example"))
        assertFalse(isHttpSubscriptionLink(" https://a.example"))
    }

    @Test
    fun `编辑器额外接受选择文件得到的 content 链接，深链规则不变`() {
        assertTrue(isEditableSubscriptionLink("https://a.example/sub"))
        assertTrue(isEditableSubscriptionLink("content://docs/x"))
        assertFalse(isEditableSubscriptionLink("ftp://a.example/sub"))
        assertFalse(isEditableSubscriptionLink("javascript:alert(1)"))
        assertFalse(isEditableSubscriptionLink("not a link"))
        assertFalse(isHttpSubscriptionLink("content://docs/x"))
    }
}
