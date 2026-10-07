package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CLASH_ENUM_PATHS
import io.nekohasekai.sagernet.fmt.ClashFieldReason
import io.nekohasekai.sagernet.fmt.ClashFieldReason.DEFAULT_VALUE
import io.nekohasekai.sagernet.fmt.ClashFieldReason.DIAL_ADDRESS
import io.nekohasekai.sagernet.fmt.ClashFieldReason.EQUIVALENT_VALUE
import io.nekohasekai.sagernet.fmt.ClashFieldReason.FIRST_ITEM_ONLY
import io.nekohasekai.sagernet.fmt.ClashFieldReason.INACTIVE
import io.nekohasekai.sagernet.fmt.ClashFieldReason.INVALID_DROPPED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.LIST_JOINED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.MATCHES_RESULT
import io.nekohasekai.sagernet.fmt.ClashFieldReason.MTLS_CLIENT_CERT
import io.nekohasekai.sagernet.fmt.ClashFieldReason.NOT_READ
import io.nekohasekai.sagernet.fmt.ClashFieldReason.NOT_SUPPORTED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.OVERRIDDEN
import io.nekohasekai.sagernet.fmt.ClashFieldReason.RATE_UNIT_DROPPED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.REALITY_IMPLIES_TLS
import io.nekohasekai.sagernet.fmt.ClashFieldReason.REALITY_NO_PUBLIC_KEY
import io.nekohasekai.sagernet.fmt.ClashFieldReason.ROUNDED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.SEMANTICS_DIFFER
import io.nekohasekai.sagernet.fmt.ClashFieldReason.UNIT_CONVERTED
import io.nekohasekai.sagernet.fmt.ClashFieldReason.UNKNOWN_KEY
import io.nekohasekai.sagernet.fmt.ClashFieldReason.UNKNOWN_VALUE
import io.nekohasekai.sagernet.fmt.ClashFieldRecord
import io.nekohasekai.sagernet.fmt.ClashFieldResult
import io.nekohasekai.sagernet.fmt.clashShownPath
import io.nekohasekai.sagernet.fmt.clashShownValue
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint

// Clash 节点的字段记录（K3a）：解析完成后，拿输入的 map 与解析出的 bean 对照，给输入里每个非空键
// （含解析器读到的嵌套对象的子键）一个结果。只读不写：不改 bean，不影响导入结果。
// 键表来自 mihomo v1.19.32 的 adapter/outbound/*.go（proxy:"…" 标签，BasicOption 是各类型共有的键，
// smux 由 adapter/parser.go 统一处理）；mihomo 解码时键名大小写不敏感、_ 当作 -，查表前同样归一化。
// 有记录的键按记录；没记录的键：随节点而定的无影响（inactive）→ 无影响名单 → 空值 / false →
// 键表里有的记「未导入」（有损），键表里没有的记「mihomo 不认识」（无影响）

// 本应用里本来就没有对应行为的顶层键，各自的理由见 ClashFieldReason。拿不准的不放进来（按有损记）
val CLASH_NO_EFFECT_KEYS: Map<String, ClashFieldReason> = mapOf(
    // 本应用不按节点开关 UDP，UDP 是否经节点由协议决定
    "udp" to ClashFieldReason.NO_UDP_SWITCH,
    // 不按节点设 TCP Fast Open / MPTCP
    "tfo" to ClashFieldReason.NO_TCP_FAST_OPEN,
    "mptcp" to ClashFieldReason.NO_MPTCP,
    // 出站网卡与路由标记由 VPN 服务统一管理（socket protect），不按节点绑定
    "interface-name" to ClashFieldReason.NO_INTERFACE_NAME,
    "routing-mark" to ClashFieldReason.NO_ROUTING_MARK,
    // 服务器地址解析的 IPv4 / IPv6 取舍跟随全局 IPv6 设置
    "ip-version" to ClashFieldReason.NO_IP_VERSION,
)

// WireGuard 专有的无影响键：mihomo 自己的实现细节，在 sing-box 端点上没有对应项
private val CLASH_NO_EFFECT_WIREGUARD: Map<String, ClashFieldReason> = mapOf(
    "workers" to ClashFieldReason.NO_WIREGUARD_WORKERS,
    "ip-stack" to ClashFieldReason.NO_WIREGUARD_IP_STACK,
)

private typealias ClashInactive = (List<String>) -> ClashFieldReason?

// 解析出的节点 -> 字段记录。记录本身出错时不让节点被跳过，只记一条「未分类」
internal fun classifyClashFields(type: String, proxy: Map<String, Any?>, bean: AbstractBean): List<ClashFieldRecord> = try {
    // anytls / hysteria / tuic / wireguard 的解析器把键里的 _ 当作 -，其余按原样
    val fields = ClashFields(proxy, normalizeKeys = bean is AnyTLSBean || bean is HysteriaBean || bean is TuicBean || bean is WireGuardBean)
    fields.kept("name")
    if (type == "hy2") fields.converted(EQUIVALENT_VALUE, "type") else fields.kept("type")
    val (table, inactive) = when (bean) {
        is SOCKSBean -> fields.socks()
        is HttpBean -> fields.http(bean)
        is ShadowsocksBean -> fields.shadowsocks(bean)
        is StandardV2RayBean -> fields.v2ray(type, bean)
        is AnyTLSBean -> fields.anyTLS(bean)
        is HysteriaBean -> fields.hysteria(bean)
        is TuicBean -> fields.tuic(bean)
        is WireGuardBean -> fields.wireGuard(bean)
        else -> emptySet<String>() to { _: List<String> -> null }
    }
    fields.finish(table) { path -> inactive(path) ?: fields.smuxInactive(path) }
} catch (e: Exception) {
    listOf(ClashFieldRecord("*", ClashFieldResult.IGNORED, ClashFieldReason.NOT_CLASSIFIED))
}

private fun normalizeClashKey(key: String) = key.lowercase().replace('_', '-')

private fun Any?.isFalseOrEmpty(): Boolean = when (this) {
    null -> true
    is Map<*, *> -> isEmpty()
    is List<*> -> isEmpty()
    else -> toString().let { it.isBlank() || it.lowercase() in setOf("false", "no", "off", "0") }
}

// 一个节点的字段记录。路径在内部是原始键的序列，写进记录时才按显示规则处理
internal class ClashFields(private val proxy: Map<String, Any?>, private val normalizeKeys: Boolean) {
    private val records = HashMap<List<String>, ClashFieldRecord>()
    private val opened = HashSet<List<String>>()

    private fun rawKey(map: Map<*, *>, name: String): String? {
        // 同一个键写了几种拼法时，解析器按键序覆盖，最后一个生效
        var found: String? = null
        for (key in map.keys) {
            val text = key?.toString() ?: continue
            if (text == name || (normalizeKeys && text.replace('_', '-') == name)) found = text
        }
        return found
    }

