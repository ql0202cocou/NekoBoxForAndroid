package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.utils.listByLineOrComma

// 单个端口会转为整数；范围以原文发给 sing-box，两端都可省略。
internal fun parseRulePorts(text: String): Pair<List<Int>, List<String>>? {
    val ports = mutableListOf<Int>()
    val ranges = mutableListOf<String>()
    val items = text.listByLineOrComma()
    if (items.isEmpty() && text.isNotBlank()) return null
    for (item in items) {
        if (':' !in item) {
            val port = item.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            ports += port
        } else {
            fun endpoint(value: String, default: Int): Int? = when {
                value.isEmpty() -> default
                value.all { it in '0'..'9' } -> value.toIntOrNull()?.takeIf { it in 0..65535 }
                else -> null
            }
            val first = endpoint(item.substringBefore(':'), 0) ?: return null
            val last = endpoint(item.substringAfter(':'), 65535) ?: return null
            if (first > last) return null
            ranges += item
        }
    }
    return ports to ranges
}
