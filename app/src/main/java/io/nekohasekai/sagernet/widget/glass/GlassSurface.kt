package io.nekohasekai.sagernet.widget.glass

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.motionDuration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把 [GlassRecipe] 画到一个悬浮层（Dock、状态卡片、连接按钮）上。宿主各有一个，宿主在自己的绘制钩子里转调：
 * 子视图之前 [drawBelow]（环境色渐变、sheen、中心衬底），子视图之后 [drawAbove]（按压光、顶亮边、内高光、外侧细线）；
 * 底色（半透明的角色色）不在这里画，经 [applyFill] 交给宿主自己的背景（卡片的 cardBackgroundColor、
 * 按钮的 backgroundTint），形状、系统 elevation 阴影、涟漪都沿用宿主原样。
 *
 * 档位来自 [GlassTiers.tier]，经 [bind] 订阅：档位变了只重算配方并重绘，不重建 Activity。
 * - SOLID：宿主原样，这里不画任何东西，也不改宿主的任何属性（截图基线保持逐像素不变）；
 * - FROST：按配方画静态磨砂；
 * - BLUR：F3 才有下层捕获，现在按 FROST 画（[effectiveTier]）。
 *
 * 阴影：F2 保留宿主的系统 elevation 阴影，不用配方里的自绘阴影（`GlassRecipe.shadows`）；FROST 底色 92–95% 不透明，
 * 系统阴影在底色之下不外漏。前景（文字、图标）不经这里，始终是宿主的 on- 角色，不会变半透明。
 * 颜色一律由 [ColorRole] 按宿主的主题解析（?attr/ 角色），只在这里加透明度。
 */
