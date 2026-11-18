package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.RunGeneration
import io.nekohasekai.sagernet.bg.proto.BoxInstanceLifecycleTest.ShadowCoreBox
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.uitest.UiFixtures
import io.nekohasekai.sagernet.uitest.UiSettle
import io.nekohasekai.sagernet.uitest.UiTestBase
import libcore.BoxInstance
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Config(shadows = [ShadowCoreBox::class])
class ServiceFinalTrafficTest : UiTestBase() {

    private enum class Shutdown { STOP, DESTROY, OVERLAP }

    @Test
    fun `正常停止补采末段，重复关停只落一次增量`() = verifyFinalTraffic(Shutdown.STOP)

    @Test
    fun `框架直接销毁也结算已采样和未采样的流量`() = verifyFinalTraffic(Shutdown.DESTROY)

    @Test
    fun `首次采样之前停止也能取到核心计数`() = verifyFinalTraffic(Shutdown.STOP, sampled = false)

    @Test
    fun `销毁时旧世代的增量不能写入恢复后的节点`() = verifyFinalTraffic(Shutdown.DESTROY, restored = true)

    @Test
    fun `正常停止补采期间框架销毁也只结算一次`() = verifyFinalTraffic(Shutdown.OVERLAP)

    private fun verifyFinalTraffic(shutdown: Shutdown, sampled: Boolean = true, restored: Boolean = false) {
        val profile = UiFixtures.install().profiles[3]
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        val generation = ui.offMain {
            DataStore.profileTrafficStatistics = true
            SagerDatabase.dataGeneration()
        }
        val proxy = ProxyInstance(profile, service, "traffic", RunGeneration(generation, 0L))
        val core = BoxInstance()
        val state = Shadow.extract<ShadowCoreBox>(core)
        val config = ConfigBuildResult(
            "{}", emptyList(), profile.id,
            TrafficBindings(mapOf(TAG_PROXY to listOf(profile.id)), mapOf(profile.id to TrafficTotals(0, 0)), emptySet()),
            emptyMap(), -1L,
        )
        proxy.config = config
        proxy.box = core
        service.data.proxy = proxy
        val stats = state.stats
        stats.route(TAG_PROXY, 1024, 80)
        val querying = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var finalSweep = false
        if (sampled) {
            val acc = proxy.looper!!.initializeAccounting(config, { tag, direction ->
                if (finalSweep && shutdown == Shutdown.OVERLAP) {
                    querying.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                }
                core.queryStats(tag, direction)
            }, 0)
            assertEquals(1024L, acc.sweep(1000).txSession)
        }
        stats.route(TAG_PROXY, 256, 20)
        if (restored) ui.offMain { SagerDatabase.instance.runInTransaction { SagerDatabase.bumpDataGeneration() } }
        finalSweep = true
        when (shutdown) {
            Shutdown.STOP -> {
                ui.bg { service.killProcesses() }
                controller.destroy()
            }
            Shutdown.DESTROY -> controller.destroy()
            Shutdown.OVERLAP -> {
                val stop = runOnIoDispatcher { service.killProcesses() }
                try {
                    assertTrue(querying.await(10, TimeUnit.SECONDS))
                    controller.destroy()
                } finally {
                    resume.countDown()
                }
                ui.bg { stop.join() }
            }
        }
        UiSettle.settle()
        assertNull(service.data.proxy)
        assertEquals(1, state.closes)
        assertEquals(if (sampled) 2 else 1, stats.takeLog().count { it == "$TAG_PROXY/uplink" })
        val saved = ui.offMain { SagerDatabase.proxyDao.getById(profile.id)!! }
        assertEquals(if (restored) 0L else 1280L, saved.tx)
        assertEquals(if (restored) 0L else 100L, saved.rx)
    }
}
