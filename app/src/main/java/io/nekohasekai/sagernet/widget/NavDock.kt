package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.motionDuration
import io.nekohasekai.sagernet.ktx.motionEasingStandard
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 主界面底部的悬浮 Dock：四个一级页面（配置、分组、路由、设置）的入口，横向等分。
 *
 * 外壳是全圆角的卡片（surfaceContainer 底，阴影 1dp），每项的图标与文字颜色见 `nav_dock_item`。
 * 选中胶囊是一个共享的视图（`nav_dock_pill`，secondaryContainer），放在四个项之下，选中变化时
 * 用弹簧（刚度 380、阻尼比 0.8，M3 默认的空间弹簧）滑到新选中项下面；首次布局、尺寸变化与系统动画
 * 关闭时直接放到位。选中哪一项由宿主按当前页面调用 [select]，
 * 点击或拖选松手时通过 [onItemSelected] 交给宿主；拖动期间只预览目标，不切换页面。
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

    /** [id] 在 Dock 上从左到右的位置，不在 Dock 上为 -1 */
    fun indexOf(@IdRes id: Int): Int = items.indexOfFirst { it.id == id }

    private val itemViews = mutableMapOf<Int, View>()

    // 共享的选中胶囊：首次对齐前不可见，之后只动 translationX
    private val pill = View(context).apply {
        setBackgroundResource(R.drawable.nav_dock_pill)
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val pillSpring = SpringAnimation(pill, DynamicAnimation.TRANSLATION_X).apply {
        spring = SpringForce().apply {
            stiffness = PILL_STIFFNESS
            dampingRatio = PILL_DAMPING_RATIO
        }
    }
    private var selectedId = View.NO_ID
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val itemBounds = Rect()
    private var downX = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var dragging = false
    private var dragTargetId = View.NO_ID
    private val displayedId get() = if (dragging && dragTargetId != View.NO_ID) dragTargetId else selectedId
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

    /** 点击或拖选松手时回调，参数是该项对应的页面 id */
    var onItemSelected: ((Int) -> Unit)? = null

    init {
        radius = resources.getDimension(R.dimen.nav_dock_radius)
        cardElevation = resources.getDimension(R.dimen.nav_dock_elevation)
        strokeWidth = 0
        setCardBackgroundColor(context.getColorAttr(com.google.android.material.R.attr.colorSurfaceContainer))
        val padding = resources.getDimensionPixelSize(R.dimen.nav_dock_padding)
        setContentPadding(padding, padding, padding, padding)

        val content = FrameLayout(context)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 胶囊在前、各项在后：项的焦点提示与图标画在胶囊之上
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
        selectedId = id
        showSelection()
    }

    private fun showSelection(animate: Boolean = true) {
        // 首次放位（还没有胶囊位置）不做动画；系统动画关闭时 setIconFilled 直接切换
        for ((itemId, view) in itemViews) {
            val selected = itemId == displayedId
            val wasSelected = view.isSelected
            view.isSelected = selected
            if (!animate || selected != wasSelected || !pillPlaced) setIconFilled(view, selected, animate && pillPlaced)
        }
        alignPill(animate = animate && pillPlaced)
    }

    // 整个 Dock 都可命中；内侧留白归最近的项，按实际位置兼容窗口尺寸与 RTL 排列。
    private fun itemAt(x: Float, y: Float, allowOutside: Boolean = false): Int {
        if (!allowOutside && (x < 0 || x >= width || y < 0 || y >= height)) return View.NO_ID
        var closestId = View.NO_ID
        var closestDistance = Float.POSITIVE_INFINITY
        for ((id, view) in itemViews) {
            if (view.visibility != VISIBLE || view.width == 0) continue
            view.getDrawingRect(itemBounds)
            offsetDescendantRectToMyCoords(view, itemBounds)
            val distance = abs(x - itemBounds.exactCenterX())
            if (distance < closestDistance) {
                closestDistance = distance
                closestId = if (view.isEnabled) id else View.NO_ID
            }
        }
        return closestId
    }

    // 从 DOWN 起统一接管，避免子按钮的点击与 Dock 的拖选各自保留一份触摸状态。
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    // 触摸结束仍通过目标按钮 performClick；键盘与无障碍直接使用按钮的原有入口。
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (activePointerId != MotionEvent.INVALID_POINTER_ID) finishTouch(commit = false, animate = false)
            if (!isEnabled) return false
            val target = itemAt(event.x, event.y)
            if (target == View.NO_ID) return false
            activePointerId = event.getPointerId(0)
            downX = event.x
            dragTargetId = target
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (!isEnabled || pointerIndex < 0) {
            finishTouch(commit = false)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> previewTouch(event.getX(pointerIndex), event.getY(pointerIndex))
            MotionEvent.ACTION_UP -> {
                previewTouch(event.getX(pointerIndex), event.getY(pointerIndex))
                finishTouch(commit = true)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> finishTouch(commit = false)
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == activePointerId) {
                finishTouch(commit = false)
            }
        }
        return true
    }

    private fun previewTouch(x: Float, y: Float) {
        if (abs(x - downX) > touchSlop) dragging = true
        // 横向拖选一旦开始就只跟随 x；滑到手势线附近仍可继续，取消由触摸流的 CANCEL 决定。
        dragTargetId = itemAt(x, y, allowOutside = dragging)
        showSelection()
    }

    private fun finishTouch(commit: Boolean, animate: Boolean = true) {
        val target = itemViews[dragTargetId]
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        dragTargetId = View.NO_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (commit) target?.performClick()
        // 宿主可能拒绝导航；最终显示仍以 select 收到的实际页面为准。
        showSelection(animate)
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
            .setInterpolator(context.motionEasingStandard()).start()
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
        val target = itemViews[displayedId] ?: return
        if (target.width == 0) return
        val left = target.left.toFloat()
        // 目标相同不重启动画；要求立即复位时仍要停止回程中的弹簧，不能只比较终点。
        if (pillPlaced && left == pillTarget && pill.layoutParams.width == target.width &&
            (animate || !pillSpring.isRunning)) return
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // 系统手势或其它窗口接管后，旧触摸流作废；回到应用时高亮与实际页面一致。
        if (!hasWindowFocus) finishTouch(commit = false, animate = false)
    }

    override fun onDetachedFromWindow() {
        finishTouch(commit = false, animate = false)
        pillSpring.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        // M3 默认的空间弹簧（spatial default）
        const val PILL_STIFFNESS = 380f
        const val PILL_DAMPING_RATIO = 0.8f

        // 填充层淡入与脉冲：350ms（主题的 motionDurationMedium3），脉冲峰值放大 10%
        const val ICON_FILL_DURATION_MS = 350
        const val ICON_PULSE = 0.1f
    }
}
