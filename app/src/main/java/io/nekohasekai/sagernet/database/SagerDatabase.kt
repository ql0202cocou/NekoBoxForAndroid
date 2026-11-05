package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.preserveLegacyAnyTLSCertificatePolicy
import io.nekohasekai.sagernet.fmt.preserveRemovedMihomoAnyTLSOverride
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Executors

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class, DataGeneration::class],
    version = 12,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        // 只新建 data_generation 表，不插行（读不到行按 0，见 DataGeneration）
        AutoMigration(from = 9, to = 10)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
abstract class SagerDatabase : RoomDatabase() {

    companion object {

        // proxy_groups 新增 isSelector / frontProxy / landingProxy（NOT NULL，没有 SQL 默认值）：
        // 所以要重建这张表；proxy_entities 只新增了可空的 shadowTLSBean
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `proxy_groups_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userOrder` INTEGER NOT NULL,
                        `ungrouped` INTEGER NOT NULL,
                        `name` TEXT,
                        `type` INTEGER NOT NULL,
                        `subscription` BLOB,
                        `order` INTEGER NOT NULL,
                        `isSelector` INTEGER NOT NULL,
                        `frontProxy` INTEGER NOT NULL,
                        `landingProxy` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `proxy_groups_new` (
                        `id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                        `isSelector`, `frontProxy`, `landingProxy`
                    ) SELECT `id`, `userOrder`, `ungrouped`, `name`, `type`, `subscription`, `order`,
                        0, -1, -1 FROM `proxy_groups`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `proxy_groups`")
                db.execSQL("ALTER TABLE `proxy_groups_new` RENAME TO `proxy_groups`")
                db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `shadowTLSBean` BLOB")
            }
        }

        // proxy_entities 新增了可空的 mieruBean
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `mieruBean` BLOB")
            }
        }

        // K1：存量节点的一次性升级标注（fmt/LegacyProfileUpgrade.kt）。表结构不变，只改写 core 列、VMess 行的
        // vmessBean 与 Trojan 行的 trojanBean：mux 协议族、ws early data 的携带方式、uTLS 指纹、越界取值与手动核心值，
        // 让 K1 之前写下的节点在新的选核下照原样运行。读写流程在 migrateLegacyRows（LegacyRowMigration.kt），逐行判断
        // 在纯函数 upgradeLegacyRow 里，这里只执行 SQL。全局「允许不安全」从 configuration.db（另一个库，
        // PublicDatabase）读，不会反过来打开本库；只在遇到 bean 时读一次。
        // 只写有变化的行；bean 读不出的行只规范 core 列，只记行号与异常类型。日志不含节点内容
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val store = object : LegacyRowStore {
                    override fun query(sql: String, block: (LegacyRowCursor) -> Unit) {
                        db.query(sql).use { cursor ->
                            block(object : LegacyRowCursor {
                                override fun moveToNext() = cursor.moveToNext()
                                override fun getLong(index: Int) = cursor.getLong(index)
                                override fun getInt(index: Int) = cursor.getInt(index)
                                override fun isNull(index: Int) = cursor.isNull(index)
                                override fun getBlob(index: Int): ByteArray = cursor.getBlob(index)
                            })
                        }
                    }

                    override fun updateCore(id: Long, core: Int) {
                        db.execSQL("UPDATE `proxy_entities` SET `core` = ? WHERE `id` = ?", arrayOf<Any>(core, id))
                    }

                    override fun updateCoreAndVmessBean(id: Long, core: Int, vmessBean: ByteArray) {
                        db.execSQL(
                            "UPDATE `proxy_entities` SET `core` = ?, `vmessBean` = ? WHERE `id` = ?",
                            arrayOf<Any>(core, vmessBean, id),
                        )
                    }

                    override fun updateCoreAndTrojanBean(id: Long, core: Int, trojanBean: ByteArray) {
                        db.execSQL(
                            "UPDATE `proxy_entities` SET `core` = ?, `trojanBean` = ? WHERE `id` = ?",
                            arrayOf<Any>(core, trojanBean, id),
                        )
                    }
                }
                migrateLegacyRows(store, { DataStore.globalAllowInsecure }, { Logs.w(it) }, { Logs.i(it) })
            }
        }

        // 先按旧核心独立保留证书策略，再将全部 AnyTLS 恢复自动选核。
        // 只执行一次；之后用户手动选择的核心以及备份恢复的选择照常保留。
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 损坏行保留的未解析策略在能力匹配处拒绝，不能猜成固定。
                rewriteAnyTLSBeans(db, "`type` = ${ProxyEntity.TYPE_ANYTLS}", ::preserveLegacyAnyTLSCertificatePolicy)
                db.execSQL(
                    "UPDATE `proxy_entities` SET `core` = ? WHERE `type` = ? AND `core` != ?",
                    arrayOf<Any>(ProxyEntity.CORE_AUTO, ProxyEntity.TYPE_ANYTLS, ProxyEntity.CORE_AUTO),
                )
            }
        }

        // mihomo 已移除：先保留旧覆写的目标，再恢复自动，不能把本机 SOCKS JSON 静默套到 AnyTLS 上。
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 没有补标记的行不重写 bean，证书策略与来源都不变。
                rewriteAnyTLSBeans(
                    db,
                    "`type` = ${ProxyEntity.TYPE_ANYTLS} AND `core` = ${ProxyEntity.CORE_MIHOMO}",
                    ::preserveRemovedMihomoAnyTLSOverride,
                )
                db.execSQL(
                    "UPDATE `proxy_entities` SET `core` = ? WHERE `type` = ? AND `core` = ?",
                    arrayOf<Any>(ProxyEntity.CORE_AUTO, ProxyEntity.TYPE_ANYTLS, ProxyEntity.CORE_MIHOMO),
                )
            }
        }

        // 逐行改写 AnyTLS bean：损坏字节保持原样，transform（bean、core 列）返回 false 的行不写回。
        private fun rewriteAnyTLSBeans(db: SupportSQLiteDatabase, where: String, transform: (AnyTLSBean, Int) -> Boolean) {
            db.query("SELECT `id`, `core`, `anyTLSBean` FROM `proxy_entities` WHERE $where").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.isNull(2)) continue
                    val bean = runCatching { KryoConverters.deserialize(AnyTLSBean(), cursor.getBlob(2)) }.getOrNull() ?: continue
                    if (!transform(bean, cursor.getInt(1))) continue
                    db.execSQL(
                        "UPDATE `proxy_entities` SET `anyTLSBean` = ? WHERE `id` = ?",
                        arrayOf<Any>(KryoConverters.serialize(bean), cursor.getLong(0)),
                    )
                }
            }
        }

        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_8_9, MIGRATION_10_11, MIGRATION_11_12)
                .setJournalMode(JournalMode.TRUNCATE)
                .enableMultiInstanceInvalidation()
                // 不用 destructive fallback：缺迁移时它会静默丢掉所有配置、分组和规则——
                // 而降级到旧版 APK 必然缺迁移。打开失败至少能让数据保持可恢复。
                // 单线程执行器让提交的任务保持顺序，并且不占主线程
                .setQueryExecutor(Executors.newSingleThreadExecutor())
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()
        val dataGenerationDao get() = instance.dataGenerationDao()

        // 当前数据世代号，没有行时为 0（见 DataGeneration）
        fun dataGeneration(): Long = dataGenerationDao.get() ?: 0L

        // 世代号 +1，只在恢复备份整表替换的事务里调用（BackupRestore.commit）
        fun bumpDataGeneration() {
            dataGenerationDao.ensureRow()
            dataGenerationDao.increment()
        }

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao
    abstract fun dataGenerationDao(): DataGeneration.Dao

}
