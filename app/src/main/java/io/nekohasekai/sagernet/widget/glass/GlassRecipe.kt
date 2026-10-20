package io.nekohasekai.sagernet.widget.glass

/**
 * 材质配方：每个表面（Dock、状态卡片、连接按钮）在每个档位下的不透明度、模糊、环境色、高光与阴影参数。
 * 纯数据，不依赖 Android 框架，数字钉在 `GlassRecipeTest`。
 *
 * 取值来源：ui-plan 9.1 评审结论的固定档位——整体强度「中等」、环境色「克制」、高光「中等」——
 * 和静态磨砂的不透明度（u5-plan 3.2 / 附录 B）。原生端以观感微调时改这里，并把改动记回 ui-plan 9.1。
 *
 * 颜色一律写成「主题角色 + 透明度」（[ColorRole]、[Layer]），不出现颜色字面量；角色到
 * `?attr/` 的映射由绘制端（F2 的 GlassSurface）统一做。尺寸单位 dp，透明度 0..1。
 */
enum class ColorRole {
    SURFACE_CONTAINER,
    SURFACE_CONTAINER_LOWEST,
    PRIMARY_CONTAINER,
    PRIMARY,
    SECONDARY,
    ON_SURFACE,
    OUTLINE_VARIANT,
}

/** 角色色按 [alpha] 叠出的一层 */
data class Layer(val role: ColorRole, val alpha: Float)

/** 同一参数在浅色 / 深色界面下的取值 */
data class ModeValue<T>(val light: T, val dark: T) {
    fun pick(dark: Boolean): T = if (dark) this.dark else light
}

/** 表面形状；具体半径由宿主传入（Dock 沿用 nav_dock_radius，卡片 28dp，按钮取边长一半） */
enum class GlassShape { CAPSULE, EXTRA_LARGE, CIRCLE }

/** 动效时长，对应主题属性 `motionDuration*`，绘制端按名取值，不写毫秒字面量 */
enum class MotionToken { SHORT_2, MEDIUM_2 }

/** 自绘阴影的一层：纵向偏移、模糊半径（dp）与黑色阴影的不透明度 */
data class ShadowLayer(val dyDp: Float, val blurDp: Float, val alpha: Float)

/** 环境色渐变：从 [from] 到 [to]，[angleDeg] 为渐变方向（135° = 左上到右下） */
data class Ambient(val from: Layer, val to: Layer, val angleDeg: Float)

/**
 * 边缘与高光（三个表面共用同一套「中等」档）。
 *
 * - 顶亮边：[edgeWidthDp] 宽的梯度描边，顶 / 中 / 底三个不透明度，色取 [edgeColor]；
 * - 内高光：顶部 [innerHighlightHeightDp] 高的一道亮线；
 * - sheen：顶部柔光，自上向下渐变，到高度的 [sheenReach] 处消失；
 * - 外侧细线：[hairlineWidthDp] 宽的 `colorOutlineVariant`；
 * - 按压光：触点处的径向亮斑，淡入 [pressFadeIn]、淡出 [pressFadeOut]。
 */
data class Highlight(
    val edgeWidthDp: Float,
    val edgeColor: ModeValue<ColorRole>,
    val edgeTop: ModeValue<Float>,
    val edgeMiddle: ModeValue<Float>,
    val edgeBottom: ModeValue<Float>,
    val innerHighlightHeightDp: Float,
    val innerHighlight: ModeValue<Float>,
    val sheen: ModeValue<Float>,
    val sheenReach: Float,
    val hairlineWidthDp: Float,
    val hairlineColor: ColorRole,
    val hairline: ModeValue<Float>,
    val pressGlow: ModeValue<Float>,
    val pressFadeIn: MotionToken,
    val pressFadeOut: MotionToken,
)

/**
 * 一个表面在一个档位下的配方。
 *
 * - [base] / [baseAlpha]：底色角色与不透明度（状态卡片 BLUR 档是「边缘」不透明度）；
 * - [centerBackdrop]：中心衬底（径向渐变，边缘透明），只有状态卡片有，让数据区接近实色卡片；
 * - [blurRadiusDp]、[saturation]：下层模糊半径与下层饱和度，只有 [GlassTier.BLUR] 有（0 / 1 = 无）；
 * - [ambient]：环境色渐变，连接按钮没有；
 * - [highlight]：边缘高光，[GlassTier.SOLID] 没有；
 * - [shadows]：自绘阴影，[GlassTier.SOLID] 没有（用系统 elevation）；
 * - [elevationDp]：仅 [GlassTier.SOLID] 的系统 elevation（Dock 3、卡片 3、按钮 6）。
 */
data class GlassRecipe(
    val target: GlassTarget,
    val tier: GlassTier,
    val shape: GlassShape,
    val base: ColorRole,
    val baseAlpha: Float,
    val centerBackdrop: Layer?,
    val blurRadiusDp: Float,
    val saturation: Float,
    val ambient: Ambient?,
    val highlight: Highlight?,
    val shadows: ModeValue<List<ShadowLayer>>?,
    val elevationDp: Float,
)

object GlassRecipes {

    /** 半分辨率模糊：捕获时缩小到的比例（S1 实测肉眼无差别、压力下便宜 3–5 倍），默认开 */
    const val BLUR_CAPTURE_SCALE = 0.5f

    /** 状态卡片的圆角（`shapeAppearanceCornerExtraLarge`） */
    const val CARD_CORNER_DP = 28f

