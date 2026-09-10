package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.utils.listByLineOrComma
import java.net.InetAddress

fun isWireGuardLocalAddressList(value: String): Boolean =
    value.listByLineOrComma().all { entry ->
        val parts = entry.split('/')
        if (parts.size != 2) return@all false
        val host = parts[0]
        val prefix = parts[1]
        if (prefix.isEmpty() || prefix.any { it !in '0'..'9' }) return@all false
        if (prefix.length > 1 && prefix[0] == '0') return@all false
        val bits = prefix.toIntOrNull() ?: return@all false
        if (':' !in host) {
            bits in 0..32 && isStrictIPv4(host)
        } else {
            if (bits !in 0..128 || host.any { it !in "0123456789abcdefABCDEF:." }) return@all false
            if ('.' in host && !isStrictIPv4(host.substringAfterLast(':'))) return@all false
            // A colon and numeric-only alphabet force the IPv6 literal parser;
            // no hostnames, scope identifiers or DNS lookups reach this call.
            runCatching { InetAddress.getByName(host) }.isSuccess
        }
    }

private fun isStrictIPv4(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all {
        it.isNotEmpty() && it.length <= 3 && (it.length == 1 || it[0] != '0') &&
            it.all { digit -> digit in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255
    }
}
