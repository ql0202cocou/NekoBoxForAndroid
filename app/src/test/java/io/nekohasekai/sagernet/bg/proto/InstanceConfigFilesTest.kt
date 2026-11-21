package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstanceConfigFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `重启只回收本进程和旧布局的临时配置`() {
        val dir = tmp.newFolder()
        val bg = prepareExternalConfigDirectory(dir, true)
        val main = prepareExternalConfigDirectory(dir, false)
        val other = File(bg, "running.json").apply { writeText("active") }
        val stale = File(main, "stale.json").apply { writeText("stale") }
        val unrelated = File(dir, "user.ca").apply { writeText("keep") }
        val old = File(dir, "tmpcfg").apply { mkdir() }.resolve("xray_123.json").apply { writeText("old") }
        val ca = File(dir, "hysteria_123.ca").apply { writeText("old") }
        prepareExternalConfigDirectory(dir, false)
        assertFalse(stale.exists() || old.exists() || ca.exists())
        assertTrue(other.exists() && unrelated.exists())
    }

    @Test
    fun `写入途中关闭会回收迟到的配置且保留其它测速实例的配置`() {
        val dir = tmp.newFolder()
        val files = InstanceConfigFiles()
        val other = InstanceConfigFiles()
        val keep = other.write(dir) { create -> create("plugin", "json").apply { writeText("other") } }
        val written = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<Throwable?> {
                runCatching {
                    files.write(dir) { create ->
                        val ca = create("plugin", "ca")
                        val config = create("plugin", "json")
                        written.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                        ca.writeText("synthetic CA")
                        config.writeText("synthetic config")
                    }
                }.exceptionOrNull()
            }
            assertTrue(written.await(10, TimeUnit.SECONDS))
            files.close()
            resume.countDown()
            assertTrue(task.get(10, TimeUnit.SECONDS) is CancellationException)
            assertEquals(listOf(keep), dir.listFiles()!!.toList())
            assertEquals("other", keep.readText())
        } finally {
            resume.countDown()
            worker.shutdownNow()
            other.close()
        }
    }

    @Test
    fun `写入失败时清理部分文件且正常发布的配置按各自时机删除`() {
        val files = InstanceConfigFiles()
        val dir = tmp.newFolder()
        val running = files.write(dir) { it("run", "json") }
        val check = files.write(dir) { it("check", "json") }
        assertThrows(IOException::class.java) {
            files.write(dir) { create ->
                create("partial", "json").writeText("incomplete")
                throw IOException("synthetic write failure")
            }
        }
        assertEquals(setOf(running, check), dir.listFiles()!!.toSet())
        files.delete(check)
        assertEquals(listOf(running), dir.listFiles()!!.toList())
        files.close()
        files.close()
        assertArrayEquals(emptyArray<File>(), dir.listFiles())
    }
}