    /** 环境色「克制」：primary 3% → secondary 1%，沿 135° */
    private val AMBIENT = Ambient(
        from = Layer(ColorRole.PRIMARY, 0.03f),
        to = Layer(ColorRole.SECONDARY, 0.01f),
        angleDeg = 135f,
    )

    /** 边缘高光「中等」 */
    private val HIGHLIGHT = Highlight(
        edgeWidthDp = 1f,
        edgeColor = ModeValue(ColorRole.SURFACE_CONTAINER_LOWEST, ColorRole.ON_SURFACE),
        edgeTop = ModeValue(0.85f, 0.20f),
        edgeMiddle = ModeValue(0.25f, 0.05f),
        edgeBottom = ModeValue(0.30f, 0.07f),
        innerHighlightHeightDp = 1f,
        innerHighlight = ModeValue(0.50f, 0.08f),
        sheen = ModeValue(0.18f, 0.03f),
        sheenReach = 0.55f,
        hairlineWidthDp = 0.5f,
        hairlineColor = ColorRole.OUTLINE_VARIANT,
        hairline = ModeValue(0.55f, 0.60f),
        pressGlow = ModeValue(0.28f, 0.11f),
        pressFadeIn = MotionToken.SHORT_2,
        pressFadeOut = MotionToken.MEDIUM_2,
    )

    // 自绘阴影：两层（近处小影 + 远处大影），Dock 与状态卡片同值，按钮略重
    private val PANEL_SHADOWS = ModeValue(
        light = listOf(ShadowLayer(1f, 2f, 0.10f), ShadowLayer(6f, 18f, 0.10f)),
        dark = listOf(ShadowLayer(1f, 2f, 0.35f), ShadowLayer(8f, 22f, 0.40f)),
    )
    private val BUTTON_SHADOWS = ModeValue(
        light = listOf(ShadowLayer(1f, 3f, 0.14f), ShadowLayer(6f, 16f, 0.14f)),
        dark = listOf(ShadowLayer(1f, 3f, 0.40f), ShadowLayer(8f, 20f, 0.45f)),
    )

    private fun solid(target: GlassTarget, shape: GlassShape, base: ColorRole, elevationDp: Float) =
        GlassRecipe(
            target, GlassTier.SOLID, shape, base, baseAlpha = 1f,
            centerBackdrop = null, blurRadiusDp = 0f, saturation = 1f,
            ambient = null, highlight = null, shadows = null, elevationDp = elevationDp,
        )

    private val DOCK_SOLID = solid(GlassTarget.DOCK, GlassShape.CAPSULE, ColorRole.SURFACE_CONTAINER, 3f)
    private val CARD_SOLID = solid(GlassTarget.STATUS_CARD, GlassShape.EXTRA_LARGE, ColorRole.SURFACE_CONTAINER, 3f)
    private val BUTTON_SOLID = solid(GlassTarget.CONNECT_BUTTON, GlassShape.CIRCLE, ColorRole.PRIMARY_CONTAINER, 6f)

    // FROST 无模糊，下层的锐利文字会透出来（鬼影）：Dock / 卡片 / 按钮取 98 / 99 / 98%，
    // 鬼影只剩极淡的轮廓；卡片底色已到 99%，不再叠中心衬底（叠了也只多 0.5%）
    private val DOCK_FROST = DOCK_SOLID.copy(
        tier = GlassTier.FROST, baseAlpha = 0.98f, ambient = AMBIENT, highlight = HIGHLIGHT,
        shadows = PANEL_SHADOWS, elevationDp = 0f,
    )
    private val CARD_FROST = CARD_SOLID.copy(
        tier = GlassTier.FROST, baseAlpha = 0.99f,
        ambient = AMBIENT, highlight = HIGHLIGHT, shadows = PANEL_SHADOWS, elevationDp = 0f,
    )
    private val BUTTON_FROST = BUTTON_SOLID.copy(
        tier = GlassTier.FROST, baseAlpha = 0.98f, highlight = HIGHLIGHT,
        shadows = BUTTON_SHADOWS, elevationDp = 0f,
    )

    private val DOCK_BLUR = DOCK_FROST.copy(
        tier = GlassTier.BLUR, baseAlpha = 0.72f, blurRadiusDp = 20f, saturation = 1.15f,
    )
    // 边缘 64%，叠 60% 的中心衬底后中心约 86%
    private val CARD_BLUR = CARD_FROST.copy(
        tier = GlassTier.BLUR, baseAlpha = 0.64f,
        centerBackdrop = Layer(ColorRole.SURFACE_CONTAINER, 0.60f),
        blurRadiusDp = 24f, saturation = 1.15f,
    )

    // 连接按钮不做下层捕获（u5-plan 第 6 节第 4 条）：BLUR 档沿用 FROST 配方。
    // 稿里 BLUR 档的 88% / 12dp 只在 S1 的 A/B 推翻该决定时才启用
    fun of(target: GlassTarget, tier: GlassTier): GlassRecipe = when (target) {
        GlassTarget.DOCK -> when (tier) {
            GlassTier.SOLID -> DOCK_SOLID
            GlassTier.FROST -> DOCK_FROST
            GlassTier.BLUR -> DOCK_BLUR
        }
        GlassTarget.STATUS_CARD -> when (tier) {
            GlassTier.SOLID -> CARD_SOLID
            GlassTier.FROST -> CARD_FROST
            GlassTier.BLUR -> CARD_BLUR
        }
        GlassTarget.CONNECT_BUTTON -> when (tier) {
            GlassTier.SOLID -> BUTTON_SOLID
            GlassTier.FROST, GlassTier.BLUR -> BUTTON_FROST
        }
    }
}
