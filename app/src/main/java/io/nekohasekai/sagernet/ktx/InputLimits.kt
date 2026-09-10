package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

// Match the HTTP subscription body limit, including all entries of a ZIP import.
const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

fun InputStream.readBytesLimited(limit: Int = MAX_IMPORT_BYTES): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (count > limit - output.size()) throw IOException("Import exceeds size limit")
        output.write(buffer, 0, count)
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
