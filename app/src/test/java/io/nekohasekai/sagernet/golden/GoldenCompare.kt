package io.nekohasekai.sagernet.golden

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger

// 配置结构比较：把 JSON / YAML 文档解析成统一的值树，按采集产物里的动态值换成占位符后逐项比较。
// 规则：对象键序与空白不计；数组顺序与长度严格；整数与小数的写法分开；字符串与数字分开；
// 键缺失与 null 不同；重复键、解析失败一律报错。宁可误报，不可漏报。

/** 统一的文档值。字符串拆成字面文本与占位符两种片段，替换之后仍能逐片比较。 */
sealed class GoldenValue {
    data class Obj(val fields: Map<String, GoldenValue>) : GoldenValue()
    data class Arr(val items: List<GoldenValue>) : GoldenValue()
    data class Str(val parts: List<StrPart>) : GoldenValue()

    /** 整数写法：按数值比较。 */
    data class Integer(val value: BigInteger) : GoldenValue()

    /** 小数或指数写法：按数值比较（1.5 与 1.50 相等），保留原文用于展示。YAML 的 .inf / .nan 归入 [Raw]。 */
    class Decimal(val text: String, val value: BigDecimal) : GoldenValue() {
        override fun equals(other: Any?) = other is Decimal && value.compareTo(other.value) == 0
        override fun hashCode(): Int = value.stripTrailingZeros().hashCode()
    }

    data class Bool(val value: Boolean) : GoldenValue()
    data object Null : GoldenValue()

    /** YAML 里不按普通类型解释的标量（yes / on、0x1BB、时间戳、自定义标签等），按标签与原文比较。 */
    data class Raw(val tag: String, val text: String) : GoldenValue()

    /** 被替换成占位符的整数（动态端口）。 */
    data class Ref(val name: String) : GoldenValue()

    companion object {
        fun str(text: String): Str = Str(if (text.isEmpty()) emptyList() else listOf(StrPart.Text(text)))
    }
}

sealed class StrPart {
    data class Text(val text: String) : StrPart()
    data class Ref(val name: String) : StrPart()
}

class GoldenParseException(message: String) : Exception(message)

enum class GoldenFormat {
    JSON,

    /** JSON，且顶层必须是对象。 */
    JSON_OBJECT,
    YAML,

    /** 先按 JSON，再按 YAML。 */
    JSON_OR_YAML,
}

object GoldenParser {

    fun parse(text: String, format: GoldenFormat): GoldenValue = when (format) {
        GoldenFormat.JSON -> parseJson(text)
        GoldenFormat.JSON_OBJECT -> parseJson(text).also {
            if (it !is GoldenValue.Obj) throw GoldenParseException("顶层不是 JSON 对象")
        }

        GoldenFormat.YAML -> parseYaml(text)
        GoldenFormat.JSON_OR_YAML -> try {
            parseJson(text)
        } catch (json: GoldenParseException) {
            try {
                parseYaml(text)
            } catch (yaml: GoldenParseException) {
                throw GoldenParseException("既不是 JSON（${json.message}），也不是 YAML（${yaml.message}）")
            }
        }
    }

