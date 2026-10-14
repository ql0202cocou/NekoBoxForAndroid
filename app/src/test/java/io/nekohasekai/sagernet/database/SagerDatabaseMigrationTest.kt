package io.nekohasekai.sagernet.database

import androidx.room.migration.Migration
import io.nekohasekai.sagernet.SilencedLogs
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// SagerDatabase 的迁移：用 app/schemas 里 Room 导出的 JSON 在 JVM 内存库里建出旧版本的库、插入有代表性的旧数据，
// 执行真正的迁移（手写的 MIGRATION_*、Room 为 AutoMigration 生成的实现类），再与新版本的 JSON 逐项比对（口径同 Room
// 打开库时的 TableInfo 校验，设施见 MigrationTestSupport.kt），并核对数据。新增迁移时：导出新版本的 schema JSON，
// 手写迁移照 MIGRATION_ 命名放进 SagerDatabase 并加进 addMigrations（本文件的登记检查会发现漏掉的），再在这里补该
// 迁移特有的数据断言；逐级升级、登记检查与 schema 比对不用改
class SagerDatabaseMigrationTest {

    // MIGRATION_8_9 会写日志；Logs 开着就走 JNI（Libcore），JVM 上没有
    @get:Rule
    val logs = SilencedLogs()

    // ---- 迁移登记：全部手写迁移（SagerDatabase 里 private 的 MIGRATION_*）与 Room 生成的 AutoMigration

