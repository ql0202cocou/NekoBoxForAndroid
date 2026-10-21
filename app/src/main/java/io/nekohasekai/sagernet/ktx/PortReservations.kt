package io.nekohasekai.sagernet.ktx

import java.io.File
import java.io.IOException

/**
 * 跨进程的端口保留表：主进程（测速）与 :bg（运行服务）拿本机端口都经这里，免得两边分到同一个空闲端口。
 *
 * 发出去的端口要等使用者真正绑定才算占用（外核在进程启动后才绑），这段时间里别的调用方再向系统要临时端口，
 * 可能拿到同一个。所以发出去的端口先记进保留表，[ttlMs] 内不再发给别人。之后没有人回报是否已经绑定：
 * 过了启动窗口也就不会再撞，记录到期作废，一直留着会把临时端口段占满。
 *
 * 保留表是应用私有目录下的文本文件，每行「端口 时间戳」。读写都在 [lockFile] 的跨进程锁里，锁文件与数据文件分开：
 * POSIX 记录锁在本进程关闭同一文件的任一描述符时就会释放。时间戳取开机计时（[now]，两个进程可比）；
 * 比当前时间还大的记录来自重启之前，按过期处理。读到的坏行直接丢弃，最坏只是少挡几个端口。
 *
 * 纯 JVM：时钟与挑端口的函数由调用方注入（[mkPort] 用 SystemClock 与临时端口），单测直接构造。
 */
class PortReservations(
    private val file: File,
    private val now: () -> Long,
    private val pickPort: () -> Int,
    private val ttlMs: Long = TTL_MS,
    private val lock: File = File(file.path + ".lock"),
) {

    /** 挑一个不在保留表里的端口并记下；保留表满到挑不出来、或文件读写失败时抛 [IOException] */
    fun reserve(): Int = lockFile(lock) {
        val time = now()
        val live = read().filterValues { it <= time && time - it <= ttlMs }
        var port: Int
        var tries = 0
        do {
            if (++tries > MAX_TRIES) {
                throw IOException("no unreserved port after $MAX_TRIES tries (${live.size} reserved)")
            }
            port = pickPort()
        } while (port in live)
        write(live + (port to time))
        port
    }

    private fun read(): Map<Int, Long> {
        if (!file.isFile) return emptyMap()
        val entries = HashMap<Int, Long>()
        file.forEachLine { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 2) return@forEachLine
            val port = parts[0].toIntOrNull() ?: return@forEachLine
            val time = parts[1].toLongOrNull() ?: return@forEachLine
            if (port in 1..65535) entries[port] = time
        }
        return entries
    }

    // 先写临时文件再改名：进程中途被杀也不会留下写了一半的保留表
    private fun write(entries: Map<Int, Long>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.entries.joinToString("") { "${it.key} ${it.value}\n" })
        if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }

    companion object {
        /** 保留时长：覆盖外核进程启动、绑定端口的窗口 */
        const val TTL_MS = 60_000L

        // 挑这么多次还撞在保留表上，说明表已异常膨胀，直接失败，不无限重试
        private const val MAX_TRIES = 1000
    }
}
