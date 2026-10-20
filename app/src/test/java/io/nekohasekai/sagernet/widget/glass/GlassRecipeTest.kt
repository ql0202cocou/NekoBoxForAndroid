package io.nekohasekai.sagernet.widget.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 把 ui-plan 9.1「中等 / 克制 / 中等」与静态磨砂的数字钉成断言（纯 JVM）；改配方必须同时改这里和 ui-plan */
class GlassRecipeTest {

    private val eps = 1e-6f

    private fun recipe(target: GlassTarget, tier: GlassTier) = GlassRecipes.of(target, tier)

    @Test
    fun everyCombinationReportsItsOwnTargetAndShape() {
        for (target in GlassTarget.entries) for (tier in GlassTier.entries) {
            val r = recipe(target, tier)
            assertEquals(target, r.target)
            assertEquals(
                when (target) {
                    GlassTarget.DOCK -> GlassShape.CAPSULE
                    GlassTarget.STATUS_CARD -> GlassShape.EXTRA_LARGE
                    GlassTarget.CONNECT_BUTTON -> GlassShape.CIRCLE
                },
                r.shape,
            )
        }
    }

    @Test
    fun baseRoles() {
        for (tier in GlassTier.entries) {
            assertEquals(ColorRole.SURFACE_CONTAINER, recipe(GlassTarget.DOCK, tier).base)
            assertEquals(ColorRole.SURFACE_CONTAINER, recipe(GlassTarget.STATUS_CARD, tier).base)
            assertEquals(ColorRole.PRIMARY_CONTAINER, recipe(GlassTarget.CONNECT_BUTTON, tier).base)
        }
    }

    @Test
    fun solidIsOpaqueWithSystemElevationAndNoGlassLayers() {
        val elevations = mapOf(GlassTarget.DOCK to 3f, GlassTarget.STATUS_CARD to 3f, GlassTarget.CONNECT_BUTTON to 6f)
        for (target in GlassTarget.entries) {
            val r = recipe(target, GlassTier.SOLID)
            assertEquals(GlassTier.SOLID, r.tier)
            assertEquals(1f, r.baseAlpha, eps)
            assertEquals(elevations[target]!!, r.elevationDp, eps)
            assertNull(r.centerBackdrop)
            assertNull(r.ambient)
            assertNull(r.highlight)
            assertNull(r.shadows)
            assertEquals(0f, r.blurRadiusDp, eps)
            assertEquals(1f, r.saturation, eps)
        }
    }

    @Test
    fun frostOpacities() {
        assertEquals(0.92f, recipe(GlassTarget.DOCK, GlassTier.FROST).baseAlpha, eps)
        assertEquals(0.93f, recipe(GlassTarget.STATUS_CARD, GlassTier.FROST).baseAlpha, eps)
        assertEquals(0.95f, recipe(GlassTarget.CONNECT_BUTTON, GlassTier.FROST).baseAlpha, eps)
        val card = recipe(GlassTarget.STATUS_CARD, GlassTier.FROST).centerBackdrop!!
        assertEquals(Layer(ColorRole.SURFACE_CONTAINER, 0.50f), card)
        for (target in GlassTarget.entries) {
            val r = recipe(target, GlassTier.FROST)
            // 静态磨砂：不模糊、不提饱和度、不用系统 elevation（阴影自绘）
            assertEquals(0f, r.blurRadiusDp, eps)
            assertEquals(1f, r.saturation, eps)
            assertEquals(0f, r.elevationDp, eps)
            assertNotNull(r.highlight)
            assertNotNull(r.shadows)
        }
    }

    @Test
    fun blurOpacitiesRadiiAndSaturation() {
        val dock = recipe(GlassTarget.DOCK, GlassTier.BLUR)
        assertEquals(0.72f, dock.baseAlpha, eps)
        assertEquals(20f, dock.blurRadiusDp, eps)
        assertEquals(1.15f, dock.saturation, eps)
        assertNull(dock.centerBackdrop)

        val card = recipe(GlassTarget.STATUS_CARD, GlassTier.BLUR)
        assertEquals(0.64f, card.baseAlpha, eps)
        assertEquals(24f, card.blurRadiusDp, eps)
        assertEquals(1.15f, card.saturation, eps)
        assertEquals(Layer(ColorRole.SURFACE_CONTAINER, 0.60f), card.centerBackdrop)
        // 边缘 64% 叠 60% 中心衬底，中心约 86%
        val center = 1f - (1f - card.baseAlpha) * (1f - card.centerBackdrop!!.alpha)
        assertEquals(0.856f, center, 1e-3f)
    }

