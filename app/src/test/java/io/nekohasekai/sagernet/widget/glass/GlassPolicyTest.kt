package io.nekohasekai.sagernet.widget.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 档位策略的每个分支（纯 JVM）：u5-plan 3.1 的表 + 连接按钮永不 BLUR */
class GlassPolicyTest {

    private fun resolve(
        choice: GlassChoice,
        target: GlassTarget = GlassTarget.DOCK,
        sdk: Int = 36,
        hw: Boolean = true,
        lowRam: Boolean = false,
        powerSave: Boolean = false,
    ) = GlassPolicy.resolve(choice, target, sdk, hw, lowRam, powerSave)

    @Test
    fun solidChoiceIsSolidEverywhere() {
        for (target in GlassTarget.entries) {
            assertEquals(GlassTier.SOLID, resolve(GlassChoice.SOLID, target))
            assertEquals(GlassTier.SOLID, resolve(GlassChoice.SOLID, target, sdk = 24, hw = false, lowRam = true, powerSave = true))
        }
    }

    @Test
    fun frostedChoiceIsFrostEverywhereEvenOnCapableDevice() {
        for (target in GlassTarget.entries) {
            assertEquals(GlassTier.FROST, resolve(GlassChoice.FROSTED, target))
            assertEquals(GlassTier.FROST, resolve(GlassChoice.FROSTED, target, sdk = 24, hw = false, lowRam = true, powerSave = true))
        }
    }

    @Test
    fun autoIsBlurOnCapableDeviceForDockAndCard() {
        assertEquals(GlassTier.BLUR, resolve(GlassChoice.AUTO, GlassTarget.DOCK))
        assertEquals(GlassTier.BLUR, resolve(GlassChoice.AUTO, GlassTarget.STATUS_CARD))
    }

    @Test
    fun blurNeedsApi31() {
        assertEquals(31, GlassPolicy.BLUR_MIN_SDK)
        assertEquals(GlassTier.BLUR, resolve(GlassChoice.AUTO, sdk = 31))
        for (sdk in listOf(24, 28, 30)) {
            assertEquals("API $sdk", GlassTier.FROST, resolve(GlassChoice.AUTO, sdk = sdk))
        }
    }

    @Test
    fun autoFallsBackToFrostWhenAnyConditionFails() {
        for (target in listOf(GlassTarget.DOCK, GlassTarget.STATUS_CARD)) {
            assertEquals(GlassTier.FROST, resolve(GlassChoice.AUTO, target, hw = false))
            assertEquals(GlassTier.FROST, resolve(GlassChoice.AUTO, target, lowRam = true))
            assertEquals(GlassTier.FROST, resolve(GlassChoice.AUTO, target, powerSave = true))
        }
    }

    @Test
    fun autoNeverFallsBackToSolid() {
        // 全部条件取遍：自动档只在 FROST 与 BLUR 之间选
        for (sdk in listOf(24, 30, 31, 36)) for (hw in listOf(true, false))
            for (low in listOf(true, false)) for (save in listOf(true, false))
                for (target in GlassTarget.entries) {
                    assertNotEquals(GlassTier.SOLID, resolve(GlassChoice.AUTO, target, sdk, hw, low, save))
                }
    }

    @Test
    fun connectButtonIsNeverBlur() {
        for (choice in GlassChoice.entries) for (sdk in listOf(24, 31, 37)) for (hw in listOf(true, false))
            for (low in listOf(true, false)) for (save in listOf(true, false)) {
                assertNotEquals(GlassTier.BLUR, resolve(choice, GlassTarget.CONNECT_BUTTON, sdk, hw, low, save))
            }
        assertEquals(GlassTier.FROST, resolve(GlassChoice.AUTO, GlassTarget.CONNECT_BUTTON))
    }

    @Test
    fun choiceStoredValues() {
        assertEquals(GlassChoice.AUTO, GlassChoice.fromStored(0))
        assertEquals(GlassChoice.FROSTED, GlassChoice.fromStored(1))
        assertEquals(GlassChoice.SOLID, GlassChoice.fromStored(2))
        // 未知值按「自动」
        assertEquals(GlassChoice.AUTO, GlassChoice.fromStored(3))
        assertEquals(GlassChoice.AUTO, GlassChoice.fromStored(-1))
    }
}
