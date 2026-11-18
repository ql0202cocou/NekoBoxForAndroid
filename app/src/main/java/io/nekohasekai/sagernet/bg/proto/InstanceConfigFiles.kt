package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.CancellationException
import java.io.File

/** 写完才把文件交给实例。关闭不等磁盘写入，仍在写的调用负责收回自己的文件。 */
internal class InstanceConfigFiles : AutoCloseable {
    private val files = mutableSetOf<File>()
    private var closed = false

    fun <T> write(dir: File, writer: (newFile: (String, String) -> File) -> T): T {
        val pending = mutableListOf<File>()
        try {
            val result = writer { prefix, ext ->
                dir.mkdirs()
                File.createTempFile(prefix + "_", ".$ext", dir).also(pending::add)
            }
            synchronized(files) {
                if (closed) throw CancellationException("instance closed while writing external configuration")
                files.addAll(pending)
            }
            return result
        } catch (e: Throwable) {
            pending.forEach { it.delete() }
            throw e
        }
    }

    // 校验进程的配置用完即删，失败的留给 close 再试。
    fun delete(file: File) {
        if (file.delete() || !file.exists()) synchronized(files) { files.remove(file) }
    }

    override fun close() {
        val owned = synchronized(files) {
            closed = true
            files.toList().also { files.clear() }
        }
        owned.forEach { it.delete() }
    }
}