    fun parseJson(text: String): GoldenValue {
        val reader = JsonReader(StringReader(text))
        reader.setStrictness(Strictness.STRICT)
        try {
            val value = readJson(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw GoldenParseException("JSON 文档末尾有多余内容")
            return value
        } catch (e: IOException) {
            throw GoldenParseException("JSON 解析失败：${e.message}")
        } catch (e: IllegalStateException) {
            throw GoldenParseException("JSON 解析失败：${e.message}")
        } catch (e: NumberFormatException) {
            throw GoldenParseException("JSON 数字无效：${e.message}")
        }
    }

    private fun readJson(reader: JsonReader): GoldenValue = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val fields = LinkedHashMap<String, GoldenValue>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in fields) throw GoldenParseException("重复的对象键 \"$name\"（${reader.path}）")
                fields[name] = readJson(reader)
            }
            reader.endObject()
            GoldenValue.Obj(fields)
        }

        JsonToken.BEGIN_ARRAY -> {
            val items = ArrayList<GoldenValue>()
            reader.beginArray()
            while (reader.hasNext()) items.add(readJson(reader))
            reader.endArray()
            GoldenValue.Arr(items)
        }

        JsonToken.STRING -> GoldenValue.str(reader.nextString())
        // 数字取原文判断写法：带小数点或指数的是小数，其余是整数
        JsonToken.NUMBER -> numberFromText(reader.nextString())
        JsonToken.BOOLEAN -> GoldenValue.Bool(reader.nextBoolean())
        JsonToken.NULL -> {
            reader.nextNull()
            GoldenValue.Null
        }

        else -> throw GoldenParseException("意外的 JSON 记号 ${reader.peek()}（${reader.path}）")
    }

    private fun numberFromText(text: String): GoldenValue =
        if (text.any { it == '.' || it == 'e' || it == 'E' }) {
            GoldenValue.Decimal(text, BigDecimal(text))
        } else {
            GoldenValue.Integer(BigInteger(text))
        }

    // YAML 只做组合（compose）不做构造：拿到每个标量的原文与解析出的标签，
    // 由这里决定类型，避免 1.1 规则把 yes / on、0x1BB 之类与 true、443 混为一谈
    fun parseYaml(text: String): GoldenValue {
        val node = try {
            Yaml().compose(StringReader(text))
        } catch (e: YAMLException) {
            throw GoldenParseException("YAML 解析失败：${e.message?.replace('\n', ' ')}")
        } ?: throw GoldenParseException("YAML 文档为空")
        return fromYaml(node, 0)
    }

    private val yamlInt = Regex("[-+]?(0|[1-9][0-9]*)")
    private val yamlDec = Regex("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?")
    private val yamlBool = mapOf("true" to true, "True" to true, "TRUE" to true, "false" to false, "False" to false, "FALSE" to false)
    private val yamlNull = setOf("", "~", "null", "Null", "NULL")

    private fun fromYaml(node: Node, depth: Int): GoldenValue {
        if (depth > 512) throw GoldenParseException("YAML 嵌套过深（可能有循环引用）")
        return when (node) {
            is MappingNode -> {
                val fields = LinkedHashMap<String, GoldenValue>()
                for (tuple in node.value) {
                    val key = yamlKey(tuple.keyNode)
                    if (key in fields) throw GoldenParseException("重复的对象键 \"$key\"（第 ${tuple.keyNode.startMark.line + 1} 行）")
                    fields[key] = fromYaml(tuple.valueNode, depth + 1)
                }
                GoldenValue.Obj(fields)
            }

            is SequenceNode -> GoldenValue.Arr(node.value.map { fromYaml(it, depth + 1) })
            is ScalarNode -> yamlScalar(node)
            else -> throw GoldenParseException("不支持的 YAML 节点 ${node.nodeId}")
        }
    }

    private fun yamlKey(node: Node): String {
        if (node !is ScalarNode) throw GoldenParseException("YAML 对象键不是标量（第 ${node.startMark.line + 1} 行）")
        // 非字符串键带上标签，免得 443 与 "443" 两个键被当成同一个
        return if (node.tag == Tag.STR) node.value else "${node.tag.value}:${node.value}"
    }

    private fun yamlScalar(node: ScalarNode): GoldenValue {
        val text = node.value
        val raw = GoldenValue.Raw(node.tag.value, text)
        return when (node.tag) {
            Tag.STR -> GoldenValue.str(text)
            Tag.INT -> if (yamlInt.matches(text)) GoldenValue.Integer(BigInteger(text.removePrefix("+"))) else raw
            Tag.FLOAT -> if (yamlDec.matches(text)) GoldenValue.Decimal(text, BigDecimal(text.removePrefix("+"))) else raw
            Tag.BOOL -> yamlBool[text]?.let { GoldenValue.Bool(it) } ?: raw
            Tag.NULL -> if (text in yamlNull) GoldenValue.Null else raw
            else -> raw
        }
    }
}

/** 一次构建里每次运行都可能不同的取值，来自 result.json 的 dynamic，或由黄金测试的调用方直接给出。 */
class GoldenDynamic(
    val ports: List<Long> = emptyList(),
    val paths: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
) {
    companion object {
        val NONE = GoldenDynamic()
    }
}

