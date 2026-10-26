package io.nekohasekai.sagernet.ui

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour

/**
 * 测速与日志的语义色。字面色（res/color 的 status_* / log_*，亮暗各一套）在显示前向主题的 primary 和谐化
 * （material 的 Blend.harmonize：色相向 primary 靠最多 15°，彩度与明度不变，同 FlClash 的做法）；
 * 白 / 黑主题是单色方案、primary 没有色相，不和谐化。
 * 延迟分三档：不超过 [SLOW_THRESHOLD_MS] 用可用色、更慢的用「慢」色、失败用错误色。
 */
object StatusColors {

    /** 延迟超过这个值（ms）算「慢」 */
    const val SLOW_THRESHOLD_MS = 600

    // 主题是否单色（白 / 黑）：看 primary 的饱和度，不读设置
    private fun Context.isMonochromeTheme(): Boolean {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(getColorAttr(androidx.appcompat.R.attr.colorPrimary), hsl)
        return hsl[1] < 0.05f
    }

    /** 语义色资源经和谐化后的颜色 */
    @ColorInt
    fun Context.semanticColor(@ColorRes res: Int): Int {
        val color = getColour(res)
        return if (isMonochromeTheme()) color else MaterialColors.harmonizeWithPrimary(this, color)
    }

    /** 测速成功时按延迟取色 */
    @ColorInt
    fun Context.latencyColor(ping: Int): Int =
        semanticColor(if (ping > SLOW_THRESHOLD_MS) R.color.status_slow else R.color.status_ok)

    /** 测速失败 / 不可用 */
    @ColorInt
    fun Context.statusErrorColor(): Int = semanticColor(R.color.status_error)
}
