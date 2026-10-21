package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.nekohasekai.sagernet.fmt.SHOWABLE_VALUE
import java.io.StringReader
import java.util.Locale

// XHTTP 的 extra 与 mode 校验。纯函数：不读 DataStore、不碰 Android，导入、编辑器保存与生成器共用。
//
// extra 来自远端订阅或分享链接，里面的内容会原样进入 Xray 的 xhttpSettings，所以不能照单全收：
// 按 Xray v26.10.17 SplitHTTPConfig（infra/conf/transport_method.go）的客户端键做白名单，
// 解析后用规范键名与校验过类型的值重建一个新对象，输入里没通过白名单的任何内容都到不了生成器。
//  - 键名按 ASCII 大小写不敏感匹配（Go 的 encoding/json 大小写不敏感，还会把 Unicode 里的「近似字符」折叠成 ASCII，
//    如 U+212A 开尔文符号等于 k），所以含非 ASCII 的键一律当未知键拒绝；同一个键的两种大小写写法同时出现算重复；
//  - 拒绝 downloadSettings（它是独立的拨号目标，会绕过 ExternalDialTarget.Mapped 的映射）、嵌套的 extra，
//    以及 host / path / mode（Xray 里 extra 存在时只保留外层的这三项，extra 里写了也不会生效，写进来只会造成误解）；
//  - 只有 headers 与 xmux 是对象；其余键各有自己的取值类型，类型不对即拒绝；
//  - 数值设上限（见下面的表），免得一个节点耗尽连接数或内存。
// 违规抛 InvalidXhttpExtraException。消息只带通过 SHOWABLE_VALUE 的键名，其余写 (key not shown)；从不带值。
// 键名顺序按白名单表的顺序输出，同一内容的不同写法得到同一个字符串。

// extra 文本的长度上限（字符数）。正常配置只有几百个字符，留足 headers 的余量
const val XHTTP_EXTRA_MAX_LENGTH = 16 * 1024

// Xray 认的 mode；空值同义于 auto
val XHTTP_MODES = listOf("auto", "packet-up", "stream-up", "stream-one")

const val XHTTP_DEFAULT_MODE = "auto"

// XHTTP 节点的校验不通过。继承 UnsupportedTransportException：批量导入、订阅更新与格式回退链已按它处理，
// 一条被拒只记原因、不拖垮其余条目。what 是被校验的部分（extra / mode），path 是违规处的键名路径（不含值）
class InvalidXhttpExtraException(
    val what: String,
    val path: List<String>,
    val reason: String,
) : UnsupportedTransportException(
    "xhttp",
    "xhttp $what rejected" + (if (path.isEmpty()) "" else " at " + path.joinToString(".") { shownKey(it) }) + ": $reason",
)

// 路径里的键名：形态合格才回显
private fun shownKey(key: String): String = if (SHOWABLE_VALUE.matches(key)) key else "(key not shown)"

// mode：空值是 auto，大小写不敏感，返回规范小写名；不认识的值抛 InvalidXhttpExtraException（值不进消息）
fun requireXhttpMode(value: String?): String {
    if (value.isNullOrBlank()) return XHTTP_DEFAULT_MODE
    val name = value.trim().lowercase(Locale.ROOT)
    if (name !in XHTTP_MODES) throw InvalidXhttpExtraException("mode", emptyList(), "unsupported mode")
    return name
}

// extra → 规范的紧凑 JSON；空值与空对象返回 ""。违规抛 InvalidXhttpExtraException
fun canonicalXhttpExtra(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    if (raw.length > XHTTP_EXTRA_MAX_LENGTH) throw reject(emptyList(), "extra is too long")
    val root = try {
        parseExtra(raw)
    } catch (e: InvalidXhttpExtraException) {
        throw e
    } catch (e: Exception) {
        // 解析器的消息带行列与路径（键名），不转述
        throw reject(emptyList(), "not a valid JSON object")
    }
    if (root.size() == 0) return ""
    return GsonBuilder().disableHtmlEscaping().create().toJson(root)
}

