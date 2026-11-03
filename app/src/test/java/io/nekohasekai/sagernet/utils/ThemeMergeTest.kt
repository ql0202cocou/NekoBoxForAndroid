package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Test

// 旧版的 21 个编号直接恢复各自主题，读取过程不迁移或覆盖用户设置。
class ThemeMergeTest {

    // 手写编号与样式的对应关系，防止补颜色时把两个编号接到同一套资源。
    private val styles = mapOf(
        2 to (R.style.Theme_SagerNet_Pink_SSR to R.style.Theme_SagerNet_Dialog_Pink_SSR),
        1 to (R.style.Theme_SagerNet_Red to R.style.Theme_SagerNet_Dialog_Red),
        3 to (R.style.Theme_SagerNet_Pink to R.style.Theme_SagerNet_Dialog_Pink),
        4 to (R.style.Theme_SagerNet_Purple to R.style.Theme_SagerNet_Dialog_Purple),
        5 to (R.style.Theme_SagerNet_DeepPurple to R.style.Theme_SagerNet_Dialog_DeepPurple),
        6 to (R.style.Theme_SagerNet_Indigo to R.style.Theme_SagerNet_Dialog_Indigo),
        7 to (R.style.Theme_SagerNet_Blue to R.style.Theme_SagerNet_Dialog_Blue),
        8 to (R.style.Theme_SagerNet_LightBlue to R.style.Theme_SagerNet_Dialog_LightBlue),
        9 to (R.style.Theme_SagerNet_Cyan to R.style.Theme_SagerNet_Dialog_Cyan),
        10 to (R.style.Theme_SagerNet_Teal to R.style.Theme_SagerNet_Dialog_Teal),
        11 to (R.style.Theme_SagerNet_Green to R.style.Theme_SagerNet_Dialog_Green),
        12 to (R.style.Theme_SagerNet_LightGreen to R.style.Theme_SagerNet_Dialog_LightGreen),
        13 to (R.style.Theme_SagerNet_Lime to R.style.Theme_SagerNet_Dialog_Lime),
        14 to (R.style.Theme_SagerNet_Yellow to R.style.Theme_SagerNet_Dialog_Yellow),
        15 to (R.style.Theme_SagerNet_Amber to R.style.Theme_SagerNet_Dialog_Amber),
        16 to (R.style.Theme_SagerNet_Orange to R.style.Theme_SagerNet_Dialog_Orange),
        17 to (R.style.Theme_SagerNet_DeepOrange to R.style.Theme_SagerNet_Dialog_DeepOrange),
        18 to (R.style.Theme_SagerNet_Brown to R.style.Theme_SagerNet_Dialog_Brown),
        19 to (R.style.Theme_SagerNet_Grey to R.style.Theme_SagerNet_Dialog_Grey),
        20 to (R.style.Theme_SagerNet_BlueGrey to R.style.Theme_SagerNet_Dialog_BlueGrey),
        21 to (R.style.Theme_SagerNet_Black to R.style.Theme_SagerNet_Dialog_Black),
    )

    @Test
    fun everyLegacyIdKeepsItself() {
        for (id in 1..21) {
            assertEquals("编号 $id", id, Theme.canonicalTheme(id))
        }
    }

    @Test
    fun keptOrder() {
        assertEquals(listOf(2, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21), Theme.KEPT)
        assertEquals((1..21).toSet(), Theme.KEPT.toSet())
    }

    @Test
    fun unsetAndUnknownFallBackToDefault() {
        // DataStore 从未写过时读到 0
        for (id in listOf(0, -1, 22, 99)) {
            assertEquals("编号 $id", Theme.PINK_SSR, Theme.canonicalTheme(id))
        }
    }

    @Test
    fun styleFollowsId() {
        for (id in 0..30) {
            val kept = if (id in 1..21) id else Theme.PINK_SSR
            val (theme, dialog) = styles.getValue(kept)
            assertEquals("编号 $id", theme, Theme.getTheme(id))
            assertEquals("编号 $id", dialog, Theme.getDialogTheme(id))
        }
        assertEquals(R.style.Theme_SagerNet_Black, Theme.getTheme(Theme.BLACK))
    }
}
