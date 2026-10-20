package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.motionDuration
import io.nekohasekai.sagernet.ktx.themeInteger
import io.nekohasekai.sagernet.ktx.unwrapTo
import io.nekohasekai.sagernet.widget.glass.GlassSurface
import io.nekohasekai.sagernet.widget.glass.GlassTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.PI
import kotlin.math.cos

class ServiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    FloatingActionButton(context, attrs, defStyleAttr), DynamicAnimation.OnAnimationEndListener {

    /**
     * 玻璃材质（U5f）：非 SOLID 档时底色（primaryContainer）按配方变半透明，顶亮边、内高光、按压光画在图标之上。
     * SOLID 档什么也不做。按钮不捕获下层内容（u5-plan 第 6 节），BLUR 档同 FROST
     */
    val glassSurface = GlassSurface(
        this, GlassTarget.CONNECT_BUTTON, { backgroundTintList = GlassSurface.tint(it) }, pressable = true,
    )

    private val callback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            super.onAnimationEnd(drawable)
            var next = animationQueue.peek() ?: return
            if (next.icon.current == drawable) {
                animationQueue.pop()
                next = animationQueue.peek() ?: return
            }
            next.start()
        }
    }

    private inner class AnimatedState(
        @DrawableRes resId: Int,
        private val onStart: BaseProgressIndicator<*>.() -> Unit = { hideProgress() }
    ) {
        val icon: AnimatedVectorDrawableCompat =
            AnimatedVectorDrawableCompat.create(context, resId)!!.apply {
                registerAnimationCallback(this@ServiceButton.callback)
            }

        fun start() {
            setImageDrawable(icon)
            icon.start()
            progress.onStart()
        }

        fun stop() = icon.stop()
    }

    private val iconStopped by lazy { AnimatedState(R.drawable.ic_service_stopped) }
    private val iconConnecting by lazy {
        AnimatedState(R.drawable.ic_service_connecting) {
            hideProgress()
            delayedAnimation = context.unwrapTo<LifecycleOwner>().lifecycleScope.launch {
                delay(context.resources.getInteger(R.integer.service_icon_morph_duration) + 1000L)
                isIndeterminate = true
                show()
            }
        }
    }
    private val iconConnected by lazy {
        AnimatedState(R.drawable.ic_service_connected) {
            delayedAnimation?.cancel()
            setProgressCompat(1, true)
        }
    }
    private val iconStopping by lazy { AnimatedState(R.drawable.ic_service_stopping) }
    private val animationQueue = ArrayDeque<AnimatedState>()

    private var checked = false
    private var delayedAnimation: Job? = null
    private lateinit var progress: BaseProgressIndicator<*>
    fun initProgress(progress: BaseProgressIndicator<*>) {
        this.progress = progress
        progress.progressDrawable?.addSpringAnimationEndListener(this)
    }

    override fun onAnimationEnd(
        animation: DynamicAnimation<out DynamicAnimation<*>>?, canceled: Boolean, value: Float,
        velocity: Float
    ) {
        if (!canceled) progress.hide()
    }

    private fun hideProgress() {
        delayedAnimation?.cancel()
        progress.hide()
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) View.mergeDrawableStates(
            drawableState,
            intArrayOf(android.R.attr.state_checked)
        )
        return drawableState
    }

    fun changeState(state: BaseService.State, animate: Boolean) {
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animate)
            BaseService.State.Connected -> changeState(iconConnected, animate)
            // 变形动画只从已连接的图标开始；此时 checked 还是上一个状态的值。
            // 不能用 ServiceRegistry.state：SagerConnection 在 binder 线程先写入新状态再派发回调，读到的已是新值
            BaseService.State.Stopping -> changeState(iconStopping, animate && checked)
            else -> changeState(iconStopped, animate)
        }
        checked = state == BaseService.State.Connected
        refreshDrawableState()
        updateBreathing()
        val description = context.getText(if (state.canStop) R.string.stop else R.string.connect)
        contentDescription = description
        TooltipCompat.setTooltipText(this, description)
        val enabled = state.canStop || state == BaseService.State.Stopped
        isEnabled = enabled
        pointerIcon = PointerIcon.getSystemIcon(
            context,
            if (enabled) PointerIcon.TYPE_HAND else PointerIcon.TYPE_WAIT
        )
    }

    private fun changeState(icon: AnimatedState, animate: Boolean) {
        fun counters(a: AnimatedState, b: AnimatedState): Boolean =
            a == iconStopped && b == iconConnecting ||
                    a == iconConnecting && b == iconStopped ||
                    a == iconConnected && b == iconStopping ||
                    a == iconStopping && b == iconConnected
        if (animate) {
            if (animationQueue.size < 2 || !counters(animationQueue.last, icon)) {
                animationQueue.add(icon)
                if (animationQueue.size == 1) icon.start()
            } else animationQueue.removeLast()
        } else {
            animationQueue.peekFirst()?.stop()
            animationQueue.clear()
            icon.start()    // force ensureAnimatorSet to be called so that stop() will work
            icon.stop()
        }
    }

    // ---- 已连接时的「呼吸」叠层 ----
    // 叠层是与按钮同形（圆）的前景，颜色 colorOnPrimaryContainer，不透明度在 0 与 8% 之间往返，
    // 周期 3000ms。为免整屏每帧重绘，用 Handler 按 100ms 步进（10fps，8% 只有约 20 级，够用），
    // 不用无限循环的 ValueAnimator；
    // 进出已连接态时整体 300ms 淡入淡出。
    // 离开已连接态、脱离窗口、窗口或视图不可见、系统动画被关闭（动画时长缩放为 0）时都停，不留叠层

    // 三个时长取自主题（serviceBreath*），回退值与主题定义一致
    private val breathPeriodMs = context.themeInteger(R.attr.serviceBreathPeriod, 3000).toLong()
    private val breathStepMs = context.themeInteger(R.attr.serviceBreathStep, 100).toLong()
    private val breathFadeMs = context.themeInteger(R.attr.serviceBreathFade, 300).toLong()

    private val breathHandler = Handler(Looper.getMainLooper())
    private val breathTick = Runnable { stepBreath() }
    private var breathOverlay: GradientDrawable? = null
    private var breathWanted = false

    // 叠层整体的包络（0..1）与上次更新时刻；包络向目标值线性推进，300ms 走完
    private var breathEnvelope = 0f
    private var breathLastTime = 0L
    private var breathPhaseStart = 0L

    private fun breathAllowed(): Boolean =
        checked && isAttachedToWindow && windowVisibility == View.VISIBLE && isShown &&
                context.animatorsEnabled()

    private fun updateBreathing() {
        if (breathAllowed()) {
            if (!breathWanted) {
                breathWanted = true
                val now = SystemClock.uptimeMillis()
                breathLastTime = now
                if (breathPhaseStart == 0L) breathPhaseStart = now
                if (breathOverlay == null) {
                    breathOverlay = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    foreground = breathOverlay
                }
                breathHandler.removeCallbacks(breathTick)
                breathHandler.post(breathTick)
            }
        } else if (breathWanted) {
            breathWanted = false
            if (isAttachedToWindow && isShown && context.animatorsEnabled()) {
                // 正常离开已连接态：保留叠层让它淡出（stepBreath 淡完后清掉）
                breathLastTime = SystemClock.uptimeMillis()
                breathHandler.removeCallbacks(breathTick)
                breathHandler.post(breathTick)
            } else {
                clearBreath()
            }
        }
    }

    private fun stepBreath() {
        val overlay = breathOverlay ?: return
        val now = SystemClock.uptimeMillis()
        val delta = (now - breathLastTime).coerceAtLeast(0L)
        breathLastTime = now
        val step = delta / breathFadeMs.toFloat()
        breathEnvelope = if (breathWanted) (breathEnvelope + step).coerceAtMost(1f)
        else (breathEnvelope - step).coerceAtLeast(0f)
        // 余弦曲线：从 0 缓入到峰值再缓出回 0
        val phase = ((now - breathPhaseStart) % breathPeriodMs) / breathPeriodMs.toFloat()
        val wave = (1f - cos(2.0 * PI * phase).toFloat()) / 2f
        val alpha = (BREATH_MAX_ALPHA * breathEnvelope * wave * 255f).toInt().coerceIn(0, 255)
        val base = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        overlay.setColor(ColorUtils.setAlphaComponent(base, alpha))
        if (!breathWanted && breathEnvelope <= 0f) {
            clearBreath()
        } else {
            breathHandler.postDelayed(breathTick, breathStepMs)
        }
    }

    private fun clearBreath() {
        breathWanted = false
        breathHandler.removeCallbacks(breathTick)
        breathEnvelope = 0f
        breathPhaseStart = 0L
        breathOverlay = null
        foreground = null
    }

    /** 按下压缩到 [PRESSED_SCALE]、松开恢复，时长取主题的 motionDurationShort2；系统动画关闭时直接到位 */
    override fun setPressed(pressed: Boolean) {
        val changed = isPressed != pressed
        super.setPressed(pressed)
        if (!changed) return
        val target = if (pressed) PRESSED_SCALE else 1f
        animate().cancel()
        if (!context.animatorsEnabled()) {
            scaleX = target
            scaleY = target
            return
        }
        animate().scaleX(target).scaleY(target)
            .setDuration(context.motionDuration(com.google.android.material.R.attr.motionDurationShort2, 100))
            .setInterpolator(
                MotionUtils.resolveThemeInterpolator(
                    context, com.google.android.material.R.attr.motionEasingStandardInterpolator,
                    FastOutSlowInInterpolator()
                )
            ).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glassSurface.refresh()
        updateBreathing()
    }

    // 底色之后、图标前画环境色 / sheen，图标之后画亮边与按压光；呼吸叠层是前景，在这些之上
    override fun onDraw(canvas: Canvas) {
        glassSurface.drawBelow(canvas)
        super.onDraw(canvas)
        glassSurface.drawAbove(canvas)
    }

    // 只观察触点（按压光的位置），不消费事件
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        glassSurface.trackTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        glassSurface.cancelPress()
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        clearBreath()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateBreathing()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateBreathing()
    }

    private companion object {
        const val BREATH_MAX_ALPHA = 0.08f

        // 按下时整体缩放到 98%（M3 FAB 按下不抬升，用轻微压缩给反馈）
        const val PRESSED_SCALE = 0.98f
    }
}
