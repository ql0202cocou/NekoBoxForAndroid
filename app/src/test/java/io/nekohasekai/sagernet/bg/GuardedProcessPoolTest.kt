package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

// start() 依赖 SagerNet.application（Android Application），looper 线程还会触
// libcore native（gojni 在本地 JVM 不存在），这两条路径在纯 JVM 都跑不了。
// 这里用反射把带假进程的 Guard 直接注入 guards 列表，只覆盖 close() 的两条
// 收割路径（looper 已启动 / 未启动）与幂等性——它们不触 Android 也不触 native。
@OptIn(ExperimentalCoroutinesApi::class)
class GuardedProcessPoolTest {

    @Before
    fun setUp() {
        // 池的 coroutineContext 建在 Dispatchers.Main 上，本地 JVM 没有 Main
        // dispatcher 会初始化失败；close() 不在 Main 上调度，给个占位的即可
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // java.lang.Process 是纯 JDK 抽象类，只记录 destroy() 是否被调用
    private class FakeProcess : Process() {
        @Volatile
        var destroyed = false

        override fun destroy() {
            destroyed = true
        }

        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    // Guard 是 private inner class，正常只有 start() 能创建；反射构造后填入
    // FakeProcess，绕开 start() 对 SagerNet.application 的依赖
    private fun newGuard(pool: GuardedProcessPool, process: FakeProcess, looperStarted: Boolean): Any {
        val guardClass = Class.forName("io.nekohasekai.sagernet.bg.GuardedProcessPool\$Guard")
        val guard = guardClass.getDeclaredConstructor(
            GuardedProcessPool::class.java, List::class.java, Map::class.java
        ).apply { isAccessible = true }.newInstance(pool, listOf("fake"), emptyMap<String, String>())
        guardClass.getDeclaredField("process").apply { isAccessible = true }.set(guard, process)
        guardClass.getDeclaredField("looperStarted").apply { isAccessible = true }
            .setBoolean(guard, looperStarted)
        return guard
    }

    private fun addGuard(pool: GuardedProcessPool, guard: Any) {
        val guardsField = GuardedProcessPool::class.java.getDeclaredField("guards")
        guardsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (guardsField.get(pool) as MutableList<Any>).add(guard)
    }

    @Test
    fun `looper 未启动的 guard 由 close 直接收割`() = runBlocking {
        val pool = GuardedProcessPool { }
        val process = FakeProcess()
        addGuard(pool, newGuard(pool, process, looperStarted = false))

        pool.close(this).join()

        assertTrue(process.destroyed)
    }

    @Test
    fun `looper 已启动的 guard 不被 close 收割`() = runBlocking {
        val pool = GuardedProcessPool { }
        val process = FakeProcess()
        addGuard(pool, newGuard(pool, process, looperStarted = true))

        pool.close(this).join()

        // 已启动的 looper 在自己的 finally 里收割进程，close() 代劳会截断优雅退出
        assertFalse(process.destroyed)
    }

    // close() 不清空 guards，第二次调用会对未启动的 guard 再 destroy 一次（Process.destroy
    // 本身幂等，无害）；这里只验证重复调用不出错、已启动的 guard 仍不被收割
    @Test
    fun `close 重复调用不出错且不收割已启动的 guard`() = runBlocking {
        val pool = GuardedProcessPool { }
        val notStarted = FakeProcess()
        val started = FakeProcess()
        addGuard(pool, newGuard(pool, notStarted, looperStarted = false))
        addGuard(pool, newGuard(pool, started, looperStarted = true))

        pool.close(this).join()
        pool.close(this).join()

        assertTrue(notStarted.destroyed)
        assertFalse(started.destroyed)
    }

    @Test
    fun `空池 close 正常完成`() = runBlocking {
        GuardedProcessPool { }.close(this).join()
    }
}
