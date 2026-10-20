package io.nekohasekai.sagernet.widget.glass

import io.nekohasekai.sagernet.utils.M3Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ui-plan 第 7 节的对比度，算在 FROST 配方的最终合成背景上（纯 JVM，颜色取自 M3Palette 生成的六主题亮 / 暗两套角色）：
 * 正文与小标签 ≥ 4.5:1，图标 ≥ 3:1。
 *
 * 合成顺序同 GlassSurface 的绘制顺序：页面底 → 底色（角色色 @ 配方不透明度）→ 环境色 → sheen → 中心衬底。
 * 取最坏情况：
 * - 背景取两种——页面底（colorBackground）与「页面底混 25% onSurface」（滚动内容经过玻璃下方时明暗变化最大的情形）；
 * - 环境色取 from / to 中更差的一个，sheen 取最强（顶部），状态卡片的中心衬底按「文字在卡片边缘、衬底已淡出」不计
 *   （只会让实际更好）。
 * 前景始终是 on- 角色的实色，不随材质变半透明。
 */
class GlassContrastTest {

    private fun palette(spec: M3Palette.Spec, dark: Boolean): Map<String, Int> =
        M3Palette.colors(spec, dark).toMap()

    private fun name(role: ColorRole) = when (role) {
        ColorRole.SURFACE_CONTAINER -> "surface_container"
        ColorRole.SURFACE_CONTAINER_LOWEST -> "surface_container_lowest"
        ColorRole.PRIMARY_CONTAINER -> "primary_container"
        ColorRole.PRIMARY -> "primary"
        ColorRole.SECONDARY -> "secondary"
        ColorRole.ON_SURFACE -> "on_surface"
        ColorRole.OUTLINE_VARIANT -> "outline_variant"
    }

    private fun channel(c: Int, shift: Int) = (c shr shift) and 0xFF

    /** [top] 以 [alpha] 叠在不透明的 [bottom] 上 */
    private fun over(top: Int, alpha: Float, bottom: Int): Int {
        fun mix(shift: Int) = (channel(top, shift) * alpha + channel(bottom, shift) * (1 - alpha)).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(channel(c, 16)) + 0.7152 * lin(channel(c, 8)) + 0.0722 * lin(channel(c, 0))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val background = "background"

    /** 一个表面在 FROST 档下、两种背景、两种环境色端点下可能出现的全部合成底色 */
    private fun backdrops(target: GlassTarget, p: Map<String, Int>): List<Int> {
        val r = GlassRecipes.of(target, GlassTier.FROST)
        val h = r.highlight!!
        val dark = luminance(p.getValue("surface")) < 0.5
        val page = p.getValue(background)
        val pages = listOf(page, over(p.getValue("on_surface"), 0.25f, page))
        val sheenRole = name(h.edgeColor.pick(dark))
        val ambientEnds = r.ambient?.let { listOf(it.from, it.to) } ?: listOf(null)
        return pages.flatMap { pg ->
            ambientEnds.map { end ->
                var c = over(p.getValue(name(r.base)), r.baseAlpha, pg)
                if (end != null) c = over(p.getValue(name(end.role)), end.alpha, c)
                over(p.getValue(sheenRole), h.sheen.pick(dark), c)
            }
        }
    }

    private fun worst(target: GlassTarget, p: Map<String, Int>, foreground: String): Double =
        backdrops(target, p).minOf { contrast(p.getValue(foreground), it) }

    private fun forEveryTheme(check: (label: String, p: Map<String, Int>) -> Unit) {
        for (spec in M3Palette.specs) for (dark in listOf(false, true)) {
            check("${spec.name}-${if (dark) "dark" else "light"}", palette(spec, dark))
        }
    }

    @Test
    fun dockUnselectedLabelAndIcon() = forEveryTheme { label, p ->
        val ratio = worst(GlassTarget.DOCK, p, "on_surface_variant")
        println("CONTRAST dock on_surface_variant $label = ${"%.2f".format(ratio)}")
        assertTrue("Dock 未选中标签 $label 只有 $ratio:1", ratio >= 4.5)
    }

    @Test
    fun statusCardText() = forEveryTheme { label, p ->
        for (fg in listOf("on_surface", "on_surface_variant")) {
            val ratio = worst(GlassTarget.STATUS_CARD, p, fg)
            println("CONTRAST card $fg $label = ${"%.2f".format(ratio)}")
            assertTrue("状态卡片 $fg $label 只有 $ratio:1", ratio >= 4.5)
        }
    }

    @Test
    fun connectButtonIcon() = forEveryTheme { label, p ->
        val ratio = worst(GlassTarget.CONNECT_BUTTON, p, "on_primary_container")
        println("CONTRAST button on_primary_container $label = ${"%.2f".format(ratio)}")
        assertTrue("连接按钮图标 $label 只有 $ratio:1", ratio >= 3.0)
    }

    /** 选中胶囊保持实色 secondaryContainer（不经玻璃）：粉色主题刚过 4.5:1（浅 4.57、深 4.54） */
    @Test
    fun selectedPillStaysSolidAndReadable() {
        val pink = M3Palette.specs.first { it.name == "pink" }
        for (dark in listOf(false, true)) {
            val p = palette(pink, dark)
            val ratio = contrast(p.getValue("on_secondary_container"), p.getValue("secondary_container"))
            println("CONTRAST dock pill pink-${if (dark) "dark" else "light"} = ${"%.2f".format(ratio)}")
            assertTrue("选中胶囊 only $ratio:1", ratio >= 4.5)
        }
    }
}
