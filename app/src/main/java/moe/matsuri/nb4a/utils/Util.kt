package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import libcore.StringBox
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

object Util {

    // Universal links are intended to carry one profile or one portable group,
    // not arbitrary archives. Bound inflation before a small link can exhaust the heap.
    private const val MAX_UNIVERSAL_PAYLOAD_SIZE = 8 * 1024 * 1024

    // Base64 for all

    fun b64EncodeUrlSafe(s: String): String {
        return b64EncodeUrlSafe(s.toByteArray())
    }

    fun b64EncodeUrlSafe(b: ByteArray): String {
        return String(Base64.encode(b, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE))
    }

    fun b64Decode(b: String): ByteArray {
        // padding 自动处理，不用理
        // URLSafe 需要替换这两个，不要用 URL_SAFE 否则处理非 Safe 的时候会乱码
        val str = b.replace("-", "+").replace("_", "/")

        // android.util.Base64 解码只看 URL_SAFE 一个标志，单行 / 多行都由 DEFAULT 处理
        try {
            return Base64.decode(str, Base64.DEFAULT)
        } catch (_: Exception) {
        }

        throw IllegalStateException("Cannot decode base64")
    }

    fun zlibCompress(input: ByteArray, level: Int): ByteArray {
        // Compress the bytes
        // 1 to 4 bytes/char for UTF-8
        val output = ByteArray(input.size * 4)
        val compressor = Deflater(level).apply {
            setInput(input)
            finish()
        }
        // end() in a finally: Deflater holds a native buffer that only the
        // finalizer would reclaim if deflate() threw
        try {
            val compressedDataLength: Int = compressor.deflate(output)
            return output.copyOfRange(0, compressedDataLength)
        } finally {
            compressor.end()
        }
    }