class GlassSurface(
    private val host: View,
    private val target: GlassTarget,
    private val applyFill: (Int) -> Unit,
    private val pressable: Boolean = false,
) : GlassTiers.Listener {

    /** 配方里的颜色按当前主题解析后的结果；只在档位 / 主题变化时重算 */
    private class Colors(
        val fill: Int,
        val ambientFrom: Int?,
        val ambientTo: Int?,
        val centre: Int?,
        val sheen: Int,
        val edgeTop: Int,
        val edgeMiddle: Int,
        val edgeBottom: Int,
        val innerHighlight: Int,
        val hairline: Int,
        val press: Int,
    )

    private var tiers: GlassTiers? = null

    // 当前生效的配方；null = SOLID（宿主原样）
    private var recipe: GlassRecipe? = null
    private var colors: Colors? = null

    // 几何与着色器缓存：只在尺寸或颜色变化时重建
    private var geometryKey: Long = 0L
    private var geometryValid = false
    private val rect = RectF()
    private val shape = Path()
    private val innerShape = Path()
    private var corner = 0f
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 按压光
    private var pressAlpha = 0f
    private var pressX = 0f
    private var pressY = 0f
    private var pressAnimator: ValueAnimator? = null

    /** 订阅档位变化并立刻按当前档位生效；同一个 [GlassTiers] 重复调用无副作用 */
    fun bind(glass: GlassTiers) {
        tiers = glass
        glass.addListener(this)
        refresh()
    }

    override fun onGlassTiersChanged() = refresh()

    /** 宿主挂上窗口 / 主题变化后调用：按当前主题重新解析颜色 */
    fun refresh() {
        val glass = tiers ?: return
        val tier = effectiveTier(glass.tier(target, hardwareAccelerated = true))
        if (tier == GlassTier.SOLID) {
            if (recipe == null) return
            recipe = null
            colors = null
            cancelPress()
            applyFill(MaterialColors.getColor(host, attr(GlassRecipes.of(target, GlassTier.SOLID).base)))
            host.invalidate()
            return
        }
        val r = GlassRecipes.of(target, tier)
        val c = resolve(r)
        recipe = r
        colors = c
        geometryValid = false
        applyFill(c.fill)
        host.invalidate()
    }

    // F3 之前 BLUR 按 FROST 画：BLUR 配方的底色只有 64–72%，没有下层模糊托着读不清
    private fun effectiveTier(tier: GlassTier) = if (tier == GlassTier.BLUR) GlassTier.FROST else tier

    private fun resolve(r: GlassRecipe): Colors {
        val dark = !MaterialColors.isColorLight(MaterialColors.getColor(host, com.google.android.material.R.attr.colorSurface))
        val h = r.highlight!!
        fun role(role: ColorRole, alpha: Float) = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(host, attr(role)), (alpha * 255f).roundToInt().coerceIn(0, 255),
        )
        fun layer(l: Layer) = role(l.role, l.alpha)
        val edgeRole = h.edgeColor.pick(dark)
        return Colors(
            fill = role(r.base, r.baseAlpha),
            ambientFrom = r.ambient?.let { layer(it.from) },
            ambientTo = r.ambient?.let { layer(it.to) },
            centre = r.centerBackdrop?.let { layer(it) },
            sheen = role(edgeRole, h.sheen.pick(dark)),
            edgeTop = role(edgeRole, h.edgeTop.pick(dark)),
            edgeMiddle = role(edgeRole, h.edgeMiddle.pick(dark)),
            edgeBottom = role(edgeRole, h.edgeBottom.pick(dark)),
            innerHighlight = role(edgeRole, h.innerHighlight.pick(dark)),
            hairline = role(h.hairlineColor, h.hairline.pick(dark)),
            press = role(edgeRole, h.pressGlow.pick(dark)),
        )
    }

    /** 子视图之前：环境色渐变、sheen、中心衬底（都在形状之内） */
    fun drawBelow(canvas: Canvas) {
        val r = recipe ?: return
        val c = colors ?: return
        if (!prepare(r, c)) return
        if (c.ambientFrom != null) canvas.drawRoundRect(rect, corner, corner, ambientPaint)
        canvas.drawRoundRect(rect, corner, corner, sheenPaint)
        if (c.centre != null) canvas.drawRoundRect(rect, corner, corner, centrePaint)
    }

    /** 子视图之后：按压光、内高光、顶亮边、外侧细线 */
    fun drawAbove(canvas: Canvas) {
        val r = recipe ?: return
        val c = colors ?: return
        val h = r.highlight ?: return
        if (!prepare(r, c)) return
        val density = host.resources.displayMetrics.density
        if (pressAlpha > 0f) {
            glowPaint.alpha = (pressAlpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.withClip(shape) { drawCircle(pressX, pressY, glowRadius(density), glowPaint) }
        }
        // 内高光：顶部 1dp 的一道亮线，限在顶亮边之内
        val top = (h.hairlineWidthDp + h.edgeWidthDp) * density
        canvas.withClip(innerShape) {
            drawRect(0f, top, rect.width(), top + h.innerHighlightHeightDp * density, innerPaint)
        }
        // 顶亮边：1dp 的梯度描边，贴在外侧细线之内
        val hairline = h.hairlineWidthDp * density
        val edge = h.edgeWidthDp * density
        val edgeInset = hairline + edge / 2f
        canvas.drawRoundRect(
            edgeInset, edgeInset, rect.width() - edgeInset, rect.height() - edgeInset,
            max(0f, corner - edgeInset), max(0f, corner - edgeInset), edgePaint,
        )
        val hairInset = hairline / 2f
        canvas.drawRoundRect(
            hairInset, hairInset, rect.width() - hairInset, rect.height() - hairInset,
            max(0f, corner - hairInset), max(0f, corner - hairInset), hairlinePaint,
        )
    }

    private fun glowRadius(density: Float) = min(max(rect.width(), rect.height()), GLOW_MAX_DP * density)

    // 尺寸或颜色变了就重建几何与着色器；返回 false 表示还没有尺寸
    private fun prepare(r: GlassRecipe, c: Colors): Boolean {
        val w = host.width
        val hgt = host.height
        if (w <= 0 || hgt <= 0) return false
        val key = (w.toLong() shl 32) or hgt.toLong()
        if (geometryValid && key == geometryKey) return true
        geometryKey = key
        geometryValid = true
        val density = host.resources.displayMetrics.density
        val hl = r.highlight!!
        val wf = w.toFloat()
        val hf = hgt.toFloat()
        rect.set(0f, 0f, wf, hf)
        corner = when (r.shape) {
            GlassShape.CAPSULE, GlassShape.CIRCLE -> min(wf, hf) / 2f
            GlassShape.EXTRA_LARGE -> GlassRecipes.CARD_CORNER_DP * density
        }
        shape.reset()
        shape.addRoundRect(rect, corner, corner, Path.Direction.CW)
        val inset = (hl.hairlineWidthDp + hl.edgeWidthDp) * density
        innerShape.reset()
        innerShape.addRoundRect(
            RectF(inset, inset, wf - inset, hf - inset),
            max(0f, corner - inset), max(0f, corner - inset), Path.Direction.CW,
        )

        // 环境色渐变：沿 CSS 角度（135° = 左上到右下）取整个矩形的投影长度
        val ambient = r.ambient
        if (ambient != null && c.ambientFrom != null && c.ambientTo != null) {
            val a = Math.toRadians(ambient.angleDeg.toDouble())
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            val half = (abs(wf * dx) + abs(hf * dy)) / 2f
            ambientPaint.shader = LinearGradient(
                wf / 2f - dx * half, hf / 2f - dy * half, wf / 2f + dx * half, hf / 2f + dy * half,
                c.ambientFrom, c.ambientTo, Shader.TileMode.CLAMP,
            )
        }
        // sheen：自上向下，到高度的 sheenReach 处消失
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, hf * hl.sheenReach, c.sheen, withAlpha(c.sheen, 0), Shader.TileMode.CLAMP,
        )
        // 中心衬底：椭圆径向渐变，中间一片实、向边缘淡出到透明
        if (c.centre != null) {
            centrePaint.shader = RadialGradient(
                wf / 2f, hf / 2f, wf / 2f,
                intArrayOf(c.centre, c.centre, withAlpha(c.centre, 0)), floatArrayOf(0f, CENTRE_SOLID_STOP, 1f),
                Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(Matrix().apply { setScale(1f, hf / wf, wf / 2f, hf / 2f) }) }
        }
        edgePaint.strokeWidth = hl.edgeWidthDp * density
        val edgeTop = hl.hairlineWidthDp * density
        edgePaint.shader = LinearGradient(
            0f, edgeTop, 0f, hf - edgeTop,
            intArrayOf(c.edgeTop, c.edgeMiddle, c.edgeBottom), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        hairlinePaint.strokeWidth = hl.hairlineWidthDp * density
        hairlinePaint.color = c.hairline
        innerPaint.color = c.innerHighlight
        return true
    }

    private fun withAlpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha)

    /** 宿主的 dispatchTouchEvent 里转调（只观察，不消费）：按下时在触点亮起按压光，抬起 / 取消时淡出 */
    fun trackTouch(ev: MotionEvent) {
        if (!pressable || recipe == null) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(ev.x, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    private fun press(x: Float, y: Float) {
        val c = colors ?: return
        val r = recipe ?: return
        if (!prepare(r, c)) return
        pressX = x
        pressY = y
        glowPaint.shader = RadialGradient(
            x, y, glowRadius(host.resources.displayMetrics.density),
            c.press, withAlpha(c.press, 0), Shader.TileMode.CLAMP,
        )
        animatePress(1f, r.highlight!!.pressFadeIn)
    }

    private fun release() {
        val r = recipe ?: return
        animatePress(0f, r.highlight!!.pressFadeOut)
    }

    private fun animatePress(target: Float, token: MotionToken) {
        pressAnimator?.cancel()
        pressAnimator = null
        if (!host.context.animatorsEnabled()) {
            pressAlpha = target
            host.invalidate()
            return
        }
        val duration = host.context.motionDuration(
            when (token) {
                MotionToken.SHORT_2 -> com.google.android.material.R.attr.motionDurationShort2
                MotionToken.MEDIUM_2 -> com.google.android.material.R.attr.motionDurationMedium2
            },
            when (token) {
                MotionToken.SHORT_2 -> 100
                MotionToken.MEDIUM_2 -> 300
            },
        )
        pressAnimator = ValueAnimator.ofFloat(pressAlpha, target).apply {
            this.duration = duration
            interpolator = MotionUtils.resolveThemeInterpolator(
                host.context, com.google.android.material.R.attr.motionEasingStandardInterpolator,
                FastOutSlowInInterpolator(),
            )
            addUpdateListener {
                pressAlpha = it.animatedValue as Float
                host.invalidate()
            }
            start()
        }
    }

    /** 宿主脱离窗口时调用：停掉按压光，免得动画回调拿着视图 */
    fun cancelPress() {
        pressAnimator?.cancel()
        pressAnimator = null
        if (pressAlpha != 0f) {
            pressAlpha = 0f
            host.invalidate()
        }
    }

    companion object {
        // 按压光半径的上限（dp）：Dock 上取这个，按钮（56dp）取自己的边长
        private const val GLOW_MAX_DP = 72f

        // 中心衬底：椭圆半径的这个比例以内保持满强度，再往外淡出
        private const val CENTRE_SOLID_STOP = 0.55f

        /** 配方里的颜色角色 → 主题属性（?attr/ 角色，不写颜色字面量） */
        fun attr(role: ColorRole): Int = when (role) {
            ColorRole.SURFACE_CONTAINER -> com.google.android.material.R.attr.colorSurfaceContainer
            ColorRole.SURFACE_CONTAINER_LOWEST -> com.google.android.material.R.attr.colorSurfaceContainerLowest
            ColorRole.PRIMARY_CONTAINER -> com.google.android.material.R.attr.colorPrimaryContainer
            ColorRole.PRIMARY -> androidx.appcompat.R.attr.colorPrimary
            ColorRole.SECONDARY -> com.google.android.material.R.attr.colorSecondary
            ColorRole.ON_SURFACE -> com.google.android.material.R.attr.colorOnSurface
            ColorRole.OUTLINE_VARIANT -> com.google.android.material.R.attr.colorOutlineVariant
        }

        /** 按钮的背景着色：单色，各状态同色（与布局里的 app:backgroundTint 同形） */
        fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)
    }
}
