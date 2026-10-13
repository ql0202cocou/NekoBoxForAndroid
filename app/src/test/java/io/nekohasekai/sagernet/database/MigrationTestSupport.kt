package io.nekohasekai.sagernet.database

import android.database.Cursor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

// Room 迁移单测的 JVM 侧设施（SagerDatabaseMigrationTest 用）。官方的 MigrationTestHelper 在这里用不了：
// Android 变体要仪器（Instrumentation / 框架 SQLite），而 SQLiteDriver 模式下基类 Migration.migrate(SQLiteConnection)
// 对只覆盖了 migrate(SupportSQLiteDatabase) 的手写迁移直接抛 NotImplementedError。所以这里用 sqlite-jdbc（只进测试）
// 开内存库，把 JDBC 连接包成迁移真正会调用的两种入口：手写迁移走 SupportSQLiteDatabase，AutoMigration 的生成类走
// SQLiteConnection；两者都只实现迁移用到的方法，别的方法一律抛异常，免得悄悄放过新增的调用。
// 库结构的建立与比对都读 app/schemas 下 Room 导出的 JSON

internal data class Blob(val hex: String) {
    override fun toString() = "x'$hex'"
}

internal fun blobOf(vararg bytes: Int) = Blob(bytes.joinToString("") { "%02x".format(it and 0xff) })

internal fun ByteArray.toBlob() = Blob(joinToString("") { "%02x".format(it.toInt() and 0xff) })

internal class SqliteMemoryDb : AutoCloseable {
    private val jdbc: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    fun execute(sql: String, args: List<Any?> = emptyList()) {
        if (args.isEmpty()) {
            jdbc.createStatement().use { it.execute(sql) }
            return
        }
        jdbc.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg ->
                if (arg == null) statement.setNull(index + 1, Types.NULL)
                else statement.setObject(index + 1, if (arg is Blob) arg.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else arg)
            }
            statement.execute()
        }
    }

    // 原样返回 JDBC 的值（整数可能是 Integer，blob 是 ByteArray），列名取结果集的标签
    private fun rawQuery(sql: String): Pair<List<String>, List<List<Any?>>> = jdbc.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            val count = rs.metaData.columnCount
            val labels = (1..count).map { rs.metaData.getColumnLabel(it) }
            val rows = ArrayList<List<Any?>>()
            while (rs.next()) rows += (1..count).map { rs.getObject(it) }
            labels to rows
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Int, is Short, is Byte -> (value as Number).toLong()
        is ByteArray -> value.toBlob()
        else -> value
    }

    // 整数统一成 Long、blob 统一成 Blob，方便直接比较
    fun query(sql: String): List<List<Any?>> = rawQuery(sql).second.map { row -> row.map(::normalize) }

    fun table(name: String, orderBy: String = "id"): List<Map<String, Any?>> {
        val (labels, rows) = rawQuery("SELECT * FROM `$name` ORDER BY `$orderBy`")
        return rows.map { row -> labels.zip(row.map(::normalize)).toMap(LinkedHashMap()) }
    }

    fun scalar(sql: String): Any? = query(sql).single().single()

    override fun close() = jdbc.close()

    private fun proxy(type: Class<*>, handler: InvocationHandler): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when {
                method.name == "toString" && method.parameterCount == 0 -> "${type.simpleName}(test)"
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "equals" && method.parameterCount == 1 -> proxy === args[0]
                else -> handler.invoke(proxy, method, args)
            }
        }

    // 手写迁移的入口：只支持 execSQL（可带参数）与 query(sql)
    fun supportDatabase(): SupportSQLiteDatabase = proxy(SupportSQLiteDatabase::class.java) { _, method, args ->
        when (method.name) {
            "execSQL" -> {
                val params = (args.getOrNull(1) as Array<*>?)?.toList() ?: emptyList()
                execute(args[0] as String, params)
                null
            }
            "query" -> {
                require(args[0] is String) { "测试设施只支持按 SQL 字符串的 query" }
                val params = (args.getOrNull(1) as Array<*>?)?.toList() ?: emptyList<Any?>()
                require(params.isEmpty()) { "测试设施的 query 不支持绑定参数" }
                cursor(rawQuery(args[0] as String).second)
            }
            else -> throw UnsupportedOperationException("SupportSQLiteDatabase.${method.name} 测试设施没有实现")
        }
    } as SupportSQLiteDatabase

    private fun cursor(rows: List<List<Any?>>): Cursor {
        var position = -1
        var closed = false
        return proxy(Cursor::class.java) { _, method, args ->
            fun value() = rows[position][args[0] as Int]
            when (method.name) {
                "moveToNext" -> ++position < rows.size
                "getCount" -> rows.size
                "isNull" -> value() == null
                "getLong" -> (value() as Number).toLong()
                "getInt" -> (value() as Number).toInt()
                "getBlob" -> value() as ByteArray
                "getString" -> value() as String?
                "isClosed" -> closed
                "close" -> {
                    closed = true
                    null
                }
                else -> throw UnsupportedOperationException("Cursor.${method.name} 测试设施没有实现")
            }
        } as Cursor
    }

    // AutoMigration 生成类的入口：生成的迁移只调 connection.execSQL(sql)，即 prepare(sql) → step() → close()
    fun connection(): SQLiteConnection = proxy(SQLiteConnection::class.java) { _, method, args ->
        when (method.name) {
            "prepare" -> statement(args[0] as String)
            "close" -> null
            else -> throw UnsupportedOperationException("SQLiteConnection.${method.name} 测试设施没有实现")
        }
    } as SQLiteConnection

    private fun statement(sql: String): SQLiteStatement = proxy(SQLiteStatement::class.java) { _, method, _ ->
        when (method.name) {
            "step" -> {
                execute(sql)
                false
            }
            "reset", "clearBindings", "close" -> null
            else -> throw UnsupportedOperationException("SQLiteStatement.${method.name} 测试设施没有实现")
        }
    } as SQLiteStatement
}

