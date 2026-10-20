package io.nekohasekai.sagernet.widget.glass

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import io.nekohasekai.sagernet.ktx.animatorsEnabled

/**
 * BLUR 档的下层来源：MainActivity 登记一次的 `fragment_holder`，加「结构版本号」。
 *
 * 玻璃把来源录进自己的显示列表（见 [GlassBackdrop]）。子视图的 RenderNode 是按引用重放的，所以列表滚动、
 * 子视图属性动画都是实时的，不需要重录；要重录的只有来源的结构变了：子视图增删、页面转场、托管内容重新布局。
 * 这些事件汇到 [markChanged]（版本号加一并让可见的玻璃宿主重绘一次）；转场期间由 [pulse] 在转场时长内每帧调用。
 * 绝不每帧无条件重录：静置、呼吸、速率刷新都不产生任何额外的帧。
 *
 * 来源在 Z 序上位于 Dock、状态卡片、连接按钮之下，且不含它们，所以玻璃抓不到自己。
 * 一个 [GlassTiers] 一个，只在主线程使用。
 */
class GlassBackdropSource {

    /** 来源变化的订阅者（每个 BLUR 表面的 [GlassBackdrop]） */
    internal interface Client {
        fun onSourceChanged()
    }

    /** 当前来源视图；null 表示还没登记（此时 BLUR 一律降到 FROST） */
    var view: View? = null
        private set

    /** 结构版本号：每次 [markChanged] 加一，[GlassBackdrop] 据此判断录下来的列表是否过期 */
    var version = 0
        private set

    val hasSource: Boolean get() = view != null

    private val clients = ArrayList<Client>()
    private var pulseMillis: () -> Long = { 0L }
    private var attachedTo: ViewGroup? = null

    private val pulseWindow = PulseWindow()
    private var pulsing = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (pulseWindow.active(SystemClock.uptimeMillis())) {
                markChanged()
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                pulsing = false
            }
        }
    }

    private val hierarchy = object : ViewGroup.OnHierarchyChangeListener {
        // 新页面挂上：转场开始，脉冲覆盖整个转场；旧页面摘下（转场结束或无转场）：来源的内容变了，标脏一次
        override fun onChildViewAdded(parent: View?, child: View?) = pulse()
        override fun onChildViewRemoved(parent: View?, child: View?) = markChanged()
    }

    /**
     * 登记来源（`fragment_holder`）。[transitionMillis] 给出页面转场的时长（毫秒），由 MainActivity 从
     * 同一个主题属性读出，转场与脉冲窗口因此不会各写一个数字
     */
    fun attach(source: ViewGroup, transitionMillis: () -> Long) {
        stopListening()
        view = source
        pulseMillis = transitionMillis
        attachedTo = source
        source.setOnHierarchyChangeListener(hierarchy)
        markChanged()
    }

    /** 摘掉监听与脉冲（Activity 销毁时） */
    fun release() {
        stopListening()
        view = null
        clients.clear()
    }

    private fun stopListening() {
        attachedTo?.setOnHierarchyChangeListener(null)
        attachedTo = null
        if (pulsing) Choreographer.getInstance().removeFrameCallback(frame)
        pulsing = false
        pulseWindow.clear()
    }

    internal fun addClient(client: Client) {
        if (clients.none { it === client }) clients += client
    }

    internal fun removeClient(client: Client) {
        clients.removeAll { it === client }
    }

    /** 来源的结构变了：版本号加一，让各表面在下一次绘制时重录 */
    fun markChanged() {
        version++
        for (client in clients.toTypedArray()) client.onSourceChanged()
    }

    /**
     * 页面转场开始：立刻标脏一次，并在转场时长（加 [PULSE_SLACK_MS] 余量）内每帧再标脏。
     * 系统动画关闭时转场不播，只标脏一次。重复调用只会把窗口往后推
     */
    fun pulse() {
        markChanged()
        val source = view ?: return
        if (!source.context.animatorsEnabled()) return
        pulseWindow.extend(SystemClock.uptimeMillis(), pulseMillis() + PULSE_SLACK_MS)
        if (!pulsing) {
            pulsing = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private companion object {
        // 转场起步有一两帧的延迟、结束时最后一帧要画完整：窗口比转场时长多留的余量（不是动画时长）
        const val PULSE_SLACK_MS = 50L
    }
}
