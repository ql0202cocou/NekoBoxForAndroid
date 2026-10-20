package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.motion.MotionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.motionDuration
import io.nekohasekai.sagernet.widget.glass.GlassSurface
import io.nekohasekai.sagernet.widget.glass.GlassTarget
import kotlin.math.PI
import kotlin.math.sin

/**
 * 主界面底部的悬浮 Dock：四个一级页面（配置、分组、路由、设置）的入口，横向等分。
 *
 * 外壳是全圆角的卡片（surfaceContainer 底，阴影 3dp），每项的图标与文字颜色见 `nav_dock_item`。
 * 选中胶囊是一个共享的视图（`nav_dock_pill`，secondaryContainer），放在四个项之下，选中变化时
 * 用弹簧滑到新选中项下面（取主题的 motionSpringFastSpatial，Expressive 快速空间弹簧，
 * 刚度 800、阻尼比 0.6）；首次布局、尺寸变化与系统动画关闭时直接放到位。选中哪一项由宿主按当前页面调用 [select]，
 * 点击只通过 [onItemSelected] 交给宿主，自己不改选中状态。
 */
class NavDock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    private class Item(
        @IdRes val id: Int,
        @DrawableRes val icon: Int,
        @DrawableRes val iconOutline: Int,
        @StringRes val title: Int,
    )

    private val items = listOf(
        Item(R.id.nav_configuration, R.drawable.ic_action_description, R.drawable.ic_outline_description_24, R.string.menu_configuration),
        Item(R.id.nav_group, R.drawable.ic_baseline_view_list_24, R.drawable.ic_outline_view_list_24, R.string.menu_group),
        Item(R.id.nav_route, R.drawable.ic_maps_directions, R.drawable.ic_outline_directions_24, R.string.menu_route),
        Item(R.id.nav_settings, R.drawable.ic_action_settings, R.drawable.ic_outline_settings_24, R.string.settings),
    )

    private val itemViews = mutableMapOf<Int, View>()

    // 共享的选中胶囊：首次对齐前不可见，之后只动 translationX
    private val pill = View(context).apply {
        setBackgroundResource(R.drawable.nav_dock_pill)
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val pillSpring = SpringAnimation(pill, DynamicAnimation.TRANSLATION_X).apply {
        spring = MotionUtils.resolveThemeSpringForce(
            context, com.google.android.material.R.attr.motionSpringFastSpatial,
            R.style.Motion_SagerNet_Spring_FastSpatial
        )
    }
    private var selectedId = View.NO_ID
    // 胶囊是否已经放到过位；没有就不做动画
    private var pillPlaced = false
    // 胶囊当前的目标位置（选中项的左边），用来判断布局是否真的变了
    private var pillTarget = Float.NaN

    // 每一项在无障碍树里报成按钮，读屏才会读出「按钮」；选中状态仍由 isSelected 报告
    private val buttonRole = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    }

    /** 点击某一项时回调，参数是该项对应的页面 id */
    var onItemSelected: ((Int) -> Unit)? = null

    /**
     * 玻璃材质（U5f）：非 SOLID 档时外壳底色按配方变半透明，环境色、顶亮边、按压光画在壳上；
     * 选中胶囊与前景不经它，仍是实色的 secondaryContainer 与 on- 角色。SOLID 档什么也不做
     */
    val glassSurface = GlassSurface(this, GlassTarget.DOCK, { setCardBackgroundColor(it) }, pressable = true)

    init {
        radius = resources.getDimension(R.dimen.nav_dock_radius)
        cardElevation = resources.getDimension(R.dimen.nav_dock_elevation)
        strokeWidth = 0
        setCardBackgroundColor(context.getColorAttr(com.google.android.material.R.attr.colorSurfaceContainer))
        val padding = resources.getDimensionPixelSize(R.dimen.nav_dock_padding)
        setContentPadding(padding, padding, padding, padding)

        val content = FrameLayout(context)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 胶囊在前、各项在后：项的涟漪与图标画在胶囊之上
        content.addView(pill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 布局变化（旋转、字体或窗口尺寸变化）后重新对齐胶囊
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignPill(animate = false) }
        val inflater = LayoutInflater.from(context)
        for (item in items) {
            val view = inflater.inflate(R.layout.layout_nav_dock_item, row, false)
            val title = context.getText(item.title)
            view.findViewById<ImageView>(R.id.nav_dock_icon_outline).setImageResource(item.iconOutline)
            view.findViewById<ImageView>(R.id.nav_dock_icon_fill).setImageResource(item.icon)
            view.findViewById<TextView>(R.id.nav_dock_label).text = title
            view.contentDescription = title
            ViewCompat.setAccessibilityDelegate(view, buttonRole)
            view.setOnClickListener { onItemSelected?.invoke(item.id) }
            row.addView(view)
            itemViews[item.id] = view
        }
    }

    /** 把 [id] 对应的项设为选中（无障碍也据此报「已选中」），其余取消 */
    fun select(@IdRes id: Int) {
        val changed = selectedId != id
        selectedId = id
        // 首次放位（还没有胶囊位置）不做动画；系统动画关闭时 setIconFilled 直接切换
        val animate = changed && pillPlaced
        for ((itemId, view) in itemViews) {
            val selected = itemId == id
            val wasSelected = view.isSelected
            view.isSelected = selected
            if (selected != wasSelected || !pillPlaced) setIconFilled(view, selected, animate)
        }
        if (changed) alignPill(animate = pillPlaced)
    }

    /**
     * 图标：未选中是线条，选中是填充。选中时填充层 350ms 淡入（主题 motionEasingStandardInterpolator）
     * 并做一次 1 到 1.1 到 1 的正弦缩放脉冲；取消选中时填充层淡出。[animate] 为 false
     * 或系统动画关闭时直接切换
     */
    private fun setIconFilled(item: View, filled: Boolean, animate: Boolean) {
        val fill = item.findViewById<View>(R.id.nav_dock_icon_fill)
        val box = item.findViewById<View>(R.id.nav_dock_icon)
        (box.getTag(R.id.nav_dock_pulse) as? ValueAnimator)?.cancel()
        fill.animate().cancel()
        val target = if (filled) 1f else 0f
        if (!animate || !context.animatorsEnabled()) {
            fill.alpha = target
            box.scaleX = 1f
            box.scaleY = 1f
            return
        }
        val duration = context.motionDuration(
            com.google.android.material.R.attr.motionDurationMedium3, ICON_FILL_DURATION_MS
        )
        fill.animate().alpha(target).setDuration(duration)
            .setInterpolator(
                MotionUtils.resolveThemeInterpolator(
                    context, com.google.android.material.R.attr.motionEasingStandardInterpolator,
                    FastOutSlowInInterpolator()
                )
            ).start()
        if (filled) {
            val pulse = ValueAnimator.ofFloat(0f, 1f).setDuration(duration)
            pulse.addUpdateListener {
                val scale = 1f + ICON_PULSE * sin(PI * it.animatedFraction).toFloat()
                box.scaleX = scale
                box.scaleY = scale
            }
            pulse.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    box.scaleX = 1f
                    box.scaleY = 1f
                }
            })
            box.setTag(R.id.nav_dock_pulse, pulse)
            pulse.start()
        }
    }

    /**
     * 把胶囊对到选中项下面。[animate] 为 true 且胶囊已放过位、系统动画开着时弹簧滑过去，
     * 否则直接放到位。四项等宽，宽度变化（尺寸变化后）也一并直接设置
     */
    private fun alignPill(animate: Boolean) {
        val target = itemViews[selectedId] ?: return
        if (target.width == 0) return
        val left = target.left.toFloat()
        // 布局回调里每次都会走到这里：位置没变就什么也不做，别打断正在滑的弹簧
        if (pillPlaced && left == pillTarget && pill.layoutParams.width == target.width) return
        pillTarget = left
        if (pill.layoutParams.width != target.width) {
            pill.layoutParams.width = target.width
            pill.requestLayout()
        }
        if (animate && pillPlaced && pill.isLaidOut && context.animatorsEnabled()) {
            pillSpring.animateToFinalPosition(left)
        } else {
            pillSpring.cancel()
            pill.translationX = left
        }
        pill.visibility = VISIBLE
        pillPlaced = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glassSurface.refresh()
    }

    override fun onDetachedFromWindow() {
        pillSpring.cancel()
        glassSurface.detach()
        super.onDetachedFromWindow()
    }

    // 玻璃：BLUR 档先把模糊后的下层画在宿主的半透明底色之下（super.draw 画底色与内容）
    override fun draw(canvas: Canvas) {
        glassSurface.drawBackdrop(canvas)
        super.draw(canvas)
    }

    // 玻璃：材质层在各项之下（环境色、sheen）与之上（按压光、亮边）
    override fun dispatchDraw(canvas: Canvas) {
        glassSurface.drawBelow(canvas)
        super.dispatchDraw(canvas)
        glassSurface.drawAbove(canvas)
    }

    // 只观察触点（按压光的位置），不消费事件
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        glassSurface.trackTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        // 填充层淡入与脉冲：350ms（主题的 motionDurationMedium3），脉冲峰值放大 10%
        const val ICON_FILL_DURATION_MS = 350
        const val ICON_PULSE = 0.1f
    }
}