// ---- Room 导出的 schema JSON

internal class SchemaField(val name: String, val affinity: String, val notNull: Boolean, val defaultValue: String?)

internal class SchemaIndex(val name: String, val unique: Boolean, val columns: List<String>, val orders: List<String>, val createSql: String)

internal class SchemaTable(
    val name: String,
    val createSql: String,
    val fields: List<SchemaField>,
    val primaryKey: List<String>,
    val indices: List<SchemaIndex>,
    val hasForeignKeys: Boolean,
) {
    fun field(column: String) = fields.single { it.name == column }
}

internal class Schema(val version: Int, val identityHash: String, val tables: List<SchemaTable>, val setupQueries: List<String>) {
    fun table(name: String) = tables.single { it.name == name }

    companion object {
        const val DIRECTORY = "schemas/io.nekohasekai.sagernet.database.SagerDatabase"

        private fun directory(): File =
            listOf(File(DIRECTORY), File("app/$DIRECTORY")).firstOrNull { it.isDirectory } ?: error("找不到 app/$DIRECTORY")

        // 目录里导出的最高版本号
        fun latestVersion(): Int = directory().listFiles()!!.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.max()

        fun load(version: Int): Schema {
            val root = JsonParser.parseString(File(directory(), "$version.json").readText()).asJsonObject.getAsJsonObject("database")
            require(root["version"].asInt == version) { "$version.json 里的版本号对不上" }
            fun JsonObject.strings(key: String) = getAsJsonArray(key)?.map { it.asString } ?: emptyList()
            fun JsonObject.flag(key: String) = get(key)?.asBoolean ?: false
            return Schema(
                version,
                root["identityHash"].asString,
                root.getAsJsonArray("entities").map { it.asJsonObject }.map { entity ->
                    SchemaTable(
                        entity["tableName"].asString,
                        entity["createSql"].asString,
                        entity.getAsJsonArray("fields").map { it.asJsonObject }.map { field ->
                            // 导出时 notNull 为 false 的字段不写这个键
                            SchemaField(
                                field["columnName"].asString,
                                field["affinity"].asString,
                                field.flag("notNull"),
                                field.get("defaultValue")?.asString,
                            )
                        },
                        entity.getAsJsonObject("primaryKey").strings("columnNames"),
                        entity.getAsJsonArray("indices")?.map { it.asJsonObject }?.map { index ->
                            SchemaIndex(index["name"].asString, index.flag("unique"), index.strings("columnNames"), index.strings("orders"), index["createSql"].asString)
                        } ?: emptyList(),
                        (entity.getAsJsonArray("foreignKeys")?.size() ?: 0) > 0,
                    )
                },
                root.strings("setupQueries"),
            )
        }
    }
}

private fun String.tableName(table: SchemaTable) = replace("\${TABLE_NAME}", table.name)

internal fun SchemaTable.createStatement() = createSql.tableName(this)

// 照 Room 导出的 createSql 与 setupQueries（room_master_table 与身份哈希）建出这一版的库
internal fun SqliteMemoryDb.createSchema(schema: Schema) {
    for (table in schema.tables) {
        execute(table.createStatement())
        for (index in table.indices) execute(index.createSql.tableName(table))
    }
    for (query in schema.setupQueries) execute(query)
}

// ---- 与 Room 打开库时的 TableInfo 校验同一口径：列（名、类型亲和性、NOT NULL、主键位置、默认值）、索引（名、
// 唯一性、列与排序）、外键；另外要求表集合、索引集合与导出的 schema 完全一致（Room 只比对声明过的表）

