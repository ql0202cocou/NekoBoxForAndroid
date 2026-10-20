package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.widget.glass.GlassChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 钉住设置页按分类拆成二级页后，编译、lint 都查不出的约定：
 * - global_preferences.xml 根下依次是七个分类（PreferenceScreen）加四个页面入口行（不再包分类，
 *   原因见 XML 注释），
 *   分类 key 与顺序等于 [SettingsPreferenceFragment.sections]：key 对不上，
 *   setPreferencesFromResource 会抛异常，一级页上的入口也会缺；
 * - 设置项一个不少、也不重复：七个分类里所有行的 key 恰好是拆分前的 40 个，加上之后新增的 1 个（glassMaterial）；
 * - 每个分类都有标题和图标（一级页上的入口行靠它们显示）。
 */
class SettingsSectionsResourcesTest {

    private val root: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/global_preferences.xml")).documentElement

    private fun Element.children(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    // 分类里所有的行（PreferenceCategory 只是小标题分组，向下展开）
    private fun Element.rows(): List<Element> = children().flatMap {
        if (it.tagName == "PreferenceCategory") it.rows() else listOf(it)
    }

    private val screens get() = root.children().filter { it.tagName == "PreferenceScreen" }

    // 拆分前设置页的全部 40 个设置项，加上之后新增的 glassMaterial
    private val expectedKeys = setOf(
        "isAutoConnect", "appTheme", "nightTheme", "serviceMode", "tunImplementation", "mtu",
        "speedInterval", "profileTrafficStatistics", "showDirectSpeed", "showGroupInNotification",
        "hideFromRecents", "alwaysShowAddress", "meteredNetwork", "acquireWakeLock", "logLevel",
        "globalCustomConfig", "proxyApps", "bypassLan", "bypassLanInCore", "trafficSniffing",
        "resolveDestination", "ipv6Mode", "rulesProvider", "remoteDns", "domain_strategy_for_remote",
        "directDns", "domain_strategy_for_direct", "domain_strategy_for_server", "enableDnsRouting",
        "enableFakeDns", "mixedPort", "appendHttpProxy", "allowAccess", "connectionTestURL",
        "enableClashAPI", "networkChangeResetConnections", "wakeResetConnections",
        "globalAllowInsecure", "allowInsecureOnRequest", "appTLSVersion",
        "glassMaterial",
    )

    @Test
    fun rootHoldsSevenSectionsThenFourEntries() {
        val tags = root.children().map { it.tagName }
        assertEquals(List(7) { "PreferenceScreen" } + List(4) { "Preference" }, tags)
        assertEquals(
            SettingsPreferenceFragment.sections.map { it.first },
            screens.map { it.getAttribute("app:key") },
        )
    }

    @Test
    fun sectionsHoldEverySettingExactlyOnce() {
        assertEquals(41, expectedKeys.size)
        val keys = screens.flatMap { it.rows() }.map { it.getAttribute("app:key") }
        assertTrue("有行缺少 app:key", keys.none { it.isEmpty() })
        assertEquals("有设置项出现了不止一次", keys.size, keys.toSet().size)
        assertEquals(expectedKeys, keys.toSet())
    }

    /** 玻璃材质：「界面」分类的最后一行，有图标，三个选项与 GlassChoice 一一对应 */
    @Test
    fun glassMaterialIsLastRowOfInterfaceWithIcon() {
        val section = screens.single { it.getAttribute("app:key") == SettingsPreferenceFragment.KEY_INTERFACE }
        val row = section.rows().last()
        assertEquals(Key.GLASS_MATERIAL, row.getAttribute("app:key"))
        assertTrue("玻璃材质行缺少 app:icon", row.getAttribute("app:icon").startsWith("@drawable/"))
        assertEquals("@array/glass_material", row.getAttribute("app:entries"))
        assertEquals("@array/int_array_3", row.getAttribute("app:entryValues"))
        assertEquals("0", row.getAttribute("app:defaultValue"))
        assertEquals(GlassChoice.AUTO.stored, row.getAttribute("app:defaultValue").toInt())
        assertEquals(listOf(0, 1, 2), GlassChoice.entries.map { it.stored })
    }

    @Test
    fun sectionsHaveTitleAndIcon() {
        for (screen in screens) {
            val key = screen.getAttribute("app:key")
            assertTrue("$key 缺少 app:title", screen.getAttribute("app:title").startsWith("@string/"))
            assertTrue("$key 缺少 app:icon", screen.getAttribute("app:icon").startsWith("@drawable/"))
        }
    }
}