private fun reject(path: List<String>, reason: String) = InvalidXhttpExtraException("extra", path, reason)

// ---- 白名单

private enum class Kind { TEXT, ENUM, BOOL, RANGE, INT, HEADERS, XMUX }

private class Field(
    val name: String,
    val kind: Kind,
    // RANGE / INT：取值上限（含）；TEXT：长度上限
    val max: Long = 0,
    // INT 的下限
    val min: Long = 0,
    // ENUM 的合法取值（Xray 区分大小写）；空串另算合法（Xray 里表示用默认）
    val values: Set<String> = emptySet(),
)

// 键名字符：可见 ASCII，不含空白与控制字符；用作 query / cookie / header 的名字
private val KEY_TEXT = Regex("^[\\x21-\\x7E]*$")

// 会话表：可见 ASCII 与空格（Xray 要求每个字符都是 ASCII），或预置表的名字
private val TABLE_TEXT = Regex("^[\\x20-\\x7E]*$")

// HTTP 方法：Xray 会转成大写
private val METHOD_TEXT = Regex("^[A-Za-z]*$")

private val PLACEMENTS = setOf("path", "cookie", "header", "query")

// 数值上限。依据是「正常用法的数量级再留余量」，不是 Xray 自己的限制（它基本不设上限）：
//  - 填充字节数 / 会话 ID 长度：65536 / 256；
//  - 单次上传大小（scMaxEachPostBytes、uplinkChunkSize）：16 MiB，Xray 默认 1 MB；
//  - POST 间隔 60000 ms，服务端保活秒数 3600；缓冲的 POST 数 4096（默认 30）；服务端头部上限 1 MiB；
//  - xmux：并发 1024、连接数 256（每条都是一个真实的出站连接）、连接复用次数与请求数 100 万、复用时长 86400 秒、
//    保活周期 -1（关闭）到 3600 秒。
private const val MIB = 1024L * 1024L

private val XMUX_FIELDS = listOf(
    Field("maxConcurrency", Kind.RANGE, max = 1024),
    Field("maxConnections", Kind.RANGE, max = 256),
    Field("cMaxReuseTimes", Kind.RANGE, max = 1_000_000),
    Field("hMaxRequestTimes", Kind.RANGE, max = 1_000_000),
    Field("hMaxReusableSecs", Kind.RANGE, max = 86_400),
    Field("hKeepAlivePeriod", Kind.INT, max = 3600, min = -1),
)

// 顺序即输出顺序。键名对照 Xray v26.10.17 SplitHTTPConfig 的 json 标签（host / path / mode / downloadSettings / extra 不在其中）
private val EXTRA_FIELDS = listOf(
    Field("headers", Kind.HEADERS),
    Field("xPaddingBytes", Kind.RANGE, max = 65_536),
    Field("xPaddingObfsMode", Kind.BOOL),
    Field("xPaddingKey", Kind.TEXT, max = 128),
    Field("xPaddingHeader", Kind.TEXT, max = 128),
    Field("xPaddingPlacement", Kind.ENUM, values = setOf("cookie", "header", "query", "queryInHeader")),
    Field("xPaddingMethod", Kind.ENUM, values = setOf("repeat-x", "tokenish")),
    Field("uplinkHTTPMethod", Kind.TEXT, max = 16),
    Field("sessionIDPlacement", Kind.ENUM, values = PLACEMENTS),
    Field("sessionIDKey", Kind.TEXT, max = 128),
    Field("sessionIDTable", Kind.TEXT, max = 256),
    Field("sessionIDLength", Kind.RANGE, max = 256),
    Field("seqPlacement", Kind.ENUM, values = PLACEMENTS),
    Field("seqKey", Kind.TEXT, max = 128),
    Field("uplinkDataPlacement", Kind.ENUM, values = setOf("auto", "body", "cookie", "header")),
    Field("uplinkDataKey", Kind.TEXT, max = 128),
    Field("uplinkChunkSize", Kind.RANGE, max = 16 * MIB),
    Field("noGRPCHeader", Kind.BOOL),
    Field("noSSEHeader", Kind.BOOL),
    Field("scMaxEachPostBytes", Kind.RANGE, max = 16 * MIB),
    Field("scMinPostsIntervalMs", Kind.RANGE, max = 60_000),
    Field("scMaxBufferedPosts", Kind.INT, max = 4096),
    Field("scStreamUpServerSecs", Kind.RANGE, max = 3600),
    Field("serverMaxHeaderBytes", Kind.INT, max = MIB),
    Field("xmux", Kind.XMUX),
)

