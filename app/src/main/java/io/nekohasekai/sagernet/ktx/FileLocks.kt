package io.nekohasekai.sagernet.ktx

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

// 跨进程阻塞互斥：FileChannel.lock() 是 POSIX fcntl 记录锁，与 libcore 的
// FcntlFlock 同源，主进程与 :bg（Kotlin 或 Go）在同一文件上互斥。
// fcntl 锁不可重入：同进程对同一文件重叠加锁会抛
// OverlappingFileLockException（如 completePending 持锁重放时经
// DataStore.selectedGroup 惰性默认值再次走到这里）。按 canonical path
// 包一层 JVM 内可重入锁：同线程重入直接执行，不再重复加 fcntl 锁；
// 同 JVM 其他线程在 guard 上排队等待（此前是直接抛异常），语义与
// 跨进程的阻塞等待对齐。
private val lockGuards = ConcurrentHashMap<String, ReentrantLock>()

fun <T> lockFile(file: File, block: () -> T): T {
    file.parentFile?.mkdirs()
    val guard = lockGuards.getOrPut(file.canonicalPath) { ReentrantLock() }
    guard.lock()
    try {
        if (guard.holdCount > 1) return block()
        return RandomAccessFile(file, "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    } finally {
        guard.unlock()
    }
}
