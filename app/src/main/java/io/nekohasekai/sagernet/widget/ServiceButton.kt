package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.PointerIcon
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.BaseProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.animatorsEnabled
import io.nekohasekai.sagernet.ktx.themeInteger
import io.nekohasekai.sagernet.ktx.unwrapTo
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
        // 不播动画、直接落到这个状态时对外圈做的事；null 表示同 onStart
        private val onJump: (BaseProgressIndicator<*>.() -> Unit)? = null,
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

        // 图标直接停在终帧：先 start 建好动画集，stop 才会跳到终帧
        fun jump() {
            setImageDrawable(icon)
            icon.start()
            icon.stop()
            progress.(onJump ?: onStart)()
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
        // 不播动画时不画「补满再收起」的外圈，直接收起
        AnimatedState(R.drawable.ic_service_connected, onJump = { hideProgress() }) {
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
        // 窗口不可见（主界面在后台，如用快捷方式启动）时不播：图标动画不跑、结束回调不来，排在后面的状态
        // 一直起不了头，「连接中」延时显示的外圈也就取消不掉，回到前台还要把过时的变形补播一遍
        val animated = animate && isAttachedToWindow && windowVisibility == View.VISIBLE && isShown
        when (state) {
            BaseService.State.Connecting -> changeState(iconConnecting, animated)
            BaseService.State.Connected -> changeState(iconConnected, animated)
            // 变形动画只从已连接的图标开始；此时 checked 还是上一个状态的值。
            // 不能用 ServiceRegistry.state：SagerConnection 在 binder 线程先写入新状态再派发回调，读到的已是新值
            BaseService.State.Stopping -> changeState(iconStopping, animated && checked)
            else -> changeState(iconStopped, animated)
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
            icon.jump()
        }
    }

    // ---- 已连接时的「呼吸」叠层 ----
    // 叠层是与按钮同形（圆）的前景，颜色 colorOnPrimaryContainer，不透明度在 0 与 14% 之间往返，
    // 周期 1400ms。为免整屏每帧重绘，用 Handler 按 66ms 步进（约 15fps），不用无限循环的 ValueAnimator；
    // 进出已连接态时整体 300ms 淡入淡出。
    // 离开已连接态、脱离窗口、窗口或视图不可见、系统动画被关闭（动画时长缩放为 0）时都停，不留叠层

    // 三个时长取自主题（serviceBreath*），回退值与主题定义一致
    private val breathPeriodMs = context.themeInteger(R.attr.serviceBreathPeriod, 1400).toLong()
    private val breathStepMs = context.themeInteger(R.attr.serviceBreathStep, 66).toLong()
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBreathing()
    }

    override fun onDetachedFromWindow() {
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
        const val BREATH_MAX_ALPHA = 0.14f
    }
}
