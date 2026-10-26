package io.nekohasekai.sagernet.utils

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

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

    // 选色网格里的 12 种主题，顺序即显示顺序：粉（默认）、红、橙、琥珀、绿、青、蓝、靛蓝、紫、棕、白、黑
    // （2026-10-26 从归并表里恢复了红、琥珀、青、靛蓝、紫、棕六种）
    val KEPT = listOf(PINK_SSR, RED, ORANGE, AMBER, GREEN, TEAL, BLUE, INDIGO, PURPLE, BROWN, GREY, BLACK)

    // 已并入代表的旧编号 → 代表编号。归并只在读取时映射，不改写存储的编号。
    // 以后把某种颜色加回来，要改这些地方：
    //   1. 从这里删掉那一行，把它加进 KEPT（顺序即选色网格的显示顺序）；
    //   2. 在下面 getTheme / getDialogTheme 两个 when 里补分支；
    //   3. 在 M3Palette.specs（测试代码）加一条种子，彩色主题同时加进 seedColorNames，
    //      再按 M3PaletteTest 的说明重新生成 colors_m3_*.xml 与 themes_m3.xml；
    //   4. 在 ColorPickerPreference.SWATCH_COLORS 补上它的色块颜色；
    //   5. 改 ThemeMergeTest 手写的归并表（expected）与样式表（styles），以及其中 KEPT 的顺序。
    // 原来选过它的用户会恢复为该颜色重新生成的 M3 配色（不是 M2 时代的原色）
    // 代表取色相最近的保留色：黄 → 琥珀、深紫 → 紫、青色 → 青（恢复六种颜色时改的三处）
    val MERGED: Map<Int, Int> = mapOf(
        PINK to PINK_SSR,
        YELLOW to AMBER,
        DEEP_ORANGE to ORANGE,
        DEEP_PURPLE to PURPLE,
        LIGHT_BLUE to BLUE,
        CYAN to TEAL,
        BLUE_GREY to BLUE,
        LIGHT_GREEN to GREEN,
        LIME to GREEN,
    )

    // 存储的编号 → 实际使用的主题编号：KEPT 里的取自身，MERGED 里的取代表，
    // 其它（含从未写过的 0、负数和未知编号）取默认主题
    fun canonicalTheme(theme: Int): Int = when (theme) {
        in KEPT -> theme
        else -> MERGED[theme] ?: defaultTheme()
    }

    // 选色网格与设置行摘要里显示的主题名称：按归并后的代表取，只认 KEPT 里的十二个
    @StringRes
    fun nameRes(themeId: Int): Int = when (canonicalTheme(themeId)) {
        RED -> R.string.theme_red
        ORANGE -> R.string.theme_orange
        AMBER -> R.string.theme_amber
        GREEN -> R.string.theme_green
        TEAL -> R.string.theme_teal
        BLUE -> R.string.theme_blue
        INDIGO -> R.string.theme_indigo
        PURPLE -> R.string.theme_purple
        BROWN -> R.string.theme_brown
        GREY -> R.string.theme_white
        BLACK -> R.string.theme_black
        else -> R.string.theme_pink
    }

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        return when (canonicalTheme(theme)) {
            RED -> R.style.Theme_SagerNet_Red
            ORANGE -> R.style.Theme_SagerNet_Orange
            AMBER -> R.style.Theme_SagerNet_Amber
            GREEN -> R.style.Theme_SagerNet_Green
            TEAL -> R.style.Theme_SagerNet_Teal
            BLUE -> R.style.Theme_SagerNet_Blue
            INDIGO -> R.style.Theme_SagerNet_Indigo
            PURPLE -> R.style.Theme_SagerNet_Purple
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLACK -> R.style.Theme_SagerNet_Black
            else -> R.style.Theme_SagerNet_Pink_SSR
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (canonicalTheme(theme)) {
            RED -> R.style.Theme_SagerNet_Dialog_Red
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            else -> R.style.Theme_SagerNet_Dialog_Pink_SSR
        }
    }

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