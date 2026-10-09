package moe.matsuri.nb4a.ui

import android.content.Context
import android.graphics.Color
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

    init {
        // 摘要显示当前主题名；存的可能是被归并的旧编号，nameRes 按代表取
        setSummaryProvider { context.getString(Theme.nameRes(getPersistedInt(0))) }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout

        // holder 会被复用：每次绑定都重建右侧色点，不用一次性标记，否则重新绑定后不会再加回来
        widgetFrame.removeAllViews()
        // 当前主题的种子色，与选色网格一致（M3 配色下 colorPrimary 不是种子色本身）
        val seed = ContextCompat.getColor(
            context, SWATCH_COLORS.getValue(Theme.canonicalTheme(getPersistedInt(0)))
        )
        val factor = context.resources.displayMetrics.density
        widgetFrame.addView(ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams((48 * factor).roundToInt(), (48 * factor).roundToInt())
            setPadding((8 * factor).roundToInt())
            setImageDrawable(seedCircle(seed))
            // 装饰：主题名称已在摘要里
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        widgetFrame.visibility = View.VISIBLE
    }

    // 种子色圆，外圈一道 colorOutline 描边：白色在浅色底、黑色在深色底上才看得见
    private fun seedCircle(color: Int): GradientDrawable {
        val factor = context.resources.displayMetrics.density
        // 主题没有 colorOutline 时退回次要文字色
        val outline = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutline,
            MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY)
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(factor.roundToInt().coerceAtLeast(1), outline)
        }
    }

    // 选色网格的一格：中心是带描边的种子色圆；选中的那格中心再叠一个对比色的勾
    private fun getSwatchView(color: Int, selected: Boolean): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (64 * factor).roundToInt()
        val padding = (10 * factor).roundToInt()
        val circle = seedCircle(color)
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
                    contentDescription = context.getString(Theme.nameRes(themeId))
                    setOnClickListener {
                        // 遵循 Preference 契约：监听器接受才持久化；写入的总是代表编号
                        if (callChangeListener(themeId)) {
                            persistInt(themeId)
                            // persistInt 不会通知刷新，摘要要跟着变
                            notifyChanged()
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