    fun path(vararg names: String): List<String>? {
        var map: Map<*, *> = proxy
        val out = ArrayList<String>(names.size)
        for ((i, name) in names.withIndex()) {
            val key = rawKey(map, name) ?: return null
            out += key
            if (i < names.lastIndex) map = map[key] as? Map<*, *> ?: return null
        }
        return out
    }

    fun valueAt(path: List<String>): Any? {
        var value: Any? = proxy
        for (key in path) value = (value as? Map<*, *>)?.get(key) ?: return null
        return value
    }

    fun value(vararg names: String): Any? = path(*names)?.let { valueAt(it) }
    fun text(vararg names: String): String? = value(*names)?.toString()
    fun flag(vararg names: String): Boolean = value(*names).clashBoolean()
    fun present(vararg names: String) = value(*names) != null

    private fun shownValue(path: List<String>, value: Any?): String? {
        val normalized = path.joinToString(".") { normalizeClashKey(it) }
        return if (normalized in CLASH_ENUM_PATHS) clashShownValue(value) else null
    }

    fun recordAt(path: List<String>, result: ClashFieldResult, reason: ClashFieldReason?) {
        val value = valueAt(path) ?: return
        records[path] = ClashFieldRecord(clashShownPath(path), result, reason, shownValue(path, value))
        // 子键有记录时，父对象按「已展开」处理
        for (i in 1 until path.size) opened += path.subList(0, i)
    }

    fun kept(vararg names: String) {
        path(*names)?.let { recordAt(it, ClashFieldResult.KEPT, null) }
    }

    fun converted(reason: ClashFieldReason, vararg names: String) {
        path(*names)?.let { recordAt(it, ClashFieldResult.CONVERTED, reason) }
    }

    fun ignored(reason: ClashFieldReason, vararg names: String) {
        path(*names)?.let { recordAt(it, ClashFieldResult.IGNORED, reason) }
    }

    // 值相同记 KEPT，不同说明被别的键改写了
    fun keptIfFinal(final: String?, vararg names: String) {
        val path = path(*names) ?: return
        val value = valueAt(path) ?: return
        if (value.toString() != final) recordAt(path, ClashFieldResult.IGNORED, OVERRIDDEN)
        else recordAt(path, ClashFieldResult.KEPT, null)
    }

    // 解析器读进了这个嵌套对象：没有记录的子键逐个走未读分类
    fun open(vararg names: String) {
        path(*names)?.let { if (valueAt(it) is Map<*, *>) opened += it }
    }

    fun smuxInactive(path: List<String>): ClashFieldReason? {
        if (path[0] != "smux") return null
        val smux = value("smux") as? Map<*, *> ?: return INACTIVE
        if (!smux["enabled"].clashBoolean()) return INACTIVE
        if (path.size > 1 && path[1] == "brutal-opts") {
            if (!(smux["brutal-opts"] as? Map<*, *>)?.get("enabled").clashBoolean()) return INACTIVE
        }
        return null
    }

    fun finish(table: Set<String>, inactive: ClashInactive): List<ClashFieldRecord> {
        val out = ArrayList<ClashFieldRecord>()
        walk(proxy, emptyList(), table, inactive, out)
        return out
    }

    private fun walk(
        map: Map<*, *>, prefix: List<String>, table: Set<String>, inactive: ClashInactive,
        out: MutableList<ClashFieldRecord>,
    ) {
        for ((key, value) in map) {
            // mihomo 的解码器对 null 什么都不做，与没写一样
            if (value == null) continue
            val path = prefix + key.toString()
            val record = records[path]
            when {
                record != null -> out += record
                path in opened && value is Map<*, *> -> walk(value, path, table, inactive, out)
                else -> out += unread(path, value, table, inactive)
            }
        }
    }

    private fun unread(path: List<String>, value: Any, table: Set<String>, inactive: ClashInactive): ClashFieldRecord {
        val normalized = path.map { normalizeClashKey(it) }
        val reason = inactive(normalized)
            ?: (if (normalized.size == 1) CLASH_NO_EFFECT_KEYS[normalized[0]] else null)
            ?: (if (value.isFalseOrEmpty()) DEFAULT_VALUE else null)
            ?: (if (table.knows(normalized)) NOT_READ else UNKNOWN_KEY)
        return ClashFieldRecord(clashShownPath(path), ClashFieldResult.IGNORED, reason, shownValue(path, value))
    }

    // 键表里的 a.b.* 表示 a.b 是键名自由的 map（如 headers）
    private fun Set<String>.knows(path: List<String>): Boolean {
        if (path.joinToString(".") in this) return true
        return (1 until path.size).any { path.subList(0, it).joinToString(".") + ".*" in this }
    }
}

// ---------- mihomo 键表 ----------

private val BASIC_KEYS = setOf(
    "name", "type", "server", "port",
    "tfo", "mptcp", "interface-name", "routing-mark", "ip-version", "dialer-proxy",
    "smux", "smux.enabled", "smux.protocol", "smux.max-connections", "smux.min-streams", "smux.max-streams",
    "smux.padding", "smux.statistic", "smux.only-tcp", "smux.brutal-opts",
)
private val CERT_KEYS = setOf("skip-cert-verify", "name-cert-verify", "fingerprint", "certificate", "private-key")
private val ECH_KEYS = setOf("ech-opts", "ech-opts.enable", "ech-opts.config", "ech-opts.query-server-name")
private val TLS_VARIANT_KEYS = setOf("shadow-tls-opts", "restls-opts", "jls-opts")
private val REALITY_KEYS = setOf(
    "reality-opts", "reality-opts.public-key", "reality-opts.short-id", "reality-opts.support-x25519mlkem768",
)
private val TRANSPORT_KEYS = setOf(
    "ws-opts", "ws-opts.path", "ws-opts.headers", "ws-opts.headers.*", "ws-opts.max-early-data",
    "ws-opts.early-data-header-name", "ws-opts.v2ray-http-upgrade", "ws-opts.v2ray-http-upgrade-fast-open",
    "grpc-opts", "grpc-opts.grpc-service-name", "grpc-opts.grpc-user-agent", "grpc-opts.ping-interval",
    "grpc-opts.max-connections", "grpc-opts.min-streams", "grpc-opts.max-streams",
)
private val HTTP_TRANSPORT_KEYS = setOf(
    "http-opts", "http-opts.method", "http-opts.path", "http-opts.headers", "http-opts.headers.*",
    "h2-opts", "h2-opts.host", "h2-opts.path",
)

// 只在 TLS 打开时起作用的键（mihomo 与本应用都是），TLS 关着时它们没有影响
private val TLS_ONLY_KEYS = setOf(
    "sni", "servername", "alpn", "client-fingerprint", "ech-opts",
) + CERT_KEYS + TLS_VARIANT_KEYS + "tlsmirror-opts"

