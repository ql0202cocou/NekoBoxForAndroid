package io.nekohasekai.sagernet.utils

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore

object Theme {

    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21

    private fun defaultTheme() = PINK_SSR

    // 一个编号对应的全部资源：主题样式、对话框样式、纯黑覆盖样式、名称、取色网格的种子色
    private class Styles(
        @StyleRes val theme: Int,
        @StyleRes val dialog: Int,
        @StyleRes val pureBlack: Int,
        @StringRes val name: Int,
        @ColorRes val swatch: Int,
    )

    // 恢复 1.4.2 的全部主题编号，一张表：默认粉色在最前，其余按旧编号排列（即 KEPT 的顺序）。
    // 读取时不改写用户保存的编号；补颜色只改这里（样式与颜色资源由 M3Palette 生成）
    private val STYLES: Map<Int, Styles> = linkedMapOf(
        PINK_SSR to Styles(
            R.style.Theme_SagerNet_Pink_SSR, R.style.Theme_SagerNet_Dialog_Pink_SSR,
            R.style.ThemeOverlay_SagerNet_PureBlack_Pink_SSR, R.string.theme_pink, R.color.color_pink_ssr,
        ),
        RED to Styles(
            R.style.Theme_SagerNet_Red, R.style.Theme_SagerNet_Dialog_Red,
            R.style.ThemeOverlay_SagerNet_PureBlack_Red, R.string.theme_red, R.color.material_red_500,
        ),
        PINK to Styles(
            R.style.Theme_SagerNet_Pink, R.style.Theme_SagerNet_Dialog_Pink,
            R.style.ThemeOverlay_SagerNet_PureBlack_Pink, R.string.theme_pink_material, R.color.material_pink_500,
        ),
        PURPLE to Styles(
            R.style.Theme_SagerNet_Purple, R.style.Theme_SagerNet_Dialog_Purple,
            R.style.ThemeOverlay_SagerNet_PureBlack_Purple, R.string.theme_purple, R.color.material_purple_500,
        ),
        DEEP_PURPLE to Styles(
            R.style.Theme_SagerNet_DeepPurple, R.style.Theme_SagerNet_Dialog_DeepPurple,
            R.style.ThemeOverlay_SagerNet_PureBlack_DeepPurple, R.string.theme_deep_purple, R.color.material_deep_purple_500,
        ),
        INDIGO to Styles(
            R.style.Theme_SagerNet_Indigo, R.style.Theme_SagerNet_Dialog_Indigo,
            R.style.ThemeOverlay_SagerNet_PureBlack_Indigo, R.string.theme_indigo, R.color.material_indigo_500,
        ),
        BLUE to Styles(
            R.style.Theme_SagerNet_Blue, R.style.Theme_SagerNet_Dialog_Blue,
            R.style.ThemeOverlay_SagerNet_PureBlack_Blue, R.string.theme_blue, R.color.material_blue_500,
        ),
        LIGHT_BLUE to Styles(
            R.style.Theme_SagerNet_LightBlue, R.style.Theme_SagerNet_Dialog_LightBlue,
            R.style.ThemeOverlay_SagerNet_PureBlack_LightBlue, R.string.theme_light_blue, R.color.material_light_blue_500,
        ),
        CYAN to Styles(
            R.style.Theme_SagerNet_Cyan, R.style.Theme_SagerNet_Dialog_Cyan,
            R.style.ThemeOverlay_SagerNet_PureBlack_Cyan, R.string.theme_cyan, R.color.material_cyan_500,
        ),
        TEAL to Styles(
            R.style.Theme_SagerNet_Teal, R.style.Theme_SagerNet_Dialog_Teal,
            R.style.ThemeOverlay_SagerNet_PureBlack_Teal, R.string.theme_teal, R.color.material_teal_500,
        ),
        GREEN to Styles(
            R.style.Theme_SagerNet_Green, R.style.Theme_SagerNet_Dialog_Green,
            R.style.ThemeOverlay_SagerNet_PureBlack_Green, R.string.theme_green, R.color.material_green_500,
        ),
        LIGHT_GREEN to Styles(
            R.style.Theme_SagerNet_LightGreen, R.style.Theme_SagerNet_Dialog_LightGreen,
            R.style.ThemeOverlay_SagerNet_PureBlack_LightGreen, R.string.theme_light_green, R.color.material_light_green_500,
        ),
        LIME to Styles(
            R.style.Theme_SagerNet_Lime, R.style.Theme_SagerNet_Dialog_Lime,
            R.style.ThemeOverlay_SagerNet_PureBlack_Lime, R.string.theme_lime, R.color.material_lime_500,
        ),
        YELLOW to Styles(
            R.style.Theme_SagerNet_Yellow, R.style.Theme_SagerNet_Dialog_Yellow,
            R.style.ThemeOverlay_SagerNet_PureBlack_Yellow, R.string.theme_yellow, R.color.material_yellow_500,
        ),
        AMBER to Styles(
            R.style.Theme_SagerNet_Amber, R.style.Theme_SagerNet_Dialog_Amber,
            R.style.ThemeOverlay_SagerNet_PureBlack_Amber, R.string.theme_amber, R.color.material_amber_500,
        ),
        ORANGE to Styles(
            R.style.Theme_SagerNet_Orange, R.style.Theme_SagerNet_Dialog_Orange,
            R.style.ThemeOverlay_SagerNet_PureBlack_Orange, R.string.theme_orange, R.color.material_orange_500,
        ),
        DEEP_ORANGE to Styles(
            R.style.Theme_SagerNet_DeepOrange, R.style.Theme_SagerNet_Dialog_DeepOrange,
            R.style.ThemeOverlay_SagerNet_PureBlack_DeepOrange, R.string.theme_deep_orange, R.color.material_deep_orange_500,
        ),
        BROWN to Styles(
            R.style.Theme_SagerNet_Brown, R.style.Theme_SagerNet_Dialog_Brown,
            R.style.ThemeOverlay_SagerNet_PureBlack_Brown, R.string.theme_brown, R.color.material_brown_500,
        ),
        GREY to Styles(
            R.style.Theme_SagerNet_Grey, R.style.Theme_SagerNet_Dialog_Grey,
            R.style.ThemeOverlay_SagerNet_PureBlack_Grey, R.string.theme_grey, R.color.material_grey_500,
        ),
        BLUE_GREY to Styles(
            R.style.Theme_SagerNet_BlueGrey, R.style.Theme_SagerNet_Dialog_BlueGrey,
            R.style.ThemeOverlay_SagerNet_PureBlack_BlueGrey, R.string.theme_blue_grey, R.color.material_blue_grey_500,
        ),
        BLACK to Styles(
            R.style.Theme_SagerNet_Black, R.style.Theme_SagerNet_Dialog_Black,
            R.style.ThemeOverlay_SagerNet_PureBlack_Black, R.string.theme_black, R.color.color_ng_black_primary,
        ),
    )

