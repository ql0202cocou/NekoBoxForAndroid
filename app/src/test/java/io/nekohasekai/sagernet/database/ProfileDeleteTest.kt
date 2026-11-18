package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test

/** 删除确认前的节点快照可能已过期，实际归属必须在删除事务内读取。 */
class ProfileDeleteTest : UiTestBase() {
    private suspend fun group() = GroupRepository.createGroup(ProxyGroup(name = "Delete fixture"))
    private suspend fun node(group: ProxyGroup) = ProfileRepository.createProfile(group.id, SOCKSBean().apply {
        name = "Synthetic node"
        serverAddress = "192.0.2.1"
        serverPort = 1080
    })

    private class Removed : ProfileManager.Listener {
        val rows = mutableListOf<Pair<Long, Long>>()
        override suspend fun onAdd(profile: ProxyEntity) {}
        override suspend fun onUpdated(profile: ProxyEntity) {}
        override suspend fun onUpdated(data: TrafficData) {}
        override suspend fun onRemoved(groupId: Long, profileId: Long) { rows += groupId to profileId }
    }

    @Test
    fun `确认前移组后按实际归属删除和通知且重复或已删除 id 不重复通知`() = ui.bg {
        val source = group()
        val target = group()
        val moved = node(source)
        val old = node(source)
        val first = node(target)
        val last = node(target)
        ProfileRepository.moveProfile(moved.id, target.id)
        SagerDatabase.proxyDao.updateOrder(first.id, 3)
        SagerDatabase.proxyDao.updateOrder(last.id, 7)
        SagerDatabase.proxyDao.deleteById(old.id)
        val removed = Removed()
        ProfileRepository.addListener(removed)
        try {
            ProfileRepository.deleteProfiles(listOf(moved, old, moved))
            assertEquals(listOf(target.id to moved.id), removed.rows)
            assertEquals(listOf(1L, 2L), SagerDatabase.proxyDao.getByGroup(target.id).map { it.userOrder })
            assertNull(ProfileRepository.getProfile(moved.id))
        } finally {
            ProfileRepository.removeListener(removed)
        }
    }

    @Test
    fun `单条入口同样不能信任旧分组且迟到流量不会重建已删除行`() = ui.bg {
        val source = group()
        val target = group()
        val row = node(source)
        val generation = ProfileManager.getProfileForRun(row.id)!!.second
        ProfileRepository.moveProfile(row.id, target.id)
        val removed = Removed()
        ProfileRepository.addListener(removed)
        try {
            ProfileRepository.deleteProfile(row.id)
            ProfileManager.persistTrafficIncrements(listOf(io.nekohasekai.sagernet.fmt.TrafficIncrement(row.id, 10, 20)), generation)
            assertEquals(listOf(target.id to row.id), removed.rows)
            assertNull(ProfileRepository.getProfile(row.id))
        } finally {
            ProfileRepository.removeListener(removed)
        }
    }

    @Test
    fun `混合组删除一次整理所有受影响组并清理选中和前置引用`() = ui.bg {
        val groups = List(2) { group() }
        val rows = groups.map { g -> List(3) { node(g) } }
        val deleted = rows.map { it[1] }
        val reference = group().apply { frontProxy = deleted[0].id; landingProxy = deleted[1].id }
        GroupRepository.updateGroup(reference)
        DataStore.selectedProxy = deleted[0].id
        ProfileRepository.deleteProfiles(deleted)
        for (g in groups) assertEquals(listOf(1L, 2L), SagerDatabase.proxyDao.getByGroup(g.id).map { it.userOrder })
        assertEquals(0L, DataStore.selectedProxy)
        val updated = GroupRepository.getGroup(reference.id)!!
        assertEquals(-1L, updated.frontProxy)
        assertEquals(-1L, updated.landingProxy)
    }

    @Test
    fun `中途删除失败整批回滚且不通知`() = ui.bg {
        val g = group()
        val rows = List(3) { node(g) }
        val db = SagerDatabase.instance.openHelper.writableDatabase
        val removed = Removed()
        ProfileRepository.addListener(removed)
        db.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON proxy_entities WHEN OLD.id = ${rows[1].id} BEGIN SELECT RAISE(ABORT, 'synthetic delete failure'); END")
        try {
            assertNotNull(runCatching { ProfileRepository.deleteProfiles(rows) }.exceptionOrNull())
            assertEquals(rows.map { it.id }, SagerDatabase.proxyDao.getByGroup(g.id).map { it.id })
            assertTrue(removed.rows.isEmpty())
        } finally {
            db.execSQL("DROP TRIGGER fail_delete")
            ProfileRepository.removeListener(removed)
        }
    }
}
