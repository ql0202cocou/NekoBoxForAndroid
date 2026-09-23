package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
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

    // JSON "key": "value" pairs whose value is a credential
    // (trailing ["] is a literal quote; a raw string cannot end with ")
    private val SENSITIVE_JSON_VALUE = Regex(
        """("(?:password|uuid|id|private_key|pre_shared_key|auth|auth_str|token|secret|key|authorization|cookie)"\s*:\s*)"(?:\\.|[^"\\])*["]""",
        RegexOption.IGNORE_CASE
    )

    // YAML "key: value" lines whose value is a credential (e.g. mihomo configs)
    private val SENSITIVE_YAML_VALUE = Regex(
        """^(\s*(?:password|uuid|id|private-key|pre-shared-key|auth|auth-str|token|secret|key|authorization|cookie)\s*:\s*).+$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
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
        var result = SENSITIVE_JSON_VALUE.replace(text) { it.groupValues[1] + "\"***\"" }
        result = SENSITIVE_YAML_VALUE.replace(result) { it.groupValues[1] + "***" }
        result = URL_USERINFO_PASSWORD.replace(result) { it.groupValues[1] + ":***@" }
        result = URL_USERINFO_BARE.replace(result) { it.groupValues[1] + "***@" }
        result = URL_QUERY_SECRET.replace(result) { it.groupValues[1] + "***" }
        return result
    }

    // scheme://host/path: everything after the host
    private val URL_PATH = Regex("""(://[^/\s]+)/\S*""")

    // keep scheme and host only; DoH addresses carry a per-user id in the path
    // (NextDNS, ControlD, AdGuard private)
    fun redactUrlPath(text: String): String =
        URL_PATH.replace(text) { it.groupValues[1] + "/***" }
}
