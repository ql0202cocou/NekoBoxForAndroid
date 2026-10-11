package io.nekohasekai.sagernet.ktx

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 系统「动画时长缩放」是否开着（不为 0）。ValueAnimator / ViewPropertyAnimator / Transition 自己服从它；
 * SpringAnimation、Handler 驱动的持续动画不服从，用到之前先问这个，关闭时直接跳到终态或不启动
 */
fun Context.animatorsEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
    else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