private val SOCKS_KEYS = BASIC_KEYS + CERT_KEYS + setOf("username", "password", "tls", "udp")
private val HTTP_KEYS = BASIC_KEYS + CERT_KEYS + setOf("username", "password", "tls", "sni", "headers", "headers.*")
private val SS_KEYS = BASIC_KEYS + setOf(
    "password", "cipher", "udp", "plugin", "plugin-opts", "udp-over-tcp", "udp-over-tcp-version", "client-fingerprint",
)
private val SS_OBFS_KEYS = setOf("plugin-opts.mode", "plugin-opts.host")
private val SS_V2RAY_PLUGIN_KEYS = setOf(
    "plugin-opts.mode", "plugin-opts.host", "plugin-opts.path", "plugin-opts.tls", "plugin-opts.ech-opts",
    "plugin-opts.fingerprint", "plugin-opts.certificate", "plugin-opts.private-key", "plugin-opts.headers",
    "plugin-opts.skip-cert-verify", "plugin-opts.name-cert-verify", "plugin-opts.mux",
    "plugin-opts.v2ray-http-upgrade", "plugin-opts.v2ray-http-upgrade-fast-open",
)
private val VMESS_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + TLS_VARIANT_KEYS + REALITY_KEYS + TRANSPORT_KEYS +
        HTTP_TRANSPORT_KEYS + setOf(
    "uuid", "alterid", "cipher", "udp", "network", "tls", "alpn", "servername", "tlsmirror-opts", "mekya-opts",
    "mkcp-opts", "packet-addr", "xudp", "packet-encoding", "global-padding", "authenticated-length",
    "client-fingerprint",
)

// ws-headers 虽在 VlessOption 里声明，mihomo 并不使用它，按不认识的键处理
private val VLESS_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + TLS_VARIANT_KEYS + REALITY_KEYS + TRANSPORT_KEYS +
        HTTP_TRANSPORT_KEYS + setOf(
    "uuid", "flow", "tls", "alpn", "udp", "packet-addr", "xudp", "packet-encoding", "encryption", "network",
    "xhttp-opts", "servername", "client-fingerprint",
)
private val TROJAN_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + TLS_VARIANT_KEYS + REALITY_KEYS + TRANSPORT_KEYS +
        setOf("password", "alpn", "sni", "udp", "network", "ss-opts", "client-fingerprint")
private val ANYTLS_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + TLS_VARIANT_KEYS + setOf(
    "password", "alpn", "sni", "client-fingerprint", "udp", "client-metadata", "idle-session-check-interval",
    "idle-session-timeout", "min-idle-session", "disable-reuse",
)

// ca / ca-str 是旧版 Clash.Meta 的键（mihomo v1.19.32 已没有）：本应用仍读 ca-str，ca（本地文件路径）读不了
private val HYSTERIA_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + setOf(
    "ports", "protocol", "obfs-protocol", "up", "up-speed", "down", "down-speed", "auth", "auth-str", "obfs", "sni",
    "alpn", "recv-window-conn", "recv-window", "disable-mtu-discovery", "fast-open", "hop-interval", "ca", "ca-str",
)
private val HYSTERIA2_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + setOf(
    "ports", "hop-interval", "up", "down", "password", "obfs", "obfs-password", "obfs-min-packet-size",
    "obfs-max-packet-size", "sni", "alpn", "cwnd", "bbr-profile", "udp-mtu", "handshake-timeout", "realm-opts",
    "initial-stream-receive-window", "max-stream-receive-window", "initial-connection-receive-window",
    "max-connection-receive-window", "ca", "ca-str",
)
private val TUIC_KEYS = BASIC_KEYS + CERT_KEYS + ECH_KEYS + setOf(
    "token", "uuid", "password", "ip", "heartbeat-interval", "alpn", "reduce-rtt", "request-timeout",
    "udp-relay-mode", "congestion-controller", "disable-sni", "max-udp-relay-packet-size", "fast-open",
    "max-open-streams", "cwnd", "bbr-profile", "recv-window-conn", "recv-window", "disable-mtu-discovery",
    "max-datagram-frame-size", "sni", "udp-over-stream", "udp-over-stream-version", "ca", "ca-str",
)
private val WIREGUARD_KEYS = BASIC_KEYS + setOf(
    "ip", "ipv6", "private-key", "workers", "mtu", "udp", "persistent-keepalive", "ip-stack", "amnezia-wg-option",
    "peers", "remote-dns-resolve", "dns", "refresh-server-ip-interval", "public-key", "pre-shared-key", "reserved",
    "allowed-ips",
)

// ---------- 按类型分类 ----------

private fun ClashFields.tlsOffInactive(tlsOn: Boolean): ClashInactive =
    { path -> if (!tlsOn && path[0] in TLS_ONLY_KEYS) INACTIVE else null }

// fingerprint：SHA-256 摘要原样保留；其它形状的值被解析器丢掉（TLS 关着时本来就不起作用）
private fun ClashFields.fingerprint(tlsOn: Boolean) {
    val value = text("fingerprint") ?: return
    when {
        value.isEmpty() || isCertificateFingerprint(value) -> kept("fingerprint")
        !tlsOn -> ignored(INACTIVE, "fingerprint")
        else -> ignored(INVALID_DROPPED, "fingerprint")
    }
}

// alpn 列表按行保存；不是列表时解析器读不出来
private fun ClashFields.alpnList(final: String?) {
    val value = value("alpn") ?: return
    if (value is List<*> && final != null) converted(LIST_JOINED, "alpn") else ignored(INVALID_DROPPED, "alpn")
}

private fun ClashFields.socks(): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("username"); kept("password")
    // TLS 不导入：tls: true 按键表记「未导入」，下面的证书类键随之
    return SOCKS_KEYS to tlsOffInactive(flag("tls"))
}

private fun ClashFields.http(bean: HttpBean): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("username"); kept("password")
    kept("tls"); kept("sni"); kept("skip-cert-verify")
    return HTTP_KEYS to tlsOffInactive(bean.security == "tls")
}

