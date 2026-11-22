package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.app
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

// 服务从登记到最终流量落库持锁；恢复须独占同一文件。进程死亡会释放内核锁。
// 同一 :bg 内新旧服务可以交叠，共用一个描述符，最后一个实例退出时才关闭。
internal class ServiceRestoreLock(private val file: File) {
    companion object {
        val default by lazy { ServiceRestoreLock(File(app.filesDir, "service-restore.lock")) }
    }

    private var channel: FileChannel? = null
    private var services = 0

    @Synchronized
    fun tryAcquire(forService: Boolean): Closeable? {
        if (channel == null) {
            val opened = RandomAccessFile(file, "rw").channel
            try {
                if (opened.tryLock() == null) return null
                channel = opened
            } finally {
                if (channel == null) opened.close()
            }
        } else if (!forService || services == 0) {
            return null
        }
        if (forService) services++
        return object : Closeable {
            private var released = false
            override fun close() = synchronized(this@ServiceRestoreLock) {
                if (!released) {
                    released = true
                    if (forService) services--
                    if (services == 0) {
                        channel?.close()
                        channel = null
                    }
                }
            }
        }
    }
}
