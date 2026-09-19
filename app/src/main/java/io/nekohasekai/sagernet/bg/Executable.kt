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
        "libxray.so", "libmihomo.so"
    )

    fun killAll() {
        val myPid = Process.myPid()
        for (process in File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return) {
            val exe = File(
                try {
                File(process, "cmdline").inputStream().bufferedReader().use {
                    it.readText()
                }
            } catch (_: IOException) {
                continue
            }.split(Character.MIN_VALUE, limit = 2).first())
            if (!EXECUTABLES.contains(exe.name)) continue
            // Only kill this (:bg) process's own children or orphaned
            // leftovers of a dead one: the main process runs the same
            // plugin binaries for TestInstance URL tests and killing
            // those breaks concurrent tests.
            val ppid = try {
                File(process, "stat").readText()
                    .substringAfterLast(')').trim().split(' ')[1].toIntOrNull()
            } catch (_: Exception) {
                null
            } ?: continue
            if (ppid != myPid && ppid != 1) continue
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
