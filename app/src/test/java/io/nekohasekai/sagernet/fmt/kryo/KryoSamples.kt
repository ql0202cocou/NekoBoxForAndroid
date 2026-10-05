package io.nekohasekai.sagernet.fmt.kryo

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.byteBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.util.Base64

// app/src/test/resources/kryo/ 下的 Kryo 兼容样本：历史提交的实现写出的真实字节，
// 以及各层当前版本号的登记（registry.json）。做法见该目录的 README.md
object KryoSamples {

    class Sample(val file: String, val json: JsonObject) {
        val id: String = json["id"].asString
        val className: String = json["class"].asString
        // storage：Room / 备份里的格式；share：ProxyGroup 的分享链接格式（export = true）
        val form: String = json["form"].asString
        val versions: Map<String, Int> = json["versions"].asJsonObject.entrySet().associate { it.key to it.value.asInt }
        val expected: JsonObject = json["expected"].asJsonObject
        val bytes: ByteArray = Base64.getDecoder().decode(json["base64"].asString)
        val current: Boolean = json["current"].asBoolean
        val knownIssue: String? = json["knownIssue"]?.takeIf { !it.isJsonNull }?.asString
        override fun toString() = "$id（$file）"
    }

    private val root: File by lazy {
        File(requireNotNull(javaClass.classLoader?.getResource("kryo")) { "缺少测试资源 kryo/" }.toURI())
    }

    val registry: JsonObject by lazy { JsonParser.parseString(root.resolve("registry.json").readText()).asJsonObject }