    val KEPT: List<Int> = STYLES.keys.toList()

    fun canonicalTheme(theme: Int): Int = if (theme in STYLES) theme else defaultTheme()

    private fun styles(theme: Int): Styles = STYLES.getValue(canonicalTheme(theme))

    @StringRes
    fun nameRes(themeId: Int): Int = styles(themeId).name

    /** 取色网格里这个主题的种子色 */
    @ColorRes
    fun swatchRes(themeId: Int): Int = styles(themeId).swatch

    fun apply(context: Context) {
        context.setTheme(getTheme())
        applyPureBlack(context)
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
        applyPureBlack(context)
    }

    // 旧版的黑主题也使用普通夜间背景；所有主题都通过同一个开关选择纯黑背景。
    private fun applyPureBlack(context: Context) {
        if (DataStore.pureBlack) context.theme.applyStyle(styles(DataStore.appTheme).pureBlack, true)
    }

    fun getTheme(): Int = getTheme(DataStore.appTheme)

    fun getDialogTheme(): Int = getDialogTheme(DataStore.appTheme)

    fun getTheme(theme: Int): Int = styles(theme).theme

    fun getDialogTheme(theme: Int): Int = styles(theme).dialog

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    // 本进程上次交给系统的按应用日夜模式（UiModeManager.MODE_NIGHT_*），-1 表示还没设过
    private var platformNightMode = -1

    fun applyNightTheme() {
        val mode = getNightMode()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AppCompatDelegate.setDefaultNightMode(mode)
            return
        }
        // 12+ 交给系统按应用记住：启动画面在应用代码运行前就按它选底色（否则跟随系统，与应用设置不一致时
        // 先闪另一种底色），切换时由系统推送 uiMode 变化、重建界面。AppCompat 这时只跟随系统，不再叠加一层覆盖；
        // 「自动」（按省电模式）系统没有对应值，仍由 AppCompat 判断，系统一侧跟随系统
        val platform = when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> UiModeManager.MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> UiModeManager.MODE_NIGHT_NO
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        if (platform != platformNightMode) {
            SagerNet.uiMode.setApplicationNightMode(platform)
            platformNightMode = platform
        }
        AppCompatDelegate.setDefaultNightMode(
            if (mode == AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY) mode
            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }

}