// 小写键名 → 字段
private val EXTRA_BY_LOWER = EXTRA_FIELDS.associateBy { it.name.lowercase(Locale.ROOT) }
private val XMUX_BY_LOWER = XMUX_FIELDS.associateBy { it.name.lowercase(Locale.ROOT) }

// extra 里不许出现的键：给出明确原因，免得被当成「不认识的键」
private val FORBIDDEN = mapOf(
    "downloadsettings" to "downloadSettings is not allowed",
    "extra" to "nested extra is not allowed",
    "host" to "host must not be set in extra",
    "path" to "path must not be set in extra",
    "mode" to "mode must not be set in extra",
)

// headers 的限制：条数、名字（RFC 9110 token）、值的长度
private const val HEADERS_MAX = 32
private const val HEADER_VALUE_MAX = 4096
private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")

// ---- 解析

private fun parseExtra(raw: String): JsonObject {
    val reader = JsonReader(StringReader(raw))
    reader.strictness = Strictness.STRICT
    if (reader.peek() != JsonToken.BEGIN_OBJECT) throw reject(emptyList(), "not a valid JSON object")
    val result = readFields(reader, emptyList(), EXTRA_BY_LOWER, topLevel = true)
    // 对象之后不许再有内容
    if (reader.peek() != JsonToken.END_DOCUMENT) throw reject(emptyList(), "not a valid JSON object")
    return result
}

// 读一个对象，按 table 校验每个键；遇到第一处违规就抛，不再读后面的内容（未知键的值可能很深）
private fun readFields(
    reader: JsonReader,
    parent: List<String>,
    table: Map<String, Field>,
    topLevel: Boolean = false,
): JsonObject {
    val found = HashMap<String, JsonElement>()
    reader.beginObject()
    while (reader.hasNext()) {
        val key = reader.nextName()
        val path = parent + key
        val ascii = key.all { it.code in 0x21..0x7E }
        val lower = key.lowercase(Locale.ROOT)
        if (!ascii) throw reject(path, "unknown key")
        if (topLevel) FORBIDDEN[lower]?.let { throw reject(path, it) }
        val field = table[lower] ?: throw reject(path, "unknown key")
        if (lower in found) throw reject(path, "duplicate key")
        found[lower] = readValue(reader, path, field)
    }
    reader.endObject()
    val out = JsonObject()
    // 按白名单表的顺序输出，规范键名
    for (field in table.values) found[field.name.lowercase(Locale.ROOT)]?.let { out.add(field.name, it) }
    return out
}

private fun readValue(reader: JsonReader, path: List<String>, field: Field): JsonElement = when (field.kind) {
    Kind.TEXT -> {
        val text = readString(reader, path)
        if (text.length > field.max) throw reject(path, "value is too long")
        val charset = when (field.name) {
            "sessionIDTable" -> TABLE_TEXT
            "uplinkHTTPMethod" -> METHOD_TEXT
            else -> KEY_TEXT
        }
        if (!charset.matches(text)) throw reject(path, "value has unsupported characters")
        JsonPrimitive(text)
    }

    Kind.ENUM -> {
        val text = readString(reader, path)
        if (text.isNotEmpty() && text !in field.values) throw reject(path, "unsupported value")
        JsonPrimitive(text)
    }

    Kind.BOOL -> {
        if (reader.peek() != JsonToken.BOOLEAN) throw reject(path, "must be a boolean")
        JsonPrimitive(reader.nextBoolean())
    }

    Kind.RANGE -> readRange(reader, path, field.max)

    Kind.INT -> {
        val value = readInteger(reader, path) ?: throw reject(path, "must be an integer")
        if (value < field.min || value > field.max) throw reject(path, "value is out of range")
        JsonPrimitive(value)
    }

    Kind.HEADERS -> readHeaders(reader, path)

    Kind.XMUX -> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw reject(path, "must be an object")
        val xmux = readFields(reader, path, XMUX_BY_LOWER)
        // Xray 在构建配置时拒绝两者同时指定，提前在导入时报
        val connections = xmux.get("maxConnections")?.let { rangeUpper(it) } ?: 0
        val concurrency = xmux.get("maxConcurrency")?.let { rangeUpper(it) } ?: 0
        if (connections > 0 && concurrency > 0) {
            throw reject(path, "maxConnections and maxConcurrency can not both be set")
        }
        xmux
    }
}

