package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.checkJsonNesting
import io.nekohasekai.sagernet.ktx.lockFile
import io.nekohasekai.sagernet.ktx.readBytesLimited
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// 一次备份恢复横跨两个 Room 数据库，也就是两个事务。选中的、已校验过的备份
// 在第一次提交之前先暂存在这里；两次提交之间进程被杀的话，下次任一进程启动时
// 会重放（replay）它，所以配置和设置不会停在只恢复了一半的状态。
// 应用备份的做法是先重置再插入，因此重放可以安全地重复执行。
class RestoreJournal(private val dir: File, private val lockFile: File) {

    class IncompleteReplacementException : Exception()

    enum class Result { NONE, REPLAYED, PENDING }

    companion object {
        const val MAX_ATTEMPTS = 3

        val default by lazy {
            RestoreJournal(File(app.filesDir, "restore"), File(app.filesDir, "restore.lock"))
        }
    }

    private val pending get() = File(dir, "pending.json")

    // 旧版本放弃重放后把日志改名留在这里「供诊断」：含全部节点凭证的明文备份，
    // 没有任何代码再读它。下次暂存时清除旧版本留下的副本。
    private val legacyFailed get() = File(dir, "pending.failed.json")

    fun isPending(): Boolean = pending.exists()

    // 返回是否替换了已有的恢复日志。只有覆盖所有待恢复部分的新导入才能接手。
    fun stage(backup: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean): Boolean {
        val wasPending = isPending()
        val payload = JSONObject().apply {
            put("attempts", 0)
            put("profiles", profile)
            put("rules", rule)
            put("settings", setting)
            put("backup", backup)
        }
        if (wasPending) {
            val required = readPending()?.selectedSections().orEmpty()
            val selected = payload.selectedSections()
            if (selected.isEmpty() || !selected.containsAll(required)) {
                throw IncompleteReplacementException()
            }
        }
        write(payload)
        legacyFailed.delete()
        return wasPending
    }

    private fun JSONObject.selectedSections(): List<String> {
        val backup = optJSONObject("backup") ?: return emptyList()
        return listOf("profiles", "rules", "settings").filter { optBoolean(it) && backup.has(it) }
    }

    private fun readPending(): JSONObject? = try {
        // 已通过导入大小与深度检查的备份，在日志里多一层对象及固定的状态字段。
        JSONObject(pending.inputStream().use {
            it.readBytesLimited(MAX_IMPORT_BYTES + 256).toString(Charsets.UTF_8).checkJsonNesting(65)
        })
    } catch (e: Exception) {
        Logs.w("Restore journal unreadable: ${e.javaClass.simpleName}")
        null
    }

    fun clear() {
        if (pending.exists() && !pending.delete()) throw IOException("Cannot clear restore journal")
    }

    // 与安装标记共用的跨进程文件锁：main 和 :bg 都会跑启动检查，
    // 阻塞式的锁让后到的那个等着。
    fun <T> withLock(block: () -> T): T = lockFile(lockFile, block)

    // 启动时重放。`apply` 负责解码并提交暂存的备份；失败的话日志留给下次启动，
    // 达到上限后保留唯一的恢复来源，只有备份页发起的显式重试才再提交。
    fun completePending(retry: Boolean = false, apply: (JSONObject, Boolean, Boolean, Boolean) -> Unit): Result {
        if (!isPending()) return Result.NONE
        return withLock {
            if (!isPending()) return@withLock Result.NONE
            val payload = readPending() ?: return@withLock Result.PENDING
            // 先计一次尝试再应用：崩溃循环必须能终止。
            val previous = payload.optInt("attempts", 0)
            if (previous >= MAX_ATTEMPTS && !retry) return@withLock Result.PENDING
            val attempts = previous.coerceIn(0, MAX_ATTEMPTS - 1) + 1
            try {
                if (attempts != previous) {
                    payload.put("attempts", attempts)
                    write(payload)
                }
                apply(
                    payload.getJSONObject("backup"),
                    payload.optBoolean("profiles"),
                    payload.optBoolean("rules"),
                    payload.optBoolean("settings"),
                )
                clear()
            } catch (e: Exception) {
                Logs.w("Restore replay failed (attempt $attempts): ${e.javaClass.simpleName}")
                return@withLock Result.PENDING
            }
            Result.REPLAYED
        }
    }

    // 临时文件 + fsync + 重命名：日志要么完整，要么不存在。
    private fun write(payload: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "pending.json.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(payload.toString().toByteArray())
                it.fd.sync()
            }
            if (!tmp.renameTo(pending)) throw IOException("Cannot stage restore journal")
        } finally {
            tmp.delete()
        }
    }
}