// 与 androidx.room.util.TableInfo.findAffinity 同一套规则
private fun affinityOf(type: String): String {
    val upper = type.uppercase()
    return when {
        "INT" in upper -> "INTEGER"
        "CHAR" in upper || "CLOB" in upper || "TEXT" in upper -> "TEXT"
        upper.isEmpty() || "BLOB" in upper -> "BLOB"
        "REAL" in upper || "FLOA" in upper || "DOUB" in upper -> "REAL"
        else -> "NUMERIC"
    }
}

// 与 TableInfo.Column.defaultValueEquals 一致：去掉最外层一对括号后再比。另外 `DEFAULT NULL` 与没写默认值等价
// （ALTER TABLE ADD COLUMN 的自动迁移就带这个子句），按没有默认值算
private fun sameDefault(expected: String?, actual: String?): Boolean {
    fun strip(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val inner = if (trimmed.length > 2 && trimmed.startsWith("(") && trimmed.endsWith(")")) trimmed.substring(1, trimmed.length - 1).trim() else trimmed
        return inner.takeUnless { it.equals("NULL", ignoreCase = true) }
    }
    return strip(expected) == strip(actual)
}

internal fun SqliteMemoryDb.schemaDifferences(schema: Schema): List<String> {
    val problems = ArrayList<String>()
    val hidden = "('sqlite_sequence', 'room_master_table', 'android_metadata')"
    val tables = query("SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT IN $hidden").map { it[0] as String }.toSet()
    val expectedTables = schema.tables.map { it.name }.toSet()
    (expectedTables - tables).forEach { problems += "缺表 $it" }
    (tables - expectedTables).forEach { problems += "多出的表或视图 $it" }
    val indexNames = query("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").map { it[0] as String }.toSet()
    val expectedIndexNames = schema.tables.flatMap { it.indices }.map { it.name }.toSet()
    (expectedIndexNames - indexNames).forEach { problems += "缺索引 $it" }
    (indexNames - expectedIndexNames).forEach { problems += "多出的索引 $it" }
    val triggers = query("SELECT name FROM sqlite_master WHERE type = 'trigger'")
    if (triggers.isNotEmpty()) problems += "多出的触发器 $triggers"

    for (table in schema.tables.filter { it.name in tables }) {
        val t = table.name
        // PRAGMA table_info：cid, name, type, notnull, dflt_value, pk
        val actual = query("PRAGMA table_info(`$t`)").associateBy { it[1] as String }
        (table.fields.map { it.name }.toSet() - actual.keys).forEach { problems += "$t 缺列 $it" }
        (actual.keys - table.fields.map { it.name }.toSet()).forEach { problems += "$t 多出列 $it" }
        for (field in table.fields) {
            val row = actual[field.name] ?: continue
            val affinity = affinityOf(row[2] as String)
            if (affinity != field.affinity) problems += "$t.${field.name} 类型亲和性 ${field.affinity} → $affinity"
            if ((row[3] as Long == 1L) != field.notNull) problems += "$t.${field.name} NOT NULL ${field.notNull} → ${row[3] == 1L}"
            val pk = table.primaryKey.indexOf(field.name) + 1
            if ((row[5] as Long).toInt() != pk) problems += "$t.${field.name} 主键位置 $pk → ${row[5]}"
            if (!sameDefault(field.defaultValue, row[4] as String?)) problems += "$t.${field.name} 默认值 ${field.defaultValue} → ${row[4]}"
        }
        // 索引：只看 CREATE INDEX 建的（origin = 'c'），不含主键与 UNIQUE 约束的自动索引
        val indexList = query("PRAGMA index_list(`$t`)").filter { it[3] == "c" }
        val actualIndices = indexList.associateBy { it[1] as String }
        for (index in table.indices) {
            val row = actualIndices[index.name] ?: continue
            if ((row[2] == 1L) != index.unique) problems += "$t 索引 ${index.name} 唯一性 ${index.unique} → ${row[2] == 1L}"
            // PRAGMA index_xinfo：seqno, cid, name, desc, coll, key；key = 1 的才是索引键列
            val keys = query("PRAGMA index_xinfo(`${index.name}`)").filter { it[5] == 1L }
            val columns = keys.map { it[2] as String }
            val orders = keys.map { if (it[3] == 1L) "DESC" else "ASC" }
            if (columns != index.columns) problems += "$t 索引 ${index.name} 列 ${index.columns} → $columns"
            val expectedOrders = index.orders.ifEmpty { index.columns.map { "ASC" } }
            if (orders != expectedOrders) problems += "$t 索引 ${index.name} 排序 $expectedOrders → $orders"
        }
        if (table.hasForeignKeys) problems += "$t 声明了外键，测试设施尚未实现外键比对"
        val foreignKeys = query("PRAGMA foreign_key_list(`$t`)")
        if (foreignKeys.isNotEmpty()) problems += "$t 多出外键 $foreignKeys"
    }
    return problems
}
