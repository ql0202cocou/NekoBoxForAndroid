package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.checkJsonNesting
import io.nekohasekai.sagernet.ktx.lockFile
import io.nekohasekai.sagernet.ktx.readBytesLimited
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// 一次备份恢复横跨两个 Room 数据库，也就是两个事务。选中的、已校验过的备份
// 在第一次提交之前先暂存在这里；两次提交之间进程被杀的话，下次任一进程启动时
// 会重放（replay）它，所以配置和设置不会停在只恢复了一半的状态。
// 应用备份的做法是先重置再插入，因此重放可以安全地重复执行。
class RestoreJournal(private val dir: File, private val lockFile: File) {

    enum class Result { NONE, REPLAYED, GAVE_UP }

    companion object {
        const val MAX_ATTEMPTS = 3

        val default by lazy {
            RestoreJournal(File(app.filesDir, "restore"), File(app.filesDir, "restore.lock"))
        }
    }

    private val pending get() = File(dir, "pending.json")

    // 旧版本放弃重放后把日志改名留在这里「供诊断」：含全部节点凭证的明文备份，
    // 没有任何代码再读它，非 root 也取不出来。现在放弃即删，残留的在下次 stage 时清掉
    private val legacyFailed get() = File(dir, "pending.failed.json")

    fun isPending(): Boolean = pending.exists()

    fun stage(backup: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean) {
        legacyFailed.delete()
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

    // 与安装标记共用的跨进程文件锁：main 和 :bg 都会跑启动检查，
    // 阻塞式的锁让后到的那个等着。
    fun <T> withLock(block: () -> T): T = lockFile(lockFile, block)

    // 启动时重放。`apply` 负责解码并提交暂存的备份；失败的话日志留给下次启动，
    // 直到达到 MAX_ATTEMPTS。
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
            // 先计一次尝试再应用：崩溃循环必须能终止。
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

    // 临时文件 + fsync + 重命名：日志要么完整，要么不存在。
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
        pending.delete()
        legacyFailed.delete()
    }
}
