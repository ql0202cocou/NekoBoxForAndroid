@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig

import libcore.Libcore
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

fun linkBuilder() = HttpUrl.Builder().scheme("https")

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    // replaceFirst: the scheme prefix sits at the front, and a global replace
    // would also rewrite user-controlled path/query content. The placeholder is
    // matched together with the "/" that always follows the port: the userinfo
    // comes first and could start with "14514", but "/" is percent-encoded there.
    return url.toString().replaceFirst("${url.scheme}://", "$scheme://").let {
        if (replace) it.replaceFirst(":14514/", ":$defaultPort/") else it
    }
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

fun String.isIpAddressV6(): Boolean {
    return NGUtil.isIpv6Address(this)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

// True for loopback/unspecified DNS addresses (127.0.0.1, [::1]:53, udp://0.0.0.0 …):
// they only make sense inside the core that owns them (e.g. a mihomo config pointing
// at its own dns.listen) and are dead ends on any real device.
fun String.isLocalNameserverAddress(): Boolean {
    var host = substringAfter("://").substringBefore("/").trim()
    host = when {
        host.startsWith("[") -> host.substringAfter("[").substringBefore("]")
        host.count { it == ':' } == 1 -> host.substringBefore(":")
        else -> host
    }
    val ip = host.substringBefore("%").parseNumericAddress()
        ?: return host.equals("localhost", ignoreCase = true)
    return ip.isLoopbackAddress || ip.isAnyLocalAddress
}

// One address per line. Strips mihomo-style "#h3" suffixes, the clash "system"/"local"
// sentinels and loopback/unspecified entries, so every reader of a group's
// proxyServerNameserver — the config builder, the resolver, the subscription mirror —
// agrees on what counts as a usable address.
fun String?.usableNameservers(): List<String> = this?.lineSequence()
    ?.map { it.trim().substringBefore("#").trim() }
    ?.filter {
        it.isNotBlank() && it != "system" && it != "local" && !it.isLocalNameserverAddress()
    }
    ?.distinct()?.toList() ?: emptyList()

// Resolve via the group's proxyServerNameserver (first usable address, e.g. DoH);
// returns null on failure so callers can fall back to system DNS.
// Note: Libcore.lookupHost does not cache, so callers on a per-profile path
// should memoize — a dead nameserver costs the full timeout every call.
fun lookupViaNameserver(nameserver: String?, domain: String): List<InetAddress>? {
    val server = nameserver.usableNameservers().firstOrNull() ?: return null
    return try {
        Libcore.lookupHost(server, domain).lineSequence()
            .mapNotNull { it.trim().parseNumericAddress() }
            .toList().takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        // scheme + authority only: a DoH path can embed tokens
        Logs.d(
            "Lookup $domain via " +
                    server.substringAfter("://").substringBefore("/") +
                    " failed: ${e.readableMessage}"
        )
        null
    }
}

// Ports handed out but not yet bound by their consumer (external cores bind only
// after process spawn). Keep them out of rotation so concurrent callers — e.g.
// parallel URL-test instances — can't be assigned the same ephemeral port.
// Nothing reports back once a port is really bound, so reservations expire: past
// the spawn window they can no longer collide, and holding them forever would eat
// the ephemeral range until the retry loop below just spins.
private const val MK_PORT_RESERVE_MS = 60_000L
private val mkPortReserved = ConcurrentHashMap<Int, Long>()

fun mkPort(): Int {
    val now = SystemClock.elapsedRealtime()
    mkPortReserved.values.removeAll { now - it > MK_PORT_RESERVE_MS }
    while (true) {
        val port = Socket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.localPort
        }
        if (mkPortReserved.putIfAbsent(port, now) == null) return port
    }
}

const val USER_AGENT = "NekoBox/Android/" + BuildConfig.VERSION_NAME + " (Prefer ClashMeta Format)"