    private val manual: Map<String, Migration> by lazy {
        SagerDatabase::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("MIGRATION_") }
            .associate { field ->
                field.isAccessible = true
                field.name to field.get(null) as Migration
            }
    }

    private val auto: List<Migration> by lazy { SagerDatabase_Impl().createAutoMigrations(emptyMap()) }

    private val latest by lazy { Schema.latestVersion() }

    private fun migrationFrom(version: Int): Migration {
        val candidates = manual.values.filter { it.startVersion == version } + auto.filter { it.startVersion == version }
        assertEquals("从 $version 版出发的迁移应恰好一个", 1, candidates.size)
        return candidates.single().also { assertEquals(version + 1, it.endVersion) }
    }

    // 手写迁移走 SupportSQLiteDatabase，AutoMigration 生成类走 SQLiteConnection：与 Room 实际调用的入口一致
    private fun SqliteMemoryDb.migrate(migration: Migration) {
        if (migration in auto) migration.migrate(connection()) else migration.migrate(supportDatabase())
    }

    private fun open(version: Int) = SqliteMemoryDb().also { it.createSchema(Schema.load(version)) }

    private fun SqliteMemoryDb.assertSchema(version: Int) {
        val problems = schemaDifferences(Schema.load(version))
        assertTrue("库结构与第 $version 版 schema 不一致：\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    // ---- 造数据

    private fun Schema.row(table: String, vararg values: Pair<String, Any?>): Map<String, Any?> {
        val given = values.associate { (key, value) -> key to (if (value is Int) value.toLong() else value) }
        val fields = table(table).fields
        val unknown = given.keys - fields.map { it.name }.toSet()
        require(unknown.isEmpty()) { "第 $version 版 $table 没有列 $unknown" }
        return fields.associate { field ->
            field.name to if (field.name in given) given[field.name] else when {
                !field.notNull -> null
                field.affinity == "INTEGER" -> 0L
                field.affinity == "TEXT" -> ""
                field.affinity == "REAL" -> 0.0
                else -> blobOf()
            }
        }
    }

    private fun SqliteMemoryDb.insert(table: String, row: Map<String, Any?>) {
        val columns = row.keys.joinToString(", ") { "`$it`" }
        execute("INSERT INTO `$table` ($columns) VALUES (${row.keys.joinToString(", ") { "?" }})", row.values.toList())
    }

    // ---- 登记检查

    @Test
    fun `迁移登记完整并覆盖每一级`() {
        assertEquals(latest, (1 until latest).map { migrationFrom(it) }.last().endVersion)
        // schema JSON 逐版导出，最高版本就是库的当前版本；手写与自动迁移合起来恰好每级一个
        assertEquals((1 until latest).toList(), (manual.values + auto).map { it.startVersion }.sorted())
        // 手写迁移必须真的注册进 Room.databaseBuilder，否则测试通过而线上升级时直接抛异常
        val source = listOf(
            File("src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt"),
            File("app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt"),
        ).first { it.isFile }.readText()
        val registered = Regex("""addMigrations\(([^)]*)\)""").find(source)!!.groupValues[1]
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(manual.keys, registered)
    }

    // ---- 比对设施自检：每个比对维度都要能发现差异，否则下面的迁移测试通过也不说明什么

    @Test
    fun `比对设施能发现各类差异`() {
        val schema = Schema.load(latest)
        fun differences(table: String = "rules", mutate: (String) -> String = { it }, after: (SqliteMemoryDb) -> Unit = {}): List<String> =
            SqliteMemoryDb().use { db ->
                for (t in schema.tables) {
                    db.execute(if (t.name == table) mutate(t.createStatement()) else t.createStatement())
                    for (index in t.indices) db.execute(index.createSql.replace("\${TABLE_NAME}", t.name))
                }
                after(db)
                db.schemaDifferences(schema)
            }

        assertEquals(emptyList<String>(), differences())
        // 默认值写错
        assertTrue(differences(mutate = { it.replace("`config` TEXT NOT NULL DEFAULT ''", "`config` TEXT NOT NULL DEFAULT 'x'") })
            .any { "rules.config 默认值" in it })
        // 默认值漏掉
        assertTrue(differences(mutate = { it.replace(" DEFAULT ''", "") }).any { "rules.config 默认值" in it })
        // NOT NULL 漏掉
        assertTrue(differences(mutate = { it.replace("`name` TEXT NOT NULL", "`name` TEXT") }).any { "rules.name NOT NULL" in it })
        // 类型亲和性不同
        assertTrue(differences(mutate = { it.replace("`enabled` INTEGER", "`enabled` TEXT") }).any { "rules.enabled 类型亲和性" in it })
        // 主键位置不同（id 不是主键）
        assertTrue(differences(mutate = { it.replace("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", "`id` INTEGER NOT NULL") })
            .any { "rules.id 主键位置" in it })
        // 缺列、多列
        assertTrue(differences(mutate = { it.replace(", `packages` TEXT NOT NULL", "") }).any { "rules 缺列 packages" in it })
        assertTrue(differences(mutate = { it.replace(")", ", `extra` TEXT)") }).any { "rules 多出列 extra" in it })
        // 缺索引、索引列不同、多出的索引
        assertTrue(differences(after = { it.execute("DROP INDEX `groupId`") }).any { "缺索引 groupId" in it })
        assertTrue(differences(after = { it.execute("DROP INDEX `groupId`"); it.execute("CREATE INDEX `groupId` ON `proxy_entities` (`userOrder`)") })
            .any { "索引 groupId 列" in it })
        assertTrue(differences(after = { it.execute("CREATE INDEX `stray` ON `rules` (`name`)") }).any { "多出的索引 stray" in it })
        // 多出的表（迁移里重建表时忘了删临时表）
        assertTrue(differences(after = { it.execute("CREATE TABLE `proxy_groups_new` (`id` INTEGER)") })
            .any { "多出的表或视图 proxy_groups_new" in it })
    }

    // ---- 1 → 2：proxy_groups 重建表（新增三个 NOT NULL 且无 SQL 默认值的列），proxy_entities 加 shadowTLSBean

    @Test
    fun `1 到 2 重建分组表并补默认值，其余数据原样保留`() {
        val v1 = Schema.load(1)
        val groups = listOf(
            v1.row("proxy_groups", "id" to 1, "userOrder" to 0, "ungrouped" to 1, "name" to null, "type" to 0, "subscription" to null, "order" to 0),
            v1.row("proxy_groups", "id" to 2, "userOrder" to 1, "ungrouped" to 0, "name" to "订阅 A", "type" to 1, "subscription" to blobOf(1, 2, 3, 0, 255), "order" to 2),
            // id 不连续：重建表后自增计数要从最大的 id 接着走
            v1.row("proxy_groups", "id" to 5, "userOrder" to 2, "ungrouped" to 0, "name" to "分组 B", "type" to 0, "subscription" to null, "order" to 1),
        )
        val entities = listOf(
            v1.row("proxy_entities", "id" to 1, "groupId" to 1, "type" to 0, "userOrder" to 0, "tx" to 10, "rx" to 20, "status" to 1, "ping" to 35, "uuid" to "u-1", "socksBean" to blobOf(9, 8, 7)),
            v1.row("proxy_entities", "id" to 2, "groupId" to 2, "type" to 4, "userOrder" to 1, "tx" to 0, "rx" to 0, "status" to 3, "ping" to -1, "uuid" to "u-2", "error" to "timeout", "vmessBean" to blobOf(0, 0, 1)),
            v1.row("proxy_entities", "id" to 9, "groupId" to 5, "type" to 6, "userOrder" to 2, "tx" to 123456789012, "rx" to 1, "status" to 0, "ping" to 0, "uuid" to "u-9", "trojanBean" to blobOf(255)),
        )
        val rules = listOf(v1.row("rules", "id" to 3, "name" to "规则", "userOrder" to 0, "enabled" to 1, "domains" to "geosite:cn", "outbound" to -1))
        open(1).use { db ->
            groups.forEach { db.insert("proxy_groups", it) }
            entities.forEach { db.insert("proxy_entities", it) }
            rules.forEach { db.insert("rules", it) }

            db.migrate(migrationFrom(1))

            db.assertSchema(2)
            assertEquals(
                groups.map { it + mapOf("isSelector" to 0L, "frontProxy" to -1L, "landingProxy" to -1L) },
                db.table("proxy_groups"),
            )
            assertEquals(entities.map { it + ("shadowTLSBean" to null) }, db.table("proxy_entities"))
            assertEquals(rules, db.table("rules"))
            // 重建的表仍是 AUTOINCREMENT，新行的 id 接在存量最大 id 之后
            db.insert("proxy_groups", Schema.load(2).row("proxy_groups", "id" to null, "name" to "新分组"))
            assertEquals(6L, db.scalar("SELECT max(id) FROM proxy_groups"))
            assertEquals(6L, db.scalar("SELECT seq FROM sqlite_sequence WHERE name = 'proxy_groups'"))
        }
    }

    // ---- 2 → 3：proxy_entities 加可空的 mieruBean

    @Test
    fun `2 到 3 给节点表加 mieruBean，旧行为空`() {
        val v2 = Schema.load(2)
        val entities = listOf(
            v2.row("proxy_entities", "id" to 1, "groupId" to 1, "type" to 0, "uuid" to "a", "socksBean" to blobOf(1)),
            v2.row("proxy_entities", "id" to 2, "groupId" to 1, "type" to 4, "uuid" to "b", "error" to "e", "shadowTLSBean" to blobOf(2, 3)),
        )
        val groups = listOf(v2.row("proxy_groups", "id" to 1, "name" to "g", "isSelector" to 1, "frontProxy" to 4, "landingProxy" to -1))
        open(2).use { db ->
            entities.forEach { db.insert("proxy_entities", it) }
            groups.forEach { db.insert("proxy_groups", it) }

            db.migrate(migrationFrom(2))

            db.assertSchema(3)
            assertEquals(entities.map { it + ("mieruBean" to null) }, db.table("proxy_entities"))
            assertEquals(groups, db.table("proxy_groups"))
            // 新列可写、可读
            db.execute("UPDATE proxy_entities SET mieruBean = ? WHERE id = 1", listOf(blobOf(7, 7)))
            assertEquals(blobOf(7, 7), db.scalar("SELECT mieruBean FROM proxy_entities WHERE id = 1"))
        }
    }

    // ---- 8 → 9：表结构不变，只按存量标注规则改写 proxy_entities.core（见 LegacyProfileUpgrade.kt）。
    // 读得出的 bean（VMess / Trojan）要取全局「允许不安全」，那一步读 DataStore，需要 Android 的 Context，JVM 上
    // 没有，所以这里的行只含 bean 为空或读不出的情形；读得出的 bean 的写回由 LegacyRowMigrationTest 用假表覆盖

    @Test
    fun `8 到 9 只规范 core 列，读不出的 bean 原样保留`() {
        val brokenVmess = KryoSamples.samples.single { it.id == "VMessBean/dd8f56d3/vmess-tcp-tls" }.bytes.let { it.copyOf(it.size / 2) }.toBlob()
        val brokenTrojan = KryoSamples.samples.single { it.id == "TrojanBean/aa275d5e/trojan-tcp-tls-ech" }.bytes.let { it.copyOf(it.size / 2) }.toBlob()
        val v8 = Schema.load(8)
        fun entity(id: Int, type: Int, core: Int, vararg beans: Pair<String, Blob?>) = v8.row(
            "proxy_entities", "id" to id, "groupId" to 1, "type" to type, "userOrder" to id, "tx" to id * 100, "rx" to id, "status" to 1,
            "ping" to 20, "uuid" to "u-$id", "error" to (if (id % 2 == 0) "err-$id" else null), "core" to core, *beans,
        )
        // 行 → 迁移后的 core
        val cases = listOf(
            entity(1, TYPE_SOCKS, CORE_XRAY, "socksBean" to blobOf(1, 2, 3)) to CORE_AUTO,
            entity(2, TYPE_VMESS, CORE_MIHOMO) to CORE_SING_BOX,
            entity(3, TYPE_VMESS, CORE_XRAY, "vmessBean" to brokenVmess) to CORE_XRAY,
            entity(4, TYPE_VMESS, 9, "vmessBean" to brokenVmess) to CORE_SING_BOX,
            entity(5, TYPE_TROJAN, CORE_SING_BOX) to CORE_AUTO,
            entity(6, TYPE_TROJAN, CORE_XRAY, "trojanBean" to brokenTrojan) to CORE_AUTO,
            entity(7, TYPE_ANYTLS, CORE_XRAY) to CORE_SING_BOX,
            entity(8, TYPE_ANYTLS, CORE_MIHOMO) to CORE_MIHOMO,
            entity(9, TYPE_ANYTLS, CORE_AUTO) to CORE_AUTO,
            entity(10, TYPE_CHAIN, CORE_SING_BOX) to CORE_AUTO,
            entity(11, TYPE_HYSTERIA, CORE_MIHOMO, "hysteriaBean" to blobOf(4, 5)) to CORE_AUTO,
            // vmessBean 是空字节：不当作 bean 读
            entity(12, TYPE_VMESS, CORE_AUTO, "vmessBean" to blobOf()) to CORE_AUTO,
            entity(13, TYPE_VMESS, CORE_SING_BOX, "vmessBean" to brokenVmess) to CORE_SING_BOX,
        )
        val groups = listOf(
            v8.row("proxy_groups", "id" to 1, "name" to "g", "subscription" to blobOf(1, 2), "proxyServerNameserver" to "8.8.8.8"),
            v8.row("proxy_groups", "id" to 4, "isSelector" to 1, "frontProxy" to 3, "landingProxy" to 2),
        )
        val rules = listOf(v8.row("rules", "id" to 1, "name" to "r", "config" to "{}", "outbound" to 3, "packages" to "a.b"))
        open(8).use { db ->
            cases.forEach { db.insert("proxy_entities", it.first) }
            groups.forEach { db.insert("proxy_groups", it) }
            rules.forEach { db.insert("rules", it) }
            // 迁移之前就已是 8 版的数据：断言前先确认起点符合预期，免得下面的相等是空转
            assertEquals(cases.size.toLong(), db.scalar("SELECT count(*) FROM proxy_entities"))

            db.migrate(migrationFrom(8))

            db.assertSchema(9)
            assertEquals(cases.map { (row, core) -> row + ("core" to core.toLong()) }, db.table("proxy_entities"))
            assertEquals(groups, db.table("proxy_groups"))
            assertEquals(rules, db.table("rules"))
        }
    }

    // ---- 从每个旧版本一路升到最新版：每一级之后都比对 schema，旧数据不丢，新增列取默认值。
    // 3 → 8 是 Room 生成的 AutoMigration（实现类只在 SQLiteConnection 入口上执行 ALTER TABLE），8 → 9 因取不到
    // DataStore 只能带读不出 / 为空的 bean，这里种的行都是 SOCKS 类型、core = 自动，不受 8 → 9 的规范化影响

    private fun seed(db: SqliteMemoryDb, schema: Schema): Map<String, List<Map<String, Any?>>> {
        for (table in schema.tables) {
            for (id in listOf(1, 2, 7)) {
                val values = table.fields.filter { it.name != "id" }.mapNotNull { field ->
                    val value: Any? = when {
                        table.name == "proxy_entities" && (field.name == "type" || field.name == "core") -> 0L
                        field.affinity == "INTEGER" -> (if (field.notNull) id.toLong() else null)
                        field.affinity == "TEXT" -> (if (field.notNull) "s$id-${field.name}" else "t$id-${field.name}")
                        else -> if (field.notNull) blobOf() else blobOf(id, id + 1)
                    }
                    field.name to value
                }.toTypedArray()
                db.insert(table.name, schema.row(table.name, "id" to id, *values))
            }
        }
        return schema.tables.associate { it.name to db.table(it.name) }
    }

    // 迁移新增的列在旧行上的取值：可空列为 NULL，带默认值的列取默认值，proxy_groups 的三列由 1 → 2 的 INSERT 补
    private fun addedColumnValue(table: SchemaTable, field: SchemaField): Any? = when {
        table.name == "proxy_groups" && field.name == "isSelector" -> 0L
        table.name == "proxy_groups" && field.name == "frontProxy" -> -1L
        table.name == "proxy_groups" && field.name == "landingProxy" -> -1L
        !field.notNull -> null
        field.defaultValue == "''" -> ""
        else -> field.defaultValue!!.toLong()
    }

    @Test
    fun `从每个旧版本逐级升级到最新版`() {
        for (start in 1 until latest) {
            val startSchema = Schema.load(start)
            open(start).use { db ->
                val before = seed(db, startSchema)
                for (version in start until latest) {
                    db.migrate(migrationFrom(version))
                    val problems = db.schemaDifferences(Schema.load(version + 1))
                    assertTrue("$start 版出发，升到 ${version + 1} 版后库结构不一致：\n${problems.joinToString("\n")}", problems.isEmpty())
                }
                val finalSchema = Schema.load(latest)
                for (table in finalSchema.tables) {
                    val startTable = startSchema.table(table.name)
                    val added = table.fields.filter { f -> startTable.fields.none { it.name == f.name } }
                    val expected = before.getValue(table.name).map { row -> row + added.associate { it.name to addedColumnValue(table, it) } }
                    assertEquals("$start 版出发，${table.name} 的数据", expected, db.table(table.name))
                }
            }
        }
    }
}
