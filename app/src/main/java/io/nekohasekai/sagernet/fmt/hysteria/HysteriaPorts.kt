package io.nekohasekai.sagernet.fmt.hysteria

// Keep ranges compact: expanding a hopping range needlessly allocates ports.
fun parseHysteriaPorts(value: String): List<IntRange> = value.split(',').map { item ->
    val endpoints = item.trim().split('-', ':')
    require(endpoints.size in 1..2) { "Invalid Hysteria port range" }
    val ports = endpoints.map { endpoint ->
        val text = endpoint.trim()
        require(text.isNotEmpty() && text.all { it in '0'..'9' }) { "Invalid Hysteria port" }
        val port = text.toIntOrNull()
        require(port != null && port in 1..65535) { "Hysteria port must be in 1..65535" }
        port
    }
    val first = ports.first()
    val last = ports.last()
    require(first <= last) { "Hysteria port range is reversed" }
    first..last
}
