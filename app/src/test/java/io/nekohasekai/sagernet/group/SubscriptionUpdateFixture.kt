package io.nekohasekai.sagernet.group

import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.File
import java.security.MessageDigest

// content:// 走真实读取、解析、差异计算与 Room 写入；没有网络、DNS 或代理核心参与。
internal class SubscriptionUpdateFixture(val format: String, val pattern: String, val count: Int) {
    val file = File(File(app.cacheDir, "share").apply { mkdirs() }, "subscription-test.txt")
    val expectedCount get() = if (pattern == "duplicates") count / 2 else count
    var groupId = 0L

    fun text(port: Int = 443): String = buildString {
        if (format == "yaml") append("proxies:\n")
        repeat(count) { i ->
            val endpoint = if (pattern == "duplicates") i / 2 else i
            val name = if (pattern == "same-name") "node" else "node-$i"
            val server = "node-$endpoint.example.com"
            if (format == "yaml") {
                append("  - {name: '$name', type: trojan, server: $server, port: $port, password: scale-test-password}\n")
            } else {
                append("trojan://scale-test-password@$server:$port#$name\n")
            }
        }
    }

    fun prepare() {
        val group = ProxyGroup(name = "subscription test", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply {
                // 存量订阅可在 URI 首尾带有空白，更新时应与身份比较使用同一地址。
                link = "  " + FileProvider.getUriForFile(app, "${app.packageName}.cache", file) + "  "
                deduplication = pattern == "duplicates"
                forceResolve = false
                lastUpdated = 123L
            })
        groupId = SagerDatabase.groupDao.createGroup(group)
    }

    suspend fun update(): UpdateReport {
        val group = SagerDatabase.groupDao.getById(groupId)!!
        val report = UpdateReport()
        RawUpdater.doUpdate(group, group.subscription!!, report, false, SagerDatabase.dataGeneration())
        check(report.succeeded)
        return report
    }

    fun verify(port: Int) {
        val rows = SagerDatabase.proxyDao.getByGroup(groupId)
        check(rows.size == expectedCount) { "rows=${rows.size}, expected=$expectedCount" }
        rows.forEachIndexed { index, row ->
            val bean = row.requireBean()
            val name = when (pattern) {
                "same-name" -> if (index == 0) "node" else "node ($index)"
                "duplicates" -> "node-${index * 2}"
                else -> "node-$index"
            }
            check(bean.name == name) { "unexpected name at $index" }
            check(bean.serverAddress == "node-$index.example.com")
            check(bean.serverPort == port)
            check(row.userOrder == index + 1L)
        }
    }

    // 对真实 SQLite 各列做有边界的摘要，含原始 bean 字节；不靠重新序列化实体判断回滚。
    fun snapshot(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (table in listOf("proxy_entities", "proxy_groups", "rules", "data_generation")) {
            digest.update(table.toByteArray())
            SagerDatabase.instance.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { index ->
                        val type = cursor.getType(index)
                        val bytes = when (type) {
                            android.database.Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                            android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                            else -> cursor.getString(index).toByteArray()
                        }
                        digest.update("$type:${bytes.size}:".toByteArray())
                        digest.update(bytes)
                    }
                }
            }
        }
        digest.update(DataStore.selectedProxy.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cleanup() {
        SagerDatabase.proxyDao.deleteAll(groupId)
        SagerDatabase.groupDao.deleteById(groupId)
        file.delete()
    }
}

internal class UpdateReport : GroupManager.Interface {
    var succeeded = false
    var changed = -1
    var added = -1
    var updated = -1
    var deleted = -1
    var duplicates = -1
    override suspend fun confirm(message: String) = error("unexpected confirmation")
    override suspend fun alert(message: String) = error("unexpected alert")
    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = error("unexpected failure")
    override suspend fun onUpdateSuperseded(group: ProxyGroup) = error("unexpected supersession")
    override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>,
        updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) {
        succeeded = true
        this.changed = changed
        this.added = added.size
        this.updated = updated.size
        this.deleted = deleted.size
        duplicates = duplicate.size
    }
}
