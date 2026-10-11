package io.nekohasekai.sagernet.ktx

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import com.google.android.material.motion.MotionUtils
import io.nekohasekai.sagernet.R

/**
 * 系统「动画时长缩放」是否开着（不为 0）。ValueAnimator / ViewPropertyAnimator / Transition 自己服从它；
 * SpringAnimation、Handler 驱动的持续动画不服从，用到之前先问这个，关闭时直接跳到终态或不启动
 */
fun Context.animatorsEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
    else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** 主题里的动效时长（ms），如 motionDurationMedium2 */
fun Context.motionDuration(@AttrRes attr: Int, default: Int): Long =
    MotionUtils.resolveThemeDuration(this, attr, default).toLong()

/**
 * 空状态占位的出现 / 消失。出现：透明度 0 到 1 加缩放 0.92 到 1，300ms（motionDurationMedium2），
 * 插值器取主题的 emphasized decelerate；消失：透明度到 0，150ms（motionDurationShort3）后 GONE。
 * 重复设同一个目标是空操作，所以可以在每次列表变化时无条件调用；系统动画关闭时直接切换
 */
fun View.setPlaceholderVisible(visible: Boolean) {
    val current = getTag(R.id.placeholder_target) as? Boolean ?: isVisible
    if (current == visible) return
    setTag(R.id.placeholder_target, visible)
    animate().cancel()
    if (!context.animatorsEnabled()) {
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        visibility = if (visible) View.VISIBLE else View.GONE
        return
    }
    if (visible) {
        alpha = 0f
        scaleX = PLACEHOLDER_START_SCALE
        scaleY = PLACEHOLDER_START_SCALE
        visibility = View.VISIBLE
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(context.motionDuration(com.google.android.material.R.attr.motionDurationMedium2, 300))
            .setInterpolator(
                MotionUtils.resolveThemeInterpolator(
                    context,
                    com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator,
                    android.view.animation.DecelerateInterpolator()
                )
            )
            .withEndAction(null)
            .start()
    } else {
        animate().alpha(0f)
            .setDuration(context.motionDuration(com.google.android.material.R.attr.motionDurationShort3, 150))
            .setInterpolator(null)
            .withEndAction {
                // 淡出期间又被要求出现时，目标已变，不再收起
                if (getTag(R.id.placeholder_target) == false) {
                    visibility = View.GONE
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            .start()
    }
}

private const val PLACEHOLDER_START_SCALE = 0.92f
