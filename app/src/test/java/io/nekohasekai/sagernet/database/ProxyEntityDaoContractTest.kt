package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * proxy_entities 的列归属契约：每一列只属于一类写入口，各类只经自己的 DAO 方法写，谁也不整行写回。
 *
 * - 配置类：type、core 与全部 bean 列，只经 `@Update(entity = ProxyEditableFields::class)` 写（编辑器保存、订阅更新）。
 * - 排序与归属类：userOrder、groupId（拖拽排序、订阅更新的排序、移动分组）。
 * - 流量类：tx、rx（:bg 落库的增量、界面清除流量的归零）。
 * - 测试状态类：status、ping、error（延迟测试、配置变了之后的清零）。
 * - 插入后不再改的列：id、uuid。
 *
 * Room 的注解是 CLASS 保留期，反射读不到 `@Update` / `@Query`，所以 DAO 的声明从源码文本里解析，
 * 再用反射核对「源码里的方法 = 编译后的方法」，保证解析没有漏项。
 */
class ProxyEntityDaoContractTest {

    private val configColumnsOfEditor: Set<String> =
        ProxyEditableFields::class.java.declaredFields.filter(::isColumn).map { it.name }.toSet() - "id"

    private val orderColumns = setOf("userOrder", "groupId")
    private val trafficColumns = setOf("tx", "rx")
    private val statusColumns = setOf("status", "ping", "error")
    private val immutableColumns = setOf("id", "uuid")

    private val classes: Map<String, Set<String>> by lazy {
        mapOf(
            "配置" to configColumnsOfEditor,
            "排序与归属" to orderColumns,
            "流量" to trafficColumns,
            "测试状态" to statusColumns,
        )
    }

    private fun isColumn(f: java.lang.reflect.Field) =
        !Modifier.isStatic(f.modifiers) && !Modifier.isTransient(f.modifiers)

    private class DaoMethod(val annotation: String, val args: String, val name: String, val params: String)

