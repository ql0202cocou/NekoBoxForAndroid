package io.nekohasekai.sagernet.bg

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
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
        // 正常停止由进程池回收子进程，服务进程死亡时由 Android 清理其进程组。
        // 启动时额外回收残留外核只用稳定句柄，避免向已被复用的 PID 发信号。
        if (Build.VERSION.SDK_INT < 31) return
        val myPid = Process.myPid()
        for (process in File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return) {
            try {
                ParcelFileDescriptor.open(process, ParcelFileDescriptor.MODE_READ_ONLY).use { dir ->
                    val exe = childExecutable(File("/proc/self/fd/${dir.fd}"), myPid) ?: return@use
                    when (val errno = Libcore.killProcessByProcFD(dir.fd)) {
                        0 -> Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
                        OsConstants.ESRCH -> Unit
                        OsConstants.ENOSYS -> return
                        else -> throw ErrnoException("pidfd_send_signal", errno)
                    }
                }
            } catch (_: FileNotFoundException) {
                // 枚举后进程可能已经退出，也可能无权打开其 proc 目录。
            } catch (e: ErrnoException) {
                Logs.w("SIGKILL (${process.name}) failed")
                Logs.w(e)
            }
        }
    }
}
