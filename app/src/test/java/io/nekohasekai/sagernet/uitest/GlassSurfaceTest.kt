package io.nekohasekai.sagernet.uitest

import android.graphics.Color
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.widget.glass.GlassTier
import io.nekohasekai.sagernet.widget.glass.GlassTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * U5f F2：三个悬浮层的玻璃表面随档位变化。SOLID 不改宿主（保持原来的不透明底色），FROST 把底色换成配方里的
 * 不透明度；档位切换（设置项改了）只重算、不重建 Activity。不截图，所有平台都跑。
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
        // Dock 92%、状态卡片 93%、连接按钮 95%
        assertEquals(Triple(pct(92), pct(93), pct(95)), main.fills())
    }

    @Test
    fun settingChangeRepaintsWithoutRecreate() {
        UiFixtures.install()
        GlassTiers.testOverride = null
        val controller = ui.launch<MainActivity>()
        val main = controller.get()
        // 自动：Robolectric（API 35）解析成 BLUR，F3 之前按 FROST 画
        assertEquals(Triple(pct(92), pct(93), pct(95)), main.fills())

        ui.offMain { DataStore.glassMaterial = 2 }
        UiSettle.settle()
        assertEquals(Triple(255, 255, 255), main.fills())

        ui.offMain { DataStore.glassMaterial = 1 }
        UiSettle.settle()
        assertEquals(Triple(pct(92), pct(93), pct(95)), main.fills())
        assertSame(main, controller.get())
    }
}
