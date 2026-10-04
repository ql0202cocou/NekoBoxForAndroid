package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExpandChainInOrderTest {

    // members 为 null 表示普通节点，否则是链的成员 id
    private data class Node(val id: Long, val members: List<Long>? = null)

    private class LoopException(val chainId: Long) : RuntimeException()

    private fun nodes(vararg list: Node) = list.associateBy { it.id }

    private val missing = mutableListOf<Pair<Long, Long>>()

    private fun expand(db: Map<Long, Node>, rootId: Long): List<Long> = expandChainInOrder(
        db.getValue(rootId),
        idOf = { it.id },
        membersOf = { it.members },
        lookup = { ids -> ids.mapNotNull { db[it] }.associateBy { it.id } },
        onMissing = { chain, missingId -> missing += chain.id to missingId },
        onLoop = { throw LoopException(it.id) },
    ).map { it.id }

    @Test
    fun `普通节点展开成自己`() {
        assertEquals(listOf(1L), expand(nodes(Node(1)), 1))
    }

    @Test
    fun `单层链按填写顺序`() {
        val db = nodes(Node(1), Node(2), Node(3), Node(10, listOf(1, 2, 3)))
        assertEquals(listOf(1L, 2L, 3L), expand(db, 10))
    }

    @Test
    fun `两层嵌套子链不被反转`() {
        // 内链 [A, B]，外链 [内链, C]：流量走 A→B→C
        val db = nodes(Node(1), Node(2), Node(3), Node(10, listOf(1, 2)), Node(20, listOf(10, 3)))
        assertEquals(listOf(1L, 2L, 3L), expand(db, 20))
    }

    @Test
    fun `两层嵌套子链在中间`() {
        val db = nodes(Node(1), Node(2), Node(3), Node(4), Node(10, listOf(2, 3)), Node(20, listOf(1, 10, 4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), expand(db, 20))
    }

    @Test
    fun `三层嵌套`() {
        val db = nodes(
            Node(1), Node(2), Node(3), Node(4), Node(5),
            Node(10, listOf(1, 2)),
            Node(20, listOf(10, 3)),
            Node(30, listOf(4, 20, 5)),
        )
        assertEquals(listOf(4L, 1L, 2L, 3L, 5L), expand(db, 30))
    }

    @Test
    fun `缺失成员跳过并上报其余照常`() {
        val db = nodes(Node(1), Node(3), Node(10, listOf(1, 99, 3)), Node(20, listOf(10, 98)))
        assertEquals(listOf(1L, 3L), expand(db, 20))
        assertEquals(listOf(10L to 99L, 20L to 98L), missing)
    }

    @Test
    fun `成员全部缺失得到空列表`() {
        val db = nodes(Node(10, listOf(99)))
        assertTrue(expand(db, 10).isEmpty())
        assertEquals(listOf(10L to 99L), missing)
    }

    @Test
    fun `链包含自己报循环引用`() {
        val db = nodes(Node(1), Node(10, listOf(1, 10)))
        try {
            expand(db, 10)
            fail("应报循环引用")
        } catch (e: LoopException) {
            assertEquals(10L, e.chainId)
        }
    }

    @Test
    fun `两条链互相包含报循环引用`() {
        val db = nodes(Node(1), Node(10, listOf(1, 20)), Node(20, listOf(10)))
        try {
            expand(db, 10)
            fail("应报循环引用")
        } catch (e: LoopException) {
            assertEquals(10L, e.chainId)
        }
    }

    @Test
    fun `同一节点出现在不同位置照常展开`() {
        val db = nodes(Node(1), Node(2), Node(10, listOf(1, 2)), Node(20, listOf(1, 10, 2)))
        assertEquals(listOf(1L, 1L, 2L, 2L), expand(db, 20))
    }

    @Test
    fun `同一子链出现两次不算循环引用`() {
        val db = nodes(Node(1), Node(2), Node(3), Node(10, listOf(1, 2)), Node(20, listOf(10, 3, 10)))
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), expand(db, 20))
    }
}
