package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.captureConfigInput
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.resolveChainInternal
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.ChainMembersViewModel
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test

/** 真实 Room / SQLite 查询，不以 SQL 字符串桩代替数据库的参数限制。 */
class ProfileBatchQueryTest : UiTestBase() {
    private fun install(): ProxyEntity = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "Query fixture"))
        DataStore.selectedGroup = group.id
        DataStore.rulesFirstCreate = true
        ProfileRepository.createProfile(group.id, SOCKSBean().apply {
            name = "SOCKS fixture"
            serverAddress = "192.0.2.1"
            serverPort = 1080
        })
    }

    private fun aboveSqliteLimit(): Int = ui.bg {
        val db = SagerDatabase.instance.openHelper.readableDatabase
        // 旧 SQLite 的 compile_options 未必报告默认上限，直接 prepare 编号参数探测实际限制。
        fun accepts(count: Int): Boolean = try {
            db.compileStatement("SELECT ?$count").close()
            true
        } catch (e: SQLiteException) {
            assertTrue(e.toString(), e.message.orEmpty().contains("variable number"))
            false
        }
        var low = 1
        var high = 1024
        while (accepts(high)) {
            low = high
            high *= 2
            check(high <= 1_048_576) { "SQLite parameter limit probe exceeded its bound" }
        }
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (accepts(mid)) low = mid else high = mid
        }
        println("SQLite parameter limit=$low")
        high
    }

    @Test
    fun `超过 SQLite 参数上限的不同 id 仍能查询且缺失节点省略`() {
        val node = install()
        val count = aboveSqliteLimit()
        val ids = (node.id until node.id + count).reversed().toList()
        assertEquals(listOf(node.id), ui.bg { SagerDatabase.proxyDao.getEntities(ids).map { it.id } })
        assertEquals(listOf(node.id), ui.bg { ProfileRepository.getProfiles(ids).map { it.id } })
    }

    @Test
    fun `保存后重读的超长重复成员链在各模式均能采集完整快照`() {
        val node = install()
        val ids = List(aboveSqliteLimit()) { node.id }
        val chain = ui.bg {
            val created = ProfileRepository.createProfile(node.groupId, ChainBean().apply {
                name = "Long chain fixture"
                proxies = ids
            })
            ProfileRepository.getProfile(created.id)!!
        }
        assertEquals(ids, chain.chainBean!!.proxies)
        // 走真实编辑器重载：查询错误曾被仓库转成空列表，导致有效成员全部显示为缺失。
        val editor = ui.launchEditor<ChainSettingsActivity>(chain.id)
        val members = ViewModelProvider(editor.get())[ChainMembersViewModel::class.java]
        assertTrue(members.loaded)
        assertEquals(ids.size, members.proxyList.size)
        assertEquals(ids, members.proxyList.map { it.id })
        assertTrue(members.missingIds.isEmpty())
        ui.destroyNow(editor)
        for (mode in ConfigBuildMode.entries) {
            val input = ui.bg { captureConfigInput(chain, mode) }
            assertEquals(ids, input.main.newEntity().chainBean!!.proxies)
            assertEquals(node.id, input.data.profile(node.id)!!.id)
            assertEquals(ids, input.main.newEntity().resolveChainInternal(input.data) { fail(it) }.map { it.id })
        }
    }

    @Test
    fun `分批边界保持主键顺序唯一行和完整字段且全程处于事务内`() = ui.bg {
        val template = install()
        val queries = mutableListOf<Pair<Int, Boolean>>()
        var failOnSecondBatch = false
        lateinit var db: SagerDatabase
        db = Room.inMemoryDatabaseBuilder(app, SagerDatabase::class.java)
            .setQueryCallback({ sql, args ->
                if (sql.startsWith("SELECT * FROM proxy_entities WHERE id", ignoreCase = true) && " IN (" in sql.uppercase()) {
                    queries += args.size to db.inTransaction()
                    if (failOnSecondBatch && queries.size == 2) {
                        // 第二批准备前临时改表名，让真实查询失败；事务退出时必须回滚，不能返回第一批。
                        failOnSecondBatch = false
                        db.openHelper.writableDatabase.execSQL("ALTER TABLE proxy_entities RENAME TO unavailable_profiles")
                    }
                }
            }, { it.run() })
            .build()
        try {
            val dao = db.proxyDao()
            val rows = (1L..2005L).map { id ->
                template.copy(id = id * 2, userOrder = 2006 - id, tx = id, rx = id * 3)
            }
            dao.insert(rows)
            for (count in listOf(0, 1, 999, 1000, 1998, 1999, 2005)) {
                queries.clear()
                val expected = rows.take(count)
                val ids = expected.map { it.id }.reversed()
                val found = dao.getEntities(ids + ids)
                assertEquals(expected.map { it.id }, found.map { it.id })
                found.zip(expected).forEach { (actual, original) ->
                    assertEquals(original.userOrder, actual.userOrder)
                    assertEquals(original.tx, actual.tx)
                    assertEquals(original.rx, actual.rx)
                    assertArrayEquals(KryoConverters.serialize(original.requireBean()), KryoConverters.serialize(actual.requireBean()))
                }
                assertEquals((count + 998) / 999, queries.size)
                assertTrue(queries.toString(), queries.all { (size, transaction) -> size in 1..999 && transaction })
            }
            // 缺失 id 分布在所有批次中；返回行仍按全局主键排序，重复请求不增加返回次数。
            val withMissing = (1L..4010L).reversed().toList()
            assertEquals(rows.map { it.id }, dao.getEntities(withMissing + withMissing).map { it.id })
            queries.clear()
            failOnSecondBatch = true
            val failure = runCatching { dao.getEntities(rows.map { it.id }) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SQLiteException)
            assertTrue(failure!!.message.orEmpty().contains("no such table: proxy_entities"))
            assertFalse(db.inTransaction())
            assertEquals(rows.map { it.id }, dao.getEntities(rows.map { it.id }).map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `跨批次的倒序重复和缺失成员在编辑器与三种模式中保持语义`() {
        val node = install()
        val rows = ui.bg {
            SagerDatabase.proxyDao.insert((1..2001).map { node.copy(id = 0, userOrder = it.toLong()) })
            SagerDatabase.proxyDao.getAll().sortedBy { it.id }
        }
        val missing = rows.last().id + 100
        val valid = rows.map { it.id }.reversed() + listOf(rows.first().id, rows.last().id)
        val ids = listOf(missing) + valid + missing
        val chain = ui.bg {
            val created = ProfileRepository.createProfile(node.groupId, ChainBean().apply {
                name = "Mixed chain fixture"
                proxies = ids
            })
            ProfileRepository.getProfile(created.id)!!
        }
        val editor = ui.launchEditor<ChainSettingsActivity>(chain.id)
        val members = ViewModelProvider(editor.get())[ChainMembersViewModel::class.java]
        assertTrue(members.loaded)
        assertEquals(valid, members.proxyList.map { it.id })
        assertEquals(listOf(missing, missing), members.missingIds)
        assertEquals(ids.joinToString(","), EditorCache.serverProtocol)
        ui.destroyNow(editor)
        for (mode in ConfigBuildMode.entries) {
            val input = ui.bg { captureConfigInput(chain, mode) }
            assertEquals(ids, input.main.newEntity().chainBean!!.proxies)
            assertNull(input.data.profile(missing))
            val warnings = mutableListOf<String>()
            val expanded = input.main.newEntity().resolveChainInternal(input.data, warnings::add)
            assertEquals(valid.reversed(), expanded.map { it.id })
            assertEquals(List(2) { "chain profile ${chain.id} references missing profile $missing, skipped" }, warnings)
        }
    }
}