    fun zlibDecompress(input: ByteArray): ByteArray {
        val inflater = Inflater()
        val outputStream = ByteArrayOutputStream()

        // end() in a finally: a corrupt link throws below, and the native
        // buffer would otherwise leak until the finalizer runs
        try {
            return outputStream.use {
                val buffer = ByteArray(1024)

                inflater.setInput(input)

                // 0 means no progress possible (truncated or corrupt input);
                // don't silently return partial data
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) throw DataFormatException("invalid or truncated zlib data")
                    if (outputStream.size() > MAX_UNIVERSAL_PAYLOAD_SIZE - count) {
                        throw DataFormatException(
                            "decompressed data exceeds $MAX_UNIVERSAL_PAYLOAD_SIZE bytes"
                        )
                    }
                    outputStream.write(buffer, 0, count)
                }

                outputStream.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    fun map2StringMap(m: Map<*, *>): MutableMap<String, Any?> {
        val o = mutableMapOf<String, Any?>()
        m.forEach {
            if (it.key is String) {
                // value stays nullable: a JSON null must pass through so
                // mergeMap can overwrite with it instead of crashing
                o[it.key as String] = it.value
            }
        }
        return o
    }

    fun mergeMap(dst: MutableMap<String, Any?>, src: Map<String, Any?>): MutableMap<String, Any?> {
        src.forEach { (k, v) ->
            if (v is Map<*, *> && dst[k] is Map<*, *>) {
                val currentMap = (dst[k] as Map<*, *>).toMutableMap()
                dst[k] = mergeMap(map2StringMap(currentMap), map2StringMap(v))
            } else if (v is List<*>) {
                if (k.startsWith("+")) {  // prepend
                    val dstKey = k.removePrefix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (v + currentList).toMutableList()
                    dst[dstKey] = currentList
                } else if (k.endsWith("+")) {  // append
                    val dstKey = k.removeSuffix("+")
                    var currentList = (dst[dstKey] as? List<*>)?.toMutableList() ?: mutableListOf()
                    currentList = (currentList + v).toMutableList()
                    dst[dstKey] = currentList
                } else {
                    dst[k] = v
                }
            } else {
                dst[k] = v
            }
        }
        return dst
    }

    fun mergeJSON(dst: MutableMap<String, Any?>, j: String) {
        if (j.isBlank()) return
        val element = JavaUtil.gson.fromJson(j, JsonElement::class.java)
        // "null" parses to JsonNull and a scalar/array to a non-object;
        // both must not reach mergeMap (NPE / opaque JsonSyntaxException)
        if (element !is JsonObject) {
            throw IllegalArgumentException("custom JSON must be an object")
        }
        val src = JavaUtil.gson.fromJson(element, dst.javaClass)
        mergeMap(dst, src)
    }

    // Format Time

    @SuppressLint("SimpleDateFormat")
    val sdf1 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun timeStamp2Text(t: Long): String {
        return sdf1.format(Date(t))
    }

    @SuppressLint("WrongConstant")
    fun collapseStatusBar(context: Context) {
        try {
            val statusBarManager = context.getSystemService("statusbar")
            val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
            collapse.invoke(statusBarManager)
        } catch (_: Exception) {
        }
    }

    fun getStringBox(b: StringBox?): String {
        if (b != null && b.value != null) {
            return b.value
        }
        return ""
    }

    fun decodeFilename(headerValue: String): String {
        // 只取到下一个参数分隔符为止；贪婪匹配会把后续参数一并吞掉
        val regex = Regex("filename\\*=[^']*''([^;]+)")
        val match = regex.find(headerValue)
        val encoded = match?.groupValues?.get(1) ?: ""
        // 畸形 % 序列会让 decode 抛 IllegalArgumentException，
        // 拿不到文件名不能拖垮整个订阅更新
        return try {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    // 值是凭据的键：JSON 里用下划线，YAML（如 mihomo 配置）里同名换连字符。
    // obfs 是 hysteria1 的混淆密码（hysteria2 的 obfs 是对象，其中 password 已覆盖）。
    // username / user / pass：socks / http 入站与出站的用户名和密码（Xray 的 accounts 用 user / pass）
    private val SENSITIVE_KEYS = listOf(
        "password", "uuid", "id", "private_key", "pre_shared_key", "auth", "auth_str",
        "token", "secret", "key", "authorization", "cookie", "obfs",
        "username", "user", "pass",
    )

    // 一个 JSON 字符串字面量（结尾的 ["] 就是引号：raw string 不能以 " 结尾）
    private const val JSON_STRING = """"(?:\\.|[^"\\])*["]"""

    // JSON "key": "value"；值也可以是字符串数组（trojan-go 的 "password": [...]），
    // 整个数组遮蔽，第 2 组非空即数组
    private val SENSITIVE_JSON_VALUE = Regex(
        """("(?:${SENSITIVE_KEYS.joinToString("|")})"\s*:\s*)(?:$JSON_STRING|(\[\s*$JSON_STRING(?:\s*,\s*$JSON_STRING)*\s*\]))""",
        RegexOption.IGNORE_CASE
    )

    // YAML 里敏感键的位置：行首（可带块序列项的「- 」），或行内映射 {k: v, …} 里 { 与 , 之后。
    // 正则只负责找到键，值的范围由 yamlValueEnd 按 YAML 写法逐字符确定。
    // 第 1 组非空即行首键（组内是缩进与「- 」）
    private val SENSITIVE_YAML_KEY = Regex(
        """(?:^([ \t]*(?:-[ \t]+)*)|[\{,][ \t]*)(?:${SENSITIVE_KEYS.joinToString("|") { it.replace('_', '-') }})[ \t]*:(?=[ \t]|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    // 行内映射里普通标量的结束符（, } ]）之后应有的样子：行尾，或「, 下一个键:」。
    // 不符合时说明值本身含这些字符（块写法的普通标量可以带逗号），改为遮到行尾
    private val FLOW_VALUE_TERMINATOR = Regex(
        """[\}\]]*+[ \t]*+(?:$|,[ \t]*+(?:$|[^\s,:\{\}\[\]'"][^,:\{\}\[\]\r\n]*+:(?=[ \t]|$)|'[^'\r\n]*+':|"[^"\r\n]*+":))""",
        RegexOption.MULTILINE
    )

    // scheme://user:password@host embedded in string values (e.g. naive "proxy" URL)
    private val URL_USERINFO_PASSWORD = Regex("""(://[^"\s:@/]+):[^"\s@/]*@""")

    // scheme://password@host where the whole userinfo is the credential
    // (trojan/vless style, no colon); runs after URL_USERINFO_PASSWORD, whose
    // replacement contains a colon and therefore cannot be re-matched here
    private val URL_USERINFO_BARE = Regex("""(://)[^"\s:@/]+@""")

    private val URL_QUERY_SECRET = Regex(
        """([?&](?:password|token|secret|auth|key|access_token)=)[^&#\s"']*""",
        RegexOption.IGNORE_CASE
    )

    // keep credentials out of the exportable log / crash report
    fun redactSecrets(text: String): String {
        var result = SENSITIVE_JSON_VALUE.replace(text) {
            it.groupValues[1] + if (it.groupValues[2].isEmpty()) "\"***\"" else "[\"***\"]"
        }
        result = redactYamlValues(result)
        result = URL_USERINFO_PASSWORD.replace(result) { it.groupValues[1] + ":***@" }
        result = URL_USERINFO_BARE.replace(result) { it.groupValues[1] + "***@" }
        result = URL_QUERY_SECRET.replace(result) { it.groupValues[1] + "***" }
        return result
    }

    // 把 YAML 里敏感键的值换成 ***，同一映射里的其它键值不动。
    // 值可以跨行（snakeyaml 把长标量在空格处折到下一行），但只沿着缩进比参照列更深的续行延伸：
    // 碰到同级或更浅的行、或以制表符开头的行（YAML 缩进只用空格）就停，
    // 所以普通日志里偶然出现的「, password: …」最多影响它所在的那一行。
    // 整体只扫一遍，每个值只往后看到它的结尾，几 MB 的日志也是线性耗时
    private fun redactYamlValues(text: String): String {
        var m = SENSITIVE_YAML_KEY.find(text) ?: return text
        val out = StringBuilder(text.length)
        var copied = 0

        // 当前行的行首，以及它之前最近一个缩进更浅的非空行的缩进（没有则为 0）。
        // 行内映射的各行（含折行的续行）都比 { 所在行缩进更深，后者就是这个「上一级」。
        // 用单调栈随扫描推进维护，整体线性
        var lineStart = 0
        var parentIndent = 0
        val indents = ArrayList<Int>()
        fun enterLine(start: Int) {
            lineStart = start
            val indent = leadingSpaces(text, start)
            val first = start + indent
            if (first >= text.length || text[first] == '\n' || text[first] == '\r') return
            while (indents.isNotEmpty() && indents.last() >= indent) indents.removeAt(indents.lastIndex)
            parentIndent = indents.lastOrNull() ?: 0
            indents.add(indent)
        }
        enterLine(0)
        var scanned = 0

        while (true) {
            val matchStart = m.range.first
            for (i in scanned until matchStart) if (text[i] == '\n') enterLine(i + 1)
            scanned = matchStart

            // flowRef：按行内映射解析时的参照列，null 表示块写法；blockRef：按块写法遮到行尾时的参照列
            val linePrefix = m.groups[1]?.value
            val flowRef: Int?
            val blockRef: Int
            if (linePrefix != null) {
                // 行首键：块写法时参照列是键所在的列，同级的下一个键不算续行。
                // 行内映射在逗号后折行时键也会落在行首（上一行以逗号结尾）；块序列项「- 键:」一定是块写法
                blockRef = linePrefix.length
                flowRef = if ('-' !in linePrefix && previousLineEndsWithComma(text, lineStart)) parentIndent else null
            } else {
                // { 或 , 之后的键：所在行本身可能就是上一个值的续行，参照列取上一级的缩进
                flowRef = parentIndent
                blockRef = parentIndent
            }

            val keyEnd = m.range.last + 1
            var valueStart = keyEnd
            while (valueStart < text.length && (text[valueStart] == ' ' || text[valueStart] == '\t')) valueStart++
            val valueEnd = yamlValueEnd(text, valueStart, flowRef, blockRef)
            if (valueEnd > valueStart) {
                // 冒号后直接换行（值在后面的续行里）时补一个空格
                out.append(text, copied, valueStart).append(if (valueStart == keyEnd) " ***" else "***")
                copied = valueEnd
            }
            m = SENSITIVE_YAML_KEY.find(text, maxOf(valueEnd, keyEnd)) ?: break
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    // 值从 start 开始，返回它的结束位置（不含）
    private fun yamlValueEnd(text: String, start: Int, flowRef: Int?, blockRef: Int): Int {
        if (start >= text.length) return start
        val c = text[start]
        if (c == '\'' || c == '"') {
            val end = yamlQuotedEnd(text, start, flowRef ?: blockRef)
            if (end >= 0 && yamlQuotedFollowOk(text, end)) return end
        } else if (flowRef != null && c != '{' && c != '[' && c != '\r' && c != '\n') {
            val end = yamlFlowPlainEnd(text, start, flowRef)
            if (end >= 0) return end
        }
        // 块写法的值、值是嵌套集合、或引号 / 行内映射的形状对不上：遮到行尾，并带上续行
        return yamlBlockEnd(text, start, blockRef)
    }

    // 单引号（'' 是转义）或双引号（反斜杠转义）标量，可以跨续行；没闭合返回 -1
    private fun yamlQuotedEnd(text: String, start: Int, ref: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == quote -> {
                    if (quote == '\'' && i + 1 < text.length && text[i + 1] == '\'') i += 2 else return i + 1
                }
                c == '\\' && quote == '"' && i + 1 < text.length && text[i + 1] != '\r' && text[i + 1] != '\n' -> i += 2
                c == '\n' -> {
                    i = yamlContinuation(text, i + 1, ref)
                    if (i < 0) return -1
                }
                else -> i++
            }
        }
        return -1
    }

    // 引号标量结束后只能是行尾、注释，或行内映射 / 序列的 , } ]
    private fun yamlQuotedFollowOk(text: String, end: Int): Boolean {
        var i = end
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i >= text.length || text[i] in ",}]#\r\n"
    }

    // 行内映射里的普通标量：到 , { } [ ] 为止，行尾之后可以接续行。
    // 结束符之后的形状不像行内映射时返回 -1
    private fun yamlFlowPlainEnd(text: String, start: Int, ref: Int): Int {
        var i = start
        while (i < text.length) {
            when (text[i]) {
                ',', '{', '}', '[', ']' ->
                    return if (FLOW_VALUE_TERMINATOR.matchAt(text, i) != null) i else -1
                '\n' -> {
                    val next = yamlContinuation(text, i + 1, ref)
                    if (next < 0) return if (i > start && text[i - 1] == '\r') i - 1 else i
                    i = next
                }
                else -> i++
            }
        }
        return i
    }

    // 从 start 遮到行尾，再带上后面的续行
    private fun yamlBlockEnd(text: String, start: Int, ref: Int): Int {
        var end = start
        while (true) {
            val nl = text.indexOf('\n', end)
            if (nl < 0) return text.length
            end = if (nl > end && text[nl - 1] == '\r') nl - 1 else nl
            val next = yamlContinuation(text, nl + 1, ref)
            if (next < 0) return end
            end = next
        }
    }

    // from 是某一行的行首。跳过空白行后，下一行的缩进（只数空格）比 ref 深就是续行，
    // 返回它第一个非空格字符的位置；否则返回 -1
    private fun yamlContinuation(text: String, from: Int, ref: Int): Int {
        var lineStart = from
        while (lineStart < text.length) {
            var i = lineStart
            while (i < text.length && text[i] == ' ') i++
            if (i >= text.length) return -1
            lineStart = when {
                text[i] == '\n' -> i + 1
                text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> i + 2
                else -> return if (i - lineStart > ref) i else -1
            }
        }
        return -1
    }

    private fun leadingSpaces(text: String, lineStart: Int): Int {
        var i = lineStart
        while (i < text.length && text[i] == ' ') i++
        return i - lineStart
    }

    private fun previousLineEndsWithComma(text: String, lineStart: Int): Boolean {
        var i = lineStart - 2
        if (i >= 0 && text[i] == '\r') i--
        return i >= 0 && text[i] == ','
    }

    // scheme://host/path: everything after the host
    private val URL_PATH = Regex("""(://[^/\s]+)/\S*""")

    // keep scheme and host only; DoH addresses carry a per-user id in the path
    // (NextDNS, ControlD, AdGuard private)
    fun redactUrlPath(text: String): String =
        URL_PATH.replace(text) { it.groupValues[1] + "/***" }

    // 整份 sing-box 配置写日志前的脱敏
    fun redactConfig(config: String) = redactSecrets(redactDnsServerPaths(config))

    // sing-box 配置里 DoH / DoH3 服务器的 path 同样带 per-user id（见 redactUrlPath），
    // 整份配置写日志前遮蔽。只动 dns.servers：ws 等传输层的 path 留着排障用。
    // 没有可遮蔽的 path、或不是 JSON 对象时原样返回（不重排格式）
    fun redactDnsServerPaths(config: String): String {
        val root = runCatching {
            JavaUtil.gson.fromJson(config, JsonElement::class.java)
        }.getOrNull() as? JsonObject ?: return config
        val servers = (root.get("dns") as? JsonObject)?.get("servers") as? JsonArray ?: return config
        var changed = false
        for (server in servers) {
            if (server is JsonObject && server.has("path")) {
                server.addProperty("path", "/***")
                changed = true
            }
        }
        return if (changed) JavaUtil.gson.toJson(root) else config
    }
}
