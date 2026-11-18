package io.nekohasekai.sagernet.database

import android.database.Cursor
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 使用真实 Room 与 Repository 验证跨组移动，不用内存 DAO 替代排序及逐列写入。 */
class ProfileMoveTest : UiTestBase() {
    private val dao get() = SagerDatabase.proxyDao
    private val db get() = SagerDatabase.instance.openHelper.writableDatabase

    private suspend fun group(name: String) = GroupRepository.createGroup(ProxyGroup(name = name))

    private suspend fun profile(group: ProxyGroup, name: String) = ProfileRepository.createProfile(
        group.id, ShadowsocksBean().apply {
            this.name = name
            serverAddress = "node.example.com"
            serverPort = 8388
            method = "aes-256-gcm"
            password = "fictional-move-password"
        }, ProxyEntity.CORE_SING_BOX,
    )

    @Test
    fun `移入非空组排在末尾且只改变分组与序号`() = ui.bg {
        val source = group("Source")
        val target = group("Target")
        val moved = profile(source, "Moved")
        val stayed = profile(source, "Stayed")
        val first = profile(target, "First")
        val last = profile(target, "Last")
        // 目标组允许有空号：追加位置取最大序号，不能用节点数量代替。
        dao.updateOrder(last.id, 8)
        dao.updateTraffic(moved.id, 1234, 5678)
        dao.updateStatus(moved.id, 3, 42, "synthetic previous error")
        db.execSQL("UPDATE proxy_entities SET uuid = 'move-test-uuid' WHERE id = ?", arrayOf(moved.id))
        DataStore.selectedProxy = moved.id
        DataStore.currentProfile = moved.id
        val before = snapshot(moved.id)
        val untouched = listOf(stayed, first, last).associate { it.id to snapshot(it.id) }
        val updates = Updates()
        ProfileRepository.addListener(updates)
        try {
            val result = ProfileRepository.moveProfile(moved.id, target.id)!!
            assertEquals(9L, result.userOrder)
            assertEquals(target.id, result.groupId)
            assertEquals(listOf(first.id, last.id, moved.id), dao.getByGroup(target.id).map { it.id })
            assertEquals(listOf(stayed.id), dao.getByGroup(source.id).map { it.id })
            assertEquals(before + mapOf("groupId" to target.id, "userOrder" to 9L), snapshot(moved.id))
            untouched.forEach { (id, row) -> assertEquals(row, snapshot(id)) }
            assertEquals(moved.id, DataStore.selectedProxy)
            assertEquals(moved.id, DataStore.currentProfile)
            assertEquals(listOf(Triple(moved.id, target.id, 9L)), updates.rows)
        } finally {
            ProfileRepository.removeListener(updates)
        }
    }

    @Test
    fun `移入空组从一开始且连续移动按完成顺序追加`() = ui.bg {
        val source = group("Source")
        val target = group("Target")
        val first = profile(source, "First")
        val second = profile(source, "Second")
        val third = profile(source, "Third")
        for ((index, node) in listOf(third, first, second).withIndex()) {
            val result = ProfileRepository.moveProfile(node.id, target.id)!!
            assertEquals(index + 1L, result.userOrder)
        }
        val created = profile(target, "Created after moves")
        assertEquals(4L, created.userOrder)
        assertEquals(listOf(third.id, first.id, second.id, created.id), dao.getByGroup(target.id).map { it.id })
        assertTrue(dao.getByGroup(source.id).isEmpty())
    }

    @Test
    fun `同组移动保留原位置且已删除节点不写入或通知`() = ui.bg {
        val target = group("Target")
        val first = profile(target, "First")
        val second = profile(target, "Second")
        val before = snapshot(first.id)
        val updates = Updates()
        ProfileRepository.addListener(updates)
        try {
            repeat(2) {
                val result = ProfileRepository.moveProfile(first.id, target.id)!!
                assertEquals(1L, result.userOrder)
                assertEquals(before, snapshot(first.id))
            }
            assertEquals(2, updates.rows.size)
            dao.deleteById(first.id)
            assertNull(ProfileRepository.moveProfile(first.id, target.id))
            assertEquals(2, updates.rows.size)
            assertEquals(listOf(second.id), dao.getByGroup(target.id).map { it.id })
        } finally {
            ProfileRepository.removeListener(updates)
        }
    }

