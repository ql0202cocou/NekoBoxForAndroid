package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.ProcessRestartBudget.Companion.HEALTHY_UPTIME_MS
import org.junit.Assert.*
import org.junit.Test

class ProcessRestartBudgetTest {
    private val short = 1_000L

    @Test
    fun `强杀在第三次停止且普通退出不能重置预算`() {
        val budget = ProcessRestartBudget()
        assertTrue(budget.allowRestart(true, short))
        assertTrue(budget.allowRestart(false, HEALTHY_UPTIME_MS))
        assertTrue(budget.allowRestart(true, short))
        assertFalse(budget.allowRestart(true, short))
    }

    @Test
    fun `预算用尽后不再恢复且新的守护有独立预算`() {
        val budget = ProcessRestartBudget()
        assertTrue(budget.allowRestart(true, short))
        assertTrue(budget.allowRestart(true, short))
        assertFalse(budget.allowRestart(true, short))
        assertFalse(budget.allowRestart(true, HEALTHY_UPTIME_MS))
        assertFalse(budget.allowRestart(false, short))
        assertTrue(ProcessRestartBudget().allowRestart(true, short))
    }

    @Test
    fun `长时间稳定运行后的强杀重新计数`() {
        val budget = ProcessRestartBudget()
        assertTrue(budget.allowRestart(true, short))
        assertTrue(budget.allowRestart(true, short))
        assertTrue(budget.allowRestart(true, HEALTHY_UPTIME_MS))
        assertTrue(budget.allowRestart(true, short))
        assertFalse(budget.allowRestart(true, HEALTHY_UPTIME_MS - 1))
    }
}
