package io.nekohasekai.sagernet.bg.proto

import android.content.pm.ProviderInfo
import io.nekohasekai.sagernet.database.RunGeneration
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ExternalCoreCheckException
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficIncrement
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appScope
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.uitest.UiTestBase
import io.nekohasekai.sagernet.uitest.UiFixtures
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
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test
    fun rejectedExternalRuntimeConfigIsLoggedOnceWithCredentialsMasked() = ui.bg {
        val node = ProxyEntity(id = 1, type = ProxyEntity.TYPE_TROJAN, core = ProxyEntity.CORE_XRAY,
            trojanBean = TrojanBean().apply {
                initializeDefaultValues()
                serverAddress = "server.example"
                password = "synthetic-rejected-secret"
            })
        ShadowLifecycleLibcore.create = { CoreBox() }
        val logs = CopyOnWriteArrayList<String>()
        ShadowLifecycleLibcore.log = { logs.add(it) }
        for (logging in listOf(true, false)) {
            val instance = ProxyInstance(node, ProxyService(), "runtime", RunGeneration(0L, 0L))
            instance.pluginPath["xray-plugin"] = PluginManager.InitResult("/usr/bin/false", ProviderInfo(), builtin = true)
            Logs.enabled = logging
            try {
                logs.clear()
                val failure = runCatching { instance.init() }.exceptionOrNull()
                assertTrue(failure.toString(), failure is ExternalCoreCheckException)
                val config = instance.config
                val raw = instance.externalProcesses.single().config
                val masked = config.loggableExternalConfig(raw)
                assertTrue(raw.contains(node.trojanBean!!.password))
                assertEquals(if (logging) 1 else 0, logs.count { it.endsWith(masked) })
                val auth = checkNotNull(config.localAuth)
                assertTrue(logs.none { node.trojanBean!!.password in it || auth.username in it || auth.password in it })
            } finally {
                instance.close()
                Logs.enabled = false
            }
        }
    }

    @Test
    fun activeServiceUrlTestMasksConfiguredCredentialsBeforeBinderReturns() {
        val service = ProxyService()
        val node = profile().apply {
            configBean!!.config = """{"outbounds":[{"type":"trojan","tag":"proxy","password":"synthetic-profile-secret"}]}"""
        }
        val instance = ProxyInstance(node, service, "probe", RunGeneration(0L, 0L))
        ShadowLifecycleLibcore.create = { CoreBox() }
        ShadowLifecycleLibcore.test = { error("probe rejected synthetic-profile-secret") }
        try {
            ui.bg { instance.init() }
            service.data.proxy = instance
            val error = assertThrows(IllegalStateException::class.java) { service.data.binder.urlTest() }
            assertEquals("probe rejected ***", error.message)
        } finally {
            service.data.proxy = null
            instance.close()
        }
    }

    @Test
    fun `首轮记账接上本实例最后一次切换，后续两轮都归给该成员`() {
        val service = ProxyService()
        val profile = profile()
        val config = ConfigBuildResult(
            "{}", emptyList(), profile.id,
            TrafficBindings((1L..3L).associate { "m$it" to listOf(it) }, (1L..3L).associateWith { TrafficTotals(0, 0) }, emptySet()),
            (1L..3L).associateWith { "m$it" }, 1L,
        )
        val stats = TrafficAccountingTest.FakeStats(listOf(TAG_PROXY))
        val looper = checkNotNull(ProxyInstance(profile, service, "selector", RunGeneration(0L, 0L)).looper)
        ui.bg { looper.selectMain(2); looper.selectMain(3) }
        val acc = looper.initializeAccounting(config, stats::query, 0L)
        assertTrue(stats.log.isEmpty())
        for (i in 1L..2L) {
            stats.route(TAG_PROXY, 10, 100)
            val round = acc.sweep(i * 1000)
            assertEquals(i * 10, round.txSession)
            assertEquals(i * 100, round.rxSession)
        }
        assertEquals(mapOf(1L to TrafficTotals(0, 0), 2L to TrafficTotals(0, 0), 3L to TrafficTotals(200, 20)), acc.snapshot())
    }

    @Test
    fun selectorChangesSettleEachMemberBeforeFirstSample() = ui.bg {
        val members = SagerDatabase.proxyDao.getByGroup(UiFixtures.install().normalGroup.id)
            .filter { it.tx == 0L && it.rx == 0L }.take(3)
        val tags = members.associate { it.id to "m${it.id}" }
        val config = ConfigBuildResult("{}", emptyList(), members.first().id,
            TrafficBindings(tags.entries.associate { (id, tag) -> tag to listOf(id) },
                members.associate { it.id to TrafficTotals(0, 0) }, emptySet()), tags, members.first().groupId)
        val instance = ProxyInstance(members.first(), ProxyService(), "selector", RunGeneration(SagerDatabase.dataGeneration(), 0L))
        instance.config = config
        val core = CoreBox()
        instance.box = core
        val looper = checkNotNull(instance.looper)
        DataStore.profileTrafficStatistics = true
        try {
            // 服务还未发布此实例，定时循环不能采样。三段流量只经切换和停止结算。
            instance.launch()
            for ((index, member) in members.withIndex()) {
                if (index > 0) looper.selectMain(member.id)
                core.state().stats.route(TAG_PROXY, (index + 1) * 10L, (index + 1) * 100L)
            }
            instance.closeWithTraffic()
            for ((index, member) in members.withIndex()) {
                val row = SagerDatabase.proxyDao.getById(member.id)!!
                assertEquals((index + 1) * 10L, row.tx)
                assertEquals((index + 1) * 100L, row.rx)
            }
        } finally {
            instance.close()
        }
    }

    @Test
    fun `建账前收到的多次分组清零按节点生效，下一轮只落新流量`() {
        val looper = checkNotNull(ProxyInstance(profile(), ProxyService(), "traffic", RunGeneration(0L, 0L)).looper)
        val old = TrafficTotals(4000, 1000)
        val config = ConfigBuildResult(
            "{}", emptyList(), 1L,
            TrafficBindings(mapOf(TAG_PROXY to listOf(1L, 2L, 3L)), (1L..3L).associateWith { old }, emptySet()),
            emptyMap(), -1L,
        )
        val stats = TrafficAccountingTest.FakeStats(listOf(TAG_PROXY))
        looper.applyClears(mapOf(1L to 1L, 99L to 1L))
        looper.applyClears(mapOf(1L to 2L, 2L to 1L))
        val acc = looper.initializeAccounting(config, stats::query, 0L)
        val zero = TrafficTotals(0, 0)
        assertEquals(mapOf(1L to zero, 2L to zero, 3L to old), acc.snapshot())
        assertEquals(listOf("$TAG_PROXY/uplink", "$TAG_PROXY/downlink"), stats.takeLog())
        assertTrue(acc.claim(listOf(1L, 2L, 3L)).isEmpty())

        stats.route(TAG_PROXY, 10, 100)
        val round = acc.sweep(1000L)
        assertEquals(10L, round.txSession)
        assertEquals(100L, round.rxSession)
        looper.applyClears(mapOf(1L to 3L))
        assertEquals(mapOf(1L to zero, 2L to TrafficTotals(100, 10), 3L to TrafficTotals(4100, 1010)), acc.snapshot())
        assertEquals(listOf(TrafficIncrement(2, 100, 10, 1), TrafficIncrement(3, 100, 10)), acc.claim(listOf(1L, 2L, 3L)))
    }

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
    fun `外核的两个输出流写到日志前均已遮蔽凭据`() {
        val auth = LocalSocksAuth("synthetic-user", "synthetic-password")
        val config = ConfigBuildResult(
            """{"password":"main-secret"}""", emptyList(), 1L, TrafficBindings(emptyMap(), emptyMap(), emptySet()),
            emptyMap(), -1L, localAuth = auth,
        )
        val redact = config.externalOutputRedactor(listOf("""{"user":{"password":"plugin-secret"}}"""))
        val received = CountDownLatch(2)
        val lines = CopyOnWriteArrayList<String>()
        ShadowLifecycleLibcore.log = {
            if (it.startsWith("[sh] ")) {
                lines.add(it)
                received.countDown()
            }
        }
        val pool = GuardedProcessPool { fail("模拟外核意外退出") }
        Logs.enabled = true
        try {
            // 等标准输入使两个输出线程都读完后才关闭进程。
            pool.start(
                listOf("/bin/sh", "-c", """echo "out ${'$'}OUT"; echo "err ${'$'}ERR" >&2; read -r stop"""),
                mapOf("OUT" to "main-secret ${auth.password}", "ERR" to "plugin-secret ${auth.withWrongPassword().password}"),
                redact,
            )
            assertTrue(received.await(10, TimeUnit.SECONDS))
            assertEquals(setOf("[sh] out *** ***", "[sh] err *** ***"), lines.toSet())
        } finally {
            ui.viaWorker { runBlocking { withTimeout(10_000) { pool.close(this).join() } } }
            Logs.enabled = false
        }
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
        val stats = TrafficAccountingTest.FakeStats(listOf(TAG_PROXY))

        @Implementation
        fun queryStats(tag: String, direction: String): Long {
            check(closed.count > 0) { "核心关闭后不能再查询流量" }
            return stats.query(tag, direction)
        }

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
            var log: (String) -> Unit = {}
            var urlTests = 0

            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}

            @JvmStatic
            @Implementation
            fun nekoLogPrintln(message: String) = log(message)

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
                log = {}
                create = { error("未设置测试核心") }
                test = { error("不应进入测速") }
                urlTests = 0
            }
        }
    }
}
