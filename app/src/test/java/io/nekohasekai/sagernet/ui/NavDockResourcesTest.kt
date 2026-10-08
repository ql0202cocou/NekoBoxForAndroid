package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 钉住底部 Dock 相关资源里编译、lint 都查不出的约定：
 * - 设置页末尾的页面入口与 [SettingsPreferenceFragment.navEntries] 一一对应：XML 里 key 写错，
 *   打开设置页时 findPreference(...)!! 会直接崩溃；
 * - dimens.xml 里由其它尺寸算出来的值（改了 Dock 高度等却忘了跟着改，位置就对不上）；
 * - Dock 的资源只用主题角色色，不写死颜色（涟漪蒙版只取透明度，不算）。
 */
class NavDockResourcesTest {

    private val res = File("src/main/res")

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, path)).documentElement

    private fun Element.childElements(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == tag }
    }

    // dimens.xml 里的 dp 值：名字 → 数值
    private fun dimens(): Map<String, Int> =
        parse("values/dimens.xml").childElements("dimen").associate {
            val value = it.textContent.trim()
            assertTrue("${it.getAttribute("name")} 不是整数 dp：$value", value.matches(Regex("\\d+dp")))
            it.getAttribute("name") to value.removeSuffix("dp").toInt()
        }

    @Test
    fun settingsNavEntriesMatchPreferences() {
        val category = parse("xml/global_preferences.xml").childElements("PreferenceCategory").last()
        val keys = category.childElements("Preference").map { it.getAttribute("app:key") }
        assertEquals(listOf("navDashboard", "navLogs", "navTools", "navAbout"), keys)
        assertEquals(keys, SettingsPreferenceFragment.navEntries.map { it.first })
    }

    @Test
    fun dockDimensFollowTheirFormulas() {
        val d = dimens()
        val height = d.getValue("nav_dock_height")
        // 右侧让出连接按钮：右边距 12 + 按钮 56 + 间隙 8
        assertEquals(12 + 56 + 8, d["nav_dock_margin_end"])
        // 选中胶囊是项高（Dock 高减去上下内边距）的全圆角
        assertEquals((height - 2 * d.getValue("nav_dock_padding")) / 2, d["nav_dock_item_radius"])
        assertEquals(height / 2, d["nav_dock_radius"])
        val marginBottom = d.getValue("nav_dock_margin_bottom")
        // 连接按钮（56dp）与 Dock 垂直居中
        assertEquals(marginBottom + (height - 56) / 2, d["fab_margin_bottom"])
        // 状态卡片的底边距、列表在导航栏之上的留白：Dock 顶边再往上 8dp
        assertEquals(marginBottom + height + 8, d["nav_dock_clearance"])
    }

    @Test
    fun dockResourcesUseNoColorLiterals() {
        val files = listOf(
            "color/nav_dock_item.xml",
            "drawable/nav_dock_item_background.xml",
            "layout/layout_nav_dock_item.xml",
            "layout/layout_main.xml",
        )
        val literal = Regex("\"#[0-9A-Fa-f]+\"")
        for (path in files) {
            val text = File(res, path).readText()
            assertTrue("$path 里有写死的颜色", literal.find(text) == null)
        }
        // 唯一允许的固定颜色：涟漪蒙版，只取它的透明度，不会画出来
        val mask = File(res, "drawable/nav_dock_item_background.xml").readText()
        assertEquals(1, Regex("@android:color/").findAll(mask).count())
        assertTrue(mask.contains("@android:color/white"))
    }
}
