package io.nekohasekai.sagernet.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 订阅更新写库前的世代比对（DataGenerationCheck.kt）：只有开始与提交时读到的世代号相同才放行
class DataGenerationCheckTest {

    @Test
    fun `世代号没变时放行`() {
        // 从未恢复过（没有行，两次都读作 0）
        assertFalse(isDataRestoredSince(0L, 0L))
        assertFalse(isDataRestoredSince(7L, 7L))
    }

    @Test
    fun `期间恢复过一次或多次都放弃`() {
        // 升级后第一次恢复：开始时没有行（0），提交时已是 1
        assertTrue(isDataRestoredSince(0L, 1L))
        // 恢复后补偿回写：+1 两次
        assertTrue(isDataRestoredSince(3L, 5L))
    }

    @Test
    fun `世代号变小同样算换过`() {
        // 正常不会发生（只增不减），万一发生不能当作没变放行
        assertTrue(isDataRestoredSince(5L, 0L))
    }
}
