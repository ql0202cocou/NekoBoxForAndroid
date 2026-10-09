package io.nekohasekai.sagernet.utils

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
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

    // 选色网格里的 6 种主题，顺序即显示顺序：粉（默认）、橙、蓝、绿、白、黑
    val KEPT = listOf(PINK_SSR, ORANGE, BLUE, GREEN, GREY, BLACK)

    // 已并入代表的旧编号 → 代表编号。归并只在读取时映射，不改写存储的编号。
    // 以后把某种颜色加回来，要改这些地方：
    //   1. 从这里删掉那一行，把它加进 KEPT（顺序即选色网格的显示顺序）；
    //   2. 在下面 getTheme / getDialogTheme 两个 when 里补分支；
    //   3. 在 M3Palette.specs（测试代码）加一条种子，彩色主题同时加进 seedColorNames，
    //      再按 M3PaletteTest 的说明重新生成 colors_m3_*.xml 与 themes_m3.xml；
    //   4. 在 ColorPickerPreference.SWATCH_COLORS 补上它的色块颜色；
    //   5. 改 ThemeMergeTest 手写的归并表（expected）与样式表（styles），以及其中 KEPT 的顺序。
    // 原来选过它的用户会恢复为该颜色重新生成的 M3 配色（不是 M2 时代的原色）
    val MERGED: Map<Int, Int> = mapOf(
        RED to PINK_SSR,
        PINK to PINK_SSR,
        PURPLE to PINK_SSR,
        YELLOW to ORANGE,
        AMBER to ORANGE,
        DEEP_ORANGE to ORANGE,
        BROWN to ORANGE,
        DEEP_PURPLE to BLUE,
        INDIGO to BLUE,
        LIGHT_BLUE to BLUE,
        CYAN to BLUE,
        BLUE_GREY to BLUE,
        TEAL to GREEN,
        LIGHT_GREEN to GREEN,
        LIME to GREEN,
    )

    // 存储的编号 → 实际使用的主题编号：KEPT 里的取自身，MERGED 里的取代表，
    // 其它（含从未写过的 0、负数和未知编号）取默认主题
    fun canonicalTheme(theme: Int): Int = when (theme) {
        in KEPT -> theme
        else -> MERGED[theme] ?: defaultTheme()
    }

    // 选色网格与设置行摘要里显示的主题名称：按归并后的代表取，只认 KEPT 里的六个
    @StringRes
    fun nameRes(themeId: Int): Int = when (canonicalTheme(themeId)) {
        ORANGE -> R.string.theme_orange
        BLUE -> R.string.theme_blue
        GREEN -> R.string.theme_green
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
            ORANGE -> R.style.Theme_SagerNet_Orange
            BLUE -> R.style.Theme_SagerNet_Blue
            GREEN -> R.style.Theme_SagerNet_Green
            GREY -> R.style.Theme_SagerNet_Grey
            BLACK -> R.style.Theme_SagerNet_Black
            else -> R.style.Theme_SagerNet_Pink_SSR
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (canonicalTheme(theme)) {
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
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

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}