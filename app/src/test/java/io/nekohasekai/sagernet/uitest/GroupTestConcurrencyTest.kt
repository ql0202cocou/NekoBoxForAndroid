package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.PingAddressCache
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.atomic.AtomicInteger

class GroupTestConcurrencyTest : UiTestBase() {

    @Test
    fun pingKeepsTheResolvedScopeAndCachesFailures() = ui.bg {
        val cache = PingAddressCache()
        val addr = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 2)
        assertEquals(2, (cache.resolve("scoped.example") { addr } as Inet6Address).scopeId)
        assertTrue(addr === cache.resolve("scoped.example") { error("duplicate lookup") })
        assertEquals(2, (cache.resolve("fe80::1%2") { error("looked up numeric address") } as Inet6Address).scopeId)
        assertEquals(null, cache.resolve("absent.example") { null })
        assertEquals(null, cache.resolve("absent.example") { error("duplicate lookup") })
    }

    @Test
    fun sizedForEligibleProfilesAndBoundedAfterRestore() {
        val key = "connectionTestConcurrent"
        ui.offMain {
            val store = DataStore.configurationStore
            store.remove(key)
            assertEquals(5, DataStore.groupTestWorkerCount(100))
            DataStore.connectionTestConcurrent = 12
            assertEquals(0, DataStore.groupTestWorkerCount(0))
            assertEquals(1, DataStore.groupTestWorkerCount(1))
            assertEquals(12, DataStore.groupTestWorkerCount(100))

            DataStore.connectionTestConcurrent = Int.MAX_VALUE
            assertEquals(16, store.getInt(key))
            // 备份恢复直接写底层键值，绕过偏好设置界面。
            store.putLong(key, Long.MAX_VALUE)
            assertEquals(16, DataStore.groupTestWorkerCount(100))
            for (invalid in listOf(0L, -10L)) {
                store.putLong(key, invalid)
                assertEquals(5, DataStore.groupTestWorkerCount(100))
            }
        }
    }

    @Test
    @Config(shadows = [ShadowTestInstance::class])
    fun urlTestRunsAtMostSixteenNodesAndFinishesTheQueue() {
        val fixtures = UiFixtures.install()
        UiFixtures.addProfiles(fixtures.normalGroup, 18)
        ui.offMain { DataStore.configurationStore.putInt("connectionTestConcurrent", 1000) }
        val config = ui.launch<MainActivity>().hostedFragment<ConfigurationFragment>()
        val item = config.toolbar.menu.findItem(R.id.action_connection_url_test)
        ShadowTestInstance.calls.set(0)
        ShadowTestInstance.release = CompletableDeferred()

        try {
            config.onMenuItemClick(item)
            UiSettle.settle()
            assertEquals(16, ShadowTestInstance.calls.get())
        } finally {
            ShadowTestInstance.release.complete(Unit)
            UiSettle.settle()
        }
        val profiles = ui.offMain { SagerDatabase.proxyDao.getByGroup(fixtures.normalGroup.id) }
        assertEquals(25, profiles.size)
        assertEquals(profiles.size, ShadowTestInstance.calls.get())
        assertTrue(profiles.all { it.status == 1 && it.ping == 37 })
    }

    @Implements(TestInstance::class, isInAndroidSdk = false)
    class ShadowTestInstance {
        companion object {
            val calls = AtomicInteger()
            lateinit var release: CompletableDeferred<Unit>
        }

        // 挂住第一批测速，统计真实菜单入口派出的任务，放行后再检查整组结果。
        @Implementation
        protected suspend fun doTest(): Int {
            calls.incrementAndGet()
            release.await()
            return 37
        }
    }
}
