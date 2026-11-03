@file:Suppress("RestrictedApi")

package io.nekohasekai.sagernet.utils

import com.google.android.material.color.utilities.ColorUtils
import com.google.android.material.color.utilities.Contrast
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent

/**
 * 将 1.4.2 的 21 套主色、强调色和中性色映射到现有 Material 3 控件。
 * 顶部与强调色容器保留旧值；新增角色由旧色板补齐，文字用同色相的可读色阶。
 * 所有资源仍由这里生成；独立的 legacy-theme-colors.json 钉住旧版原值。
 */
object M3Palette {

    private const val SOURCE = "app/src/test/java/io/nekohasekai/sagernet/utils/M3Palette.kt"

    private val mdc = MaterialDynamicColors()

    /**
     * 颜色角色：资源名后缀、动态颜色、对应的主题属性。只收 View 体系的 M3 主题里有属性的角色
     * （surfaceTint 没有对应属性，不生成）
     */
    class Role(val name: String, val color: DynamicColor, val attr: String)

    val roles: List<Role> = listOf(
        Role("primary", mdc.primary(), "colorPrimary"),
        Role("on_primary", mdc.onPrimary(), "colorOnPrimary"),
        Role("primary_container", mdc.primaryContainer(), "colorPrimaryContainer"),
        Role("on_primary_container", mdc.onPrimaryContainer(), "colorOnPrimaryContainer"),
        Role("inverse_primary", mdc.inversePrimary(), "colorPrimaryInverse"),
        Role("secondary", mdc.secondary(), "colorSecondary"),
        Role("on_secondary", mdc.onSecondary(), "colorOnSecondary"),
        Role("secondary_container", mdc.secondaryContainer(), "colorSecondaryContainer"),
        Role("on_secondary_container", mdc.onSecondaryContainer(), "colorOnSecondaryContainer"),
        Role("tertiary", mdc.tertiary(), "colorTertiary"),
        Role("on_tertiary", mdc.onTertiary(), "colorOnTertiary"),
        Role("tertiary_container", mdc.tertiaryContainer(), "colorTertiaryContainer"),
        Role("on_tertiary_container", mdc.onTertiaryContainer(), "colorOnTertiaryContainer"),
        Role("error", mdc.error(), "colorError"),
        Role("on_error", mdc.onError(), "colorOnError"),
        Role("error_container", mdc.errorContainer(), "colorErrorContainer"),
        Role("on_error_container", mdc.onErrorContainer(), "colorOnErrorContainer"),
        Role("background", mdc.background(), "android:colorBackground"),
        Role("on_background", mdc.onBackground(), "colorOnBackground"),
        Role("surface", mdc.surface(), "colorSurface"),
        Role("on_surface", mdc.onSurface(), "colorOnSurface"),
        Role("surface_variant", mdc.surfaceVariant(), "colorSurfaceVariant"),
        Role("on_surface_variant", mdc.onSurfaceVariant(), "colorOnSurfaceVariant"),
        Role("inverse_surface", mdc.inverseSurface(), "colorSurfaceInverse"),
        Role("inverse_on_surface", mdc.inverseOnSurface(), "colorOnSurfaceInverse"),
        Role("surface_container_lowest", mdc.surfaceContainerLowest(), "colorSurfaceContainerLowest"),
        Role("surface_container_low", mdc.surfaceContainerLow(), "colorSurfaceContainerLow"),
        Role("surface_container", mdc.surfaceContainer(), "colorSurfaceContainer"),
        Role("surface_container_high", mdc.surfaceContainerHigh(), "colorSurfaceContainerHigh"),
        Role("surface_container_highest", mdc.surfaceContainerHighest(), "colorSurfaceContainerHighest"),
        Role("surface_dim", mdc.surfaceDim(), "colorSurfaceDim"),
        Role("surface_bright", mdc.surfaceBright(), "colorSurfaceBright"),
        Role("outline", mdc.outline(), "colorOutline"),
        Role("outline_variant", mdc.outlineVariant(), "colorOutlineVariant"),
        Role("primary_fixed", mdc.primaryFixed(), "colorPrimaryFixed"),
        Role("primary_fixed_dim", mdc.primaryFixedDim(), "colorPrimaryFixedDim"),
        Role("on_primary_fixed", mdc.onPrimaryFixed(), "colorOnPrimaryFixed"),
        Role("on_primary_fixed_variant", mdc.onPrimaryFixedVariant(), "colorOnPrimaryFixedVariant"),
        Role("secondary_fixed", mdc.secondaryFixed(), "colorSecondaryFixed"),
        Role("secondary_fixed_dim", mdc.secondaryFixedDim(), "colorSecondaryFixedDim"),
        Role("on_secondary_fixed", mdc.onSecondaryFixed(), "colorOnSecondaryFixed"),
        Role("on_secondary_fixed_variant", mdc.onSecondaryFixedVariant(), "colorOnSecondaryFixedVariant"),
        Role("tertiary_fixed", mdc.tertiaryFixed(), "colorTertiaryFixed"),
        Role("tertiary_fixed_dim", mdc.tertiaryFixedDim(), "colorTertiaryFixedDim"),
        Role("on_tertiary_fixed", mdc.onTertiaryFixed(), "colorOnTertiaryFixed"),
        Role("on_tertiary_fixed_variant", mdc.onTertiaryFixedVariant(), "colorOnTertiaryFixedVariant"),
    )

