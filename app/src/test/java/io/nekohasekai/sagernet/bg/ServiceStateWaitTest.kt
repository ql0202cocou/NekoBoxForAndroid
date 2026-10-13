package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.BaseService.State
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 备份恢复等服务停止（ServiceStateWait.kt）。等的是状态本身，不是固定时长
class ServiceStateWaitTest {

    @Test
    fun `只有 Stopped 与 Idle 算收尾`() {
        assertTrue(State.Stopped.isSettled)
        assertTrue(State.Idle.isSettled)
        assertFalse(State.Connecting.isSettled)
        assertFalse(State.Connected.isSettled)
        // Stopping 时最终流量可能还没落库
        assertFalse(State.Stopping.isSettled)
    }

    @Test
    fun `状态已收尾时立即返回`() = runBlocking {
        assertTrue(MutableStateFlow(State.Stopped).awaitSettled(1))
        assertTrue(MutableStateFlow(State.Idle).awaitSettled(1))
    }

    @Test
    fun `经过 Stopping 到 Stopped 才返回，Stopping 期间不放行`() = runBlocking {
        val states = MutableStateFlow(State.Connected)
        val wait = async { states.awaitSettled(5_000) }
        states.value = State.Stopping
        delay(100)
        assertFalse("Stopping 时不该放行", wait.isCompleted)
        states.value = State.Stopped
        assertTrue(wait.await())
    }

    @Test
    fun `一直不收尾则超时返回 false`() = runBlocking {
        assertFalse(MutableStateFlow(State.Stopping).awaitSettled(100))
        assertFalse(MutableStateFlow(State.Connected).awaitSettled(100))
    }
}
