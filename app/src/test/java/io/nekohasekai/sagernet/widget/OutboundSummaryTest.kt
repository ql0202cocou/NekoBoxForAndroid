package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Test

// OutboundPreference 的摘要取法（outboundSummary）：getSummary 在主线程调用、不查库，
// 节点名只用后台查好的结果，没查过就先显示选项本身的摘要并在后台查
class OutboundSummaryTest {

    @Test
    fun `不是指定配置时显示选项本身的摘要`() {
        for (value in listOf(null, "0", "1", "2")) {
            assertEquals(OutboundSummary.Default, outboundSummary(value, 5, 5L to "node"))
        }
    }

    @Test
    fun `指定配置但没选节点时显示选项本身的摘要`() {
        assertEquals(OutboundSummary.Default, outboundSummary("3", 0, null))
        assertEquals(OutboundSummary.Default, outboundSummary("3", -1, null))
    }

    @Test
    fun `没查过的节点先显示选项摘要并在后台查`() {
        assertEquals(OutboundSummary.Load(5), outboundSummary("3", 5, null))
    }

    @Test
    fun `选的节点变了要重新查`() {
        assertEquals(OutboundSummary.Load(6), outboundSummary("3", 6, 5L to "node"))
    }

    @Test
    fun `查到名字后显示节点名`() {
        assertEquals(OutboundSummary.Name("node"), outboundSummary("3", 5, 5L to "node"))
    }

    @Test
    fun `节点已不存在时回落到选项摘要且不再重查`() {
        assertEquals(OutboundSummary.Default, outboundSummary("3", 5, 5L to null))
    }
}
