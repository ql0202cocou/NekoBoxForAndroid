package io.nekohasekai.sagernet.widget.glass

import kotlin.math.ceil
import kotlin.math.max

/**
 * 下层抓取的「几何键」：玻璃宿主相对来源视图的偏移（窗口坐标之差，含状态卡片的 translationY）、
 * 宿主尺寸与抓取缩放。键没变，上一次录下来的显示列表就仍然有效；键变了才重录。
 * 纯数据，不依赖 Android 框架（`GlassBackdropGeometryTest`）。
 */
internal data class BackdropKey(
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val scale: Float,
)

/**
 * 模糊效果的参数键：半径（像素，已乘缩放）与饱和度。只有它变了才重建 RenderEffect。
 */
internal data class EffectKey(val blurPx: Float, val saturation: Float)

/** 下层抓取用到的纯计算 */
internal object BackdropGeometry {

    /** 宿主左上角在来源坐标里的位置：宿主窗口坐标减来源窗口坐标 */
    fun offset(hostInWindow: Int, sourceInWindow: Int): Int = hostInWindow - sourceInWindow

    /** 抓取缓冲的边长（像素）：宿主边长乘缩放，向上取整，至少 1 */
    fun captureExtent(extent: Int, scale: Float): Int = max(1, ceil(extent * scale).toInt())

    /**
     * 宿主矩形与来源矩形（都是窗口坐标：左、上、宽、高）是否有公共面积。只贴边不算：
     * 滑到屏幕底边之外的状态卡片与来源恰好贴边，不抓取也不绘制。
     */
    fun overlaps(
        hostLeft: Int, hostTop: Int, hostWidth: Int, hostHeight: Int,
        sourceLeft: Int, sourceTop: Int, sourceWidth: Int, sourceHeight: Int,
    ): Boolean =
        hostWidth > 0 && hostHeight > 0 && sourceWidth > 0 && sourceHeight > 0 &&
            hostLeft < sourceLeft + sourceWidth && sourceLeft < hostLeft + hostWidth &&
            hostTop < sourceTop + sourceHeight && sourceTop < hostTop + hostHeight

    /**
     * 要不要请求重录（由每帧绘制前的检查调用）：几何键变了，且这个键还没有被请求过。
     * 「已请求」的键不重复请求：宿主因故没有真的重绘时，不会每帧再失效一次而形成永动循环。
     */
    fun shouldRequestRecord(current: BackdropKey?, recorded: BackdropKey?, requested: BackdropKey?): Boolean =
        current != null && current != recorded && current != requested
}

/**
 * 「脉冲」的时间窗：页面转场期间每帧都要把来源标脏，窗口长度由转场时长（主题的 motionDuration*）
 * 加一点余量决定。延长只会往后推，不会缩短；纯计算，时间由调用方传入。
 */
internal class PulseWindow {
    private var end = Long.MIN_VALUE

    /** 从 [now] 起再覆盖 [millis] 毫秒（已有更长的窗口则不缩短） */
    fun extend(now: Long, millis: Long) {
        end = max(end, now + millis)
    }

    fun active(now: Long): Boolean = now < end

    fun clear() {
        end = Long.MIN_VALUE
    }
}