    // ProxyEntity.Dao 的源码文本，去掉行注释
    private fun daoSource(): String {
        val text = File("src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt").readText()
        val start = text.indexOf("interface Dao {")
        assertTrue("找不到 ProxyEntity.Dao 的源码", start >= 0)
        var depth = 0
        var end = -1
        for (i in text.indexOf('{', start) until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) { end = i; break }
        }
        assertTrue("ProxyEntity.Dao 的括号不配对", end > start)
        return text.substring(start, end).lines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun parseDao(): List<DaoMethod> {
        val source = daoSource()
        val withAnnotation = Regex(
            """@(Query|Update|Upsert|Insert|Delete|RawQuery)(?:\(((?:"[^"]*"|[^()"]|\([^)]*\))*)\))?\s*fun\s+(\w+)\(([^)]*)\)""",
        ).findAll(source).map { DaoMethod(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }.toList()
        // 每个 fun 都要带上面这些注解之一：没注解的方法（接口默认实现等）不在契约内
        val allNames = Regex("""\bfun\s+(\w+)\(""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("有 DAO 方法没有被解析到（缺注解或写法超出解析范围）", allNames.sorted(), withAnnotation.map { it.name }.sorted())
        val reflected = Class.forName("io.nekohasekai.sagernet.database.ProxyEntity\$Dao").declaredMethods
            .filterNot { it.isSynthetic }.map { it.name }.sorted()
        assertEquals("源码里的 DAO 方法与编译后的不一致", reflected, allNames.sorted())
        return withAnnotation
    }

    @Test
    fun `列的归属互不交叉，配置类等于实体里其余的持久化列`() {
        val names = classes.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val both = classes.getValue(names[i]) intersect classes.getValue(names[j])
            assertTrue("${names[i]} 与 ${names[j]} 都含有 $both", both.isEmpty())
        }
        val entityColumns = ProxyEntity::class.java.declaredFields.filter(::isColumn).map { it.name }.toSet()
        // 实体里新增的列，除了在上面各类里登记，配置类（bean 列）还必须同时进 ProxyEditableFields
        val nonConfig = orderColumns + trafficColumns + statusColumns + immutableColumns
        assertEquals(
            "ProxyEntity 的持久化列去掉其它几类后，必须正好等于 ProxyEditableFields 的字段（不含 id）：" +
                "新增的 bean 列要加进 ProxyEditableFields，新增的运行时列要先给它定归属",
            entityColumns - nonConfig,
            configColumnsOfEditor,
        )
        assertTrue("其它几类里有实体没有的列", entityColumns.containsAll(nonConfig))
    }

    @Test
    fun `DAO 里没有整行 Update 或 Upsert，部分更新只写一类列`() {
        val daoClass = Class.forName("io.nekohasekai.sagernet.database.ProxyEntity\$Dao")
        for (m in parseDao()) {
            if (m.annotation == "Upsert") fail("${m.name}: 不允许 @Upsert（整行写回）")
            if (m.annotation != "Update") continue
            assertTrue(
                "${m.name}: @Update 必须写成 @Update(entity = ProxyEntity::class) 的部分实体更新，不允许整行",
                Regex("""entity\s*=\s*ProxyEntity::class""").containsMatchIn(m.args),
            )
            val method = daoClass.declaredMethods.single { it.name == m.name }
            for (param in method.parameterTypes) {
                assertTrue("${m.name}: 参数不能是 ProxyEntity 整行", param != ProxyEntity::class.java)
            }
            val columns = method.parameterTypes.flatMap { type ->
                type.declaredFields.filter(::isColumn).map { it.name }
            }.toSet() - "id"
            assertWritesOneClass(m.name, columns)
        }
    }

    @Test
    fun `每条 UPDATE 查询只写一类列，四类各有写入口`() {
        val written = HashMap<String, MutableSet<String>>()
        for (m in parseDao()) {
            if (m.annotation == "Update") {
                val method = Class.forName("io.nekohasekai.sagernet.database.ProxyEntity\$Dao")
                    .declaredMethods.single { it.name == m.name }
                val columns = method.parameterTypes.flatMap { it.declaredFields.filter(::isColumn).map { f -> f.name } }.toSet() - "id"
                written.getOrPut(assertWritesOneClass(m.name, columns)) { HashSet() } += columns
                continue
            }
            if (m.annotation != "Query") continue
            val sql = m.args.trim().removePrefix("\"").removeSuffix("\"")
            if (!sql.trimStart().startsWith("UPDATE", ignoreCase = true)) continue
            val match = Regex(
                """UPDATE\s+(?:OR\s+\w+\s+)?proxy_entities\s+SET\s+(.*?)(?:\s+WHERE\s+.*)?""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).matchEntire(sql.trim())
            assertTrue("${m.name}: UPDATE 必须是 UPDATE proxy_entities SET … 的形式：$sql", match != null)
            val columns = match!!.groupValues[1].split(',').map { it.substringBefore('=').trim() }.toSet()
            written.getOrPut(assertWritesOneClass(m.name, columns)) { HashSet() } += columns
        }
        assertEquals("四类列都要有写入口", classes.keys, written.keys)
        // 每一类的写入口合起来覆盖该类的全部列：列没有写入口说明归属表和 DAO 脱节了
        for ((name, columns) in classes) {
            assertEquals("$name 类有列没有任何写入口", columns, written.getValue(name))
        }
    }

    // 这组列必须全落在同一类里，返回类名
    private fun assertWritesOneClass(method: String, columns: Set<String>): String {
        assertTrue("$method: 没有写任何列", columns.isNotEmpty())
        val owners = classes.filterValues { it.containsAll(columns) }.keys
        val touched = classes.filterValues { (it intersect columns).isNotEmpty() }.keys
        assertTrue(
            "$method: 写的列 $columns 跨了 $touched 几类（或含有归属表里没有的列），一个方法只能写一类",
            owners.size == 1,
        )
        return owners.single()
    }
}
