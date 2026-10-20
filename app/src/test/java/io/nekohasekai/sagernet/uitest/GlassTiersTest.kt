package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.widget.glass.GlassTarget
import io.nekohasekai.sagernet.widget.glass.GlassTier
import io.nekohasekai.sagernet.widget.glass.GlassTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * U5f F1：玻璃档位的 Android 一侧。设置项改了，MainActivity 的 [GlassTiers] 重算并通知监听者，不重建 Activity；
 * 测试注入点（UiTestRule 默认压成 SOLID）压得住用户设置。不截图，所有平台都跑。
 */
class GlassTiersTest : UiTestBase() {

    @Test
    fun testRuleForcesSolidWhateverTheSetting() {
        UiFixtures.install()
        val main = ui.launch<MainActivity>().get()
        ui.offMain { DataStore.glassMaterial = 1 }
        UiSettle.settle()
        for (target in GlassTarget.entries) assertEquals(GlassTier.SOLID, main.glass.tier(target))
    }

    @Test
    fun settingChangeIsPickedUpAndNotifiedWithoutRecreate() {
        UiFixtures.install()
        GlassTiers.testOverride = null
        val controller = ui.launch<MainActivity>()
        val main = controller.get()
        var notified = 0
        main.glass.addListener { notified++ }

        // 默认自动：Robolectric（API 35）上 Dock 与卡片 BLUR，按钮 FROST
        assertEquals(GlassTier.BLUR, main.glass.tier(GlassTarget.DOCK))
        assertEquals(GlassTier.BLUR, main.glass.tier(GlassTarget.STATUS_CARD))
        assertEquals(GlassTier.FROST, main.glass.tier(GlassTarget.CONNECT_BUTTON))

        ui.offMain { DataStore.glassMaterial = 1 }
        UiSettle.settle()
        assertEquals(1, notified)
        for (target in GlassTarget.entries) assertEquals(GlassTier.FROST, main.glass.tier(target))

        ui.offMain { DataStore.glassMaterial = 2 }
        UiSettle.settle()
        assertEquals(2, notified)
        for (target in GlassTarget.entries) assertEquals(GlassTier.SOLID, main.glass.tier(target))

        // 改回自动
        ui.offMain { DataStore.glassMaterial = 0 }
        UiSettle.settle()
        assertEquals(3, notified)
        assertEquals(GlassTier.BLUR, main.glass.tier(GlassTarget.DOCK))

        // 档位没变（写入同一个值）不重复通知；Activity 没有被重建
        ui.offMain { DataStore.glassMaterial = 0 }
        UiSettle.settle()
        assertEquals(3, notified)
        assertSame(main, controller.get())
    }

    @Test
    fun nonHardwareCanvasDropsAutoToFrost() {
        UiFixtures.install()
        GlassTiers.testOverride = null
        val main = ui.launch<MainActivity>().get()
        assertEquals(GlassTier.BLUR, main.glass.tier(GlassTarget.DOCK, hardwareAccelerated = true))
        assertEquals(GlassTier.FROST, main.glass.tier(GlassTarget.DOCK, hardwareAccelerated = false))
        assertEquals(GlassTier.FROST, main.glass.tier(GlassTarget.CONNECT_BUTTON))
    }
}
