@file:Suppress("RestrictedApi")

package io.nekohasekai.sagernet.utils

import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeMonochrome

/**
 * 六种主题的 Material 3 配色：从种子色算出亮 / 暗两套颜色角色，渲染成
 * res/values(-night)/colors_m3_<名字>.xml 与 res/values/themes_m3.xml 的全文。
 *
 * 算法是 material 库自带的 color.utilities（与 Android 12+ 系统取色同一套算法的 Java 移植），
 * contrast 一律 0。M3PaletteTest 用它钉住资源文件，也用它重新生成（见该类的说明）。
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

    /**
     * M2 兼容属性：Theme.Material3.Light 仍定义它们（指向 ?attr/colorPrimary / colorSecondary），
     * 这里直接指到同一种颜色。colorPrimaryDark 不写，M3 默认就是 ?attr/colorPrimary
     */
    val aliasAttrs: List<Pair<String, String>> = listOf(
        "colorPrimaryVariant" to "primary",
        "colorSecondaryVariant" to "secondary",
    )

    enum class Scheme(val label: String, val create: (Hct, Boolean) -> DynamicScheme) {
        CONTENT("SchemeContent", { h, d -> SchemeContent(h, d, 0.0) }),
        MONOCHROME("SchemeMonochrome", { h, d -> SchemeMonochrome(h, d, 0.0) }),
    }

    /**
     * 一种主题：资源名、样式名后缀（Theme.SagerNet.<style> / Theme.SagerNet.Dialog.<style>，
     * 与 Theme.kt 一致）、种子、方案、暗色套的覆盖值
     */
    class Spec(
        val name: String,
        val style: String,
        val seed: Int,
        val scheme: Scheme,
        val darkOverrides: Map<String, Int> = emptyMap(),
    )

    // 「黑」的暗色套：surface 系压到纯黑 / 近黑（AMOLED），各层容器按层级留少量亮度差，其它角色不变
    val amoled: Map<String, Int> = mapOf(
        "background" to 0xFF000000.toInt(),
        "surface" to 0xFF000000.toInt(),
        "surface_dim" to 0xFF000000.toInt(),
        "surface_container_lowest" to 0xFF000000.toInt(),
        "surface_container_low" to 0xFF0E0E0E.toInt(),
        "surface_container" to 0xFF141414.toInt(),
        "surface_container_high" to 0xFF1E1E1E.toInt(),
        "surface_container_highest" to 0xFF282828.toInt(),
        "surface_bright" to 0xFF2E2E2E.toInt(),
    )

    // Monochrome 的结果与种子无关，白 / 黑的种子只是占位（material_grey_500）
    private const val MONO_SEED = 0xFF9E9E9E.toInt()

    /** 彩色主题的种子与选色网格（ColorPickerPreference）的颜色资源名，测试核对两边一致 */
    val seedColorNames: Map<String, String> = mapOf(
        "pink" to "color_pink_ssr",
        "orange" to "material_orange_500",
        "blue" to "material_blue_500",
        "green" to "material_green_500",
    )

    val specs: List<Spec> = listOf(
        Spec("pink", "Pink_SSR", 0xFFFB7299.toInt(), Scheme.CONTENT),
        Spec("orange", "Orange", 0xFFFF9800.toInt(), Scheme.CONTENT),
        Spec("blue", "Blue", 0xFF2196F3.toInt(), Scheme.CONTENT),
        Spec("green", "Green", 0xFF4CAF50.toInt(), Scheme.CONTENT),
        Spec("white", "Grey", MONO_SEED, Scheme.MONOCHROME),
        Spec("black", "Black", MONO_SEED, Scheme.MONOCHROME, amoled),
    )

    fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    /** 某主题某模式下的全部角色：资源名后缀 → ARGB，顺序同 [roles] */
    fun colors(spec: Spec, dark: Boolean): List<Pair<String, Int>> {
        val scheme = spec.scheme.create(Hct.fromInt(spec.seed), dark)
        return roles.map { role ->
            val generated = role.color.getArgb(scheme)
            role.name to if (dark) spec.darkOverrides[role.name] ?: generated else generated
        }
    }

    fun colorName(spec: Spec, role: String) = "m3_${spec.name}_$role"

    fun colorsXml(spec: Spec, dark: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!-- 由 $SOURCE 生成，勿手改：")
        append("种子 ")
        append(if (spec.scheme == Scheme.MONOCHROME) "任意（结果与种子无关）" else hex(spec.seed))
        append("，${spec.scheme.label}，${if (dark) "暗色" else "亮色"}")
        if (dark && spec.darkOverrides.isNotEmpty()) append("，surface 系压黑")
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
        append("<!-- 由 $SOURCE 生成，勿手改：每种主题的活动与对话框样式，只写颜色角色 -->\n")
        append("<resources>\n")
        for (spec in specs) for (parent in listOf("Theme.SagerNet", "Theme.SagerNet.Dialog")) {
            append("\n    <style name=\"$parent.${spec.style}\" parent=\"$parent\">\n")
            for ((attr, role) in styleItems()) {
                append("        <item name=\"$attr\">@color/${colorName(spec, role)}</item>\n")
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
        put("values/themes_m3.xml", themesXml())
    }
}
