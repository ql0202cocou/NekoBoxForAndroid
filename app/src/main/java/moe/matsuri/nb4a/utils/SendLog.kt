package moe.matsuri.nb4a.utils

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.shareDir
import io.nekohasekai.sagernet.ktx.shareFile
import io.nekohasekai.sagernet.ktx.use
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object SendLog {
    fun prepareLog(title: String): File {
        // 必须建在 shareDir：FileProvider 只暴露该目录，目录外的文件 shareFile 会抛
        // IllegalArgumentException；shareFile 同时清掉上一次分享的残留。旧版本建在
        // cacheDir/log/ 且从不清理，顺手删掉
        File(app.cacheDir, "log").deleteRecursively()
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            app.shareDir.also { it.mkdirs() })

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(report)

        try {
            // destroy() in a finally: "logcat -d" exits on its own, but the
            // Process still holds its stderr/stdin fds until it is destroyed,
            // and every log export used to leak one set
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            try {
                process.inputStream.use(FileOutputStream(logFile, true))
            } finally {
                process.destroy()
            }
            logFile.appendText("\n")
        } catch (e: IOException) {
            Logs.w(e)
            logFile.appendText("Export logcat error: " + CrashHandler.formatThrowable(e))
        }

        logFile.appendText("\n")
        logFile.appendBytes(getNekoLog(0))

        // Native cores and logcat bypass the Kotlin logging call sites. Apply
        // the same redaction to the complete report before granting access.
        logFile.writeText(Util.redactSecrets(logFile.readText()))

        return logFile
    }

    fun shareLog(context: Context, logFile: File) = context.shareFile(logFile, "text/x-log")

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            val len = file.length()
            FileInputStream(file).use { stream ->
                // seek inside use(): a throwing seek used to leak the fd, and
                // position() lands exactly where skip() may move less than asked
                if (max in 1 until len) {
                    stream.channel.position(len - max) // TODO string?
                }
                stream.readBytes()
            }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
