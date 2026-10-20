package io.nekohasekai.sagernet.uitest

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态卡片的无障碍：速率行拆成「箭头 / 数字 / 单位」三个子视图后，每个速率列仍是一个读屏节点，
 * 朗读文字与可见文字一致（拆分前是「▲ 1.30 GB/s」），子视图不单独朗读。不截图，所有平台都跑。
 */
class StatsBarAccessibilityTest : UiTestBase() {

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun rateColumnsAreSingleNodesWithTheVisibleText() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val stats = main.get().binding.stats
        stats.updateSpeed(1_300_000_000L, 220_000L)

        for ((prefix, arrow) in listOf("tx" to "▲", "rx" to "▼")) {
            val column = stats.findViewById<View>(
                if (prefix == "tx") R.id.tx_column else R.id.rx_column
            )
            val number = stats.text(if (prefix == "tx") R.id.tx_number else R.id.rx_number)
            val unit = stats.text(if (prefix == "tx") R.id.tx_unit else R.id.rx_unit)
            assertTrue("$prefix：数字不该为空", number.isNotEmpty())
            assertTrue("$prefix：单位应带 /s：$unit", unit.endsWith("/s"))
            assertEquals("$prefix 列的朗读文字", "$arrow $number $unit", column.contentDescription.toString())
            assertTrue("$prefix 列应是读屏焦点", ViewCompat.isScreenReaderFocusable(column))
            assertNotEquals("$prefix 列自己要参与无障碍", View.IMPORTANT_FOR_ACCESSIBILITY_NO, column.importantForAccessibility)
            for (id in listOf(
                R.id.tx_arrow to "tx", R.id.rx_arrow to "rx", R.id.tx_number to "tx",
                R.id.rx_number to "rx", R.id.tx_unit to "tx", R.id.rx_unit to "rx",
            ).filter { it.second == prefix }.map { it.first }) {
                assertEquals(
                    "$prefix 列的子视图不该单独朗读",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, stats.findViewById<View>(id).importantForAccessibility
                )
            }
        }
    }

    @Test
    fun descriptionFollowsTheText() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>()
        val stats = main.get().binding.stats
        val column = stats.findViewById<View>(R.id.tx_column)
        stats.updateSpeed(1_300_000_000L, 0L)
        val first = column.contentDescription.toString()
        stats.updateSpeed(1_300_000_000L, 5L)
        assertEquals("文字没变时朗读文字不变", first, column.contentDescription.toString())
        stats.updateSpeed(0L, 0L)
        assertFalse("文字变了朗读文字要跟着变", first == column.contentDescription.toString())
        assertEquals("▲ 0 B/s", column.contentDescription.toString())
    }
}
