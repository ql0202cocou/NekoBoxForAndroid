package io.nekohasekai.sagernet.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 本库数据的世代号（单行表，id 固定为 1）：每次恢复备份把分组 / 节点 / 规则整表换掉时 +1（BackupRestore.commit）。
 * 恢复是「清表 + 按备份原 id 插回」，是唯一制造 id 复用的路径；跨过恢复的「先读后写」任务（目前只有订阅更新）
 * 在开始时记下世代号，写库事务里再读一次，不一致就说明期间整库被换过，手里按 id 算出的增删改都不再可信，整次放弃。
 *
 * 为什么放在库里而不是文件：+1 与恢复的整表替换在同一个事务里，比对与订阅更新的写入在同一个事务里，两边由
 * SQLite 的库锁排出先后，跨进程（主进程恢复、:bg 自动更新）也成立；放文件的话正确性要靠文件锁与事务的先后
 * 顺序，进程在两者之间被杀就会错位。
 *
 * 这张表不进备份（BackupRepository / BackupRestore 不读写它）、不进 Kryo；没有行时按 0 算（9 → 10 的
 * AutoMigration 只建表不插行，第一次恢复时才插入）。升到含这张表的版本（库版本 10）后不能降级装回旧版 APK。
 */
@Entity(tableName = "data_generation")
data class DataGeneration(
    @PrimaryKey var id: Long = ROW_ID,
    var generation: Long = 0L,
) {

    companion object {
        const val ROW_ID = 1L

        // SQL 写成常量：注解与单测（DataGenerationSqlTest）用的是同一份
        const val SQL_GET = "SELECT generation FROM data_generation WHERE id = 1"

        // minSdk 24 的 SQLite 是 3.9，没有 UPSERT（3.24 才有）：先补一行 0，再 +1
        const val SQL_ENSURE_ROW = "INSERT OR IGNORE INTO data_generation (id, generation) VALUES (1, 0)"
        const val SQL_INCREMENT = "UPDATE data_generation SET generation = generation + 1 WHERE id = 1"
    }

    @androidx.room.Dao
    interface Dao {

        @Query(SQL_GET)
        fun get(): Long?

        @Query(SQL_ENSURE_ROW)
        fun ensureRow()

        @Query(SQL_INCREMENT)
        fun increment()
    }
}