private fun ClashFields.shadowsocks(bean: ShadowsocksBean): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("password")
    if (text("cipher") == "dummy") converted(EQUIVALENT_VALUE, "cipher") else kept("cipher")
    // 未支持的插件整个节点被拒，到不了这里
    val plugin = text("plugin").blankAsNull()
    when (plugin) {
        // 写成 sip003 的 obfs-local
        "obfs" -> converted(EQUIVALENT_VALUE, "plugin")
        else -> kept("plugin")
    }
    var table = SS_KEYS
    var pluginTls = false
    val opts = value("plugin-opts")
    if (plugin != null && opts != null) {
        if (opts !is Map<*, *>) {
            ignored(INVALID_DROPPED, "plugin-opts")
        } else {
            open("plugin-opts")
            kept("plugin-opts", "mode"); kept("plugin-opts", "host")
            if (plugin == "obfs") {
                table = table + SS_OBFS_KEYS
            } else {
                table = table + SS_V2RAY_PLUGIN_KEYS
                pluginTls = flag("plugin-opts", "tls")
                kept("plugin-opts", "path"); kept("plugin-opts", "tls")
                // mux: true 写成 mux=8（sing-box 的插件只看是否大于 0）；false 不写，而 sing-box 的插件不写时默认开 mux
                if (present("plugin-opts", "mux")) {
                    if (flag("plugin-opts", "mux")) converted(EQUIVALENT_VALUE, "plugin-opts", "mux")
                    else ignored(SEMANTICS_DIFFER, "plugin-opts", "mux")
                }
            }
        }
    }
    // udp-over-tcp：mihomo 不写版本时用 1，sing-box 的 udp_over_tcp 用 2，只有显式写 2 时两边一致
    val uot = bean.sUoT == true
    val uotVersion = text("udp-over-tcp-version")
    if (present("udp-over-tcp")) {
        if (uot && uotVersion != "2") ignored(SEMANTICS_DIFFER, "udp-over-tcp") else kept("udp-over-tcp")
    }
    if (uot && uotVersion == "2") ignored(MATCHES_RESULT, "udp-over-tcp-version")
    return table to { path ->
        when {
            path[0] == "plugin-opts" && plugin == null -> INACTIVE
            // mihomo 只在 shadow-tls / restls / jls 插件里用它，这几种插件在这里整个节点被拒
            path[0] == "client-fingerprint" -> INACTIVE
            path[0] == "udp-over-tcp-version" && !uot -> INACTIVE
            path[0] == "plugin-opts" && path.size > 1 && !pluginTls &&
                    path[1] in setOf("ech-opts") + CERT_KEYS -> INACTIVE

            else -> null
        }
    }
}

// 带 mihomo 激活条件的 TLS 变体 / 附加加密层子对象：激活时本应用没有对应实现
private fun ClashFields.tlsVariantActive(key: String): Boolean {
    val opts = value(key) as? Map<*, *> ?: return false
    fun has(name: String) = !opts[name].isFalseOrEmpty()
    return when (key) {
        "shadow-tls-opts" -> has("password") || has("version")
        "restls-opts" -> has("password") || has("version-hint") || has("restls-script")
        "jls-opts" -> has("username") || has("password")
        "tlsmirror-opts" -> has("primary-key")
        "ss-opts" -> opts["enabled"].clashBoolean()
        else -> false
    }
}

private fun ClashFields.tlsVariants(keys: List<String>) {
    for (key in keys) {
        val value = value(key) ?: continue
        when {
            value !is Map<*, *> -> ignored(INVALID_DROPPED, key)
            tlsVariantActive(key) -> ignored(NOT_READ, key)
            else -> ignored(INACTIVE, key)
        }
    }
}

// mihomo 实际生效的 packet encoding（0 无、1 packetaddr、2 xudp），见 vmess.go / vless.go 的 NewVmess / NewVless
private fun ClashFields.mihomoPacketEncoding(vless: Boolean): Int {
    var packetAddr = flag("packet-addr")
    var xudp = flag("xudp")
    when (value("packet-encoding")?.toString()) {
        "packetaddr", "packet" -> {
            packetAddr = true
            if (vless) xudp = false
        }

        "xudp" -> xudp = true
        else -> if (vless && !packetAddr) xudp = true
    }
    return if (xudp) 2 else if (packetAddr) 1 else 0
}

