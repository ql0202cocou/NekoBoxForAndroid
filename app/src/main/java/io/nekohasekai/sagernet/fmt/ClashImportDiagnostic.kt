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

// 字段结果的原因（封闭集合，英文短语）。lossy 只对 IGNORED 有意义：false 表示在本应用里本来就没有
// 对应行为（无影响），不计入「有损」
enum class ClashFieldReason(val text: String, val lossy: Boolean = true) {
    INVALID_DROPPED("invalid value, dropped"),
    MTLS_CLIENT_CERT("mTLS client certificate is not supported"),
    NOT_SUPPORTED("not supported"),
}

// 一个字段的结果。path 已按显示规则处理；shownValue 只有 CLASH_ENUM_PATHS 里的键才有
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
    UNSUPPORTED_TRANSPORT("unsupported transport"),
    UNSUPPORTED_SS_PLUGIN("unsupported shadowsocks plugin"),
    TUIC_V4("TUIC v4 (token) is not supported"),

    // 兜底：detail 是异常类名
    OTHER("parse error"),
}

// proxies 列表里一个条目的结果。index 是条目在列表里的位置（从 0 起）；type 已按显示规则处理；
// name 是节点名原文，只给界面用，不进日志
sealed class ClashNodeResult {
    abstract val index: Int
    abstract val type: String
    abstract val name: String?

    data class Imported(
        override val index: Int,
        override val type: String,
        override val name: String?,
        val fields: List<ClashFieldRecord>,
    ) : ClashNodeResult()

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
        val description: String
            get() = buildString {
                append(failure.text)
                if (path != null) append(" at ").append(path)
                if (shownValue != null) append(" = ").append(shownValue)
                if (detail != null) append(" (").append(detail).append(")")
            }
    }
}