    // 兼容旧属性；主色深色值仍取 1.4.2 对应的 700 色。
    val aliasAttrs: List<Pair<String, String>> = listOf(
        "colorPrimaryVariant" to "primary",
        "colorSecondaryVariant" to "secondary",
        "colorPrimaryDark" to "legacy_primary_dark",
    )

    class Spec(
        val name: String,
        val style: String,
        val seed: Int,
        val primaryDark: Int,
        val accent: Int,
        val light: Int,
        val mid: Int,
    )

    // 顺序同 Theme.KEPT；数值逐项来自 5768494d（1.4.2）的 themes.xml / colors.xml。
    val specs: List<Spec> = listOf(
        Spec("pink", "Pink_SSR", 0xFFFB7299.toInt(), 0xFFFB7299.toInt(), 0xFFFF4081.toInt(), 0xFFFF80AB.toInt(), 0xFFFB7299.toInt()),
        Spec("red", "Red", 0xFFF44336.toInt(), 0xFFD32F2F.toInt(), 0xFFFF5252.toInt(), 0xFFFFCDD2.toInt(), 0xFFE57373.toInt()),
        Spec("pink_material", "Pink", 0xFFE91E63.toInt(), 0xFFC2185B.toInt(), 0xFFFF4081.toInt(), 0xFFF8BBD0.toInt(), 0xFFF06292.toInt()),
        Spec("purple", "Purple", 0xFF9C27B0.toInt(), 0xFF7B1FA2.toInt(), 0xFFE040FB.toInt(), 0xFFE1BEE7.toInt(), 0xFFBA68C8.toInt()),
        Spec("deep_purple", "DeepPurple", 0xFF673AB7.toInt(), 0xFF512DA8.toInt(), 0xFF7C4DFF.toInt(), 0xFFD1C4E9.toInt(), 0xFF9575CD.toInt()),
        Spec("indigo", "Indigo", 0xFF3F51B5.toInt(), 0xFF303F9F.toInt(), 0xFF536DFE.toInt(), 0xFFC5CAE9.toInt(), 0xFF7986CB.toInt()),
        Spec("blue", "Blue", 0xFF2196F3.toInt(), 0xFF1976D2.toInt(), 0xFF448AFF.toInt(), 0xFFBBDEFB.toInt(), 0xFF64B5F6.toInt()),
        Spec("light_blue", "LightBlue", 0xFF03A9F4.toInt(), 0xFF0288D1.toInt(), 0xFF40C4FF.toInt(), 0xFFB3E5FC.toInt(), 0xFF4FC3F7.toInt()),
        Spec("cyan", "Cyan", 0xFF00BCD4.toInt(), 0xFF0097A7.toInt(), 0xFF18FFFF.toInt(), 0xFFB2EBF2.toInt(), 0xFF4DD0E1.toInt()),
        Spec("teal", "Teal", 0xFF009688.toInt(), 0xFF00796B.toInt(), 0xFF64FFDA.toInt(), 0xFFB2DFDB.toInt(), 0xFF4DB6AC.toInt()),
        Spec("green", "Green", 0xFF4CAF50.toInt(), 0xFF388E3C.toInt(), 0xFF69F0AE.toInt(), 0xFFC8E6C9.toInt(), 0xFF81C784.toInt()),
        Spec("light_green", "LightGreen", 0xFF8BC34A.toInt(), 0xFF689F38.toInt(), 0xFFB2FF59.toInt(), 0xFFDCEDC8.toInt(), 0xFFAED581.toInt()),
        Spec("lime", "Lime", 0xFFCDDC39.toInt(), 0xFFAFB42B.toInt(), 0xFFEEFF41.toInt(), 0xFFF0F4C3.toInt(), 0xFFDCE775.toInt()),
        Spec("yellow", "Yellow", 0xFFFFEB3B.toInt(), 0xFFFBC02D.toInt(), 0xFFFFFF00.toInt(), 0xFFFFF9C4.toInt(), 0xFFFFF176.toInt()),
        Spec("amber", "Amber", 0xFFFFC107.toInt(), 0xFFFFA000.toInt(), 0xFFFFD740.toInt(), 0xFFFFECB3.toInt(), 0xFFFFD54F.toInt()),
        Spec("orange", "Orange", 0xFFFF9800.toInt(), 0xFFF57C00.toInt(), 0xFFFFAB40.toInt(), 0xFFFFE0B2.toInt(), 0xFFFFB74D.toInt()),
        Spec("deep_orange", "DeepOrange", 0xFFFF5722.toInt(), 0xFFE64A19.toInt(), 0xFFFF6E40.toInt(), 0xFFFFCCBC.toInt(), 0xFFFF8A65.toInt()),
        Spec("brown", "Brown", 0xFF795548.toInt(), 0xFF5D4037.toInt(), 0xFFBCAAA4.toInt(), 0xFFD7CCC8.toInt(), 0xFFA1887F.toInt()),
        Spec("grey", "Grey", 0xFF9E9E9E.toInt(), 0xFF616161.toInt(), 0xFFEEEEEE.toInt(), 0xFFF5F5F5.toInt(), 0xFFE0E0E0.toInt()),
        Spec("blue_grey", "BlueGrey", 0xFF607D8B.toInt(), 0xFF455A64.toInt(), 0xFFB0BEC5.toInt(), 0xFFCFD8DC.toInt(), 0xFF90A4AE.toInt()),
        Spec("black", "Black", 0xFF2B2B2B.toInt(), 0xFF2B2B2B.toInt(), 0xFF9E9E9E.toInt(), 0xFF2B2B2B.toInt(), 0xFF2B2B2B.toInt()),
    )

