package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 钉住两个基础主题的自定义属性：attrs.xml 里的每个属性，Theme.SagerNet 与 Theme.SagerNet.Dialog
 * 都必须给值，且两边相同。
 *
 * 布局与 drawable 直接引用这些属性（如 ?attr/appBarColor），对话框主题的界面（SwitchActivity）
 * 也加载同样的布局；某个主题漏了属性，解析颜色时会抛异常崩溃，而编译和 lint 都发现不了。
 *
 * 同样钉住两个基础主题覆盖的文字与控件颜色属性（themedTextAttrs）：两边都指向 res/color/theme_text_*，取值相同。
 *
 * Theme.Start 豁免：它是应用级主题，只作用于启动窗口和不调用 Theme.apply 的界面（BlankActivity、
 * VpnRequestActivity 与几个快捷方式入口），这些界面都不加载布局，用不到自定义属性。
 */
class ThemeAttrsTest {

    private val values = File("src/main/res/values")

    private val baseThemes = listOf("Theme.SagerNet", "Theme.SagerNet.Dialog")

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(values, name)).documentElement

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    // attrs.xml 声明的全部自定义属性
    private fun customAttrs(): Set<String> =
        parse("attrs.xml").children("attr").map { it.getAttribute("name") }.toSet()

    // themes.xml 里某个样式自身写的条目：属性名 → 去掉首尾空白的值
    private fun styleItems(style: String): Map<String, String> {
        val element = parse("themes.xml").children("style").singleOrNull { it.getAttribute("name") == style }
        assertTrue("themes.xml 里没有 $style", element != null)
        return element!!.children("item").associate { it.getAttribute("name") to it.textContent.trim() }
    }

    @Test
    fun baseThemesDefineEveryCustomAttr() {
        val attrs = customAttrs()
        assertFalse("attrs.xml 没有解析出属性", attrs.isEmpty())
        for (theme in baseThemes) {
            val items = styleItems(theme)
            for (attr in attrs) {
                assertFalse("$theme 没有给 $attr 赋值", items[attr].isNullOrEmpty())
            }
        }
    }

    // 两个基础主题覆盖 M3 固定基线色的文字与控件属性（U2 N2）：必须都写、取值相同，且指向主题角色的状态列表
    private val themedTextAttrs = listOf(
        "android:textColorPrimary",
        "android:textColorSecondary",
        "android:textColorTertiary",
        "android:textColorHint",
        "android:textColorPrimaryDisableOnly",
        "android:textColorAlertDialogListItem",
        "colorControlNormal",
    )

    @Test
    fun baseThemesAgreeOnThemedTextColors() {
        val (app, dialog) = baseThemes.map { theme ->
            val items = styleItems(theme)
            for (attr in themedTextAttrs) {
                val value = items[attr]
                assertTrue("$theme 的 $attr 没有指向 @color/theme_text_*：$value", value?.startsWith("@color/theme_text_") == true)
            }
            items.filterKeys { it in themedTextAttrs }
        }
        assertEquals("Theme.SagerNet 与 Theme.SagerNet.Dialog 的文字颜色属性取值不同", app, dialog)
    }

    @Test
    fun baseThemesAgreeOnCustomAttrs() {
        val attrs = customAttrs()
        val (app, dialog) = baseThemes.map { theme -> styleItems(theme).filterKeys { it in attrs } }
        assertEquals("Theme.SagerNet 与 Theme.SagerNet.Dialog 的自定义属性取值不同", app, dialog)
    }
}
