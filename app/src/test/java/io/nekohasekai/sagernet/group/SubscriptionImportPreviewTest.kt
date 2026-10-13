package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImportPreviewTest {

    @Test
    fun `短文本原样返回`() {
        assertEquals("abc", truncateForDisplay("abc", 3))
        assertEquals("", truncateForDisplay("", 3))
    }

    @Test
    fun `超长文本截断并加省略号`() {
        val huge = "a".repeat(8 * 1024 * 1024)
        val shown = truncateForDisplay(huge, IMPORT_PREVIEW_LINK_MAX)
        assertEquals(IMPORT_PREVIEW_LINK_MAX + 1, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `不从代理对中间切开`() {
        val s = "ab" + "😀" + "cd"
        assertEquals("ab…", truncateForDisplay(s, 3))
    }

    @Test
    fun `没有预设时为空`() {
        assertTrue(subscriptionPresets(null, null, null).isEmpty())
        assertTrue(subscriptionPresets("", false, "  ").isEmpty())
    }

    @Test
    fun `有预设时逐项列出且截断`() {
        val list = subscriptionPresets("udp://223.5.5.5", true, "x".repeat(5000))
        assertEquals(
            listOf(
                SubscriptionPreset.PROXY_SERVER_NAMESERVER,
                SubscriptionPreset.FORCE_RESOLVE,
                SubscriptionPreset.CUSTOM_USER_AGENT,
            ),
            list.map { it.first },
        )
        assertEquals("udp://223.5.5.5", list[0].second)
        assertEquals("true", list[1].second)
        assertEquals(IMPORT_PREVIEW_PRESET_MAX + 1, list[2].second.length)
    }
}