private fun readString(reader: JsonReader, path: List<String>): String {
    if (reader.peek() != JsonToken.STRING) throw reject(path, "must be a string")
    return reader.nextString()
}

// JSON 整数字面量（不接受小数与指数写法：Xray 的 int32 / int64 解码也拒绝）；不是整数返回 null
private fun readInteger(reader: JsonReader, path: List<String>): Long? {
    if (reader.peek() != JsonToken.NUMBER) return null
    val literal = reader.nextString()
    if (!INTEGER.matches(literal) || literal.length > 12) throw reject(path, "must be an integer")
    return literal.toLong()
}

private val INTEGER = Regex("^-?[0-9]+$")
private val RANGE_TEXT = Regex("^([0-9]{1,12})(?:-([0-9]{1,12}))?$")

// Xray 的 Int32Range：整数，或 "from-to" 字符串（空串是 0）。只接受非负；from > to 时 Xray 会对调，规范化时同样对调。
// 输出：两端相等写整数，否则写 "from-to"
private fun readRange(reader: JsonReader, path: List<String>, max: Long): JsonElement {
    val (from, to) = when (reader.peek()) {
        JsonToken.NUMBER -> {
            val value = readInteger(reader, path) ?: throw reject(path, "must be an integer")
            value to value
        }

        JsonToken.STRING -> {
            val text = reader.nextString()
            if (text.isEmpty()) {
                0L to 0L
            } else {
                val m = RANGE_TEXT.matchEntire(text) ?: throw reject(path, "must be an integer or a from-to range")
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: a
                a to b
            }
        }

        else -> throw reject(path, "must be an integer or a from-to range")
    }
    if (from < 0 || to < 0) throw reject(path, "value is out of range")
    val low = minOf(from, to)
    val high = maxOf(from, to)
    if (high > max) throw reject(path, "value is out of range")
    return if (low == high) JsonPrimitive(low) else JsonPrimitive("$low-$high")
}

// 规范化之后的范围的上端
private fun rangeUpper(element: JsonElement): Long {
    val p = element.asJsonPrimitive
    return if (p.isNumber) p.asLong else p.asString.substringAfter('-').toLong()
}

// headers：字符串到字符串的对象，名字是 token、不含 Host（大小写不敏感，Xray 同样拒绝）、同名只许一个。
// 值可能带凭据（如 Authorization）：这里只校验形态；日志里的遮蔽由写出配置的一侧负责
private fun readHeaders(reader: JsonReader, path: List<String>): JsonElement {
    if (reader.peek() != JsonToken.BEGIN_OBJECT) throw reject(path, "must be an object")
    val out = JsonObject()
    val seen = HashSet<String>()
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        val here = path + name
        if (out.size() >= HEADERS_MAX) throw reject(path, "too many headers")
        if (!HEADER_NAME.matches(name)) throw reject(here, "invalid header name")
        val lower = name.lowercase(Locale.ROOT)
        if (lower == "host") throw reject(here, "headers must not contain host")
        if (!seen.add(lower)) throw reject(here, "duplicate header")
        val value = readString(reader, here)
        if (value.length > HEADER_VALUE_MAX) throw reject(here, "value is too long")
        if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7F }) {
            throw reject(here, "value has unsupported characters")
        }
        out.add(name, JsonPrimitive(value))
    }
    reader.endObject()
    return out
}
