package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(
    context, attrs, defStyle
) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout

        // holder 会被复用：每次绑定都重建右侧色点，不用一次性标记，否则重新绑定后不会再加回来
        widgetFrame.removeAllViews()
        widgetFrame.addView(
            getNekoImageViewAtColor(
                context.getColorAttr(androidx.appcompat.R.attr.colorPrimary),
                48,
                0
            )
        )
        widgetFrame.visibility = View.VISIBLE
    }

    fun getNekoImageViewAtColor(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        // dp 换算成像素
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageDrawable(getNekoAtColor(resources, color))
        }
    }

    fun getNekoAtColor(res: Resources, color: Int): Drawable {
        val neko = ResourcesCompat.getDrawable(
            res,
            R.drawable.ic_baseline_fiber_manual_record_24,
            null
        )!!
        DrawableCompat.setTint(neko.mutate(), color)
        return neko
    }

    // 选色网格的一格：中心是种子色圆，外圈一道描边，白色在浅色对话框、黑色在深色对话框上
    // 才看得见；选中的那格中心再叠一个对比色的勾
    private fun getSwatchView(color: Int, selected: Boolean): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (64 * factor).roundToInt()
        val padding = (10 * factor).roundToInt()
        // M2 主题不设置 colorOutline，退回次要文字色；换 M3 后自动取 colorOutline
        val outline = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutline,
            MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY)
        )
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(factor.roundToInt().coerceAtLeast(1), outline)
        }
        val drawable = if (selected) {
            val check = ResourcesCompat.getDrawable(
                context.resources, R.drawable.ic_action_done, context.theme
            )!!.mutate()
            // 勾的颜色取黑白里与种子色对比度更高的那个
            val checkColor =
                if (ColorUtils.calculateContrast(Color.WHITE, color) >= ColorUtils.calculateContrast(
                        Color.BLACK, color
                    )
                ) Color.WHITE else Color.BLACK
            DrawableCompat.setTint(check, checkColor)
            val inset = (size - 2 * padding) / 4
            LayerDrawable(arrayOf(circle, check)).apply {
                setLayerInset(1, inset, inset, inset, inset)
            }
        } else {
            circle
        }
        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(padding)
            setImageDrawable(drawable)
            isSelected = selected
        }
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog

        // 存的可能是被归并的旧编号，选中态落在它的代表上
        val current = Theme.canonicalTheme(getPersistedInt(0))

        val grid = GridLayout(context).apply {
            // 64dp × 6 放不进 360dp 宽屏上的对话框，排成 3 列两行
            columnCount = 3

            for (themeId in Theme.KEPT) {
                val color = ContextCompat.getColor(context, SWATCH_COLORS.getValue(themeId))
                val view = getSwatchView(color, themeId == current).apply {
                    setOnClickListener {
                        // 遵循 Preference 契约：监听器接受才持久化；写入的总是代表编号
                        if (callChangeListener(themeId)) {
                            persistInt(themeId)
                        }
                        dialog.dismiss()
                    }
                }
                addView(view)
            }

        }

        dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(grid)
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        // 网格每格的种子色，按 Theme.KEPT 的顺序取
        private val SWATCH_COLORS = mapOf(
            Theme.PINK_SSR to R.color.color_pink_ssr,
            Theme.ORANGE to R.color.material_orange_500,
            Theme.BLUE to R.color.material_blue_500,
            Theme.GREEN to R.color.material_green_500,
            Theme.GREY to R.color.white,
            Theme.BLACK to R.color.black,
        )
    }
}
