package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ProfileRecord
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.fmt.trafficBindingsOf
import io.nekohasekai.sagernet.uitest.UiFixtures
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test

class TrafficCheckpointTest : UiTestBase() {

    // 建账经生产节点快照、绑定和 Room，只有 JNI 的逐方向计数由夹具提供。
    private class Run(vararg profiles: ProxyEntity) {
        val generation = SagerDatabase.dataGeneration()
        val stats = TrafficAccountingTest.FakeStats(listOf(TAG_PROXY))
        private val rows = profiles.map { ProfileRecord.of(it).newEntity() }
        private val config = ConfigBuildResult("{}", emptyList(), rows.first().id,
            trafficBindingsOf(mapOf(TAG_PROXY to rows), null), emptyMap(), -1L)
        val looper = ProxyInstance(profiles.first(), ProxyService(), "checkpoint", generation).also {
            it.config = config
        }.looper!!
        val accounting = looper.initializeAccounting(config, stats::query, 0L)
        suspend fun tick(ms: Long) = looper.sampleTraffic(accounting, ms)
    }

    private fun persisted(id: Long): TrafficTotals = SagerDatabase.proxyDao.getById(id)!!.let { TrafficTotals(it.rx, it.tx) }

    @Test
    fun `检查点保存整分钟内的增量，模拟丢弃运行实例后从已提交值继续`() = ui.bg {
        val row = UiFixtures.install().profiles[3]
        DataStore.speedInterval = 0
        DataStore.profileTrafficStatistics = true
        val first = Run(row)
        first.stats.route(TAG_PROXY, 20, 75)
        first.tick(2000)
        first.tick(59_999)
        assertEquals(TrafficTotals(0, 0), persisted(row.id))
        // 到期这一轮完全空闲，之前采到的流量仍要入库。
        first.tick(60_000)
        assertEquals(TrafficTotals(75, 20), persisted(row.id))
        first.stats.route(TAG_PROXY, 8, 11)
        first.tick(61_000)
        val restart = Run(SagerDatabase.proxyDao.getById(row.id)!!)
        restart.tick(60_000)
        assertEquals(TrafficTotals(75, 20), persisted(row.id))
        restart.stats.route(TAG_PROXY, 1, 2)
        restart.tick(120_000)
        restart.looper.persistStats()
        assertEquals(TrafficTotals(77, 21), persisted(row.id))
    }

    @Test
    fun `清零与旧批次交错且通知延迟时只丢弃目标节点的旧字节`() = ui.bg {
        val rows = UiFixtures.install().profiles
        val cleared = rows[3]
        val shared = rows[7]
        val initial = persisted(shared.id)
        val run = Run(cleared, shared)
        DataStore.profileTrafficStatistics = true
        run.stats.route(TAG_PROXY, 10, 100)
        run.tick(1000)
        val beforeClear = run.accounting.claim()
        ProfileRepository.clearGroupTraffic(cleared.groupId)
        val stale = ProfileManager.persistTrafficIncrements(beforeClear, run.generation)
        assertEquals(mapOf(cleared.id to 1L), stale)
        assertEquals(TrafficTotals(0, 0), persisted(cleared.id))

        // 即使漏收广播，周期写入也会发现版本已变，清零后重新接续。
        run.stats.route(TAG_PROXY, 2, 3)
        run.tick(60_000)
        assertEquals(TrafficTotals(0, 0), run.accounting.snapshot()[cleared.id])
        run.stats.route(TAG_PROXY, 4, 7)
        run.looper.clearStats(longArrayOf(cleared.id))
        run.tick(120_000)
        assertEquals(TrafficTotals(7, 4), persisted(cleared.id))
        assertEquals(TrafficTotals(initial.rx + 110, initial.tx + 16), persisted(shared.id))

        // 再启动时从 Room 快照带入非零版本，晚到的相同通知不能清掉新流量。
        val restart = Run(SagerDatabase.proxyDao.getById(cleared.id)!!)
        restart.stats.route(TAG_PROXY, 1, 2)
        restart.tick(100)
        restart.looper.clearStats(longArrayOf(cleared.id))
        restart.tick(60_000)
        assertEquals(TrafficTotals(9, 5), persisted(cleared.id))
    }

    @Test
    fun `清零前未采到的核心计数只保留在共享节点与会话中`() = ui.bg {
        val rows = UiFixtures.install().profiles
        val cleared = rows[3]
        val shared = rows[7]
        val initial = persisted(shared.id)
        DataStore.profileTrafficStatistics = true
        val run = Run(cleared, shared)
        run.stats.route(TAG_PROXY, 3, 8)
        ProfileRepository.clearGroupTraffic(cleared.groupId)
        run.looper.clearStats(longArrayOf(cleared.id))
        run.stats.route(TAG_PROXY, 5, 13)
        val round = run.tick(60_000)
        assertEquals(21L, round.rxSession)
        assertEquals(TrafficTotals(13, 5), persisted(cleared.id))
        assertEquals(TrafficTotals(initial.rx + 21, initial.tx + 8), persisted(shared.id))
    }

    @Test
    fun `统计关闭时不写库，写入失败后即使没有新流量也能在下一周期补记`() = ui.bg {
        val row = UiFixtures.install().profiles[3]
        DataStore.profileTrafficStatistics = false
        val disabled = Run(row)
        disabled.stats.route(TAG_PROXY, 15, 45)
        disabled.tick(60_000)
        assertEquals(TrafficTotals(0, 0), persisted(row.id))

        DataStore.profileTrafficStatistics = true
        val run = Run(row)
        run.stats.route(TAG_PROXY, 4, 7)
        val db = SagerDatabase.instance.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_checkpoint BEFORE UPDATE OF tx ON proxy_entities BEGIN SELECT RAISE(ABORT, 'test checkpoint'); END")
        try {
            run.tick(60_000)
            assertEquals(TrafficTotals(0, 0), persisted(row.id))
        } finally {
            db.execSQL("DROP TRIGGER fail_checkpoint")
        }
        run.tick(61_000)
        assertEquals(TrafficTotals(0, 0), persisted(row.id))
        run.tick(120_000)
        assertEquals(TrafficTotals(7, 4), persisted(row.id))
    }
}