/**
 * 按遍历次序把动态值换成占位符：第一次遇到的端口记为 PORT#1，下一个没见过的记为 PORT#2，路径、secret 同理。
 * 同一模式的所有文档共用一个实例，按约定的文档次序依次调用 [substitute]。
 */
class GoldenPlaceholders(private val dynamic: GoldenDynamic) {

    private enum class Kind(val label: String, val field: String) {
        PORT("PORT", "ports"), PATH("PATH", "paths"), SECRET("SECRET", "secrets")
    }

    private val ports: Set<BigInteger> = dynamic.ports.map { BigInteger.valueOf(it) }.toSet()
    private val portTexts: Set<String> = dynamic.ports.map { it.toString() }.toSet()

    // 路径与 secret 按子串匹配，同一位置取最长的
    private val literals: List<Pair<String, Kind>> =
        (dynamic.paths.map { it to Kind.PATH } + dynamic.secrets.map { it to Kind.SECRET })
            .filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.length }

    private val numbers = HashMap<Pair<Kind, String>, Int>()
    private val counters = HashMap<Kind, Int>()

    private fun name(kind: Kind, value: String): String {
        val n = numbers.getOrPut(kind to value) {
            val next = (counters[kind] ?: 0) + 1
            counters[kind] = next
            next
        }
        return "${kind.label}#$n"
    }

    fun substitute(value: GoldenValue): GoldenValue = when (value) {
        is GoldenValue.Obj -> {
            val fields = LinkedHashMap<String, GoldenValue>()
            for (key in value.fields.keys.sorted()) {
                val newKey = substituteKey(key)
                if (newKey in fields) throw GoldenParseException("替换占位符后对象键 \"$newKey\" 重复")
                fields[newKey] = substitute(value.fields.getValue(key))
            }
            GoldenValue.Obj(fields)
        }

        is GoldenValue.Arr -> GoldenValue.Arr(value.items.map { substitute(it) })
        is GoldenValue.Str -> GoldenValue.Str(substituteParts(value.parts))
        is GoldenValue.Integer -> if (value.value in ports) GoldenValue.Ref(name(Kind.PORT, value.value.toString())) else value
        else -> value
    }

    // 对象键里出现动态值时用 {PORT#1} 的形式写回键名；键名只作展示与配对，不再拆片
    private fun substituteKey(key: String): String =
        substituteParts(listOf(StrPart.Text(key))).joinToString("") {
            when (it) {
                is StrPart.Text -> it.text
                is StrPart.Ref -> "{${it.name}}"
            }
        }

    private fun substituteParts(parts: List<StrPart>): List<StrPart> {
        val out = ArrayList<StrPart>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(StrPart.Text(buf.toString()))
                buf.clear()
            }
        }
        for (part in parts) {
            if (part is StrPart.Ref) {
                flush()
                out.add(part)
                continue
            }
            val s = (part as StrPart.Text).text
            var i = 0
            while (i < s.length) {
                val literal = literals.firstOrNull { s.startsWith(it.first, i) }
                if (literal != null) {
                    flush()
                    out.add(StrPart.Ref(name(literal.second, literal.first)))
                    i += literal.first.length
                    continue
                }
                // 端口只匹配完整的数字串：前后都不是数字
                if (s[i].isAsciiDigit() && (i == 0 || !s[i - 1].isAsciiDigit())) {
                    var j = i
                    while (j < s.length && s[j].isAsciiDigit()) j++
                    val digits = s.substring(i, j)
                    if (digits in portTexts) {
                        flush()
                        out.add(StrPart.Ref(name(Kind.PORT, digits)))
                        i = j
                        continue
                    }
                }
                buf.append(s[i])
                i++
            }
        }
        flush()
        return out
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** dynamic 里列了、但到目前为止没在任何文档里出现的值。 */
    fun unused(): List<String> {
        val out = ArrayList<String>()
        for (port in dynamic.ports.distinct()) {
            if ((Kind.PORT to port.toString()) !in numbers) out.add("dynamic.ports 里的 $port")
        }
        for (path in dynamic.paths.distinct()) {
            if (path.isEmpty()) out.add("dynamic.paths 里有空字符串，已忽略")
            else if ((Kind.PATH to path) !in numbers && (Kind.SECRET to path) !in numbers) out.add("dynamic.paths 里的 $path")
        }
        for (secret in dynamic.secrets.distinct()) {
            if (secret.isEmpty()) out.add("dynamic.secrets 里有空字符串，已忽略")
            else if ((Kind.SECRET to secret) !in numbers && (Kind.PATH to secret) !in numbers) out.add("dynamic.secrets 里的 $secret")
        }
        return out
    }
}

