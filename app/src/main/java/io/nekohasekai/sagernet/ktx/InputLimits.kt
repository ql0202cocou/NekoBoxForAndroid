package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// Match the HTTP subscription body limit, including all entries of a ZIP import.
const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

fun InputStream.readBytesLimited(limit: Int = MAX_IMPORT_BYTES): ByteArray =
    ByteArrayOutputStream().also { copyToLimited(it, limit.toLong()) }.toByteArray()

// 流式复制，超过 limit 字节即抛出 IOException；目标里已写入的部分由调用方丢弃。
fun InputStream.copyToLimited(
    output: OutputStream,
    limit: Long,
    message: String = "Import exceeds size limit",
): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var copied = 0L
    while (true) {
        // 最多多读一个字节：恰好 limit 字节的输入不会误判，超出的输入读到这一字节就判定
        val count = read(buffer, 0, minOf(buffer.size.toLong(), limit - copied + 1).toInt())
        if (count < 0) return copied
        if (count > limit - copied) throw IOException(message)
        output.write(buffer, 0, count)
        copied += count
    }
}

// Android's JSONTokener recursively parses containers before parseJSON sees
// them. Count nesting first, including its single-quote/comment extensions.
fun String.checkJsonNesting(maxDepth: Int = 64): String {
    var depth = 0
    var quote: Char? = null
    var index = 0
    while (index < length) {
        val char = this[index++]
        if (quote != null) {
            if (char == '\\') index++ else if (char == quote) quote = null
            continue
        }
        when {
            char == '"' || char == '\'' -> quote = char
            char == '#' || (char == '/' && index < length && this[index] == '/') -> {
                while (index < length && this[index] != '\n' && this[index] != '\r') index++
            }
            char == '/' && index < length && this[index] == '*' -> {
                val end = indexOf("*/", index + 1)
                index = if (end < 0) length else end + 2
            }
            char == '[' || char == '{' -> {
                depth++
                require(depth <= maxDepth) { "JSON nesting exceeds limit" }
            }
            char == ']' || char == '}' -> {
                depth--
                require(depth >= 0) { "Unbalanced JSON container" }
            }
        }
    }
    return this
}