private fun ClashFields.v2ray(type: String, bean: StandardV2RayBean): Pair<Set<String>, ClashInactive> {
    val vless = bean is VMessBean && bean.isVLESS
    val vmess = bean is VMessBean && !bean.isVLESS
    val trojan = bean is TrojanBean
    val tlsOn = bean.security == "tls"
    kept("server"); kept("port")
    if (trojan) kept("password") else kept("uuid")
    if (vmess) {
        if (present("alterId")) {
            if (text("alterId")?.toIntOrNull() != null) kept("alterId") else ignored(INVALID_DROPPED, "alterId")
        }
        if (present("cipher")) {
            if (value("cipher") is String) kept("cipher") else ignored(INVALID_DROPPED, "cipher")
        }
    }
    if (vless) {
        when (val flow = value("flow")) {
            null -> Unit
            !is String -> ignored(INVALID_DROPPED, "flow")
            "", StandardV2RayBean.FLOW_VISION -> kept("flow")
            // mihomo 截到 16 个字符后要求恰好是 vision：xtls-rprx-vision-udp443 一样是 vision
            else -> if (flow.startsWith(StandardV2RayBean.FLOW_VISION)) converted(EQUIVALENT_VALUE, "flow")
            // 只是包含 vision（如 foo-xtls-rprx-vision）：mihomo 报错，解析器仍按 vision 导入
            else if (flow.contains(StandardV2RayBean.FLOW_VISION)) ignored(SEMANTICS_DIFFER, "flow")
            else ignored(UNKNOWN_VALUE, "flow")
        }
    }
    if (vmess || vless) {
        val final = bean.packetEncoding ?: 0
        when (value("packet-encoding")) {
            null -> Unit
            "packetaddr", "xudp" -> kept("packet-encoding")
            // mihomo 把 packet 当作 packetaddr，解析器归 0
            "packet" -> ignored(SEMANTICS_DIFFER, "packet-encoding")
            else -> ignored(UNKNOWN_VALUE, "packet-encoding")
        }
        // 两个布尔键解析器不读：结果与 mihomo 一致就没有影响
        val matches = mihomoPacketEncoding(vless) == final
        for (key in listOf("packet-addr", "xudp")) {
            if (present(key)) {
                if (matches) ignored(MATCHES_RESULT, key) else ignored(NOT_READ, key)
            }
        }
    }

    val reality = value("reality-opts")
    val realityKey = !bean.realityPubKey.isNullOrBlank()
    if (!trojan && present("tls")) {
        when {
            flag("tls") == tlsOn -> kept("tls")
            realityKey -> converted(REALITY_IMPLIES_TLS, "tls")
            // reality-opts 没有公钥也会把 TLS 打开
            else -> ignored(SEMANTICS_DIFFER, "tls")
        }
    }
    when {
        reality == null -> Unit
        reality !is Map<*, *> -> ignored(INVALID_DROPPED, "reality-opts")
        reality.isEmpty() -> Unit
        !realityKey -> ignored(REALITY_NO_PUBLIC_KEY, "reality-opts")
        // 解析器按键序处理：写在 reality-opts 后面的 tls: false 把 TLS 关掉，REALITY 随之不起作用
        !tlsOn -> ignored(OVERRIDDEN, "reality-opts")
        else -> {
            kept("reality-opts", "public-key"); kept("reality-opts", "short-id")
            // sing-box 的 REALITY 不提供 X25519MLKEM768（libcore/sing-box/NEKO.md），false 与之相同
            if (present("reality-opts", "support-x25519mlkem768")) {
                if (flag("reality-opts", "support-x25519mlkem768")) ignored(NOT_READ, "reality-opts", "support-x25519mlkem768")
                else ignored(MATCHES_RESULT, "reality-opts", "support-x25519mlkem768")
            }
            open("reality-opts")
        }
    }

    // vmess / vless 的 SNI 键是 servername，trojan 的是 sni；另一个 mihomo 不读，解析器照读，后写的生效
    // （另一个键的记录见 sniAndTransport）
    val sniKey = if (trojan) "sni" else "servername"
    keptIfFinal(bean.sni, sniKey)
    alpnList(bean.alpn)
    kept("skip-cert-verify")
    fingerprint(tlsOn)
    kept("client-fingerprint")

    val network = text("network")
    val activeOpts = network(type, network, tlsOn)
    when (activeOpts) {
        "ws-opts" -> wsOpts(bean)
        "h2-opts" -> h2Opts(bean)
        "http-opts" -> httpOpts(bean)
        "grpc-opts" -> {
            keptIfFinal(bean.path, "grpc-opts", "grpc-service-name")
            open("grpc-opts")
        }
    }
    sniAndTransport(trojan, bean, network, activeOpts, tlsOn, sniKey)

    // smux：只有 enabled 时 mihomo 才套 sing-mux
    if (smuxInactive(listOf("smux")) == null) {
        open("smux")
        // vision 流控不用 mux（构建时丢掉，见 ProxyEntity.singMux）
        if (bean.isVisionFlow) ignored(SEMANTICS_DIFFER, "smux", "enabled") else kept("smux", "enabled")
        kept("smux", "max-streams"); kept("smux", "padding")
        when (value("smux", "protocol")?.toString()) {
            null -> Unit
            "", "h2mux", "smux", "yamux" -> kept("smux", "protocol")
            else -> ignored(UNKNOWN_VALUE, "smux", "protocol")
        }
    }

    // ech-opts：enable 打开时 mihomo 才用，本应用同样只看 enable
    when (val ech = value("ech-opts")) {
        null -> Unit
        !is Map<*, *> -> ignored(INVALID_DROPPED, "ech-opts")
        else -> if (ech["enable"].clashBoolean()) {
            open("ech-opts")
            kept("ech-opts", "enable"); kept("ech-opts", "config")
        }
    }

    tlsVariants(
        when {
            vmess -> listOf("shadow-tls-opts", "restls-opts", "jls-opts", "tlsmirror-opts")
            trojan -> listOf("shadow-tls-opts", "restls-opts", "jls-opts", "ss-opts")
            else -> listOf("shadow-tls-opts", "restls-opts", "jls-opts")
        }
    )
    if (vless) when (text("encryption")) {
        null -> Unit
        // mihomo 的 encryption.NewClient 对空串与 none 都不加密
        "", "none" -> ignored(MATCHES_RESULT, "encryption")
        else -> ignored(NOT_READ, "encryption")
    }

    val table = when {
        vmess -> VMESS_KEYS
        vless -> VLESS_KEYS
        else -> TROJAN_KEYS
    }
    val transportOpts = setOf("ws-opts", "h2-opts", "http-opts", "grpc-opts", "xhttp-opts", "mkcp-opts", "mekya-opts")
    return table to { path ->
        when {
            // 其它 network 的 *-opts：mihomo 不读（xhttp / mkcp / mekya 的 network 整个节点被拒）
            path[0] in transportOpts && path[0] != activeOpts -> INACTIVE
            path.size == 2 && path[0] == "ws-opts" && path[1] == "v2ray-http-upgrade-fast-open" &&
                    !flag("ws-opts", "v2ray-http-upgrade") -> INACTIVE

            path[0] == "ech-opts" && !flag("ech-opts", "enable") -> INACTIVE
            else -> tlsOffInactive(tlsOn)(path)
        }
    }
}

// network 的记录；返回 mihomo 在这个 network 下读的 *-opts 键
private fun ClashFields.network(type: String, network: String?, tlsOn: Boolean): String? {
    if (network == null) return null
    when (network) {
        "", "tcp" -> kept("network")
        "ws", "grpc" -> kept("network")
        // mihomo 的 trojan 只认 ws / grpc，h2 / http 在它那里是裸 TCP，本应用却套上 HTTP 传输
        "h2" -> when {
            type == "trojan" -> ignored(SEMANTICS_DIFFER, "network")
            tlsOn -> converted(EQUIVALENT_VALUE, "network")
            // 本应用不带 TLS 的 http 传输是 HTTP/1.1 伪装头，不是 h2
            else -> ignored(SEMANTICS_DIFFER, "network")
        }

        // mihomo 的 http 是 HTTP/1.1 伪装头（带 TLS 时在 TLS 里），本应用带 TLS 时是 h2
        "http" -> if (type == "trojan" || tlsOn) ignored(SEMANTICS_DIFFER, "network") else kept("network")
    }
    return when (network) {
        "ws" -> "ws-opts"
        "grpc" -> "grpc-opts"
        "h2" -> if (type == "trojan") null else "h2-opts"
        "http" -> if (type == "trojan") null else "http-opts"
        else -> null
    }
}

// 解析器对 ws / h2 / http / grpc 四种 *-opts 一律套用（applyClashTransportOpts），mihomo 只读当前 network 的那个
private val CLASH_PARSED_OPTS = listOf("ws-opts", "h2-opts", "http-opts", "grpc-opts")

// 构建时用到 bean.host / bean.path 的传输方式（buildSingBoxOutboundStreamSettings 与 Xray 生成器），tcp 两个都不用
private val TRANSPORTS_USING_HOST = setOf("ws", "httpupgrade", "http")
private val TRANSPORTS_USING_PATH = setOf("ws", "httpupgrade", "http", "grpc")

// 解析器从一个 *-opts 对象写进 bean 的值：子键的原始路径，写的是 host（否则是 path），写入的值
private class ClashOptsWrite(val path: List<String>, val host: Boolean, val value: String?)

