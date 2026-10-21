package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.motion.MotionUtils
import android.view.animation.AccelerateDecelerateInterpolator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面的状态卡片：悬浮在 Dock 上方，显示上下行速率与连接状态，点按测试连通性。
 *
 * 只有已连接时允许出现（[allowShow]）；已连接时列表向下滚动收起、向上滚动出现。
 * 收起时整张卡片移到屏幕底边之下（从 Dock 后面滑出），见 [Behavior]。
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs), CoordinatorLayout.AttachedBehavior {
    // 子视图随 layout_main 一起膨胀，首次使用时才查（此时子视图早已就位）
    private val statusText by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.status) }
    private val txText by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.tx) }
    private val rxText by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.rx) }
    private val behavior = Behavior { allowShow }

    /**
     * 卡片给列表底部留白的贡献比例：0 = 不占（未连接、被滚动收起），1 = 全占（卡片展开）。
     * 跟着卡片的滑入 / 滑出走（同一档时长与缓动），列表底部留白逐帧跟随，不再在连接 / 断开 / 收起时突跳
     */
    var clearanceFraction = 0f
        private set

    /** [clearanceFraction] 每次变化（逐帧）的回调，宿主据此重算列表底部留白 */
    var onClearanceChanged: (() -> Unit)? = null

    private var clearanceAnimator: ValueAnimator? = null

    // 当前这次滑动是否带动画：由 performShow / performHide 设，滚动触发的滑动恒带动画
    private var slideAnimated = true

    /** 只有已连接时为真，由 [changeState] 维护；为假时卡片保持收起 */
    var allowShow = false
        private set

    /** 卡片当前是否展开（已连接且没有被滚动收起） */
    val isExpanded get() = allowShow && behavior.isScrolledUp

    init {
        // 初始收起：此时还没布局，位移由 Behavior.onLayoutChild 补上
        behavior.slideDown(this, false)
        // 卡片的展开 / 收起（连接、断开、滚动都经父类的状态变化）带动留白；监听在初始收起之后才接上
        behavior.addOnScrollStateChangedListener { _, state ->
            followSlide(expanded = state == HideBottomViewOnScrollBehavior.STATE_SCROLLED_UP)
        }
    }

    private fun followSlide(expanded: Boolean) {
        val target = if (expanded) 1f else 0f
        clearanceAnimator?.cancel()
        clearanceAnimator = null
        if (!slideAnimated || !context.animatorsEnabled() || target == clearanceFraction) {
            setClearanceFraction(target)
            return
        }
        // 时长与缓动取父类滑动用的同一组主题属性（Material 默认：进 500ms、出 400ms）
        val duration = context.motionDuration(
            if (expanded) com.google.android.material.R.attr.motionDurationLong2
            else com.google.android.material.R.attr.motionDurationMedium4,
            if (expanded) 500 else 400,
        )
        clearanceAnimator = ValueAnimator.ofFloat(clearanceFraction, target).apply {
            this.duration = duration
            interpolator = MotionUtils.resolveThemeInterpolator(
                context,
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                AccelerateDecelerateInterpolator(),
            )
            addUpdateListener { setClearanceFraction(it.animatedValue as Float) }
            start()
        }
    }

    private fun setClearanceFraction(value: Float) {
        if (value == clearanceFraction) return
        clearanceFraction = value
        onClearanceChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        // 动画里的更新回调拿着本视图，离开窗口就停；终值在重新连接 / 重建后由状态变化补上
        clearanceAnimator?.cancel()
        clearanceAnimator = null
        super.onDetachedFromWindow()
    }

    override fun getBehavior(): Behavior = behavior

    class Behavior(val getAllowShow: () -> Boolean) : HideBottomViewOnScrollBehavior<StatsBar>() {

        override fun onLayoutChild(
            parent: CoordinatorLayout, child: StatsBar, layoutDirection: Int,
        ): Boolean {
            val handled = super.onLayoutChild(parent, child, layoutDirection)
            // 父类只在滑动时设位移：收起状态下高度或底边距变了（首次布局、插入区变化），
            // 这里把卡片重新放到底边之下，之后的滑出动画才从正确位置开始
            if (isScrolledDown) {
                val lp = child.layoutParams as MarginLayoutParams
                child.translationY = (child.measuredHeight + lp.bottomMargin).toFloat()
            }
            return handled
        }

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: StatsBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            // 列表到头后的继续滚动（未消费部分）也算：短列表上同样能收起 / 拉出
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: StatsBar, animate: Boolean) {
            if (!getAllowShow()) return
            super.slideUp(child, animate)
        }
    }

    private fun performShow(animate: Boolean) {
        slideAnimated = animate
        behavior.slideUp(this, animate)
        slideAnimated = true
    }

    private fun performHide(animate: Boolean) {
        slideAnimated = animate
        // 触摸浏览（TalkBack）开启时父类的 slideDown 直接返回，让滚动收不起卡片；断开连接时卡片
        // 必须收起（否则一直显示「未连接」并盖住列表底部），这里临时关掉这条豁免。之后重新连接
        // 照常经 slideUp 展开，滚动收起在触摸浏览下仍不生效
        behavior.disableOnTouchExploration(false)
        behavior.slideDown(this, animate)
        behavior.disableOnTouchExploration(true)
        slideAnimated = true
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    /** [animate] 为假时（启动、重建后首次拿到服务状态）直接到位，不播滑动动画 */
    fun changeState(state: BaseService.State, animate: Boolean) {
        val activity = context.unwrapTo<MainActivity>()
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.lifecycle.withStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { allowShow = it }) {
            postWhenStarted {
                if (allowShow) performShow(animate)
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            postWhenStarted {
                performHide(animate)
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    // 连通性测试只在状态卡片上显示结果（成功 / 失败文案与失败提示），是页面内的读取加显示：
    // 跟 Activity 的作用域走，页面销毁即取消，销毁后不再碰视图。测试本身是一次只读探测，没有需要做完的写入
    private var testJob: Job? = null

    fun testConnection() {
        val activity = context.unwrapTo<MainActivity>()
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        testJob?.cancel()
        testJob = activity.lifecycleScope.launch {
            try {
                val elapsed = withContext(Dispatchers.Default) { activity.urlTest() }
                isEnabled = true
                setStatus(
                    app.getString(
                        if (DataStore.connectionTestURL.startsWith("https://")) {
                            R.string.connection_test_available
                        } else {
                            R.string.connection_test_available_http
                        }, elapsed
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e.toString())
                isEnabled = true
                // 失败要落回失败文案：重置成「测试中」会让状态栏永久停在错误状态
                setStatus(app.getString(R.string.connection_test_error, e.readableMessage))

                activity.snackbar(
                    app.getString(
                        R.string.connection_test_error, e.readableMessage
                    )
                ).show()
            }
        }
    }

}