    @Test
    fun `移动写入失败时完整回滚且解除故障后可重试`() = ui.bg {
        val source = group("Source")
        val target = group("Target")
        val moved = profile(source, "Moved")
        val existing = profile(target, "Existing")
        val before = snapshot(moved.id)
        val existingBefore = snapshot(existing.id)
        val updates = Updates()
        ProfileRepository.addListener(updates)
        // AFTER 触发器确认两个字段确已改变，再中止语句，验证没有部分移动或成功通知。
        db.execSQL("""
            CREATE TRIGGER reject_profile_move AFTER UPDATE OF groupId ON proxy_entities
            WHEN OLD.id = ${moved.id}
            BEGIN
                SELECT CASE WHEN NEW.groupId = ${target.id} AND NEW.userOrder = 2
                    THEN RAISE(ABORT, 'injected move failure')
                    ELSE RAISE(ABORT, 'unexpected move state') END;
            END
        """.trimIndent())
        try {
            val failure = runCatching { ProfileRepository.moveProfile(moved.id, target.id) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure.toString(), failure!!.message.orEmpty().contains("injected move failure"))
            assertEquals(before, snapshot(moved.id))
            assertEquals(existingBefore, snapshot(existing.id))
            assertTrue(updates.rows.isEmpty())
            db.execSQL("DROP TRIGGER reject_profile_move")
            val result = ProfileRepository.moveProfile(moved.id, target.id)!!
            assertEquals(2L, result.userOrder)
            assertEquals(listOf(existing.id, moved.id), dao.getByGroup(target.id).map { it.id })
            assertEquals(listOf(Triple(moved.id, target.id, 2L)), updates.rows)
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS reject_profile_move")
            ProfileRepository.removeListener(updates)
        }
    }

    @Test
    fun `并发移动和新增不会分配重复的目标序号`() {
        val (target, moved) = ui.bg {
            val source = group("Source")
            val target = group("Target")
            profile(target, "Existing")
            target to (1..4).map { profile(source, "Moved $it") }
        }
        val workers = Executors.newFixedThreadPool(8)
        val start = CyclicBarrier(9)
        try {
            val tasks = (0 until 8).map { index ->
                workers.submit<Long> {
                    start.await(10, TimeUnit.SECONDS)
                    runBlocking {
                        if (index < moved.size) ProfileRepository.moveProfile(moved[index].id, target.id)!!.id
                        else profile(target, "Created $index").id
                    }
                }
            }
            start.await(10, TimeUnit.SECONDS)
            val ids = tasks.map { it.get(10, TimeUnit.SECONDS) }
            val rows = ui.offMain { dao.getByGroup(target.id) }
            assertEquals((1L..9L).toList(), rows.map { it.userOrder })
            assertEquals(ids.toSet(), rows.drop(1).map { it.id }.toSet())
            assertEquals(8, ids.toSet().size)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    // 比较原始列与 bean 字节，不通过当前序列化器重新生成期望值。
    private fun snapshot(id: Long): Map<String, Any?> =
        db.query("SELECT * FROM proxy_entities WHERE id = ?", arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst())
            cursor.columnNames.withIndex().associate { (index, name) ->
                name to when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                    else -> cursor.getString(index)
                }
            }
        }

    private class Updates : ProfileManager.Listener {
        val rows = mutableListOf<Triple<Long, Long, Long>>()
        override suspend fun onUpdated(profile: ProxyEntity) {
            assertFalse("通知必须在移动事务提交后", SagerDatabase.instance.inTransaction())
            val stored = SagerDatabase.proxyDao.getById(profile.id)!!
            assertEquals(profile.groupId, stored.groupId)
            assertEquals(profile.userOrder, stored.userOrder)
            rows += Triple(profile.id, profile.groupId, profile.userOrder)
        }
        override suspend fun onAdd(profile: ProxyEntity) { fail("移动不能通知新增") }
        override suspend fun onUpdated(data: TrafficData) { fail("移动不能通知流量") }
        override suspend fun onRemoved(groupId: Long, profileId: Long) { fail("移动保留原更新通知契约") }
    }
}
