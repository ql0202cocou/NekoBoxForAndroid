package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.appScope
import io.nekohasekai.sagernet.uitest.UiTestBase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import libcore.BoxInstance as CoreBox

/**
 * 真线程上运行生产 init / launch / close 与 TestInstance 取消入口，仅替换 JNI 边界。
 * 门闩覆盖生命周期交错；它们会建立可见性，不能把这些测试通过当成普通字段安全发布的证明。
 * 无序竞争用例不在 box 赋值与 close 读取之间额外同步，但也不保证稳定复现弱内存结果。
 */
@Config(shadows = [BoxInstanceLifecycleTest.ShadowCoreBox::class, BoxInstanceLifecycleTest.ShadowLifecycleLibcore::class])
class BoxInstanceLifecycleTest : UiTestBase() {

    @Before
    fun resetNativeBoundary() = ShadowLifecycleLibcore.reset()

    private fun profile() = ProxyEntity(id = 1, type = ProxyEntity.TYPE_CONFIG, configBean = ConfigBean().apply {
        initializeDefaultValues()
        name = "lifecycle-test"
        config = """{"outbounds":[{"type":"direct","tag":"proxy"}]}"""
    })

    private fun instance() = object : BoxInstance(profile()) {}

    private fun CoreBox.state(): ShadowCoreBox = Shadow.extract(this)