    val seedColorNames: Map<String, String> = mapOf(
        "pink" to "color_pink_ssr",
        "red" to "material_red_500",
        "pink_material" to "material_pink_500",
        "purple" to "material_purple_500",
        "deep_purple" to "material_deep_purple_500",
        "indigo" to "material_indigo_500",
        "blue" to "material_blue_500",
        "light_blue" to "material_light_blue_500",
        "cyan" to "material_cyan_500",
        "teal" to "material_teal_500",
        "green" to "material_green_500",
        "light_green" to "material_light_green_500",
        "lime" to "material_lime_500",
        "yellow" to "material_yellow_500",
        "amber" to "material_amber_500",
        "orange" to "material_orange_500",
        "deep_orange" to "material_deep_orange_500",
        "brown" to "material_brown_500",
        "grey" to "material_grey_500",
        "blue_grey" to "material_blue_grey_500",
        "black" to "color_ng_black_primary",
    )

    fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    /** 「纯黑夜间」覆盖样式只改这些角色（surface 系），其它角色不动 */
    val pureBlackRoles: List<String> = listOf(
        "background", "surface", "surface_dim", "surface_container_lowest", "surface_container_low",
        "surface_container", "surface_container_high", "surface_container_highest", "surface_bright",
    )

    /**
     * 「纯黑夜间」的暗色 surface 系（同 FlClash 的 toPureBlack；普通夜间遵循旧版，包括黑主题在内的所有主题都可另外开启
     * 纯黑背景）：在旧版暗色背景的基础上，
     * 各层色调整体下移 surface 的色调——surface 落到 0（纯黑），各层容器保持原来的层级差
     */
    fun pureBlackColors(spec: Spec): List<Pair<String, Int>> {
        val dark = colors(spec, true).toMap()
        val shift = Hct.fromInt(dark.getValue("surface")).tone
        return pureBlackRoles.map { role ->
            val hct = Hct.fromInt(dark.getValue(role))
            role to Hct.from(hct.hue, hct.chroma, (hct.tone - shift).coerceAtLeast(0.0)).toInt()
        }
    }