/** 一处差异：file 为相对路径（或文档名），path 为文档内路径，如 $.outbounds[2].server_port；整份文档的问题 path 为空。 */
data class GoldenDiff(val file: String, val path: String, val message: String) {
    override fun toString() = if (path.isEmpty()) "$file：$message" else "$file $path：$message"
}

data class GoldenCompareResult(val diffs: List<GoldenDiff>, val warnings: List<String>) {
    val same get() = diffs.isEmpty()
}

/** 待比较的一份文档：原文加格式，或已解析好的值（如替换过 dynamic 的 result.json）。 */
class GoldenDocument private constructor(
    val name: String,
    private val text: String?,
    private val format: GoldenFormat?,
    private val value: GoldenValue?,
    private val error: String?,
) {
    constructor(name: String, text: String, format: GoldenFormat) : this(name, text, format, null, null)

    fun parsed(): GoldenValue {
        error?.let { throw GoldenParseException(it) }
        return value ?: GoldenParser.parse(text!!, format!!)
    }

    companion object {
        fun ofValue(name: String, value: GoldenValue) = GoldenDocument(name, null, null, value, null)
        fun ofError(name: String, error: String) = GoldenDocument(name, null, null, null, error)
    }
}

object GoldenCompare {

    /** 比较两段文档原文；两侧动态值各自给出，各自从 1 开始编号。 */
    fun compareDocuments(
        expected: String,
        actual: String,
        format: GoldenFormat,
        expectedDynamic: GoldenDynamic = GoldenDynamic.NONE,
        actualDynamic: GoldenDynamic = GoldenDynamic.NONE,
        name: String = "document",
    ): GoldenCompareResult = compareDocumentSets(
        listOf(GoldenDocument(name, expected, format)), expectedDynamic,
        listOf(GoldenDocument(name, actual, format)), actualDynamic,
    )

    /**
     * 比较两组文档（一个场景的一个模式）。每侧按列表次序遍历编号，所以列表须按约定次序给出：
     * sing-box 配置、各外核配置、最后是 result.json。文档按名字配对，只在一侧出现的算差异。
     * reportUnused 为 false 时不对没出现过的动态值给警告（只比一部分文档时，其余动态值本来就不会出现）。
     */
    fun compareDocumentSets(
        expected: List<GoldenDocument>,
        expectedDynamic: GoldenDynamic,
        actual: List<GoldenDocument>,
        actualDynamic: GoldenDynamic,
        prefix: String = "",
        reportUnused: Boolean = true,
    ): GoldenCompareResult {
        val diffs = ArrayList<GoldenDiff>()
        val warnings = ArrayList<String>()
        val label = prefix.ifEmpty { "文档" }
        val e = substituteAll(expected, expectedDynamic, "预期", prefix, diffs, warnings.takeIf { reportUnused }, label)
        val a = substituteAll(actual, actualDynamic, "实际", prefix, diffs, warnings.takeIf { reportUnused }, label)
        val names = (expected.map { it.name } + actual.map { it.name }).distinct()
        for (name in names) {
            val file = prefix + name
            val inE = expected.any { it.name == name }
            val inA = actual.any { it.name == name }
            when {
                !inA -> diffs.add(GoldenDiff(file, "", "只在预期一侧存在"))
                !inE -> diffs.add(GoldenDiff(file, "", "只在实际一侧存在"))
                else -> {
                    val ev = e[name]
                    val av = a[name]
                    // 解析失败已记为差异；两侧都解析成功才逐项比较
                    if (ev != null && av != null) diffValues(file, "$", ev, av, diffs)
                }
            }
        }
        return GoldenCompareResult(diffs, warnings)
    }