    @Test
    fun connectButtonBlurIsTheFrostRecipe() {
        // 按钮不做下层捕获：BLUR 档沿用 FROST 配方（同一份数据）
        assertSame(recipe(GlassTarget.CONNECT_BUTTON, GlassTier.FROST), recipe(GlassTarget.CONNECT_BUTTON, GlassTier.BLUR))
        assertEquals(0f, recipe(GlassTarget.CONNECT_BUTTON, GlassTier.BLUR).blurRadiusDp, eps)
    }

    @Test
    fun ambientIsRestrained() {
        for (tier in listOf(GlassTier.FROST, GlassTier.BLUR)) {
            for (target in listOf(GlassTarget.DOCK, GlassTarget.STATUS_CARD)) {
                val a = recipe(target, tier).ambient!!
                assertEquals(Layer(ColorRole.PRIMARY, 0.03f), a.from)
                assertEquals(Layer(ColorRole.SECONDARY, 0.01f), a.to)
                assertEquals(135f, a.angleDeg, eps)
            }
            assertNull(recipe(GlassTarget.CONNECT_BUTTON, tier).ambient)
        }
    }

    @Test
    fun highlightIsMediumAndSharedByAllSurfaces() {
        val reference = recipe(GlassTarget.DOCK, GlassTier.FROST).highlight!!
        for (target in GlassTarget.entries) for (tier in listOf(GlassTier.FROST, GlassTier.BLUR)) {
            assertEquals(reference, recipe(target, tier).highlight)
        }
        with(reference) {
            assertEquals(1f, edgeWidthDp, eps)
            assertEquals(ModeValue(ColorRole.SURFACE_CONTAINER_LOWEST, ColorRole.ON_SURFACE), edgeColor)
            assertEquals(ModeValue(0.85f, 0.20f), edgeTop)
            assertEquals(ModeValue(0.25f, 0.05f), edgeMiddle)
            assertEquals(ModeValue(0.30f, 0.07f), edgeBottom)
            assertEquals(1f, innerHighlightHeightDp, eps)
            assertEquals(ModeValue(0.50f, 0.08f), innerHighlight)
            assertEquals(ModeValue(0.18f, 0.03f), sheen)
            assertEquals(0.55f, sheenReach, eps)
            assertEquals(0.5f, hairlineWidthDp, eps)
            assertEquals(ColorRole.OUTLINE_VARIANT, hairlineColor)
            assertEquals(ModeValue(0.55f, 0.60f), hairline)
            assertEquals(ModeValue(0.28f, 0.11f), pressGlow)
            assertEquals(MotionToken.SHORT_2, pressFadeIn)
            assertEquals(MotionToken.MEDIUM_2, pressFadeOut)
        }
    }

    @Test
    fun drawnShadows() {
        val panel = ModeValue(
            listOf(ShadowLayer(1f, 2f, 0.10f), ShadowLayer(6f, 18f, 0.10f)),
            listOf(ShadowLayer(1f, 2f, 0.35f), ShadowLayer(8f, 22f, 0.40f)),
        )
        val button = ModeValue(
            listOf(ShadowLayer(1f, 3f, 0.14f), ShadowLayer(6f, 16f, 0.14f)),
            listOf(ShadowLayer(1f, 3f, 0.40f), ShadowLayer(8f, 20f, 0.45f)),
        )
        for (tier in listOf(GlassTier.FROST, GlassTier.BLUR)) {
            assertEquals(panel, recipe(GlassTarget.DOCK, tier).shadows)
            assertEquals(panel, recipe(GlassTarget.STATUS_CARD, tier).shadows)
            assertEquals(button, recipe(GlassTarget.CONNECT_BUTTON, tier).shadows)
        }
    }

    @Test
    fun modeValuePicksByMode() {
        val v = ModeValue("light", "dark")
        assertEquals("light", v.pick(dark = false))
        assertEquals("dark", v.pick(dark = true))
    }

    @Test
    fun alphasAreProperFractions() {
        for (target in GlassTarget.entries) for (tier in GlassTier.entries) {
            val r = recipe(target, tier)
            val alphas = buildList {
                add(r.baseAlpha)
                r.centerBackdrop?.let { add(it.alpha) }
                r.ambient?.let { add(it.from.alpha); add(it.to.alpha) }
                r.highlight?.let { h ->
                    listOf(h.edgeTop, h.edgeMiddle, h.edgeBottom, h.innerHighlight, h.sheen, h.hairline, h.pressGlow)
                        .forEach { add(it.light); add(it.dark) }
                }
                r.shadows?.let { s -> (s.light + s.dark).forEach { add(it.alpha) } }
            }
            assertTrue("$target/$tier 有越界的不透明度：$alphas", alphas.all { it in 0f..1f })
        }
    }

    @Test
    fun captureScaleIsHalfResolution() {
        assertEquals(0.5f, GlassRecipes.BLUR_CAPTURE_SCALE, eps)
        assertEquals(28f, GlassRecipes.CARD_CORNER_DP, eps)
    }
}
