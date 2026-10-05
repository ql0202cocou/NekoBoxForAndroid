@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import android.net.Network
import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libcore.HTTPClient
import libcore.Libcore
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

fun linkBuilder() = HttpUrl.Builder().scheme("https")

// 经本地 mixed 端口拉取。仅代理（无 VPN）模式下服务在跑时，代理失败直接报错、不回退
// 直连：那时直连是真直连物理网络，请求地址会暴露给运营商（见 libcore StrictSocks5）。
// VPN 模式下直连会被 tun 捕获，服务没跑时直连本就是预期行为，都照旧回退
fun HTTPClient.useLocalProxy() {
    trySocks5(DataStore.mixedPort)
    if (ServiceRegistry.state.started && DataStore.serviceMode != Key.MODE_VPN) strictSocks5()
}

// Profile links share the authority/query shape of an HTTP URL, so okhttp can parse
// them once the scheme is swapped. Rebuilding the prefix — rather than replace() —
// keeps a "://" inside the query or fragment untouched.
fun String.withHttpScheme(scheme: String = "https"): String =
    "$scheme://" + substringAfter("://")

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

// Editor check for a server host: a blank value would silently become 127.0.0.1 in
// initializeDefaultValues, and whitespace or a path only fails later at dial time.
// No host name or IP literal contains either, bracketed IPv6 included.
fun isServerAddress(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() || it == '/' }

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

// [v6]:port | host:port | bare v6 | host -> host plus the raw port text, or null
// when no port was given at all. Returns null for a value that cannot be split:
// an unterminated or empty bracket, or a bracket followed by anything but ":port".
// A bare IPv6 address has no port to split off and must keep all its colons, so it
// reports no port; callers decide whether to default, reject or skip such a value,
// and whether the host still has to parse as an address.
fun String.splitHostPort(): Pair<String, String?>? {
    if (startsWith("[")) {
        val end = indexOf(']')
        if (end <= 1) return null
        val suffix = substring(end + 1)
        if (suffix.isNotEmpty() && !suffix.startsWith(":")) return null
        return substring(1, end) to suffix.drop(1).takeIf { suffix.isNotEmpty() }
    }
    if (count { it == ':' } == 1) return substringBefore(':') to substringAfter(':')
    return this to null
}

// 回环 / 未指定地址的 DNS 服务器（127.0.0.1、[::1]:53、udp://0.0.0.0 …）返回 true：它们只在拥有它的核心里
// 有意义（例如 mihomo 配置指向自己的 dns.listen），在真实设备上是死路。
// parse 把数字地址字面量解析成地址、不是数字地址时返回 null，默认用生产实现；配置构建传入注入的实现
fun String.isLocalNameserverAddress(
    parse: (String) -> InetAddress? = { it.parseNumericAddress() },
): Boolean {
    var host = substringAfter("://").substringBefore("/").trim()
    host = when {
        host.startsWith("[") -> host.substringAfter("[").substringBefore("]")
        host.count { it == ':' } == 1 -> host.substringBefore(":")
        else -> host
    }
    val ip = parse(host.substringBefore("%"))
        ?: return host.equals("localhost", ignoreCase = true)
    return ip.isLoopbackAddress || ip.isAnyLocalAddress
}

// 每行一个地址。去掉 mihomo 式的 "#h3" 后缀、clash 的 "system" / "local" 占位以及回环 / 未指定地址，
// 让分组 proxyServerNameserver 的每个读者（配置构建、解析器、订阅镜像）对「可用地址」的判断一致。
// parse 同 isLocalNameserverAddress
fun String?.usableNameservers(
    parse: (String) -> InetAddress? = { it.parseNumericAddress() },
): List<String> = this?.lineSequence()
    ?.map { it.trim().substringBefore("#").trim() }
    ?.filter {
        it.isNotBlank() && it != "system" && it != "local" && !it.isLocalNameserverAddress(parse)
    }
    ?.distinct()?.toList() ?: emptyList()

// Try the group's nameservers in order within one native ten-second budget.
// Cancelling the caller cancels the native context, so the blocking gomobile
// call returns at once instead of running out the budget; callers on a
// per-profile path should still memoize to avoid repeating failed lookups.
suspend fun lookupViaNameserver(nameserver: String?, domain: String): List<InetAddress>? {
    val servers = nameserver.usableNameservers()
    if (servers.isEmpty()) return null
    val task = Libcore.startLookupHosts(servers.joinToString("\n"), domain)
    return try {
        withContext(Dispatchers.IO) {
            val guard = launch { try { awaitCancellation() } finally { task.cancel() } }
            try {
                task.await()
            } catch (e: Exception) {
                // the native "context canceled" error must surface as cancellation, or
                // the caller would swallow it and fall through to the system resolver
                ensureActive()
                throw e
            } finally {
                guard.cancel()
            }
        }.lineSequence()
            .mapNotNull { it.trim().parseNumericAddress() }
            .toList().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Resolver errors may include the full DoH URL and its credentials.
        Logs.d("Group DNS lookup failed: ${e.javaClass.simpleName}")
        null
    }
}

// 系统解析：network 非空时走它（绕过 VPN），否则用进程默认解析器。两条
// getAllByName 都是没有超时的阻塞 JNI 调用，协程取消也打不断，会一直攥住
// 调用方的线程。把它挂到 appScope 上跑、这里只等 10 秒（与 lookupViaNameserver
// 的原生十秒预算一致）：超时按解析失败处理并取消 lookup——还没开跑的不再执行，
// 已在跑的在解析器返回后自行结束，结果直接丢弃。
// Job.cancel() 成员与同名 CoroutineScope 扩展（通配 import）同时可见：编译期
// 恒解析到成员，lint 的跨环境歧义警告在此不适用（同 BaseService.destroyRunner）
@Suppress("MemberExtensionConflict")
suspend fun lookupSystem(domain: String, network: Network?): List<InetAddress> {
    val resolve: (String) -> Array<InetAddress> = network?.let { it::getAllByName }
        ?: InetAddress::getAllByName
    val lookup = appScope.async(Dispatchers.IO) {
        runCatching { resolve(domain).filterNotNull() }.getOrDefault(emptyList())
    }
    return withTimeoutOrNull(10_000L) { lookup.await() } ?: run {
        lookup.cancel()
        Logs.w("DNS lookup for $domain timed out")
        emptyList()
    }
}

// 节点域名解析（订阅解析与 ping 测试共用）：分组配了节点解析 DNS 时优先使用，
// 没配或失败时回退系统解析
suspend fun lookupServerAddress(
    domain: String, groupNameserver: String?, network: Network?,
): List<InetAddress> = lookupViaNameserver(groupNameserver, domain) ?: lookupSystem(domain, network)

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
