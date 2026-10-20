package io.nekohasekai.sagernet.ktx

/** 一个已格式化的大小拆成「数字」与「单位」两段，如 `1.30 GB` → `1.30` 与 `GB` */
data class FileSizeParts(val number: String, val unit: String)

/**
 * 把 `Formatter.formatFileSize` 的结果拆成数字和单位（纯函数）。
 *
 * 在最后一段空白（含 NBSP、窄 NBSP）处切开；含数字的一段是数字，另一段是单位，所以单位在前的
 * 本地化也能拆对。没有空白，或两段都没有数字时，整段当数字、单位为空串
 */
fun splitFileSize(text: String): FileSizeParts {
    val trimmed = text.trim()
    val cut = trimmed.indexOfLast { it.isWhitespace() }
    if (cut < 0) return FileSizeParts(trimmed, "")
    var start = cut
    while (start > 0 && trimmed[start - 1].isWhitespace()) start--
    val head = trimmed.substring(0, start)
    val tail = trimmed.substring(cut + 1)
    return when {
        head.any { it.isDigit() } -> FileSizeParts(head, tail)
        tail.any { it.isDigit() } -> FileSizeParts(tail, head)
        else -> FileSizeParts(trimmed, "")
    }
}