    private fun substituteAll(
        docs: List<GoldenDocument>,
        dynamic: GoldenDynamic,
        side: String,
        prefix: String,
        diffs: MutableList<GoldenDiff>,
        warnings: MutableList<String>?,
        label: String,
    ): Map<String, GoldenValue> {
        val placeholders = GoldenPlaceholders(dynamic)
        val out = LinkedHashMap<String, GoldenValue>()
        for (doc in docs) {
            if (doc.name in out) {
                diffs.add(GoldenDiff(prefix + doc.name, "", "${side}一侧有重名文档"))
                continue
            }
            try {
                out[doc.name] = placeholders.substitute(doc.parsed())
            } catch (e: GoldenParseException) {
                diffs.add(GoldenDiff(prefix + doc.name, "", "${side}一侧解析失败：${e.message}"))
            }
        }
        warnings?.let { list -> placeholders.unused().forEach { list.add("$label [$side]：$it 没有在任何文档里出现") } }
        return out
    }

    fun diffValues(file: String, path: String, expected: GoldenValue, actual: GoldenValue, out: MutableList<GoldenDiff>) {
        when {
            expected is GoldenValue.Obj && actual is GoldenValue.Obj -> {
                for (key in (expected.fields.keys + actual.fields.keys).sorted()) {
                    val ev = expected.fields[key]
                    val av = actual.fields[key]
                    val sub = path + pathKey(key)
                    when {
                        av == null -> out.add(GoldenDiff(file, sub, "预期 ${render(ev!!)}，实际缺失"))
                        ev == null -> out.add(GoldenDiff(file, sub, "预期缺失，实际 ${render(av)}"))
                        else -> diffValues(file, sub, ev, av, out)
                    }
                }
            }

            expected is GoldenValue.Arr && actual is GoldenValue.Arr -> {
                val e = expected.items
                val a = actual.items
                if (e.size != a.size) out.add(GoldenDiff(file, path, "数组长度不同：预期 ${e.size}，实际 ${a.size}"))
                for (i in 0 until maxOf(e.size, a.size)) {
                    val sub = "$path[$i]"
                    when {
                        i >= a.size -> out.add(GoldenDiff(file, sub, "预期 ${render(e[i])}，实际缺失"))
                        i >= e.size -> out.add(GoldenDiff(file, sub, "预期缺失，实际 ${render(a[i])}"))
                        else -> diffValues(file, sub, e[i], a[i], out)
                    }
                }
            }

            expected != actual -> out.add(GoldenDiff(file, path, "预期 ${render(expected)}，实际 ${render(actual)}"))
        }
    }

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    private fun pathKey(key: String) = if (identifier.matches(key)) ".$key" else "[${quote(key)}]"

    private const val RENDER_LIMIT = 200

    /** 把值写成紧凑的 JSON 风格文本，超长截断；占位符整数写作 PORT#1，字符串里的占位符写作 {PORT#1}。 */
    fun render(value: GoldenValue): String {
        val sb = StringBuilder()
        renderTo(value, sb)
        return if (sb.length > RENDER_LIMIT) sb.substring(0, RENDER_LIMIT) + "…" else sb.toString()
    }

    private fun renderTo(value: GoldenValue, sb: StringBuilder) {
        if (sb.length > RENDER_LIMIT) return
        when (value) {
            is GoldenValue.Obj -> {
                sb.append('{')
                value.fields.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    sb.append(quote(k)).append(':')
                    renderTo(v, sb)
                }
                sb.append('}')
            }

            is GoldenValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    renderTo(v, sb)
                }
                sb.append(']')
            }

            is GoldenValue.Str -> sb.append(quote(value.parts.joinToString("") {
                when (it) {
                    is StrPart.Text -> it.text
                    is StrPart.Ref -> "{${it.name}}"
                }
            }))

            is GoldenValue.Integer -> sb.append(value.value)
            is GoldenValue.Decimal -> sb.append(value.text)
            is GoldenValue.Bool -> sb.append(value.value)
            GoldenValue.Null -> sb.append("null")
            is GoldenValue.Raw -> sb.append("!<${value.tag}> ").append(value.text)
            is GoldenValue.Ref -> sb.append(value.name)
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