// 与 applyClashTransportOpts 同一套规则，按写入顺序
private fun ClashFields.optsWrites(key: String): List<ClashOptsWrite> {
    val base = path(key) ?: return emptyList()
    val opts = valueAt(base) as? Map<*, *> ?: return emptyList()
    val out = ArrayList<ClashOptsWrite>()
    // Host 头不区分大小写；http-opts 的列表按行拼接，ws-opts 的原样转成字符串
    fun hostHeaders(name: String, headers: Any?, joinList: Boolean) {
        (headers as? Map<*, *>)?.forEach { (header, value) ->
            if (header !is String || header.lowercase() != "host") return@forEach
            val text = if (joinList && value is List<*>) value.mapNotNull { it?.toString() }.joinToString("\n")
            else value?.toString()
            out += ClashOptsWrite(base + name + header, true, text)
        }
    }
    for ((sub, value) in opts) {
        val name = sub as? String ?: continue
        when (key) {
            "ws-opts" -> when (name) {
                "headers" -> hostHeaders(name, value, joinList = false)
                "path" -> out += ClashOptsWrite(base + name, false, value?.toString())
            }

            "h2-opts" -> when (name) {
                "host" -> out += ClashOptsWrite(base + name, true, (value as? List<*>)?.joinToString("\n"))
                "path" -> out += ClashOptsWrite(base + name, false, value?.toString())
            }

            "http-opts" -> when (name) {
                "path" -> out += ClashOptsWrite(base + name, false, (value as? List<*>)?.firstOrNull()?.toString())
                "headers" -> hostHeaders(name, value, joinList = true)
            }

            "grpc-opts" -> if (name == "grpc-service-name") out += ClashOptsWrite(base + name, false, value?.toString())
        }
    }
    return out
}

// 当前 network 的 *-opts 记完之后，再按 mihomo 的实际行为改写几类记录：
// - SNI：本应用的 applyClashFixups 在没写 SNI 时拿第一个 host 补上；mihomo 只有 vmess / vless 的 ws 拿 Host 头，
//   其余用 server。补出来的 SNI 与 mihomo 不同时，提供 host 的子键记有损。
// - 别的 network 的 *-opts：写进了构建会用到的最终 host / path（host 也可能经上一条变成 SNI），且与当前
//   network 的 opts 给出的值不同时，按子键记有损；其余仍按整个对象记无影响。
// - alpn：mihomo 在 vmess / vless 的 ws（http/1.1）、grpc 与 h2（h2），trojan 的 grpc（h2）上把 ALPN 写死，
//   不读 alpn；本应用把它交给核心（sing-box 的传输只在它为空时补同样的默认值）。
// - servername / sni 里 mihomo 不读的那个（vmess / vless 的 sni，trojan 的 servername）：解析器照读，
//   按两边实际用的 SNI 是否相同判断。
private fun ClashFields.sniAndTransport(
    trojan: Boolean, bean: StandardV2RayBean, network: String?, activeOpts: String?, tlsOn: Boolean, sniKey: String,
) {
    val active = activeOpts?.let { optsWrites(it) }.orEmpty()
    val firstHost = bean.host.orEmpty().substringBefore("\n")
    val hostSni = tlsOn && bean.sni.isNullOrBlank() && firstHost.isNotBlank() && !firstHost.isIpAddress()
    val oursSni = bean.sni.blankAsNull() ?: (if (hostSni) firstHost else bean.serverAddress)
    val mihomoSni = text(sniKey).blankAsNull()
        ?: (if (!trojan && network == "ws") active.lastOrNull { it.host }?.value.blankAsNull() else null)
        ?: bean.serverAddress

    if (hostSni && firstHost != mihomoSni) {
        for (write in active) {
            if (write.host && write.value.orEmpty() == bean.host.orEmpty()) {
                recordAt(write.path, ClashFieldResult.IGNORED, SEMANTICS_DIFFER)
            }
        }
    }

    for (key in CLASH_PARSED_OPTS) {
        if (key == activeOpts) continue
        for (write in optsWrites(key)) {
            val used = if (write.host) bean.type in TRANSPORTS_USING_HOST || hostSni else bean.type in TRANSPORTS_USING_PATH
            val final = if (write.host) bean.host else bean.path
            val expected = active.lastOrNull { it.host == write.host }?.value
            if (used && write.value.orEmpty() == final.orEmpty() && write.value.orEmpty() != expected.orEmpty()) {
                recordAt(write.path, ClashFieldResult.IGNORED, SEMANTICS_DIFFER)
            }
        }
    }

    val fixedAlpn = when (network) {
        "ws" -> if (trojan) null else "http/1.1"
        "grpc" -> "h2"
        "h2" -> if (trojan) null else "h2"
        else -> null
    }
    if (fixedAlpn != null && present("alpn")) {
        when {
            !tlsOn -> ignored(INACTIVE, "alpn")
            bean.alpn.isNullOrBlank() || bean.alpn == fixedAlpn -> ignored(MATCHES_RESULT, "alpn")
            else -> ignored(SEMANTICS_DIFFER, "alpn")
        }
    }

    val aliasKey = if (trojan) "servername" else "sni"
    if (present(aliasKey)) {
        val alias = text(aliasKey)
        when {
            // 被后写的 servername / sni 改写了：结果与不写相同
            alias != bean.sni -> ignored(MATCHES_RESULT, aliasKey)
            alias.isNullOrEmpty() && text(sniKey).isNullOrEmpty() -> ignored(DEFAULT_VALUE, aliasKey)
            oursSni == mihomoSni -> ignored(MATCHES_RESULT, aliasKey)
            else -> ignored(SEMANTICS_DIFFER, aliasKey)
        }
    }
}

private fun ClashFields.wsOpts(bean: StandardV2RayBean) {
    val opts = value("ws-opts")
    if (opts !is Map<*, *>) {
        ignored(INVALID_DROPPED, "ws-opts")
        return
    }
    open("ws-opts")
    keptIfFinal(bean.path, "ws-opts", "path")
    when (val headers = value("ws-opts", "headers")) {
        null -> Unit
        !is Map<*, *> -> ignored(INVALID_DROPPED, "ws-opts", "headers")
        else -> {
            open("ws-opts", "headers")
            // 只有 Host 进 bean，其余头按键表记「未导入」
            val base = path("ws-opts", "headers")!!
            for (name in headers.keys) {
                if (name?.toString()?.lowercase() != "host") continue
                val path = base + name.toString()
                val value = valueAt(path) ?: continue
                recordAt(
                    path, if (value.toString() == bean.host) ClashFieldResult.KEPT else ClashFieldResult.IGNORED,
                    if (value.toString() == bean.host) null else OVERRIDDEN,
                )
            }
        }
    }
    if (present("ws-opts", "max-early-data")) {
        if (text("ws-opts", "max-early-data")?.toIntOrNull() != null) kept("ws-opts", "max-early-data")
        else ignored(INVALID_DROPPED, "ws-opts", "max-early-data")
    }
    kept("ws-opts", "early-data-header-name")
    if (present("ws-opts", "v2ray-http-upgrade")) {
        if (flag("ws-opts", "v2ray-http-upgrade")) converted(EQUIVALENT_VALUE, "ws-opts", "v2ray-http-upgrade")
        else kept("ws-opts", "v2ray-http-upgrade")
    }
}

