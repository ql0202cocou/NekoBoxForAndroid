package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

// DataGeneration.Dao 的 SQL（注解与这里用的是同一组常量）在按 10 版 schema 建出的内存库上执行：
// 恢复时的「先补行再 +1」从空表起得 1、再来一次得 2，补行不会把已有的世代号清回 0
class DataGenerationSqlTest {

    private fun open() = SqliteMemoryDb().also { it.createSchema(Schema.load(10)) }

    private fun SqliteMemoryDb.bump() {
        execute(DataGeneration.SQL_ENSURE_ROW)
        execute(DataGeneration.SQL_INCREMENT)
    }

    @Test
    fun `空表读不到行，补行加一得 1，再加一得 2`() {
        open().use { db ->
            assertEquals(emptyList<List<Any?>>(), db.query(DataGeneration.SQL_GET))
            db.bump()
            assertEquals(1L, db.scalar(DataGeneration.SQL_GET))
            db.bump()
            assertEquals(2L, db.scalar(DataGeneration.SQL_GET))
            // 始终只有 id = 1 这一行
            assertEquals(listOf(listOf(DataGeneration.ROW_ID, 2L)), db.query("SELECT id, generation FROM data_generation"))
        }
    }

    @Test
    fun `补行不覆盖已有的世代号，只加一不补行时空表保持为空`() {
        open().use { db ->
            // 没有补行的 UPDATE 在空表上什么也不做：这就是 +1 之前必须先 INSERT OR IGNORE 的原因
            db.execute(DataGeneration.SQL_INCREMENT)
            assertEquals(emptyList<List<Any?>>(), db.query(DataGeneration.SQL_GET))

            db.execute("INSERT INTO data_generation (id, generation) VALUES (1, 41)")
            db.execute(DataGeneration.SQL_ENSURE_ROW)
            assertEquals(41L, db.scalar(DataGeneration.SQL_GET))
            db.bump()
            assertEquals(42L, db.scalar(DataGeneration.SQL_GET))
        }
    }
}
