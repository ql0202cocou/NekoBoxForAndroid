package io.nekohasekai.sagernet.uitest

import android.graphics.Color
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.widget.glass.GlassTarget
import io.nekohasekai.sagernet.widget.glass.GlassTier
import io.nekohasekai.sagernet.widget.glass.GlassTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * U5f F2 / F3：三个悬浮层的玻璃表面随档位变化。SOLID 不改宿主（保持原来的不透明底色），FROST 把底色换成配方里的
 * 不透明度，BLUR 换成 BLUR 配方的（Dock 72%、状态卡片边缘 64%；连接按钮永远是 FROST 的 98%）；
 * 档位切换（设置项改了）只重算、不重建 Activity；下层抓取不可用时（来源未登记）整个表面回到完整的 FROST 配方。
 * 不截图，所有平台都跑。
 *
 * Robolectric 的窗口报告硬件加速（`View.isHardwareAccelerated` 为真），所以「自动」在这里解析成 BLUR、
 * 宿主真的建了 GlassBackdrop；但画布是软件画布，`drawBackdrop` 不画任何东西（BLUR 不在 Robolectric 里渲染，
 * 截图基线靠 UiTestRule 压成 SOLID）。这里验证的是档位、底色与降级的决定，不是像素。
 */
class GlassSurfaceTest : UiTestBase() {

    private fun alphaOf(color: Int) = Color.alpha(color)

    private fun MainActivity.fills() = Triple(
        alphaOf(binding.dock.cardBackgroundColor.defaultColor),
        alphaOf(binding.stats.cardBackgroundColor.defaultColor),
        alphaOf(binding.fab.backgroundTintList!!.defaultColor),
    )

    private fun pct(percent: Int) = Math.round(percent * 255 / 100f)

    @Test
    fun solidLeavesTheHostsOpaque() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>().get()
        assertEquals(Triple(255, 255, 255), main.fills())
    }

    @Test
    fun frostMakesTheFillTranslucentAtTheRecipeOpacity() {
        UiFixtures.install()
        GlassTiers.testOverride = GlassTier.FROST
        val main = ui.launch<MainActivity>().get()
        // Dock 98%、状态卡片 99%、连接按钮 98%
        assertEquals(Triple(pct(98), pct(99), pct(98)), main.fills())
    }

    @Test
    fun blurUsesTheBlurRecipeButNeverForTheConnectButton() {
        UiFixtures.install()
        // 注入 BLUR 也一样：连接按钮不做下层抓取，仍是 FROST 的 98%
        GlassTiers.testOverride = GlassTier.BLUR
        val main = ui.launch<MainActivity>().get()
        assertEquals(Triple(pct(72), pct(64), pct(98)), main.fills())
        assertEquals(GlassTier.FROST, main.glass.tier(GlassTarget.CONNECT_BUTTON))
    }

    @Test
    fun missingBackdropSourceFallsBackToTheFullFrostRecipe() {
        UiFixtures.install()
        GlassTiers.testOverride = GlassTier.BLUR
        val main = ui.launch<MainActivity>().get()
        assertEquals(Triple(pct(72), pct(64), pct(98)), main.fills())

        // 来源没了（下层抓取不可用）：整体回到 FROST，不是「BLUR 底色去掉模糊」
        main.glass.backdropSource.release()
        main.binding.dock.glassSurface.refresh()
        main.binding.stats.glassSurface.refresh()
        assertEquals(Triple(pct(98), pct(99), pct(98)), main.fills())
    }

    @Test
    fun settingChangeRepaintsWithoutRecreate() {
        UiFixtures.install()
        GlassTiers.testOverride = null
        val controller = ui.launch<MainActivity>()
        val main = controller.get()
        // 自动：Robolectric（API 35、硬件加速的窗口）解析成 BLUR
        assertEquals(Triple(pct(72), pct(64), pct(98)), main.fills())

        ui.offMain { DataStore.glassMaterial = 2 }
        UiSettle.settle()
        assertEquals(Triple(255, 255, 255), main.fills())

        ui.offMain { DataStore.glassMaterial = 1 }
        UiSettle.settle()
        assertEquals(Triple(pct(98), pct(99), pct(98)), main.fills())

        // 改回自动：BLUR 的底色回来
        ui.offMain { DataStore.glassMaterial = 0 }
        UiSettle.settle()
        assertEquals(Triple(pct(72), pct(64), pct(98)), main.fills())
        assertSame(main, controller.get())
    }
}
