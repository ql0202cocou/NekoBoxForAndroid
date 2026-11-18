package io.nekohasekai.sagernet.bg

import androidx.concurrent.futures.CallbackToFutureAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SchedulingCompletionTest {
    @Test
    fun `取消调用方不能提前释放已提交操作的调度锁`() = runBlocking {
        lateinit var completion: CallbackToFutureAdapter.Completer<Void>
        val future = CallbackToFutureAdapter.getFuture<Void> { completion = it; "synthetic scheduling" }
        val mutex = Mutex()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { future.awaitSchedulingCompletion() }
        }
        waiting.cancel()
        assertFalse(waiting.isCompleted)
        assertFalse(future.isCancelled)
        assertFalse(mutex.tryLock())
        completion.set(null)
        waiting.join()
        assertTrue(mutex.tryLock())
        mutex.unlock()
    }

    @Test
    fun `远端操作失败按原异常释放锁且之后能再调度`() = runBlocking {
        lateinit var completion: CallbackToFutureAdapter.Completer<Void>
        val future = CallbackToFutureAdapter.getFuture<Void> { completion = it; "synthetic scheduling" }
        val mutex = Mutex()
        val error = IllegalStateException("synthetic failure")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { mutex.withLock { future.awaitSchedulingCompletion() } }.exceptionOrNull()
        }
        completion.setException(error)
        assertSame(error, waiting.await())
        assertTrue(mutex.tryLock())
        mutex.unlock()
    }
}
