package io.nekohasekai.sagernet.utils

import com.google.gson.JsonParser
import com.google.android.material.color.utilities.ColorUtils
import com.google.android.material.color.utilities.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 钉住生成的配色资源：从种子重新算出全部角色，与 res 里的文件逐字比对，防止手改。
 *
 * 改种子、方案或「黑」的阶梯值时先改 M3Palette，再重新生成：
 *   M3_RES_OUT=$PWD/app/src/main/res ./gradlew app:testOssDebugUnitTest --tests '*M3PaletteTest*' --rerun
 * （在仓库根目录执行。M3_RES_OUT 可以是任意 res 目录，要写绝对路径：测试的工作目录是 app/，
 * 相对路径会落到 app/app/…；没设时 regenerate 跳过）
 */
class M3PaletteTest {

    private val res = File("src/main/res")

    @Test
    fun resourcesMatchGenerator() {
        for ((path, expected) in M3Palette.files()) {
            val file = File(res, path)
            assertTrue("缺少 $path，按 M3PaletteTest 的说明重新生成", file.isFile)
            assertEquals("$path 与生成结果不一致，按 M3PaletteTest 的说明重新生成", expected, file.readText())
        }
    }

    @Test
    fun noStrayPaletteFiles() {
        val expected = M3Palette.files().keys.filter { "colors_m3_" in it }.toSet()
        val actual = listOf("values", "values-night").flatMap { dir ->
            File(res, dir).listFiles().orEmpty().map { "$dir/${it.name}" }
        }.filter { "colors_m3_" in it }.toSet()
        assertEquals(expected, actual)
    }

    // material 1.14.0 的 Theme.Material3.Light（Base.V14.Theme.Material3.Light）定义的全部 color* 属性，
    // 加上复刻旧版时恢复的 colorPrimaryDark；升级 material 时对照库重新核对
    private val materialColorAttrs = setOf(
        "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer",
        "colorPrimaryInverse", "colorPrimaryVariant", "colorPrimaryDark",
        "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
        "colorSecondaryVariant",
        "colorTertiary", "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
        "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
        "android:colorBackground", "colorOnBackground",
        "colorSurface", "colorOnSurface", "colorSurfaceVariant", "colorOnSurfaceVariant",
        "colorSurfaceInverse", "colorOnSurfaceInverse",
        "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
        "colorSurfaceContainerHigh", "colorSurfaceContainerHighest", "colorSurfaceDim", "colorSurfaceBright",
        "colorOutline", "colorOutlineVariant",
        "colorPrimaryFixed", "colorPrimaryFixedDim", "colorOnPrimaryFixed", "colorOnPrimaryFixedVariant",
        "colorSecondaryFixed", "colorSecondaryFixedDim", "colorOnSecondaryFixed", "colorOnSecondaryFixedVariant",
        "colorTertiaryFixed", "colorTertiaryFixedDim", "colorOnTertiaryFixed", "colorOnTertiaryFixedVariant",
    )

    @Test
    fun stylesCoverEveryMaterialColorAttr() {
        val attrs = M3Palette.styleItems().map { it.first }
        assertEquals("属性重复", attrs.size, attrs.toSet().size)
        assertEquals(materialColorAttrs, attrs.toSet())
    }

    @Test
    fun seedsMatchColorGrid() {
        val colorsXml = File(res, "values/colors.xml").readText()
        for ((name, colorRes) in M3Palette.seedColorNames) {
            val hex = Regex("""<color name="$colorRes">(#[0-9A-Fa-f]{6})</color>""")
                .find(colorsXml)?.groupValues?.get(1)
            val spec = M3Palette.specs.first { it.name == name }
            assertEquals("$name 的种子应等于 @color/$colorRes", M3Palette.hex(spec.seed), hex?.uppercase())
        }
    }

