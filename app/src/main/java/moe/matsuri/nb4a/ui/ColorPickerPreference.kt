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
import android.widget.ScrollView
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
        // 摘要按原编号显示主题名，旧编号恢复为各自的配色
        setSummaryProvider { context.getString(Theme.nameRes(getPersistedInt(0))) }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout

        // holder 会被复用：每次绑定都重建右侧色点，不用一次性标记，否则重新绑定后不会再加回来
        widgetFrame.removeAllViews()
        // 当前主题的旧版主色，与选色网格一致
        val seed = ContextCompat.getColor(context, Theme.swatchRes(getPersistedInt(0)))
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

        // 原编号直接对应选中项，未知编号才回退到默认粉色
        val current = Theme.canonicalTheme(getPersistedInt(0))

        val grid = GridLayout(context).apply {
            // 每格 64dp，21 种主题排成 3 列 7 行；矮屏由外层滚动容器承接
            columnCount = 3

            for (themeId in Theme.KEPT) {
                val color = ContextCompat.getColor(context, Theme.swatchRes(themeId))
                val view = getSwatchView(color, themeId == current).apply {
                    contentDescription = context.getString(Theme.nameRes(themeId))
                    setOnClickListener {
                        // 遵循 Preference 契约：监听器接受才持久化；写入原主题编号
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
            .setView(ScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(grid)
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
