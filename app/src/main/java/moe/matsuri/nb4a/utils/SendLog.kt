package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.use
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object SendLog {
    // Create full log and send
    fun sendLog(context: Context, title: String) {
        shareLog(context, prepareLog(title))
    }

    fun prepareLog(title: String): File {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

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

    fun shareLog(context: Context, logFile: File) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

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
