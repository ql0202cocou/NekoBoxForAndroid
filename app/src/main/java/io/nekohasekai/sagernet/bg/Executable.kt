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
        val myPid = Process.myPid()
        for (process in File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && killThroughHandle(process, myPid)) continue
                val exe = childExecutable(process, myPid) ?: continue
                Os.kill(process.name.toInt(), OsConstants.SIGKILL)
                Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
            } catch (_: FileNotFoundException) {
                // 枚举后进程可能已经退出，也可能无权打开其 proc 目录。
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) {
                    Logs.w("SIGKILL (${process.name}) failed")
                    Logs.w(e)
                }
            }
        }
    }

    // 在同一个 proc 目录句柄上检查身份和发送信号。仅旧内核确实缺少系统调用时才按 PID 重查。
    private fun killThroughHandle(process: File, servicePid: Int): Boolean =
        ParcelFileDescriptor.open(process, ParcelFileDescriptor.MODE_READ_ONLY).use { dir ->
            val exe = childExecutable(File("/proc/self/fd/${dir.fd}"), servicePid) ?: return@use true
            when (val errno = Libcore.killProcessByProcFD(dir.fd)) {
                0 -> {
                    Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
                    true
                }
                OsConstants.ESRCH -> true
                OsConstants.ENOSYS -> false
                else -> throw ErrnoException("pidfd_send_signal", errno)
            }
        }
}
