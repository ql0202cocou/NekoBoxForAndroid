package io.nekohasekai.sagernet.ktx

import java.io.File
import java.io.RandomAccessFile

// Blocking cross-process mutex on a lock file. FileChannel.lock() is a POSIX fcntl
// record lock, the same kind libcore takes with FcntlFlock, so the main process and
// :bg (Kotlin or Go) exclude each other on the same file.
fun <T> lockFile(file: File, block: () -> T): T {
    file.parentFile?.mkdirs()
    return RandomAccessFile(file, "rw").channel.use { channel ->
        channel.lock().use { block() }
    }
}
