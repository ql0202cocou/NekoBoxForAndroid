package io.nekohasekai.sagernet.widget.glass

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.graphics.withSave
import com.google.android.material.color.MaterialColors

/**
 * BLUR 档的下层抓取（API 31+），每个 BLUR 表面一个。
 *
 * 做法（S1 spike 验证过，见 plan.md D17）：宿主自己持有一个 [RenderNode]，把来源视图（`fragment_holder`，
 * 在 Z 序上位于三个悬浮层之下且不含它们）重新画进去，再给节点加模糊 + 饱和度的 [RenderEffect]，
 * 宿主在 `draw()` 里、`super.draw()` 之前把节点画出来。于是顺序是：系统 elevation 阴影（宿主的 RenderNode 属性）、
 * 模糊后的下层、宿主半透明的底色、环境色、内容、高光。
 *
 * - 半分辨率（[GlassRecipes.BLUR_CAPTURE_SCALE]）录制，画出时放大回来，模糊本身掩盖降采样；不外扩边距，边缘用 CLAMP；
 * - `source.draw(canvas)` 在硬件画布上，子视图走各自的 RenderNode、按引用重放：列表滚动、子视图的属性动画
 *   都是实时的，**不需要重录**。只在两种情况下重录：[BackdropKey]（偏移、尺寸、缩放）变了，或来源的结构版本变了
 *   （[GlassBackdropSource.markChanged]：子视图增删、页面转场脉冲、托管内容重新布局）；
 * - 几何键的变化有两个来源：布局变化会让宿主重绘，`draw()` 里自然发现；纯属性变化（状态卡片滑入 / 滑出时的
 *   translationY，ViewPropertyAnimator 直接写 RenderNode，不触发宿主重绘）靠 [onPreDraw]：只在本来就要画的帧里
 *   比较几何键，键变了才失效宿主一次（已请求过的键不重复请求）。**不是**每帧无条件失效；静置时不产生任何额外的帧；
 * - 不可见（隐藏、滑出屏幕、与来源没有公共面积）时不录制也不画，并丢掉显示列表；
 * - 效果参数（模糊半径、饱和度）没变就不重建 RenderEffect。
 *
 * 任何一步抛异常都经 [onFailure] 报告，宿主据此降到完整的 FROST 配方。
 */
@RequiresApi(31)
internal class GlassBackdrop(
    private val host: View,
    private val source: GlassBackdropSource,
    private val onFailure: () -> Unit,
) : GlassBackdropSource.Client, ViewTreeObserver.OnPreDrawListener {

    private val node = RenderNode("glass-backdrop")
    private var recordedKey: BackdropKey? = null
    private var recordedVersion = -1
    private var requestedKey: BackdropKey? = null
    private var effectKey: EffectKey? = null

    private var attached = false
    private var observer: ViewTreeObserver? = null

    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    /** 宿主挂上窗口后开始订阅；可重复调用 */
    fun attach() {
        if (attached || !host.isAttachedToWindow) return
        attached = true
        source.addClient(this)
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    /** 宿主脱离窗口 / 档位降级：摘掉监听、丢掉显示列表，下次挂上重新录制 */
    fun detach() {
        if (attached) {
            attached = false
            source.removeClient(this)
            observer?.let { if (it.isAlive) it.removeOnPreDrawListener(this) }
            observer = null
        }
        discard()
    }

    private fun discard() {
        node.discardDisplayList()
        recordedKey = null
        recordedVersion = -1
        requestedKey = null
        effectKey = null
    }

    override fun onSourceChanged() {
        // 隐藏的宿主（未连接的状态卡片、滑出屏幕）不被唤醒
        if (attached && currentKey() != null) host.invalidate()
    }

    override fun onPreDraw(): Boolean {
        val key = currentKey()
        if (BackdropGeometry.shouldRequestRecord(key, recordedKey, requestedKey)) {
            requestedKey = key
            host.invalidate()
        }
        return true
    }

    /**
     * 此刻的几何键；宿主不可见（隐藏、与来源没有公共面积）或尚未布局时为 null。
     * 窗口坐标里取位置，已含 translationY 等变换
     */
    private fun currentKey(): BackdropKey? {
        val src = source.view ?: return null
        if (!host.isAttachedToWindow || !src.isAttachedToWindow || !host.isShown) return null
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return null
        host.getLocationInWindow(hostLocation)
        src.getLocationInWindow(sourceLocation)
        if (!BackdropGeometry.overlaps(
                hostLocation[0], hostLocation[1], w, h,
                sourceLocation[0], sourceLocation[1], src.width, src.height,
            )
        ) return null
        return BackdropKey(
            offsetX = BackdropGeometry.offset(hostLocation[0], sourceLocation[0]),
            offsetY = BackdropGeometry.offset(hostLocation[1], sourceLocation[1]),
            width = w,
            height = h,
            scale = GlassRecipes.BLUR_CAPTURE_SCALE,
        )
    }

    /**
     * 宿主的 `draw()` 里、`super.draw()` 之前调用：必要时重录，再按 [clip]（宿主形状）裁剪画出模糊后的下层。
     * 返回是否画了；非硬件画布（截图、软件层）不画，宿主仍按本档位的底色画（那一帧没有下层，不改档位）
     */
    fun draw(canvas: Canvas, clip: Path, recipe: GlassRecipe): Boolean {
        if (!canvas.isHardwareAccelerated) return false
        val src = source.view
        val key = currentKey()
        if (src == null || key == null) {
            // 隐藏：不录制也不画，丢掉显示列表（不留残影，也省内存）
            discard()
            return false
        }
        try {
            if (key != recordedKey || recordedVersion != source.version) record(src, key)
            applyEffect(recipe)
            val captureW = BackdropGeometry.captureExtent(key.width, key.scale)
            val captureH = BackdropGeometry.captureExtent(key.height, key.scale)
            canvas.withSave {
                clipPath(clip)
                scale(key.width / captureW.toFloat(), key.height / captureH.toFloat())
                drawRenderNode(node)
            }
        } catch (e: RuntimeException) {
            discard()
            onFailure()
            return false
        }
        return true
    }

    private fun record(src: View, key: BackdropKey) {
        val captureW = BackdropGeometry.captureExtent(key.width, key.scale)
        val captureH = BackdropGeometry.captureExtent(key.height, key.scale)
        node.setPosition(0, 0, captureW, captureH)
        val canvas = node.beginRecording(captureW, captureH)
        try {
            canvas.scale(captureW / key.width.toFloat(), captureH / key.height.toFloat())
            canvas.translate(-key.offsetX.toFloat(), -key.offsetY.toFloat())
            // 页面底色：来源自己也画同样的背景，这里兜住转场中来源某一层半透明的情况
            canvas.drawColor(MaterialColors.getColor(host, android.R.attr.colorBackground))
            src.draw(canvas)
        } finally {
            node.endRecording()
        }
        recordedKey = key
        recordedVersion = source.version
        requestedKey = null
    }

    private fun applyEffect(recipe: GlassRecipe) {
        val density = host.resources.displayMetrics.density
        val key = EffectKey(recipe.blurRadiusDp * density * GlassRecipes.BLUR_CAPTURE_SCALE, recipe.saturation)
        if (key == effectKey) return
        var effect = RenderEffect.createBlurEffect(key.blurPx, key.blurPx, Shader.TileMode.CLAMP)
        if (key.saturation != 1f) {
            val matrix = ColorMatrix().apply { setSaturation(key.saturation) }
            effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), effect)
        }
        node.setRenderEffect(effect)
        effectKey = key
    }
}
