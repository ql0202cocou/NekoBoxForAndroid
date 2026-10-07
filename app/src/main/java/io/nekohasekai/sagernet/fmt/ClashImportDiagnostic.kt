package io.nekohasekai.sagernet.fmt

// Clash（mihomo）订阅导入的诊断。解析只记录，不碰界面、日志与文案；
// 日志与提示由调用方从这些结果生成（与 ConfigBuildDiagnostic 同一做法）。
// 诊断里不留原始值：字段路径与可显示的值在记录时就按下面的显示规则处理过

// 可以原样显示的值 / 键名：整串匹配这个正则（与 UnsupportedSingBoxValueException 一致），
// 否则显示占位串，不透露长度
private val CLASH_SHOWABLE = Regex("^[A-Za-z0-9._+-]{1,64}$")
const val CLASH_VALUE_NOT_SHOWN = "(value not shown)"
const val CLASH_KEY_NOT_SHOWN = "(key not shown)"

// 只有这些键的值可以出现在诊断文本里（字段路径），其它值一律不出现
val CLASH_ENUM_PATHS = setOf(
    "type", "network", "cipher", "plugin", "plugin-opts.mode", "packet-encoding", "protocol",
    "smux.protocol", "flow", "client-fingerprint", "udp-relay-mode", "congestion-controller",
    "ip-version", "alpn",
)

// 只对某些 type 可以显示值的键，且只显示列出的取值，其它值一律是占位串：hysteria2（hy2）的 obfs 是混淆类型名，
// 但用户可能把口令误写进去（与 hysteria v1 混淆），所以只显示已知的类型名；hysteria（v1）的 obfs 是混淆口令，不显示
private val HYSTERIA2_OBFS_TYPES = setOf("salamander", "gecko")
val CLASH_ENUM_PATHS_BY_TYPE: Map<String, Map<String, Set<String>>> = mapOf(
    "hysteria2" to mapOf("obfs" to HYSTERIA2_OBFS_TYPES),
    "hy2" to mapOf("obfs" to HYSTERIA2_OBFS_TYPES),
)

// 字段路径的值在诊断文本里显示成什么；null 表示这个路径不带值。type 是节点的 type
// （不知道时为 null，只按 CLASH_ENUM_PATHS 判断）
fun clashShownValueAt(type: String?, path: String, value: Any?): String? {
    if (path in CLASH_ENUM_PATHS) return clashShownValue(value)
    val allowed = type?.let { CLASH_ENUM_PATHS_BY_TYPE[it]?.get(path) } ?: return null
    return if (value?.toString() in allowed) clashShownValue(value) else CLASH_VALUE_NOT_SHOWN
}

fun clashShownValue(value: Any?): String {
    // 列表（alpn）逐项判断，任何一项不能显示就整体不显示
    val items = if (value is List<*>) value.map { it?.toString() ?: "" } else listOf(value?.toString() ?: "")
    return if (items.all { CLASH_SHOWABLE.matches(it) }) items.joinToString(",") else CLASH_VALUE_NOT_SHOWN
}

fun clashShownKey(key: Any?): String =
    key?.toString()?.takeIf { CLASH_SHOWABLE.matches(it) } ?: CLASH_KEY_NOT_SHOWN

// 字段路径：每段都按键名规则显示（headers 下的头名由用户决定）
fun clashShownPath(segments: List<Any?>): String = segments.joinToString(".") { clashShownKey(it) }

enum class ClashFieldResult { KEPT, CONVERTED, IGNORED, REJECTED }

// 字段结果的原因（封闭集合，英文短语）。lossy 只对 IGNORED 有意义：false 表示这个键在本应用与 mihomo 里
// 结果一样（本来就没有对应行为、在 mihomo 里也不起作用、或取的是默认值），汇总里单列，不计入「有损」
enum class ClashFieldReason(val text: String, val lossy: Boolean = true) {
    // CONVERTED：表示法不同但语义等价
    EQUIVALENT_VALUE("equivalent value"),
    LIST_JOINED("list kept as lines"),
    UNIT_CONVERTED("converted to seconds"),
    REALITY_IMPLIES_TLS("REALITY implies TLS"),
    DIAL_ADDRESS("used as the dial address"),

    // IGNORED，有损
    NOT_READ("not imported"),
    INVALID_DROPPED("invalid value, dropped"),
    UNKNOWN_VALUE("unknown value, default used"),
    OVERRIDDEN("overridden by another key"),
    FIRST_ITEM_ONLY("only the first item imported"),
    ROUNDED("rounded to whole seconds"),
    RATE_UNIT_DROPPED("rate unit not converted"),
    SEMANTICS_DIFFER("applied differently than mihomo"),
    MTLS_CLIENT_CERT("mTLS client certificate is not supported"),
    NOT_SUPPORTED("not supported"),
    NOT_CLASSIFIED("fields not classified"),

