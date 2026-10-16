package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.util.UUID

// noBackupFilesDir 在正常运行期间会保留，但不会经过自动备份 / 换机迁移的恢复，
// 重装后也没了，所以标记缺失就说明是一次新安装：丢掉经 configuration.db 继承来的
// Clash API 密钥（JSON 备份本来就不含它），让密钥保持每次安装各一份。
// 在 RestoreJournal 的锁内运行，且两个进程都要在读密钥之前先跑完。
object InstallMarker {
    private val marker get() = File(app.noBackupFilesDir, "install.id")

    // 无锁的预检查：除了第一次启动，标记每次都在，
    // 为一次 stat() 去拿跨进程文件锁会让 main 和 :bg 相互串行。
    fun isMissing() = !marker.exists()

    fun ensure() {
        val marker = marker
        if (marker.exists()) return
        DataStore.resetClashApiSecret()
        // Android 9–11 换机只迁 configuration.db（sager_net.db 有节点凭证，见
        // xml-v28/backup_descriptor.xml；云备份已不含任何数据）：恢复后
        // rulesFirstCreate 仍是 true，规则表却是空的，默认规则就再也不会建。
        // 两库一起迁移（12+ 换机）时规则表非空，保持原样
        if (DataStore.rulesFirstCreate && SagerDatabase.rulesDao.allRules().isEmpty()) {
            DataStore.rulesFirstCreate = false
        }
        val tmp = File(app.noBackupFilesDir, "install.id.tmp")
        tmp.writeText(UUID.randomUUID().toString())
        if (!tmp.renameTo(marker)) Logs.w("cannot write install marker")
    }
}
