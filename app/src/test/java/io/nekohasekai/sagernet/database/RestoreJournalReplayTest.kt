package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteException
import io.nekohasekai.sagernet.database.RestoreJournal.Result
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.BackupFragment
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreJournalReplayTest : UiTestBase() {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun damagedJournalRequiresACompleteReplacement() {
        val dir = temp.newFolder()
        val journal = RestoreJournal(dir, File(temp.root, "restore.lock"))
        val pending = File(dir, "pending.json")
        val rulesOnly = JSONObject().put("version", 1).put("rules", JSONArray())
        val full = JSONObject(rulesOnly.toString()).put("profiles", JSONArray())
            .put("groups", JSONArray()).put("settings", JSONArray())
        val badFlags = """{"profiles":false,"rules":true,"backup":$rulesOnly}"""
        val badGroup = """{"profiles":true,"rules":true,"settings":false,"backup":{"version":1,"profiles":[],"rules":[]}}"""
        for (damaged in listOf("{", "{}", badFlags, badGroup)) {
            pending.writeText(damaged)
            assertEquals(Result.PENDING, journal.completePending { _, _, _, _ -> fail() })
            assertThrows(RestoreJournal.IncompleteReplacementException::class.java) {
                journal.withLock { journal.stage(rulesOnly, true, true, true) }
            }
            assertThrows(RestoreJournal.IncompleteReplacementException::class.java) {
                journal.withLock { journal.stage(full, true, true, false) }
            }
            assertEquals(damaged, pending.readText())
            assertTrue(journal.withLock { journal.stage(full, true, true, true) })
            assertEquals(Result.REPLAYED, journal.completePending { backup, profile, rule, setting ->
                assertEquals(full.toString(), backup.toString())
                assertTrue(profile && rule && setting)
            })
            assertFalse(journal.isPending())
        }
        // 历史日志中隐藏项也可能被勾选；有效的单项恢复仍能重放。
        pending.writeText("""{"profiles":true,"rules":true,"settings":true,"backup":$rulesOnly}""")
        assertEquals(Result.REPLAYED, journal.completePending { _, profile, rule, setting ->
            assertTrue(!profile && rule && !setting)
        })
    }

    @Test
    fun repeatedSettingsFailureKeepsRecoveryAvailableToTheNextProcess() = ui.bg {
        val journal = RestoreJournal.default
        val settings = PublicDatabase.kvPairDao
        val db = PublicDatabase.instance.openHelper.writableDatabase
        val rulesDb = SagerDatabase.instance.openHelper.writableDatabase
        val rule = RuleEntity(id = 1, name = "Imported", domains = "example.org")
        val fragment = BackupFragment()
        SagerDatabase.rulesDao.insert(listOf(rule))
        settings.put(KeyValuePair("restore_test").put("new"))
        val backup = JSONObject(fragment.doBackup(false, true, true))
        val decoded = BackupRestore.decode(backup, false, true, true)
        val oldRule = rule.copy(name = "Original")
        SagerDatabase.rulesDao.reset()
        SagerDatabase.rulesDao.insert(listOf(oldRule))
        settings.put(KeyValuePair("restore_test").put("old"))
        db.execSQL("""CREATE TRIGGER reject_settings BEFORE INSERT ON KeyValuePair
            BEGIN SELECT RAISE(ABORT, 'test'); END""")
        try {
            // 普通失败能补偿时退回完整旧状态，无需在下次启动再恢复。
            assertThrows(SQLiteException::class.java) { fragment.finishImport(backup, decoded) }
            assertEquals(listOf(oldRule), SagerDatabase.rulesDao.allRules())
            assertFalse(journal.isPending())
            // 首库已提交，第二库失败，首库的补偿也被 SQLite 拒绝。
            rulesDb.execSQL("""CREATE TRIGGER reject_compensation BEFORE INSERT ON rules WHEN NEW.name='Original'
                BEGIN SELECT RAISE(ABORT, 'test'); END""")
            assertThrows(SQLiteException::class.java) { fragment.finishImport(backup, decoded) }
            assertEquals(listOf(rule), SagerDatabase.rulesDao.allRules())
            assertTrue(journal.isPending())
            repeat(RestoreJournal.MAX_ATTEMPTS) {
                assertEquals(Result.PENDING, BackupRestore.replayPending())
                assertEquals(listOf(rule), SagerDatabase.rulesDao.allRules())
                assertEquals("old", settings["restore_test"]!!.string)
            }
            // 模拟后台先耗尽次数，随后主进程检查同一日志；不会再自动写库，仍能得到待恢复提示。
            assertEquals(Result.PENDING, journal.completePending { _, _, _, _ -> fail("自动重试已达上限") })
            assertEquals(Result.PENDING, BackupRestore.replayPending(retry = true))
            assertTrue(journal.isPending())
            val rulesOnly = BackupRestore.decode(backup, false, true, false)
            assertThrows(RestoreJournal.IncompleteReplacementException::class.java) {
                fragment.finishImport(backup, rulesOnly)
            }
            assertTrue(journal.isPending())
            db.execSQL("DROP TRIGGER reject_settings")
            assertEquals(Result.REPLAYED, BackupRestore.replayPending(retry = true))
            assertEquals(listOf(rule), SagerDatabase.rulesDao.allRules())
            assertEquals("new", settings["restore_test"]!!.string)
            assertFalse(journal.isPending())
            assertEquals(Result.NONE, BackupRestore.replayPending())
        } finally {
            rulesDb.execSQL("DROP TRIGGER IF EXISTS reject_compensation")
            db.execSQL("DROP TRIGGER IF EXISTS reject_settings")
            journal.withLock { journal.clear() }
        }
    }
}
