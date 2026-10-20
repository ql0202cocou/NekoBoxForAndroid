package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** 撤销插回的下标夹取：纯逻辑，任何下标都不得越界 */
class UndoInsertClampedTest {

    @Test
    fun inRangeIndexIsKept() {
        val list = mutableListOf("a", "b", "c")
        assertEquals(1, list.insertClamped(1, "x"))
        assertEquals(listOf("a", "x", "b", "c"), list)
    }

    @Test
    fun indexPastTheEndAppends() {
        val list = mutableListOf("a")
        assertEquals(1, list.insertClamped(5, "x"))
        assertEquals(listOf("a", "x"), list)
    }

    @Test
    fun indexEqualToSizeAppends() {
        val list = mutableListOf("a", "b")
        assertEquals(2, list.insertClamped(2, "x"))
        assertEquals(listOf("a", "b", "x"), list)
    }

    @Test
    fun negativeIndexInsertsAtFront() {
        val list = mutableListOf("a")
        assertEquals(0, list.insertClamped(-3, "x"))
        assertEquals(listOf("x", "a"), list)
    }

    @Test
    fun emptyListAcceptsAnyIndex() {
        val list = mutableListOf<String>()
        assertEquals(0, list.insertClamped(7, "x"))
        assertEquals(listOf("x"), list)
    }

    @Test
    fun replayingDeletionsRestoresTheOrder() {
        // 与 UndoSnackbarManager 的用法一致：按删除顺序的逆序、带删除时的下标回放
        val original = mutableListOf("a", "b", "c", "d")
        val list = original.toMutableList()
        val bin = ArrayList<Pair<Int, String>>()
        list.removeAt(3).also { bin += 3 to it }
        list.removeAt(0).also { bin += 0 to it }
        for ((i, item) in bin.reversed()) list.insertClamped(i, item)
        assertEquals(original, list)
    }
}
