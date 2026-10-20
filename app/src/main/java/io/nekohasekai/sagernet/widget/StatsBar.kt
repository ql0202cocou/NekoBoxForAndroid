package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.card.MaterialCardView
import kotlin.math.ceil
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
 * 两行：速率行（上行、下行并排，数字用等宽数字右对齐、单位用次要色）与状态行（状态点 + 一段状态文字）。
 * 速率高频刷新，只改文字且数字有固定宽度的槽位，不对整张卡片做任何动画。
 *
 * 只有已连接时允许出现（[allowShow]）；已连接时列表向下滚动收起、向上滚动出现。
 * 收起时整张卡片移到屏幕底边之下（从 Dock 后面滑出），见 [Behavior]。
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs), CoordinatorLayout.AttachedBehavior {
    // 子视图随 layout_main 一起膨胀，首次使用时才查（此时子视图早已就位）
    private val statusText by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.status) }
    private val statusDot by lazy(LazyThreadSafetyMode.NONE) { findViewById<View>(R.id.status_dot) }
    private val txNumber by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.tx_number) }
    private val txUnit by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.tx_unit) }
    private val rxNumber by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.rx_number) }
    private val rxUnit by lazy(LazyThreadSafetyMode.NONE) { findViewById<TextView>(R.id.rx_unit) }
    private val behavior = Behavior { allowShow }

    /** 只有已连接时为真，由 [changeState] 维护；为假时卡片保持收起 */
    var allowShow = false
        private set

    /** 卡片当前是否展开（已连接且没有被滚动收起） */
    val isExpanded get() = allowShow && behavior.isScrolledUp

    init {
        // 初始收起：此时还没布局，位移由 Behavior.onLayoutChild 补上
        behavior.slideDown(this, false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<TextView>(R.id.tx_arrow).text = ARROW_TX
        findViewById<TextView>(R.id.rx_arrow).text = ARROW_RX
        // 每个速率列是一个无障碍节点，读屏时读出整段（如「▲ 1.30 GB/s」），与拆分前一致
        for (id in listOf(R.id.tx_column, R.id.rx_column)) {
            ViewCompat.setScreenReaderFocusable(findViewById(id), true)
        }
        // 数字槽位的最小宽度按当前字体（含字号缩放与 tnum）实测，容得下 3 位数字加小数点（999 / 9.99 / 99.9），速率变化时不抖
        for (number in listOf(txNumber, rxNumber)) {
            number.minWidth = ceil(number.paint.measureText("000.")).toInt()
        }
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

    private fun performShow(animate: Boolean) = behavior.slideUp(this, animate)
    private fun performHide(animate: Boolean) {
        // 触摸浏览（TalkBack）开启时父类的 slideDown 直接返回，让滚动收不起卡片；断开连接时卡片
        // 必须收起（否则一直显示「未连接」并盖住列表底部），这里临时关掉这条豁免。之后重新连接
        // 照常经 slideUp 展开，滚动收起在触摸浏览下仍不生效
        behavior.disableOnTouchExploration(false)
        behavior.slideDown(this, animate)
        behavior.disableOnTouchExploration(true)
    }

    /** 状态点的样子：Ok 实心 primary；Pending（连接中、测试中）空心环；Error 实心 error；Idle 实心 onSurfaceVariant */
    private enum class Dot { Ok, Pending, Error, Idle }

    private fun setStatus(text: CharSequence, dot: Dot) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
        val attr = when (dot) {
            Dot.Ok -> androidx.appcompat.R.attr.colorPrimary
            Dot.Pending, Dot.Idle -> com.google.android.material.R.attr.colorOnSurfaceVariant
            Dot.Error -> androidx.appcompat.R.attr.colorError
        }
        val color = context.getColorAttr(attr)
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (dot == Dot.Pending) {
                setColor(0)
                setStroke(resources.getDimensionPixelSize(R.dimen.stats_dot_ring), color)
            } else {
                setColor(color)
            }
        }
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
                setStatus(app.getText(R.string.vpn_connected), Dot.Ok)
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
                ),
                if (state == BaseService.State.Connecting || state == BaseService.State.Stopping) Dot.Pending
                else Dot.Idle
            )
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        showRate(txRate, ARROW_TX, R.id.tx_column, txNumber, txUnit)
        showRate(rxRate, ARROW_RX, R.id.rx_column, rxNumber, rxUnit)
    }

    // 文字没变就不写（setText 即使相同也会触发重新测量），速率高频刷新时省掉大部分
    private fun showRate(rate: Long, arrow: String, @IdRes column: Int, number: TextView, unit: TextView) {
        val parts = splitFileSize(Formatter.formatFileSize(context, rate))
        // 单位沿用 R.string.speed（%s/s），本地化不变
        val unitText = if (parts.unit.isEmpty()) "" else context.getString(R.string.speed, parts.unit)
        if (number.text == parts.number && unit.text == unitText) return
        number.text = parts.number
        unit.text = unitText
        // 整列的朗读文字与可见文字一致（拆分前是「▲ 1.30 GB/s」），只在文字真的变了时更新
        findViewById<View>(column).contentDescription =
            if (unitText.isEmpty()) "$arrow ${parts.number}" else "$arrow ${parts.number} $unitText"
    }

    // 连通性测试只在状态卡片上显示结果（成功 / 失败文案与失败提示），是页面内的读取加显示：
    // 跟 Activity 的作用域走，页面销毁即取消，销毁后不再碰视图。测试本身是一次只读探测，没有需要做完的写入
    private var testJob: Job? = null

    fun testConnection() {
        val activity = context.unwrapTo<MainActivity>()
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing), Dot.Pending)
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
                    ),
                    Dot.Ok
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e.toString())
                isEnabled = true
                // 失败要落回失败文案：重置成「测试中」会让状态栏永久停在错误状态
                setStatus(app.getString(R.string.connection_test_error, e.readableMessage), Dot.Error)

                activity.snackbar(
                    app.getString(
                        R.string.connection_test_error, e.readableMessage
                    )
                ).show()
            }
        }
    }

    private companion object {
        const val ARROW_TX = "▲"
        const val ARROW_RX = "▼"
    }
}