    fun pureColorName(spec: Spec, role: String) = "m3_${spec.name}_pure_$role"

    /** 覆盖样式用的颜色文件：亮色套写各主题原来的亮色值（白天套上覆盖样式等于没变），暗色套写压黑后的值 */
    fun pureColorsXml(dark: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- 由 $SOURCE 生成，勿手改：「纯黑夜间」覆盖样式的 surface 系颜色，")
        append(if (dark) "暗色（色调下移到 surface 为纯黑）" else "亮色（与各主题的亮色值相同，覆盖样式在白天不改变外观）")
        append(" -->\n<resources>\n")
        for (spec in specs) {
            val values = if (dark) pureBlackColors(spec) else colors(spec, false).filter { it.first in pureBlackRoles }
            for ((role, argb) in values) {
                append("    <color name=\"${pureColorName(spec, role)}\">${hex(argb)}</color>\n")
            }
        }
        append("</resources>\n")
    }

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun contrast(a: Int, b: Int): Double =
        Contrast.ratioOfTones(ColorUtils.lstarFromArgb(a), ColorUtils.lstarFromArgb(b))

    private fun onColor(background: Int): Int =
        if (contrast(white, background) >= contrast(black, background)) white else black

    // 容器保留旧版原色；用作小字号文字时，只调整明度到所有中性底色上均可读。
    private fun textColor(source: Int, background: Int, dark: Boolean): Int {
        val hct = Hct.fromInt(source)
        var tone = hct.tone
        var color = source
        while (contrast(color, background) < 4.5 && tone > 0.0 && tone < 100.0) {
            tone = (tone + if (dark) 0.5 else -0.5).coerceIn(0.0, 100.0)
            color = Hct.from(hct.hue, hct.chroma, tone).toInt()
        }
        return color
    }

    /** 旧版亮底 #FFFFFF / 暗底 #121212；现有无阴影容器用中性色阶区分层次。 */
    fun colors(spec: Spec, dark: Boolean): List<Pair<String, Int>> {
        val scheme = SchemeContent(Hct.fromInt(spec.seed), dark, 0.0)
        val palette = roles.associate { it.name to it.color.getArgb(scheme) }.toMutableMap()
        fun set(role: String, rgb: Int) { palette[role] = rgb or 0xFF000000.toInt() }
        val surface = if (dark) 0x121212 else 0xFFFFFF
        val highest = if (dark) 0x383838 else 0xE0E0E0
        set("background", surface)
        set("surface", surface)
        set("surface_container_lowest", if (dark) 0x0C0C0C else 0xFFFFFF)
        set("surface_container_low", if (dark) 0x1E1E1E else 0xF5F5F5)
        set("surface_container", if (dark) 0x242424 else 0xF0F0F0)
        set("surface_container_high", if (dark) 0x2C2C2C else 0xEAEAEA)
        set("surface_container_highest", highest)
        set("surface_dim", if (dark) surface else 0xE0E0E0)
        set("surface_bright", if (dark) 0x424242 else surface)
        set("surface_variant", highest)
        set("on_background", if (dark) 0xE0E0E0 else 0x212121)
        set("on_surface", if (dark) 0xE0E0E0 else 0x212121)
        set("on_surface_variant", if (dark) 0xBDBDBD else 0x616161)
        set("inverse_surface", if (dark) 0xF5F5F5 else 0x303030)
        set("inverse_on_surface", if (dark) 0x212121 else 0xF5F5F5)
        set("outline", if (dark) 0x9E9E9E else 0x757575)
        set("outline_variant", if (dark) 0x424242 else 0xD0D0D0)
        val textBackground = (if (dark) 0x424242 else highest) or 0xFF000000.toInt()
        for ((family, original) in listOf("primary" to spec.seed, "secondary" to spec.accent, "tertiary" to spec.mid)) {
            val text = textColor(original, textBackground, dark)
            set(family, text)
            set("on_$family", onColor(text))
            set("${family}_container", original)
            set("on_${family}_container", onColor(original))
        }
        set("inverse_primary", textColor(spec.seed, palette.getValue("inverse_surface"), !dark))
        for ((family, light, dim) in listOf(
            Triple("primary", spec.light, spec.mid),
            Triple("secondary", spec.light, spec.accent),
            Triple("tertiary", spec.light, spec.mid),
        )) {
            set("${family}_fixed", light)
            set("${family}_fixed_dim", dim)
            // 固定色控件可在两种底色间切换，用在两者上更稳妥的前景。
            val foreground = if (minOf(contrast(white, light), contrast(white, dim)) >=
                minOf(contrast(black, light), contrast(black, dim))) white else black
            set("on_${family}_fixed", foreground)
            set("on_${family}_fixed_variant", foreground)
        }
        set("legacy_primary_dark", spec.primaryDark)
        // 1.4.2 的黑主题连接按钮使用灰色强调色，顶部仍是 #2B2B2B。
        val fab = if (spec.name == "black") spec.accent else spec.seed
        set("fab_background", fab)
        set("on_fab", onColor(fab))
        return palette.toList()
    }

