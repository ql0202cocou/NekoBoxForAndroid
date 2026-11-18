package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
