package io.nekohasekai.sagernet.golden

import android.database.sqlite.SQLiteException
import android.os.Bundle
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.golden.collect.CollectArgs
import io.nekohasekai.sagernet.golden.collect.CoreArgs
import io.nekohasekai.sagernet.golden.collect.DirtyArgs
import io.nekohasekai.sagernet.golden.collect.GoldenCollector
import io.nekohasekai.sagernet.golden.collect.CollectRefusedException
import io.nekohasekai.sagernet.golden.collect.GoldenSettingsJournal
import io.nekohasekai.sagernet.golden.measure.GoldenMeasure
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GoldenSessionTest : UiTestBase() {
    private val marker get() = File(app.filesDir, GoldenMeasure.OWNED_MARKER)
    private val dao get() = PublicDatabase.kvPairDao
    private fun row(value: String) = KeyValuePair().apply { key = "journal-test"; put(value) }

    @Test
    fun `重试保留第一个快照，恢复失败在真实 Room 回滚后仍可重放`() = ui.bg {
        val journal = GoldenSettingsJournal(marker)
        dao.put(row("original"))
        // 模拟首次发布前进程终止，残留临时文件不代表有权改库。
        File(marker.parentFile, "${marker.name}.tmp").writeText("[")
        journal.begin()
        val first = marker.readText()
        dao.put(row("fixture"))
        val replay = GoldenSettingsJournal(marker).begin()
        assertEquals(first, marker.readText())
        assertEquals("original", replay.single { it.key == "journal-test" }.string)
        val db = PublicDatabase.instance.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER reject_original BEFORE INSERT ON KeyValuePair BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            assertThrows(SQLiteException::class.java) { journal.restore(replay) }
            assertEquals("fixture", dao["journal-test"]?.string)
            assertEquals(first, marker.readText())
        } finally {
            db.execSQL("DROP TRIGGER reject_original")
        }
        journal.restore(journal.read())
        assertFalse(marker.exists())
        val repeated = GoldenMeasure.call(GoldenMeasure.METHOD_FINISH, Bundle())
        assertEquals(true, repeated["stopped"])
        assertNull(repeated["restoredSettings"])
        assertEquals("original", dao["journal-test"]?.string)
    }

    @Test
    fun `损坏的旧标记不能以当前设置顶替，实测结束也拒绝清理`() = ui.bg {
        dao.put(row("fixture"))
        marker.writeText("""[{"key":"original","valueType":5,"value":""")
        val before = marker.readText()
        assertThrows(CollectRefusedException::class.java) { GoldenSettingsJournal(marker).begin() }
        assertThrows(CollectRefusedException::class.java) { GoldenMeasure.call(GoldenMeasure.METHOD_FINISH, Bundle()) }
        assertEquals(before, marker.readText())
        assertEquals("fixture", dao["journal-test"]?.string)
    }

    @Test
    fun `持有标记的工具独占数据库，另一入口带 allowWipe 仍拒绝`() = ui.bg {
        val collect = File(app.filesDir, GoldenCollector.OWNED_MARKER)
        val args = CollectArgs("test", DirtyArgs(0, emptyList(), 0, emptyList()), CoreArgs("", "", ""), true)
        marker.writeText("[]")
        assertTrue(assertThrows(CollectRefusedException::class.java) { GoldenCollector(args).collect() }
            .message.orEmpty().contains("measureFinish"))
        assertFalse(collect.exists())
        assertTrue(marker.renameTo(collect))
        val wipe = Bundle().apply { putBoolean("allowWipe", true) }
        for (method in listOf(GoldenMeasure.METHOD_PREPARE, GoldenMeasure.METHOD_FINISH)) {
            assertTrue(assertThrows(CollectRefusedException::class.java) { GoldenMeasure.call(method, wipe) }
                .message.orEmpty().contains(collect.name))
        }
        assertEquals("[]", collect.readText())
        assertFalse(marker.exists())
    }
}
