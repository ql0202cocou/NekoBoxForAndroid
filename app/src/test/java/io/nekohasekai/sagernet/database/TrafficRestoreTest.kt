package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.TrafficIncrement
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test

/** 使用真实恢复事务制造 id 复用，模拟已排队的切换和最终流量结算。 */
class TrafficRestoreTest : UiTestBase() {
    @Test
    fun `恢复后的同 id 节点不接收旧运行流量而新运行可以正常结算`() = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "Traffic fixture"))
        val original = ProfileRepository.createProfile(group.id, SOCKSBean().apply {
            serverAddress = "192.0.2.1"
            serverPort = 1080
        })
        val (snapshot, generation) = ProfileManager.getProfileForRun(original.id)!!
        assertEquals(original.id, snapshot.id)
        ProfileManager.persistTrafficIncrements(listOf(TrafficIncrement(original.id, 20, 10)), generation)
        val restored = SagerDatabase.proxyDao.getById(original.id)!!.apply {
            requireBean().serverAddress = "192.0.2.2"
            tx = 500
            rx = 600
        }
        BackupRestore.commit(BackupRestore.Decoded(listOf(restored), listOf(group), null, null, 0), null)
        repeat(2) {
            ProfileManager.persistTrafficIncrements(listOf(TrafficIncrement(original.id, 200, 100)), generation)
        }
        val current = ProfileManager.getProfileForRun(original.id)!!
        assertNotEquals(generation, current.second)
        assertEquals("192.0.2.2", current.first.requireBean().serverAddress)
        assertEquals(600L, current.first.rx)
        assertEquals(500L, current.first.tx)
        ProfileManager.persistTrafficIncrements(listOf(TrafficIncrement(original.id, 7, 3)), current.second)
        val saved = SagerDatabase.proxyDao.getById(original.id)!!
        assertEquals(607L, saved.rx)
        assertEquals(503L, saved.tx)
    }

    @Test
    fun `同世代整批流量写失败全部回滚并能重试`() = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "Traffic rollback"))
        val nodes = List(2) { ProfileRepository.createProfile(group.id, SOCKSBean().apply {
            serverAddress = "192.0.2.1"; serverPort = 1080
        }) }
        val generation = ProfileManager.getProfileForRun(nodes[0].id)!!.second
        val increments = nodes.map { TrafficIncrement(it.id, 7, 3) }
        val db = SagerDatabase.instance.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER fail_traffic BEFORE UPDATE OF tx ON proxy_entities WHEN OLD.id = ${nodes[1].id} BEGIN SELECT RAISE(ABORT, 'synthetic traffic failure'); END")
        try {
            assertNotNull(runCatching { ProfileManager.persistTrafficIncrements(increments, generation) }.exceptionOrNull())
            assertTrue(SagerDatabase.proxyDao.getByGroup(group.id).all { it.rx == 0L && it.tx == 0L })
        } finally { db.execSQL("DROP TRIGGER fail_traffic") }
        ProfileManager.persistTrafficIncrements(increments, generation)
        assertTrue(SagerDatabase.proxyDao.getByGroup(group.id).all { it.rx == 7L && it.tx == 3L })
    }
}