    @Test
    fun legacyColorsMatch142InBothModes() {
        val fixture = JsonParser.parseString(File("src/test/resources/legacy-theme-colors.json").readText())
            .asJsonObject.getAsJsonArray("themes").associate { it.asJsonObject.get("id").asInt to it.asJsonObject }
        assertEquals((1..21).toSet(), fixture.keys)
        assertEquals(21, M3Palette.specs.size)
        for ((id, spec) in Theme.KEPT.zip(M3Palette.specs)) for (dark in listOf(false, true)) {
            val expected = fixture.getValue(id)
            val colors = M3Palette.colors(spec, dark).toMap()
            for ((role, field) in mapOf(
                "primary_container" to "colorPrimary", "legacy_primary_dark" to "colorPrimaryDark",
                "secondary_container" to "colorAccent", "primary_fixed" to "colorMaterial100",
                "primary_fixed_dim" to "colorMaterial300",
            )) assertEquals("编号 $id / $role / 暗色 $dark", expected.get(field).asString, M3Palette.hex(colors.getValue(role)))
            assertEquals(if (dark) "#121212" else "#FFFFFF", M3Palette.hex(colors.getValue("background")))
            assertEquals(if (dark) "#121212" else "#FFFFFF", M3Palette.hex(colors.getValue("surface")))
            assertEquals(if (id == 21) expected.get("colorAccent").asString else expected.get("colorPrimary").asString,
                M3Palette.hex(colors.getValue("fab_background")))
        }
    }

    @Suppress("RestrictedApi")
    @Test
    fun restoredThemesKeepReadableForegrounds() {
        for (spec in M3Palette.specs) for (dark in listOf(false, true)) {
            val colors = M3Palette.colors(spec, dark).toMap()
            val pairs = listOf(
                "primary_container" to "on_primary_container", "secondary_container" to "on_secondary_container",
                "tertiary_container" to "on_tertiary_container", "fab_background" to "on_fab",
                "surface" to "on_surface", "surface_container_high" to "on_surface_variant",
                "surface_container_highest" to "primary", "surface_container_highest" to "secondary",
            )
            for ((background, foreground) in pairs) {
                val ratio = Contrast.ratioOfTones(ColorUtils.lstarFromArgb(colors.getValue(background)),
                    ColorUtils.lstarFromArgb(colors.getValue(foreground)))
                assertTrue("${spec.name} 暗色 $dark / $foreground 在 $background 上的对比度 $ratio", ratio >= 4.5)
            }
        }
    }

    // 「纯黑夜间」：surface 与 background 压到纯黑，容器阶梯保持递增，所有主题都生成
    @Suppress("RestrictedApi")
    @Test
    fun pureBlackSurfacesAreBlackAndKeepLadder() {
        for (spec in M3Palette.specs) {
            val pure = M3Palette.pureBlackColors(spec).toMap()
            assertEquals(M3Palette.pureBlackRoles.toSet(), pure.keys)
            assertEquals("${spec.name} surface", "#000000", M3Palette.hex(pure.getValue("surface")))
            assertEquals("${spec.name} background", "#000000", M3Palette.hex(pure.getValue("background")))
            val ladder = listOf(
                "surface_container_lowest", "surface_container_low", "surface_container",
                "surface_container_high", "surface_container_highest", "surface_bright",
            ).map { com.google.android.material.color.utilities.Hct.fromInt(pure.getValue(it)).tone }
            assertEquals("${spec.name} 容器阶梯要递增", ladder.sorted(), ladder)
            assertTrue("${spec.name} 最亮一层也要接近黑", ladder.last() < 25.0)
        }
    }

    @Test
    fun regenerate() {
        val out = System.getenv("M3_RES_OUT")
        assumeTrue("M3_RES_OUT 未设置，不重新生成", !out.isNullOrEmpty())
        for ((path, text) in M3Palette.files()) {
            File(out!!, path).apply { parentFile!!.mkdirs() }.writeText(text)
        }
    }
}
