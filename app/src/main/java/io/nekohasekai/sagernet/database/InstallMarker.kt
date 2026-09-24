package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.util.UUID

// noBackupFilesDir survives normal runs but not an Auto Backup / device-transfer
// restore or a reinstall, so a missing marker means a new installation: drop the
// Clash API secret inherited through configuration.db (the JSON backup already
// excludes it) so the secret stays per-install. Runs under RestoreJournal's lock
// in both processes before either reads the secret.
object InstallMarker {
    private val marker get() = File(app.noBackupFilesDir, "install.id")

    // Lock-free pre-check: the marker is there on every start but the first, and
    // taking the cross-process lock for a stat() serializes main against :bg.
    fun isMissing() = !marker.exists()

    fun ensure() {
        val marker = marker
        if (marker.exists()) return
        DataStore.resetClashApiSecret()
        // 云备份只含 configuration.db（sager_net.db 有节点凭证，不上云）：恢复后
        // rulesFirstCreate 仍是 true，规则表却是空的，默认规则就再也不会建。
        // 两库一起迁移（换机）时规则表非空，保持原样
        if (DataStore.rulesFirstCreate && SagerDatabase.rulesDao.allRules().isEmpty()) {
            DataStore.rulesFirstCreate = false
        }
        val tmp = File(app.noBackupFilesDir, "install.id.tmp")
        tmp.writeText(UUID.randomUUID().toString())
        if (!tmp.renameTo(marker)) Logs.w("cannot write install marker")
    }
}
