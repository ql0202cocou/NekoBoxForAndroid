package io.nekohasekai.sagernet.bg

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.io.IOException
import androidx.core.text.isDigitsOnly

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
            val exe = childExecutable(process, myPid) ?: continue
            try {
                Os.kill(process.name.toInt(), OsConstants.SIGKILL)
                Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) {
                    Logs.w("SIGKILL ${exe.absolutePath} (${process.name}) failed")
                    Logs.w(e)
                }
            }
        }
    }
}
