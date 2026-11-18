package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class LegacyAssetMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `大文件在复制前拒绝且源文件原目标都保留`() {
        val source = temp.newFile("source")
        RandomAccessFile(source, "rw").use { it.setLength(LEGACY_ASSET_MAX_BYTES + 1) }
        val destination = temp.newFolder("destination")
        val target = File(destination, "asset").apply { writeText("old") }
        assertThrows(IOException::class.java) { migrateLegacyAsset(source, target) }
        assertEquals(LEGACY_ASSET_MAX_BYTES + 1, source.length())
        assertEquals("old", target.readText())
        assertEquals(listOf("asset"), destination.list()!!.toList())
    }

    @Test
    fun `边界大小和空文件完整发布且删除源文件`() {
        for (size in listOf(0, 1024)) {
            val source = temp.newFile("source-$size").apply { writeBytes(ByteArray(size) { (it % 127).toByte() }) }
            val destination = temp.newFolder("destination-$size")
            val target = File(destination, "asset").apply { writeText("old") }
            val expected = source.readBytes()
            migrateLegacyAsset(source, target, 1024)
            assertArrayEquals(expected, target.readBytes())
            assertFalse(source.exists())
            assertEquals(listOf("asset"), destination.list()!!.toList())
        }
    }

    @Test
    fun `发布失败清理临时文件且解除故障后可重试`() {
        val source = temp.newFile("source").apply { writeText("new") }
        val destination = temp.newFolder("destination")
        val target = File(destination, "asset").apply { mkdir() }
        assertThrows(IOException::class.java) { migrateLegacyAsset(source, target) }
        assertEquals("new", source.readText())
        assertEquals(listOf("asset"), destination.list()!!.toList())
        target.delete()
        migrateLegacyAsset(source, target)
        assertEquals("new", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `只清理过期的迁移临时文件`() {
        val dir = temp.newFolder("assets")
        val now = 100L * LEGACY_TEMP_STALE_MS
        val stale = File(dir, ".legacy-1.tmp").apply { writeText("x"); setLastModified(now - LEGACY_TEMP_STALE_MS) }
        val fresh = File(dir, ".legacy-2.tmp").apply { writeText("x"); setLastModified(now - 1) }
        val asset = File(dir, "geoip.db").apply { writeText("x"); setLastModified(0) }
        cleanupStaleLegacyAssetTemps(dir, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(asset.exists())
    }
}