private fun ClashFields.h2Opts(bean: StandardV2RayBean) {
    if (value("h2-opts") !is Map<*, *>) {
        ignored(INVALID_DROPPED, "h2-opts")
        return
    }
    open("h2-opts")
    when (val host = value("h2-opts", "host")) {
        null -> Unit
        // 构建时按行拆回列表，全部交给 sing-box
        is List<*> -> if (host.joinToString("\n") != bean.host) ignored(OVERRIDDEN, "h2-opts", "host")
        else converted(LIST_JOINED, "h2-opts", "host")

        else -> ignored(INVALID_DROPPED, "h2-opts", "host")
    }
    keptIfFinal(bean.path, "h2-opts", "path")
}

private fun ClashFields.httpOpts(bean: StandardV2RayBean) {
    if (value("http-opts") !is Map<*, *>) {
        ignored(INVALID_DROPPED, "http-opts")
        return
    }
    open("http-opts")
    when (val path = value("http-opts", "path")) {
        null -> Unit
        !is List<*> -> ignored(INVALID_DROPPED, "http-opts", "path")
        else -> when {
            path.isEmpty() -> ignored(DEFAULT_VALUE, "http-opts", "path")
            path.first()?.toString() != bean.path -> ignored(OVERRIDDEN, "http-opts", "path")
            // mihomo 每次随机取一个，这里只能带一个
            path.size > 1 -> ignored(FIRST_ITEM_ONLY, "http-opts", "path")
            else -> converted(EQUIVALENT_VALUE, "http-opts", "path")
        }
    }
    // 不带 TLS 时构建写的就是 GET（mihomo 不写 method 时同样是 GET）
    when (text("http-opts", "method")) {
        null -> Unit
        "", "GET" -> ignored(MATCHES_RESULT, "http-opts", "method")
        else -> ignored(NOT_READ, "http-opts", "method")
    }
    when (val headers = value("http-opts", "headers")) {
        null -> Unit
        !is Map<*, *> -> ignored(INVALID_DROPPED, "http-opts", "headers")
        else -> {
            open("http-opts", "headers")
            val base = path("http-opts", "headers")!!
            for (name in headers.keys) {
                if (name?.toString()?.lowercase() != "host") continue
                val path = base + name.toString()
                val value = valueAt(path) ?: continue
                val joined = if (value is List<*>) value.mapNotNull { it?.toString() }.joinToString("\n") else value.toString()
                when {
                    joined != bean.host -> recordAt(path, ClashFieldResult.IGNORED, OVERRIDDEN)
                    value is List<*> -> recordAt(path, ClashFieldResult.CONVERTED, LIST_JOINED)
                    else -> recordAt(path, ClashFieldResult.KEPT, null)
                }
            }
        }
    }
}

private fun ClashFields.anyTLS(bean: AnyTLSBean): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("password"); kept("client-fingerprint"); kept("sni"); kept("skip-cert-verify")
    for (key in listOf("certificate", "private-key")) {
        if (present(key) && !value(key).isFalseOrEmpty()) ignored(MTLS_CLIENT_CERT, key)
    }
    fingerprint(true)
    alpnList(bean.alpn)
    when (val ech = value("ech-opts")) {
        null -> Unit
        !is Map<*, *> -> ignored(INVALID_DROPPED, "ech-opts")
        else -> {
            open("ech-opts")
            kept("ech-opts", "enable")
            when {
                ech["enable"].clashBoolean() -> kept("ech-opts", "config")
                // 没写 enable 时本应用有 config 就开 ECH，mihomo v1.19.32 要 enable 才开
                ech["enable"] == null && !ech["config"].isFalseOrEmpty() -> ignored(SEMANTICS_DIFFER, "ech-opts", "config")
            }
        }
    }
    tlsVariants(listOf("shadow-tls-opts", "restls-opts", "jls-opts"))
    // enable 没打开时 mihomo 不用 ech-opts 的其余子键（ECHOptions.Parse）；没写 enable 的 config 上面已有记录
    return ANYTLS_KEYS to { path ->
        if (path.size == 2 && path[0] == "ech-opts" && path[1] != "enable" && !flag("ech-opts", "enable")) INACTIVE
        else null
    }
}

// mihomo 的 StringToBps（common/utils/mbps.go）：纯数字按 Mbps，否则按「数字 [KMGT]bps / Bps」，结果是每秒字节数
private val CLASH_RATE = Regex("^(\\d+)\\s*([KMGT]?)([Bb])ps$")

private fun mihomoBytesPerSecond(text: String): Long? {
    if (text.isEmpty()) return 0
    // 纯数字改写成「N Mbps」再解析，负数在那里不匹配，得 0
    text.toIntOrNull()?.let { return if (it < 0) 0 else it.toLong() * 125_000 }
    val match = CLASH_RATE.matchEntire(text) ?: return 0
    val unit = when (match.groupValues[2]) {
        "K" -> 1_000L
        "M" -> 1_000_000L
        "G" -> 1_000_000_000L
        "T" -> 1_000_000_000_000L
        else -> 1L
    }
    val n = (match.groupValues[1].toLongOrNull() ?: return null) * unit
    return if (match.groupValues[3] == "b") n / 8 else n
}

private fun ClashFields.rate(key: String, finalMbps: Int?) {
    val text = text(key) ?: return
    val mihomo = mihomoBytesPerSecond(text)
    when {
        mihomo != null && finalMbps != null && finalMbps.toLong() * 125_000 == mihomo -> kept(key)
        // 解析器只取空格前的数字，单位一律当 Mbps
        text.substringBefore(" ").toIntOrNull() != null -> ignored(RATE_UNIT_DROPPED, key)
        else -> ignored(INVALID_DROPPED, key)
    }
}

