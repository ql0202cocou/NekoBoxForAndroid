package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 主题读取时归并：21 个旧编号全部保留，读取时归到 12 个代表（2026-10-26 恢复六种颜色后）
class ThemeMergeTest {

    // 归并表在这里手写钉死，不从 Theme.MERGED 反推：改表必须同时改这里
    private val expected = mapOf(
        1 to 1,
        2 to 2, 3 to 2,
        4 to 4, 5 to 4,
        6 to 6,
        7 to 7, 8 to 7, 20 to 7,
        9 to 10, 10 to 10,
        11 to 11, 12 to 11, 13 to 11,
        14 to 15, 15 to 15,
        16 to 16, 17 to 16,
        18 to 18,
        19 to 19,
        21 to 21,
    )

    // 代表编号 → 应用主题 / 对话框主题
    private val styles = mapOf(
        2 to (R.style.Theme_SagerNet_Pink_SSR to R.style.Theme_SagerNet_Dialog_Pink_SSR),
        1 to (R.style.Theme_SagerNet_Red to R.style.Theme_SagerNet_Dialog_Red),
        16 to (R.style.Theme_SagerNet_Orange to R.style.Theme_SagerNet_Dialog_Orange),
        15 to (R.style.Theme_SagerNet_Amber to R.style.Theme_SagerNet_Dialog_Amber),
        11 to (R.style.Theme_SagerNet_Green to R.style.Theme_SagerNet_Dialog_Green),
        10 to (R.style.Theme_SagerNet_Teal to R.style.Theme_SagerNet_Dialog_Teal),
        7 to (R.style.Theme_SagerNet_Blue to R.style.Theme_SagerNet_Dialog_Blue),
        6 to (R.style.Theme_SagerNet_Indigo to R.style.Theme_SagerNet_Dialog_Indigo),
        4 to (R.style.Theme_SagerNet_Purple to R.style.Theme_SagerNet_Dialog_Purple),
        18 to (R.style.Theme_SagerNet_Brown to R.style.Theme_SagerNet_Dialog_Brown),
        19 to (R.style.Theme_SagerNet_Grey to R.style.Theme_SagerNet_Dialog_Grey),
        21 to (R.style.Theme_SagerNet_Black to R.style.Theme_SagerNet_Dialog_Black),
    )

    @Test
    fun everyLegacyIdMapsToItsRepresentative() {
        assertEquals((1..21).toSet(), expected.keys)
        for ((id, kept) in expected) {
            assertEquals("编号 $id", kept, Theme.canonicalTheme(id))
        }
    }

    @Test
    fun keptOrderAndRepresentatives() {
        assertEquals(listOf(2, 1, 16, 15, 11, 10, 7, 6, 4, 18, 19, 21), Theme.KEPT)
        for (id in Theme.KEPT) {
            assertEquals("编号 $id", id, Theme.canonicalTheme(id))
            assertFalse("编号 $id 不能出现在 MERGED 里", id in Theme.MERGED)
        }
    }

    @Test
    fun mergedAndKeptPartitionAllIds() {
        assertTrue(Theme.MERGED.keys.intersect(Theme.KEPT.toSet()).isEmpty())
        assertEquals((1..21).toSet(), Theme.MERGED.keys + Theme.KEPT)
        assertEquals(21, Theme.MERGED.size + Theme.KEPT.size)
        for ((id, kept) in Theme.MERGED) {
            assertTrue("编号 $id 的代表 $kept 必须在 KEPT 里", kept in Theme.KEPT)
        }
    }

    @Test
    fun unsetAndUnknownFallBackToDefault() {
        // DataStore 从未写过时读到 0
        for (id in listOf(0, -1, 22, 99)) {
            assertEquals("编号 $id", Theme.PINK_SSR, Theme.canonicalTheme(id))
        }
    }

    @Test
    fun styleFollowsRepresentative() {
        for (id in 0..30) {
            val kept = expected[id] ?: Theme.PINK_SSR
            val (theme, dialog) = styles.getValue(kept)
            assertEquals("编号 $id", theme, Theme.getTheme(id))
            assertEquals("编号 $id", dialog, Theme.getDialogTheme(id))
        }
        assertEquals(R.style.Theme_SagerNet_Black, Theme.getTheme(Theme.BLACK))
    }
}
