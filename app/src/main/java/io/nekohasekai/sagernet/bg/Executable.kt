package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.text.isDigitsOnly
import io.nekohasekai.sagernet.ktx.Logs
import libcore.Libcore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object Executable {
    private val EXECUTABLES = setOf(
        "libtrojan.so", "libtrojan-go.so", "libnaive.so", "libtuic.so", "libhysteria.so",
        "libxray.so", "libmieru.so"
    )

    internal fun childExecutable(process: File, servicePid: Int): File? {
        val command = try {
            File(process, "cmdline").readText().substringBefore(Character.MIN_VALUE)
        } catch (_: IOException) {
            return null
        }
        val executable = File(command)
        if (executable.name !in EXECUTABLES) return null
        // 主进程测速也会启动相同的外核。只回收本服务子进程和已被接管的孤儿。
        val parentPid = try {
            File(process, "stat").readText().substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
        } catch (_: IOException) {
            return null
        }
        return executable.takeIf { parentPid == servicePid || parentPid == 1 }
    }

    fun killAll() {
        // 正常停止由进程池回收子进程，服务进程死亡时由 Android 清理其进程组；这里回收逃出进程组的残留。
        // 身份都在同一个 /proc/PID 目录句柄上核实：进程退出后句柄失效，PID 被复用也不会指向新进程。
        val myPid = Process.myPid()
        // API 31 起优先在句柄上直接发信号；内核没有 pidfd_send_signal 时改走 killVerified
        var usePidfd = Build.VERSION.SDK_INT >= 31
        for (process in File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return) {
            try {
                ParcelFileDescriptor.open(process, ParcelFileDescriptor.MODE_READ_ONLY).use { dir ->
                    val handle = File("/proc/self/fd/${dir.fd}")
                    val exe = childExecutable(handle, myPid) ?: return@use
                    if (usePidfd) {
                        when (val errno = Libcore.killProcessByProcFD(dir.fd)) {
                            0 -> {
                                Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
                                return@use
                            }
                            OsConstants.ESRCH -> return@use
                            OsConstants.ENOSYS -> usePidfd = false
                            else -> throw ErrnoException("pidfd_send_signal", errno)
                        }
                    }
                    if (killVerified(handle, process.name.toInt()) { Os.kill(it, OsConstants.SIGKILL) }) {
                        Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
                    }
                }
            } catch (_: FileNotFoundException) {
                // 枚举后进程可能已经退出，也可能无权打开其 proc 目录。
            } catch (e: ErrnoException) {
                // 核实之后、发信号之前进程退出
                if (e.errno == OsConstants.ESRCH) continue
                Logs.w("SIGKILL (${process.name}) failed")
                Logs.w(e)
            }
        }
    }

    // 没有 pidfd_send_signal 时按 PID 发信号。紧挨着发信号前经同一句柄再读一次 stat：读得到说明进程还在，
    // PID 仍属于刚核实的那个进程（未回收的僵尸进程也占着 PID，不会被复用）；读不到就不发。
    // 剩下的只有这次读取与 kill 之间的极短窗口。
    internal fun killVerified(handle: File, pid: Int, kill: (Int) -> Unit): Boolean {
        try {
            File(handle, "stat").readText()
        } catch (_: IOException) {
            return false
        }
        kill(pid)
        return true
    }
}
