package io.nekohasekai.sagernet.uitest

import android.graphics.Bitmap
import android.view.View
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 截图与基线比较。Roborazzi 只负责把 View 渲染成 PNG（总是写到 build 下的 actual 目录，
 * 所以所有平台都真的走了渲染路径，渲染崩溃照样失败）；比较、录制规则由这里定：
 * - 基线在 `app/src/test/screenshots/<Shot.path>`，阈值 0 个像素（同平台两次渲染逐字节一致，没有理由放宽）。
 * - 缺基线 = 失败，绝不自动生成；只有重录开关匹配的截图才写基线。
 * - 重录只能显式点名：`-Pscreenshots.record=<正则>`，对基线相对路径做 find，匹配的才重写，其余照常比较；
 *   没有「全部重录」。
 * - 像素只在录制平台（macOS arm64，原生绘图的抗锯齿 / 字体光栅跨系统不保证一致）上比较；
 *   其它平台渲染后跳过比较（但重录开关在任何平台都不生效，免得写出另一套基线）。
 * - 不一致时实际图与差异图写到 `build/outputs/screenshots/{actual,diff}/`。
 */
object Screenshots {

    private val baselineRoot = File("src/test/screenshots")
    private val actualRoot = File("build/outputs/screenshots/actual")
    private val diffRoot = File("build/outputs/screenshots/diff")

    private val recordPattern: Regex? =
        System.getProperty("screenshots.record")?.takeIf { it.isNotBlank() }?.let { Regex(it) }

    /** 录制平台：macOS arm64。README 里有同样的说明 */
    val comparesOnThisPlatform: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac") && System.getProperty("os.arch") == "aarch64"

    fun baselineFile(shot: Shot) = File(baselineRoot, shot.path)

    private val renderOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)

    /** 单个窗口：Activity 的根视图 */
    fun check(shot: Shot, view: View) = render(shot) { view.captureRoboImage(it, renderOptions) }

    /** 全长截图：见 [drawFullLength] */
    fun checkFullLength(shot: Shot, view: View) = check(shot, view.drawFullLength())

    fun check(shot: Shot, bitmap: Bitmap) = render(shot) { bitmap.captureRoboImage(it, renderOptions) }

    /** 屏幕上的所有窗口（对话框在自己的窗口里，要和下面的 Activity 一起截） */
    fun checkScreen(shot: Shot) = render(shot) { captureScreenRoboImage(it, renderOptions) }

    private fun render(shot: Shot, capture: (File) -> Unit) {
        val actual = File(actualRoot, shot.path)
        actual.parentFile.mkdirs()
        actual.delete()
        capture(actual)
        check(actual.isFile) { "渲染没有产出 ${actual.path}" }

        val baseline = baselineFile(shot)
        val record = recordPattern?.containsMatchIn(shot.path) == true
        when {
            record && !comparesOnThisPlatform ->
                throw AssertionError("基线只能在录制平台（macOS arm64）上重录，当前是 ${platform()}")

            record -> {
                baseline.parentFile.mkdirs()
                actual.copyTo(baseline, overwrite = true)
                println("SCREENSHOT 已重录 ${shot.path}")
            }

            !comparesOnThisPlatform -> println("SCREENSHOT 跳过比较（${platform()} 不是录制平台）：${shot.path}")

            !baseline.isFile -> throw AssertionError(
                "缺基线 ${baseline.path}。基线不会自动生成；确认画面无误后用 -Pscreenshots.record='${Regex.escape(shot.path)}' 录制"
            )

            else -> compare(shot, baseline, actual)
        }
    }

    private fun platform() = System.getProperty("os.name") + " " + System.getProperty("os.arch")

    private fun compare(shot: Shot, baseline: File, actual: File) {
        val expected = ImageIO.read(baseline)
        val got = ImageIO.read(actual)
        val diffFile = File(diffRoot, shot.path)
        diffFile.delete()
        if (expected.width != got.width || expected.height != got.height) {
            throw AssertionError(
                "${shot.path} 尺寸变了：基线 ${expected.width}x${expected.height}，实际 ${got.width}x${got.height}。" +
                    "实际图 ${actual.path}"
            )
        }
        val diff = BufferedImage(got.width, got.height, BufferedImage.TYPE_INT_ARGB)
        var different = 0
        for (y in 0 until got.height) for (x in 0 until got.width) {
            val a = expected.getRGB(x, y)
            val b = got.getRGB(x, y)
            if (a == b) {
                // 未变的像素淡化，变了的标红
                diff.setRGB(x, y, (b and 0x00FFFFFF) or 0x40000000)
            } else {
                different++
                diff.setRGB(x, y, 0xFFFF0000.toInt())
            }
        }
        if (different > 0) {
            diffFile.parentFile.mkdirs()
            ImageIO.write(diff, "png", diffFile)
            throw AssertionError(
                "${shot.path} 有 $different 个像素与基线不同（共 ${got.width * got.height}）。" +
                    "实际图 ${actual.path}，差异图 ${diffFile.path}。确认变化是有意的之后才按路径重录"
            )
        }
    }
}
