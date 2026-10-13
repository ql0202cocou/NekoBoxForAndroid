package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 编辑会话归属检查的放行规则：初始化 / 迟到回调与保存对三种状态的取舍不同
class EditorSessionTest {

    @Test
    fun `缓存被别的编辑器接管后，写缓存与保存都拒绝`() {
        assertFalse(EditorSessionState.TAKEN_OVER.permitsCacheWrite())
        assertFalse(EditorSessionState.TAKEN_OVER.permitsSave())
    }

    @Test
    fun `缓存丢失时允许重建缓存，但不允许保存`() {
        // 进程死亡后初始化要往空缓存里写；此时存盘会把空内容写进实体
        assertTrue(EditorSessionState.CACHE_LOST.permitsCacheWrite())
        assertFalse(EditorSessionState.CACHE_LOST.permitsSave())
    }

    @Test
    fun `会话属于本编辑器时都放行`() {
        assertTrue(EditorSessionState.OWNER.permitsCacheWrite())
        assertTrue(EditorSessionState.OWNER.permitsSave())
    }

    @Test
    fun `三种状态各有且仅有预期的放行组合`() {
        val table = EditorSessionState.values().associateWith { it.permitsCacheWrite() to it.permitsSave() }
        assertEquals(
            mapOf(
                EditorSessionState.OWNER to (true to true),
                EditorSessionState.CACHE_LOST to (true to false),
                EditorSessionState.TAKEN_OVER to (false to false),
            ),
            table,
        )
    }
}
