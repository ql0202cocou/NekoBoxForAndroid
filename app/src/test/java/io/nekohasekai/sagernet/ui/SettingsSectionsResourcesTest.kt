package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 钉住设置页按分类拆成二级页后，编译、lint 都查不出的约定：
 * - global_preferences.xml 根下依次是七个分类（PreferenceScreen）加恰好一个页面入口组，
 *   分类 key 与顺序等于 [SettingsPreferenceFragment.sections]：key 对不上，
 *   setPreferencesFromResource 会抛异常，一级页上的入口也会缺；
 * - 设置项一个不少、也不重复：七个分类里所有行的 key 恰好是拆分前的 40 个；
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

    // 拆分前设置页的全部 40 个设置项
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
    )

    @Test
    fun rootHoldsSixSectionsThenOneEntryGroup() {
        val tags = root.children().map { it.tagName }
        assertEquals(List(7) { "PreferenceScreen" } + "PreferenceCategory", tags)
        assertEquals(
            SettingsPreferenceFragment.sections.map { it.first },
            screens.map { it.getAttribute("app:key") },
        )
    }

    @Test
    fun sectionsHoldEverySettingExactlyOnce() {
        assertEquals(40, expectedKeys.size)
        val keys = screens.flatMap { it.rows() }.map { it.getAttribute("app:key") }
        assertTrue("有行缺少 app:key", keys.none { it.isEmpty() })
        assertEquals("有设置项出现了不止一次", keys.size, keys.toSet().size)
        assertEquals(expectedKeys, keys.toSet())
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
