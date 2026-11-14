package io.nekohasekai.sagernet.widget

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
     * 卡片收放进度：0 = 收起，1 = 展开，滑动中取卡片的实际位置。Snackbar 用它跟随卡片；列表留白独立于动画进度。
     */
    val clearanceFraction get() = behavior.shownFraction(this)

    /**
     * 卡片滑动的每一帧和到位时的回调；只有 settled 为真时宿主才更新列表目标留白。
     * 动画被反向打断不算到位，避免在两段动画之间释放留白。
     */
    var onClearanceChanged: ((settled: Boolean) -> Unit)? = null

    /** 只有已连接时为真，由 [changeState] 维护；为假时卡片保持收起 */
    var allowShow = false
        private set

    /** 卡片当前是否展开（已连接且没有被滚动收起） */
    val isExpanded get() = allowShow && behavior.isScrolledUp

    init {
        // 初始收起：此时还没布局，位移由 Behavior.onLayoutChild 补上
        behavior.slideDown(this, false)
    }

    override fun onDetachedFromWindow() {
        // 离开窗口时停止动画并落在当前状态的终点，重新挂回时不能保留半途的位置。
        behavior.stopAnimation(this)
        onClearanceChanged?.invoke(true)
        super.onDetachedFromWindow()
    }

    override fun getBehavior(): Behavior = behavior

    class Behavior(val getAllowShow: () -> Boolean) : HideBottomViewOnScrollBehavior<StatsBar>() {

        private var sliding = false

        private val StatsBar.host get() = context.unwrapTo<MainContentHostOwner>().contentHost

        private fun hiddenY(child: StatsBar): Float =
            (child.measuredHeight + (child.layoutParams as MarginLayoutParams).bottomMargin).toFloat()

        /** 卡片露出的比例：静止时由状态决定，滑动中按实际位移算 */
        internal fun shownFraction(child: StatsBar): Float {
            if (!sliding) return if (isScrolledUp) 1f else 0f
            val hidden = hiddenY(child)
            return if (hidden > 0f) (1f - child.translationY / hidden).coerceIn(0f, 1f) else 0f
        }

        // 进度和到位都取自父类驱动卡片的同一个动画；被反向打断时结束动作不会执行，不算到位。
        private fun followAnimation(child: StatsBar, animate: Boolean) {
            sliding = animate && child.context.animatorsEnabled()
            if (!sliding) {
                child.onClearanceChanged?.invoke(true)
                return
            }
            child.animate()
                .setUpdateListener { if (sliding) child.onClearanceChanged?.invoke(false) }
                .withEndAction {
                    sliding = false
                    // 动画期间高度或插入区可能变化，收起完成后才校准到新的底边。
                    if (isScrolledDown) child.translationY = hiddenY(child)
                    child.onClearanceChanged?.invoke(true)
                }
        }

        internal fun stopAnimation(child: StatsBar) {
            child.animate().cancel()
            sliding = false
            child.translationY = if (isScrolledDown) hiddenY(child) else 0f
        }

        override fun onLayoutChild(
            parent: CoordinatorLayout, child: StatsBar, layoutDirection: Int,
        ): Boolean {
            val handled = super.onLayoutChild(parent, child, layoutDirection)
            // 初次布局和收起后的尺寸变化需要校准位置；动画中不能把当前位置改成终点。
            if (isScrolledDown && !sliding) {
                child.translationY = hiddenY(child)
            }
            return handled
        }

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: StatsBar, directTargetChild: View,
            target: View, nestedScrollAxes: Int, type: Int,
        ): Boolean {
            val accepted = super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, nestedScrollAxes, type)
            if (accepted) child.host.scrollStarted(target, type)
            return accepted
        }

        override fun onStopNestedScroll(coordinatorLayout: CoordinatorLayout, child: StatsBar, target: View, type: Int) {
            super.onStopNestedScroll(coordinatorLayout, child, target, type)
            child.host.scrollStopped(target, type)
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
            if (!getAllowShow() || isScrolledUp) return
            super.slideUp(child, animate)
            followAnimation(child, animate)
        }

        override fun slideDown(child: StatsBar, animate: Boolean) {
            if (isScrolledDown) return
            super.slideDown(child, animate)
            // 触摸探索模式可能拒绝收起，只有实际切换了状态才跟踪动画。
            if (isScrolledDown) followAnimation(child, animate)
        }
    }

    private fun performShow(animate: Boolean) = behavior.slideUp(this, animate)

    private fun performHide(animate: Boolean) {
        // 触摸浏览（TalkBack）开启时父类的 slideDown 直接返回，让滚动收不起卡片；断开连接时卡片
        // 必须收起（否则一直显示「未连接」并盖住列表底部），这里临时关掉这条豁免。之后重新连接
        // 照常经 slideUp 展开，滚动收起在触摸浏览下仍不生效
        behavior.disableOnTouchExploration(false)
        behavior.slideDown(this, animate)
        behavior.disableOnTouchExploration(true)
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
