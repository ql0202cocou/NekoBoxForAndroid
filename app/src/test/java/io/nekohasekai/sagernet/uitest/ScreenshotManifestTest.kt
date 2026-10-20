package io.nekohasekai.sagernet.uitest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 基线目录与矩阵一致：目录里的 PNG 恰好是 [ScreenshotMatrix] 生成的那些文件名。
 * 纯 JVM，所有平台都跑——非录制平台不比像素，但多余或缺失的基线文件照样能被拦下。
 * 缺基线在录制平台上还会被截图用例本身报失败；这里再按文件清单报一次，且不依赖 Robolectric。
 */
class ScreenshotManifestTest {

    private val root = File("src/test/screenshots")

    private val allowedNonImages = setOf("README.md")

    @Test
    fun baselineDirectoryMatchesTheMatrix() {
        assertTrue("基线目录不存在：${root.absolutePath}", root.isDirectory)
        val onDisk = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it !in allowedNonImages }
            .toSet()
        val missing = ScreenshotMatrix.paths - onDisk
        val extra = onDisk - ScreenshotMatrix.paths
        assertTrue(
            buildString {
                if (missing.isNotEmpty()) append("矩阵里有、基线目录里没有（${missing.size}）：\n  ${missing.sorted().joinToString("\n  ")}\n")
                if (extra.isNotEmpty()) append("基线目录里有、矩阵里没有（${extra.size}）：\n  ${extra.sorted().joinToString("\n  ")}\n")
            },
            missing.isEmpty() && extra.isEmpty(),
        )
    }
}