    // IGNORED，无影响：随节点而定的四种
    UNKNOWN_KEY("not a mihomo option for this type", false),
    INACTIVE("inactive for this node in mihomo", false),
    DEFAULT_VALUE("empty or false, same as absent", false),
    MATCHES_RESULT("same result as without it", false),

    // IGNORED，无影响：CLASH_NO_EFFECT_KEYS 名单里的键，各自的理由
    NO_UDP_SWITCH("UDP use follows the protocol here", false),
    NO_TCP_FAST_OPEN("no per-node TCP Fast Open here", false),
    NO_MPTCP("no per-node MPTCP here", false),
    NO_INTERFACE_NAME("outbound interface is managed by the VPN service", false),
    NO_ROUTING_MARK("routing mark is managed by the VPN service", false),
    NO_IP_VERSION("address family follows the global IPv6 setting", false),
    NO_WIREGUARD_WORKERS("mihomo worker count, no counterpart", false),
    NO_WIREGUARD_IP_STACK("mihomo user-space stack choice, no counterpart", false),
}

// 一个字段的结果。path 已按显示规则处理；shownValue 只有 CLASH_ENUM_PATHS / CLASH_ENUM_PATHS_BY_TYPE 里的键才有
data class ClashFieldRecord(
    val path: String,
    val result: ClashFieldResult,
    val reason: ClashFieldReason? = null,
    val shownValue: String? = null,
) {
    val lossy get() = result == ClashFieldResult.IGNORED && reason?.lossy != false
}

// 已知类型的节点被整体拒绝的原因（封闭集合）。不透传异常原文：NumberFormatException 之类会带出用户的值
enum class ClashNodeFailure(val text: String) {
    ENTRY_NOT_MAP("entry is not a map"),
    MISSING_TYPE("missing type"),
    MISSING_SERVER("missing server"),
    MISSING_PORT("missing port"),
    INVALID_PORT("invalid port"),
    INVALID_VALUE("invalid value"),
    UNSUPPORTED_TRANSPORT("unsupported transport"),
    UNSUPPORTED_SS_PLUGIN("unsupported shadowsocks plugin"),
    TUIC_V4("TUIC v4 (token) is not supported"),

    // 本应用没有对应实现或读不到：照常导入只会少一道安全校验或永远连不上（clashRejection）
    CERT_FINGERPRINT_INVALID("fingerprint is not a SHA-256 certificate digest"),
    REALITY_PUBLIC_KEY_MISSING("REALITY options without public-key"),
    VLESS_ENCRYPTION("VLESS encryption is not supported"),
    TLS_VARIANT("TLS variant is not supported"),
    EXTRA_ENCRYPTION("extra encryption layer is not supported"),
    REALITY_WITHOUT_TLS("REALITY with TLS turned off"),
    SECURITY_SETTING_NOT_READ("security setting not read"),
    TLS_NOT_SUPPORTED("TLS is not supported for this type"),
    CERT_PIN_NOT_SUPPORTED("certificate pinning is not supported"),
    OBFS_NOT_SUPPORTED("obfs type is not supported"),

    // 兜底：detail 是异常类名
    OTHER("parse error"),
}

// proxies 列表里一个条目的结果。index 是条目在列表里的位置（从 0 起）；type 已按显示规则处理；
// name 是节点名原文，只给界面用，不进日志
sealed class ClashNodeResult {
    abstract val index: Int
    abstract val type: String
    abstract val name: String?

    // fields 按输入里的键序列出每个非空键（含嵌套对象的子键）的结果；有损字段为空即完整导入
    data class Imported(
        override val index: Int,
        override val type: String,
        override val name: String?,
        val fields: List<ClashFieldRecord>,
    ) : ClashNodeResult() {
        val lossy get() = fields.any { it.lossy }
    }

    data class UnknownType(
        override val index: Int,
        override val type: String,
        override val name: String?,
    ) : ClashNodeResult()

    // path / shownValue 指出导致拒绝的字段（可空）；detail 只在 OTHER 时是异常类名
    data class Failed(
        override val index: Int,
        override val type: String,
        override val name: String?,
        val failure: ClashNodeFailure,
        val path: String? = null,
        val shownValue: String? = null,
        val detail: String? = null,
    ) : ClashNodeResult() {
        // 导致拒绝的字段（REJECTED）；整个条目的问题（不是 map、缺 type、端点）没有字段
        val field: ClashFieldRecord?
            get() = path?.let { ClashFieldRecord(it, ClashFieldResult.REJECTED, null, shownValue) }

        val description: String
            get() = buildString {
                append(failure.text)
                if (path != null) append(" at ").append(path)
                if (shownValue != null) append(" = ").append(shownValue)
                if (detail != null) append(" (").append(detail).append(")")
            }
    }
}
