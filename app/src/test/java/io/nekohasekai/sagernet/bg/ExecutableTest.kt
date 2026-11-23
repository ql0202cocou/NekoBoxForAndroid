package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutableTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `Mieru 只回收本服务子进程与孤儿`() {
        val process = temp.newFolder("123")
        fun candidate(name: String, parent: Int): File? {
            File(process, "cmdline").writeText("/plugins/$name\u0000run\u0000")
            File(process, "stat").writeText("123 (plugin) S $parent 34")
            return Executable.childExecutable(process, 42)
        }
        assertEquals("libmieru.so", candidate("libmieru.so", 42)?.name)
        assertEquals("libmieru.so", candidate("libmieru.so", 1)?.name)
        assertNull(candidate("libmieru.so", 43))
        assertNull(candidate("unrelated.so", 42))
    }

    @Test fun `按 PID 回收前经同一句柄复核进程仍在`() {
        val process = temp.newFolder("456")
        val killed = mutableListOf<Int>()
        File(process, "stat").writeText("456 (plugin) S 1 34")
        assertTrue(Executable.killVerified(process, 456) { killed += it })
        assertEquals(listOf(456), killed)

        // 进程已退出：句柄上的 stat 读不到，不能再按这个 PID 发信号
        File(process, "stat").delete()
        assertFalse(Executable.killVerified(process, 456) { killed += it })
        assertEquals(listOf(456), killed)
    }
}