    private class Gate : AutoCloseable {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun pause() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "生命周期门闩未放行" }
        }
        fun awaitEntered() = assertTrue("必须到达受控的生命周期阶段", entered.await(10, TimeUnit.SECONDS))
        override fun close() = release.countDown()
    }

    @Test
    fun `close 早于 init 时仍回收后来创建的核心且不再启动`() {
        val core = CoreBox()
        ShadowLifecycleLibcore.create = { core }
        val instance = instance()
        instance.close()
        ui.bg { instance.init(); instance.launch() }
        assertTrue(instance.isInitialized())
        assertEquals(1, core.state().closes)
        assertEquals(0, core.state().starts)
    }

    @Test
    fun `init 完成后由另一线程关闭已启动的核心且重复关闭无副作用`() {
        val core = CoreBox()
        ShadowLifecycleLibcore.create = { core }
        val instance = instance()
        ui.bg { instance.init(); instance.launch() }
        assertEquals(1, core.state().starts)
        ui.offMain { instance.close(); instance.close() }
        ui.bg { instance.launch() }
        assertEquals(1, core.state().closes)
        assertEquals(1, core.state().starts)
    }

    @Test
    fun `核心已赋值但 init 尚未返回时关闭并由 finally 补收`() {
        for (failAfterLoad in listOf(false, true)) {
            val core = CoreBox()
            ShadowLifecycleLibcore.create = { core }
            val failure = IllegalStateException("synthetic load failure")
            Gate().use { gate ->
                val instance = object : BoxInstance(profile()) {
                    override suspend fun loadConfig() {
                        super.loadConfig()
                        gate.pause()
                        if (failAfterLoad) throw failure
                    }
                }
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val init = executor.submit<Throwable?> { runCatching { runBlocking { instance.init() } }.exceptionOrNull() }
                    gate.awaitEntered()
                    instance.close()
                    assertEquals(1, core.state().closes)
                    gate.close()
                    assertSame(if (failAfterLoad) failure else null, init.get(10, TimeUnit.SECONDS))
                    // Go Close 自身幂等，init 的兜底允许再调用一次，但不能再次启动。
                    assertEquals(2, core.state().closes)
                    runBlocking { instance.launch() }
                    assertEquals(0, core.state().starts)
                } finally {
                    gate.close()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `测速在核心创建中取消时不等创建完成且稍后回收核心`() = runBlocking {
        val core = CoreBox()
        val instance = TestInstance(profile(), "https://example.com/", 1000)
        Gate().use { gate ->
            ShadowLifecycleLibcore.create = { gate.pause(); core }
            val existingWorkers = appScope.coroutineContext[Job]!!.children.toSet()
            val call = async(start = CoroutineStart.UNDISPATCHED) { instance.doTest() }
            try {
                gate.awaitEntered()
                // IO 工作属于 appScope，调用方取消完成不代表它已退出，必须另行等它收尾。
                val workers = appScope.coroutineContext[Job]!!.children.filterNot { it in existingWorkers }.toList()
                assertTrue("必须捕获本次测速的 IO 工作", workers.isNotEmpty())
                call.cancelAndJoin()
                assertEquals(0, core.state().closes)
                gate.close()
                withTimeout(10_000) { workers.joinAll(); instance.awaitProcessesClosed() }
                assertEquals(1, core.state().closes)
                assertEquals(0, core.state().starts)
                assertEquals(0, ShadowLifecycleLibcore.urlTests)
            } finally {
                gate.close()
                call.cancelAndJoin()
                instance.close()
            }
        }
    }

    @Test
    fun `测速运行中取消会关闭核心并让 IO 工作退出`() = runBlocking {
        val core = CoreBox()
        ShadowLifecycleLibcore.create = { core }
        val entered = CountDownLatch(1)
        ShadowLifecycleLibcore.test = { box ->
            entered.countDown()
            check(box.state().closed.await(10, TimeUnit.SECONDS)) { "取消后核心没有关闭" }
            throw IllegalStateException("synthetic closed core")
        }
        val instance = TestInstance(profile(), "https://example.com/", 1000)
        val existingWorkers = appScope.coroutineContext[Job]!!.children.toSet()
        val call = async(start = CoroutineStart.UNDISPATCHED) { instance.doTest() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val workers = appScope.coroutineContext[Job]!!.children.filterNot { it in existingWorkers }.toList()
            assertTrue("必须捕获本次测速的 IO 工作", workers.isNotEmpty())
            call.cancelAndJoin()
            withTimeout(10_000) { workers.joinAll(); instance.awaitProcessesClosed() }
            assertEquals(1, core.state().starts)
            assertEquals(1, core.state().closes)
            assertEquals(1, ShadowLifecycleLibcore.urlTests)
        } finally {
            call.cancelAndJoin()
            instance.close()
        }
    }

    @Test
    fun `init 与 close 无序竞争后核心必已关闭`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val core = CoreBox()
                ShadowLifecycleLibcore.create = { core }
                val instance = instance()
                val start = CyclicBarrier(2)
                // 只在竞争开始前与双方结束后同步，中间不通过门闩发布 box。
                val init = executor.submit { start.await(10, TimeUnit.SECONDS); runBlocking { instance.init() } }
                val close = executor.submit { start.await(10, TimeUnit.SECONDS); instance.close() }
                init.get(10, TimeUnit.SECONDS)
                close.get(10, TimeUnit.SECONDS)
                assertTrue("第 $it 轮的核心未回收", core.state().closes > 0)
                assertEquals(0, core.state().starts)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    // 仅记录 JNI 调用；实际 Go Start/Close 的互斥与幂等由 libcore/box_test.go 验证。
    @Implements(CoreBox::class, isInAndroidSdk = false)
    class ShadowCoreBox {
        var starts = 0
            private set
        var closes = 0
            private set
        val closed = CountDownLatch(1)

        @Implementation
        fun __constructor__() {}

        @Implementation
        @Synchronized
        fun start() {
            check(closed.count > 0) { "不能重启已关闭的核心" }
            starts++
        }

        @Implementation
        @Synchronized
        fun close() {
            closes++
            closed.countDown()
        }
    }

    @Implements(className = "libcore.Libcore", isInAndroidSdk = false)
    class ShadowLifecycleLibcore {
        companion object {
            var create: () -> CoreBox = { error("未设置测试核心") }
            var test: (CoreBox) -> Int = { error("不应进入测速") }
            var urlTests = 0

            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun newSingBoxInstance(config: String, transport: libcore.LocalDNSTransport): CoreBox = create()

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun urlTest(box: CoreBox, link: String, timeout: Int): Int {
                urlTests++
                return test(box)
            }

            fun reset() {
                create = { error("未设置测试核心") }
                test = { error("不应进入测速") }
                urlTests = 0
            }
        }
    }
}
