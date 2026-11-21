package io.nekohasekai.sagernet.fmt

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigSnapshotTransactionTest : UiTestBase() {

    @Test
    fun `快照期间独立数据库连接可以读取已提交的分组`() = ui.offMain {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "snapshot-group"))
        val db = SagerDatabase.instance.openHelper.readableDatabase
        SQLiteDatabase.openDatabase(requireNotNull(db.path), null, SQLiteDatabase.OPEN_READWRITE).use { other ->
            other.rawQuery("PRAGMA busy_timeout = 0", null).use { it.moveToFirst() }
            fun rename() = other.execSQL("UPDATE proxy_groups SET name = 'updated' WHERE id = ?", arrayOf(id))
            readConfigSnapshot {
                assertEquals("snapshot-group", SagerDatabase.groupDao.getById(id)?.name)
                other.rawQuery("SELECT name FROM proxy_groups WHERE id = ?", arrayOf(id.toString())).use {
                    it.moveToFirst()
                    assertEquals("snapshot-group", it.getString(0))
                }
                assertThrows(SQLiteDatabaseLockedException::class.java) { rename() }
                assertEquals("snapshot-group", SagerDatabase.groupDao.getById(id)?.name)
            }
            rename()
            assertEquals("updated", SagerDatabase.groupDao.getById(id)?.name)
        }
        assertThrows(IllegalStateException::class.java) { readConfigSnapshot { error("test") } }
        assertFalse(db.inTransaction())
    }
}