private fun ClashFields.hysteria(bean: HysteriaBean): Pair<Set<String>, ClashInactive> {
    val v1 = bean.protocolVersion == 1
    kept("server"); kept("sni"); kept("skip-cert-verify"); kept("ca-str")
    fingerprint(true)
    // ports 在时端口跳跃用它，port 两边都不再用
    val hopping = !text("ports").isNullOrBlank()
    if (hopping) ignored(INACTIVE, "port") else kept("port")
    kept("ports")
    rate("up", bean.uploadMbps)
    rate("down", bean.downloadMbps)
    if (present("hop-interval")) {
        if (text("hop-interval")?.toIntOrNull() != null) kept("hop-interval") else ignored(INVALID_DROPPED, "hop-interval")
    }
    if (v1) {
        kept("obfs"); kept("disable-mtu-discovery")
        // mihomo 的 NewHysteria：auth 非空时总是用它（base64 解码），auth-str 不起作用；auth 为空才用 auth-str。
        // 解析器两个都读、后写的生效，所以按最终值判断：与 mihomo 用的那个不同就是被改写了
        val auth = text("auth")
        val authStr = text("auth-str")
        if (!auth.isNullOrEmpty()) {
            if (authStr != null) ignored(INACTIVE, "auth-str")
            if (bean.authPayloadType == HysteriaBean.TYPE_BASE64 && bean.authPayload == auth) kept("auth")
            else ignored(OVERRIDDEN, "auth")
        } else if (authStr != null) {
            if (bean.authPayloadType == HysteriaBean.TYPE_STRING && bean.authPayload == authStr) kept("auth-str")
            else ignored(OVERRIDDEN, "auth-str")
        }
        when (text("protocol")) {
            null -> Unit
            "udp", "faketcp", "wechat-video" -> kept("protocol")
            else -> ignored(UNKNOWN_VALUE, "protocol")
        }
        for (key in listOf("recv-window-conn", "recv-window")) {
            if (present(key)) {
                if (text(key)?.toIntOrNull() != null) kept(key) else ignored(INVALID_DROPPED, key)
            }
        }
        if (present("alpn")) {
            if (value("alpn") is List<*>) converted(LIST_JOINED, "alpn") else ignored(INVALID_DROPPED, "alpn")
        }
    } else {
        kept("password")
        // 构建时只认 salamander，有混淆密码就用它
        val obfs = text("obfs")
        when {
            obfs == null -> Unit
            obfs.isEmpty() -> kept("obfs")
            // 写了 salamander 却没有混淆密码：mihomo 报 missing obfs password，节点起不来；
            // 本应用没有密码就不混淆，导入成能连但不混淆的节点，两边结果不同
            obfs == "salamander" && text("obfs-password").isNullOrEmpty() -> ignored(SEMANTICS_DIFFER, "obfs")
            obfs == "salamander" -> converted(EQUIVALENT_VALUE, "obfs")
            else -> ignored(UNKNOWN_VALUE, "obfs")
        }
        if (present("obfs-password")) {
            // 没写 obfs 时 mihomo 不混淆，本应用有密码就开 salamander
            if (obfs.isNullOrEmpty() && !value("obfs-password").isFalseOrEmpty()) ignored(SEMANTICS_DIFFER, "obfs-password")
            else kept("obfs-password")
        }
        // 构建写死 h3
        when (val alpn = value("alpn")) {
            null -> Unit
            listOf("h3") -> ignored(MATCHES_RESULT, "alpn")
            else -> if (alpn.isFalseOrEmpty()) ignored(DEFAULT_VALUE, "alpn") else ignored(SEMANTICS_DIFFER, "alpn")
        }
    }
    return (if (v1) HYSTERIA_KEYS else HYSTERIA2_KEYS) to { path ->
        if (path[0] == "ech-opts" && !flag("ech-opts", "enable")) INACTIVE else null
    }
}

private fun ClashFields.tuic(bean: TuicBean): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("uuid"); kept("password"); kept("skip-cert-verify")
    kept("disable-sni"); kept("reduce-rtt"); kept("sni"); kept("ca-str"); kept("congestion-controller")
    fingerprint(true)
    alpnList(bean.alpn)
    // ip 是实际拨号的地址，server 退为 SNI（mihomo 同样如此）
    if (!text("ip").isNullOrBlank()) converted(DIAL_ADDRESS, "ip") else ignored(DEFAULT_VALUE, "ip")
    when (text("udp-relay-mode")) {
        null -> Unit
        "native", "quic" -> kept("udp-relay-mode")
        // 其它取值两边都按 native：mihomo 不是 quic 就用 native，构建只认 quic
        else -> ignored(MATCHES_RESULT, "udp-relay-mode")
    }
    // mihomo 是毫秒，sing-box 是秒
    text("heartbeat-interval")?.let { text ->
        val ms = text.toLongOrNull()
        when {
            ms == null -> ignored(INVALID_DROPPED, "heartbeat-interval")
            // 不大于 0 时两边都用默认的 10 秒（解析结果是 0，构建不写）
            ms <= 0 -> ignored(MATCHES_RESULT, "heartbeat-interval")
            ms % 1000 == 0L -> converted(UNIT_CONVERTED, "heartbeat-interval")
            else -> ignored(ROUNDED, "heartbeat-interval")
        }
    }
    // mihomo 的 fast-open 是 TCP Fast Open，TUIC v5 走 UDP，不起作用（解析器存进 fastConnect，构建也不读）
    if (present("fast-open")) {
        if (flag("fast-open")) ignored(INACTIVE, "fast-open") else ignored(DEFAULT_VALUE, "fast-open")
    }
    return TUIC_KEYS to { path ->
        if (path[0] == "ech-opts" && !flag("ech-opts", "enable")) INACTIVE else null
    }
}

private fun ClashFields.wireGuard(bean: WireGuardBean): Pair<Set<String>, ClashInactive> {
    kept("server"); kept("port"); kept("ip"); kept("ipv6"); kept("private-key"); kept("public-key")
    kept("pre-shared-key")
    for (key in listOf("mtu", "persistent-keepalive")) {
        if (present(key)) {
            if (text(key)?.toIntOrNull() != null) kept(key) else ignored(INVALID_DROPPED, key)
        }
    }
    when (val reserved = value("reserved")) {
        null -> Unit
        is List<*> -> converted(LIST_JOINED, "reserved")
        // 字符串：mihomo 按 base64 解码成字节，本应用要逗号分隔的三个数字（不是就在构建时报错）
        else -> if (reserved.toString().isEmpty()) ignored(DEFAULT_VALUE, "reserved") else ignored(SEMANTICS_DIFFER, "reserved")
    }
    if (present("allowed-ips")) {
        when {
            // 不是合法的 CIDR 列表，导入时清空（回到放行全部）
            bean.peerAllowedIps.isNullOrEmpty() && !value("allowed-ips").isFalseOrEmpty() ->
                ignored(INVALID_DROPPED, "allowed-ips")

            value("allowed-ips") is List<*> -> converted(LIST_JOINED, "allowed-ips")
            else -> kept("allowed-ips")
        }
    }
    if (present("remote-dns-resolve")) {
        if (flag("remote-dns-resolve")) ignored(NOT_SUPPORTED, "remote-dns-resolve")
        else ignored(DEFAULT_VALUE, "remote-dns-resolve")
    }
    if (present("amnezia-wg-option") && !value("amnezia-wg-option").isFalseOrEmpty()) {
        ignored(NOT_SUPPORTED, "amnezia-wg-option")
    }
    return WIREGUARD_KEYS to { path ->
        when {
            path.size == 1 && path[0] in CLASH_NO_EFFECT_WIREGUARD -> CLASH_NO_EFFECT_WIREGUARD[path[0]]
            // mihomo 只在 remote-dns-resolve 打开时用 dns
            path[0] == "dns" && !flag("remote-dns-resolve") -> INACTIVE
            else -> null
        }
    }
}