    val samples: List<Sample> by lazy {
        root.resolve("samples").listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }
            .flatMap { f ->
                val doc = JsonParser.parseString(f.readText()).asJsonObject
                doc.getAsJsonArray("samples").map { Sample(f.name, it.asJsonObject) }
            }
    }

    // 登记的各层当前版本号
    fun currentVersion(layer: String): Int = registry.getAsJsonObject("layers").getAsJsonObject(layer)["current"].asInt

    // 某个类从叶子到根依次写出的版本层（AbstractBean 是 serialize 之后的 extraVersion）
    fun layersOf(className: String): List<String> =
        registry.getAsJsonObject("classes").getAsJsonArray(className).map { it.asString }

    // ---- 与生产代码相同的读写入口

    fun newInstance(sample: Sample): Serializable = when (sample.className) {
        ProxyEntity::class.java.name -> ProxyEntity()
        ProxyGroup::class.java.name -> ProxyGroup().apply { export = sample.form == "share" }
        else -> Class.forName(sample.className).getDeclaredConstructor().newInstance() as Serializable
    }

    // 严格路径：分享链接、备份记录与 Parcel 都走它；Room 列转换器在它外面多一层出错兜底
    fun read(sample: Sample, bytes: ByteArray = sample.bytes): Serializable =
        KryoConverters.deserialize(newInstance(sample), bytes)

    fun write(obj: Serializable): ByteArray = KryoConverters.serialize(obj)

    // ---- 从当前实现写出的字节里取各层版本号

    fun versionsOf(obj: Serializable): Map<String, Int> {
        val bytes = write(obj)
        return when (obj) {
            is AbstractBean -> beanVersions(obj, bytes)
            is SubscriptionBean -> mapOf("SubscriptionBean" to bytes.byteBuffer().readInt())
            is ProxyEntity -> mapOf("ProxyEntity" to bytes.byteBuffer().readInt()) + beanVersions(obj.requireBean())
            is ProxyGroup -> {
                val input = bytes.byteBuffer()
                if (obj.export) {
                    val version = input.readInt()
                    input.readString() // name
                    input.readInt() // type
                    mapOf("ProxyGroup.export" to version, "SubscriptionBean.share" to input.readInt())
                } else {
                    val version = input.readInt()
                    if (obj.type != GroupType.SUBSCRIPTION) return mapOf("ProxyGroup" to version)
                    input.readLong() // id
                    input.readLong() // userOrder
                    input.readBoolean() // ungrouped
                    input.readString() // name
                    input.readInt() // type
                    mapOf("ProxyGroup" to version, "SubscriptionBean" to input.readInt())
                }
            }

            else -> error("未登记的类型 ${obj.javaClass.name}")
        }
    }

    private fun beanVersions(bean: AbstractBean, bytes: ByteArray = write(bean)): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val input = bytes.byteBuffer()
        for (layer in layersOf(bean.javaClass.name)) {
            if (layer == "AbstractBean") continue
            out[layer] = input.readInt()
        }
        // extraVersion 紧跟在 serialize() 写出的部分之后
        val part = ByteArrayOutputStream()
        val output = ByteBufferOutput(part)
        bean.serialize(output)
        output.flush()
        val extra = bytes.byteBuffer()
        extra.setPosition(part.size())
        out["AbstractBean"] = extra.readInt()
        return out
    }

    // ---- 字段比较

    private val ENTITY_FIELDS = listOf("id", "groupId", "type", "userOrder", "tx", "rx", "status", "ping", "uuid", "error", "core", "dirty")
    private val GROUP_FIELDS = listOf(
        "id", "userOrder", "ungrouped", "name", "type", "order", "isSelector", "frontProxy", "landingProxy", "proxyServerNameserver",
    )

    private fun declared(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)

    // 对象的全部持久化字段：bean 取公开的非静态、非 transient 字段；实体取 Kryo 写出的属性
    fun fieldsOf(obj: Any): Map<String, Any?> = when (obj) {
        is ProxyEntity -> ENTITY_FIELDS.associateWith { declared(obj, it) } + ("bean" to obj.requireBean())
        is ProxyGroup -> GROUP_FIELDS.associateWith { declared(obj, it) } + ("subscription" to obj.subscription)
        else -> obj.javaClass.fields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
            .associate { it.name to it.get(obj) }
    }

    // 逐字段比较，返回不一致之处（空表示全部一致）
    fun diff(where: String, obj: Any?, expected: JsonElement): List<String> {
        if (obj == null || expected.isJsonNull) {
            return if (obj == null && expected.isJsonNull) emptyList() else listOf("$where：期望 $expected，实际 $obj")
        }
        val exp = expected.asJsonObject
        val fields: JsonObject
        if (obj is AbstractBean || obj is SubscriptionBean) {
            // 嵌套对象（实体里的 bean、分组里的订阅）带 class
            if (exp.has("class") && exp.has("fields")) {
                if (exp["class"].asString != obj.javaClass.name) {
                    return listOf("$where：期望类型 ${exp["class"].asString}，实际 ${obj.javaClass.name}")
                }
                fields = exp.getAsJsonObject("fields")
            } else {
                fields = exp
            }
        } else {
            fields = exp
        }
        val actual = fieldsOf(obj)
        val out = ArrayList<String>()
        val missing = actual.keys - fields.keySet()
        val extra = fields.keySet() - actual.keys
        if (missing.isNotEmpty()) out += "$where：样本没有写这些字段的期望值 $missing"
        if (extra.isNotEmpty()) out += "$where：当前类没有这些字段 $extra"
        for ((name, value) in actual) {
            val e = fields[name] ?: continue
            if (value is AbstractBean || value is SubscriptionBean) {
                out += diff("$where.$name", value, e)
            } else if (value == null && e.isJsonObject) {
                out += "$where.$name：期望 $e，实际 null"
            } else if (!matches(value, e)) {
                out += "$where.$name：期望 $e，实际 ${describe(value)}"
            }
        }
        return out
    }

    private fun describe(v: Any?) = if (v is String) "\"$v\"" else v.toString()

    private fun matches(actual: Any?, e: JsonElement): Boolean = when (actual) {
        null -> e.isJsonNull
        is String -> e.isJsonPrimitive && e.asJsonPrimitive.isString && e.asString == actual
        is Boolean -> e.isJsonPrimitive && e.asJsonPrimitive.isBoolean && e.asBoolean == actual
        is Int -> e.isJsonPrimitive && e.asJsonPrimitive.isNumber && e.asString == actual.toString()
        is Long -> e.isJsonPrimitive && e.asJsonPrimitive.isNumber && e.asString == actual.toString()
        is List<*> -> e.isJsonArray && e.asJsonArray.size() == actual.size &&
            actual.indices.all { matches(actual[it], e.asJsonArray[it]) }

        else -> false
    }
}
