package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.checkJsonNesting
import io.nekohasekai.sagernet.ktx.lockFile
import io.nekohasekai.sagernet.ktx.readBytesLimited
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// A backup restore spans two Room databases and therefore two transactions. The
// selected, already-validated backup is staged here before the first commit; a
// process killed between the commits replays it on the next start of either
// process, so profiles and settings never stay half-restored. Applying a backup
// is reset + insert, so a replay is safe to repeat.
class RestoreJournal(private val dir: File, private val lockFile: File) {

    enum class Result { NONE, REPLAYED, GAVE_UP }

    companion object {
        const val MAX_ATTEMPTS = 3

        val default by lazy {
            RestoreJournal(File(app.filesDir, "restore"), File(app.filesDir, "restore.lock"))
        }
    }

    private val pending get() = File(dir, "pending.json")

    // Kept for diagnosis after MAX_ATTEMPTS failed replays; never read again.
    val failed get() = File(dir, "pending.failed.json")

    fun isPending(): Boolean = pending.exists()

    fun stage(backup: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean) {
        write(JSONObject().apply {
            put("attempts", 0)
            put("profiles", profile)
            put("rules", rule)
            put("settings", setting)
            put("backup", backup)
        })
    }

    fun clear() {
        pending.delete()
    }

    // Cross-process mutex shared with the install marker: main and :bg both run
    // the startup checks, and a blocking lock makes the second one wait.
    fun <T> withLock(block: () -> T): T = lockFile(lockFile, block)

    // Startup replay. `apply` decodes and commits the staged backup; a failure
    // leaves the journal for the next start until MAX_ATTEMPTS is reached.
    fun completePending(apply: (JSONObject, Boolean, Boolean, Boolean) -> Unit): Result {
        if (!isPending()) return Result.NONE
        return withLock {
            if (!isPending()) return@withLock Result.NONE
            val payload = try {
                JSONObject(pending.inputStream().use {
                    it.readBytesLimited().toString(Charsets.UTF_8).checkJsonNesting()
                })
            } catch (e: Exception) {
                Logs.w("Restore journal unreadable: ${e.javaClass.simpleName}")
                giveUp()
                return@withLock Result.GAVE_UP
            }
            // Count the attempt before applying: a crash loop must end.
            val attempts = payload.optInt("attempts", 0) + 1
            if (attempts > MAX_ATTEMPTS) {
                giveUp()
                return@withLock Result.GAVE_UP
            }
            payload.put("attempts", attempts)
            write(payload)
            try {
                apply(
                    payload.getJSONObject("backup"),
                    payload.optBoolean("profiles"),
                    payload.optBoolean("rules"),
                    payload.optBoolean("settings"),
                )
            } catch (e: Exception) {
                Logs.w("Restore replay failed (attempt $attempts): ${e.javaClass.simpleName}")
                if (attempts < MAX_ATTEMPTS) return@withLock Result.NONE
                giveUp()
                return@withLock Result.GAVE_UP
            }
            clear()
            Result.REPLAYED
        }
    }

    // Temp file + fsync + rename: the journal is either complete or absent.
    private fun write(payload: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "pending.json.tmp")
        FileOutputStream(tmp).use {
            it.write(payload.toString().toByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(pending)) error("cannot stage restore journal")
    }

    private fun giveUp() {
        failed.delete()
        if (!pending.renameTo(failed)) pending.delete()
    }
}
