package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.LegacyRowUpgrade
import io.nekohasekai.sagernet.fmt.upgradeLegacyRow

// 数据库 8 → 9 迁移（SagerDatabase.MIGRATION_8_9）的读写流程，不依赖 SupportSQLiteDatabase：迁移体只实现
// LegacyRowStore（执行 SQL），逐行判断在纯函数 upgradeLegacyRow（fmt/LegacyProfileUpgrade.kt）。单测用假实现
// 核对查询的列序、空值处理、写回与计数日志

// 读取的列，按这个顺序取下标
internal const val LEGACY_ROW_QUERY = "SELECT `id`, `type`, `core`, `vmessBean`, `trojanBean` FROM `proxy_entities`"

private const val COLUMN_ID = 0
private const val COLUMN_TYPE = 1
private const val COLUMN_CORE = 2
private const val COLUMN_VMESS_BEAN = 3
private const val COLUMN_TROJAN_BEAN = 4

// 查询结果的游标：只用到这几个读取方法
interface LegacyRowCursor {
    fun moveToNext(): Boolean
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun isNull(index: Int): Boolean
    fun getBlob(index: Int): ByteArray
}

interface LegacyRowStore {
    // 执行查询，把游标交给 block，block 返回后关闭游标
    fun query(sql: String, block: (LegacyRowCursor) -> Unit)

    // 只改 core 列
    fun updateCore(id: Long, core: Int)

    // 改 core 列与 vmessBean 列（VMess / VLESS 行）
    fun updateCoreAndVmessBean(id: Long, core: Int, vmessBean: ByteArray)

    // 改 core 列与 trojanBean 列（Trojan 行）
    fun updateCoreAndTrojanBean(id: Long, core: Int, trojanBean: ByteArray)
}

// 读完全部行、关掉游标之后再写回；全局「允许不安全」最多取一次（只在读到 bean 时取）。bean 按行类型取对应的列，
// 改过的 bean 也写回那一列。读不出的行 bean 列原样保留（core 列照样规范），日志只记行号与异常类型，不含节点内容
fun migrateLegacyRows(
    store: LegacyRowStore,
    globalAllowInsecure: () -> Boolean,
    warn: (String) -> Unit,
    info: (String) -> Unit,
) {
    class Update(val id: Long, val type: Int, val core: Int, val bean: ByteArray?)

    val global by lazy(globalAllowInsecure)
    val updates = ArrayList<Update>()
    var rows = 0
    var unreadable = 0
    store.query(LEGACY_ROW_QUERY) { cursor ->
        while (cursor.moveToNext()) {
            rows++
            val id = cursor.getLong(COLUMN_ID)
            val type = cursor.getInt(COLUMN_TYPE)
            val beanColumn = when (type) {
                TYPE_VMESS -> COLUMN_VMESS_BEAN
                TYPE_TROJAN -> COLUMN_TROJAN_BEAN
                else -> null
            }
            val bytes = if (beanColumn == null || cursor.isNull(beanColumn)) null else cursor.getBlob(beanColumn)
            when (val result = upgradeLegacyRow(type, cursor.getInt(COLUMN_CORE), bytes) { global }) {
                LegacyRowUpgrade.Unchanged -> Unit
                is LegacyRowUpgrade.Changed -> updates += Update(id, type, result.core, result.bean)
                is LegacyRowUpgrade.Unreadable -> {
                    unreadable++
                    result.core?.let { updates += Update(id, type, it, null) }
                    warn("database 8 -> 9: profile #$id skipped, bean unreadable (${result.error.javaClass.simpleName})")
                }
            }
        }
    }
    for (update in updates) {
        when {
            update.bean == null -> store.updateCore(update.id, update.core)
            update.type == TYPE_VMESS -> store.updateCoreAndVmessBean(update.id, update.core, update.bean)
            update.type == TYPE_TROJAN -> store.updateCoreAndTrojanBean(update.id, update.core, update.bean)
            else -> error("type ${update.type} has no bean to write")
        }
    }
    info("database 8 -> 9: ${updates.size} of $rows profiles upgraded, $unreadable unreadable")
}