    fun colorName(spec: Spec, role: String) = "m3_${spec.name}_$role"

    fun colorsXml(spec: Spec, dark: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- 由 $SOURCE 生成，勿手改：")
        append("1.4.2 主色 ${hex(spec.seed)}、强调色 ${hex(spec.accent)}，${if (dark) "暗色" else "亮色"}")
        append(" -->\n<resources>\n")
        for ((role, argb) in colors(spec, dark)) {
            append("    <color name=\"${colorName(spec, role)}\">${hex(argb)}</color>\n")
        }
        append("</resources>\n")
    }

    /** 每个样式写的属性 → 颜色角色，顺序即输出顺序 */
    fun styleItems(): List<Pair<String, String>> =
        roles.map { it.attr to it.name } + aliasAttrs

    fun themesXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- 由 $SOURCE 生成，勿手改：每种主题的活动与对话框样式，只写颜色角色；")
        append("末尾是各主题的「纯黑夜间」覆盖样式（只写 surface 系） -->\n")
        append("<resources>\n")
        for (spec in specs) for (parent in listOf("Theme.SagerNet", "Theme.SagerNet.Dialog")) {
            append("\n    <style name=\"$parent.${spec.style}\" parent=\"$parent\">\n")
            for ((attr, role) in styleItems()) {
                append("        <item name=\"$attr\">@color/${colorName(spec, role)}</item>\n")
            }
            append("        <item name=\"fabColorBackground\">@color/${colorName(spec, "fab_background")}</item>\n")
            append("        <item name=\"fabColorForeground\">@color/${colorName(spec, "on_fab")}</item>\n")
            append("    </style>\n")
        }
        for (spec in specs) {
            append("\n    <style name=\"ThemeOverlay.SagerNet.PureBlack.${spec.style}\" parent=\"\">\n")
            for (role in pureBlackRoles) {
                val attr = roles.first { it.name == role }.attr
                append("        <item name=\"$attr\">@color/${pureColorName(spec, role)}</item>\n")
            }
            append("    </style>\n")
        }
        append("</resources>\n")
    }

    /** 生成的文件：相对 res 目录的路径 → 全文 */
    fun files(): Map<String, String> = buildMap {
        for (spec in specs) {
            put("values/colors_m3_${spec.name}.xml", colorsXml(spec, false))
            put("values-night/colors_m3_${spec.name}.xml", colorsXml(spec, true))
        }
        put("values/colors_m3_pure.xml", pureColorsXml(false))
        put("values-night/colors_m3_pure.xml", pureColorsXml(true))
        put("values/themes_m3.xml", themesXml())
    }
}
