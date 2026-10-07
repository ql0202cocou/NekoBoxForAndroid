package io.nekohasekai.sagernet.utils

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
    // 除 colorPrimaryDark（M3 默认 ?attr/colorPrimary，不写）；升级 material 时对照库重新核对
    private val materialColorAttrs = setOf(
        "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer",
        "colorPrimaryInverse", "colorPrimaryVariant",
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
    fun blackLightEqualsWhiteLight() {
        val white = M3Palette.specs.first { it.name == "white" }
        val black = M3Palette.specs.first { it.name == "black" }
        assertEquals(M3Palette.colors(white, false), M3Palette.colors(black, false))
        // 暗色套只有 surface 系不同
        val diff = M3Palette.colors(white, true).zip(M3Palette.colors(black, true))
            .filter { (w, b) -> w != b }.map { it.first.first }.toSet()
        assertEquals(M3Palette.amoled.keys, diff)
    }

    @Test
    fun contentKeepsSeedAsPrimaryContainer() {
        for (spec in M3Palette.specs.filter { it.scheme == M3Palette.Scheme.CONTENT }) {
            for (dark in listOf(false, true)) {
                val container = M3Palette.colors(spec, dark).first { it.first == "primary_container" }.second
                assertEquals("${spec.name} dark=$dark", M3Palette.hex(spec.seed), M3Palette.hex(container))
            }
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
