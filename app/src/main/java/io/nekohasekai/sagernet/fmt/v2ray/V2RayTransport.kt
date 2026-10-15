package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.shownSingBoxValue
import java.util.Locale

// StandardV2RayBean.type 认哪些值、别名怎么映射，只在这个文件里定义。
// 导入（分享链接、Clash 订阅、通用链接）与构建（sing-box、Xray）都经这里归一化：
// 不认识的值抛 UnsupportedTransportException，不再静默按 TCP 运行。
// XHTTP / SplitHTTP 尚未支持（vendored sing-box 没有这种传输），按未知值拒绝。

// 值来自远端订阅，消息会进界面与可导出的日志：只有形态合格的才回显（规则同 shownSingBoxValue）
open class UnsupportedTransportException(
    val transport: String?,
    message: String = "unsupported transport ${shownSingBoxValue(transport)}",
) : IllegalArgumentException(message)

// 分享链接的 security（VMess JSON 的 tls 字段）取值不认识。继承 UnsupportedTransportException 是有意的：
// 所有批量导入路径（parseProxies 逐条跳过、ZIP 逐条目、订阅整体）与格式回退链已按它处理，
// 一条被拒只记原因、不拖垮其余条目；取值回显规则同上
class UnsupportedSecurityException(val security: String?) :
    UnsupportedTransportException(security, "unsupported security ${shownSingBoxValue(security)}")

// 已知的 security 取值：none（明确不加密，空值同义）、tls、reality。
// 返回规范小写名（"" 表示未指定）；xtls 等未知值抛 UnsupportedSecurityException，
// 不能按无 TLS 导入让用户以为有加密、实际走明文
fun requireV2RaySecurity(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return when (val name = value.lowercase(Locale.ROOT)) {
        "none", "tls", "reality" -> name
        else -> throw UnsupportedSecurityException(value)
    }
}

// 规范名。http 在 TLS 上是 h2，不带 TLS 时是 tcp 的伪 HTTP 头
val V2RAY_TRANSPORTS = setOf("tcp", "http", "ws", "quic", "grpc", "httpupgrade")

private val V2RAY_TRANSPORT_ALIASES = mapOf(
    // Xray 新版对 tcp 的叫法，分享链接里会出现 type=raw
    "raw" to "tcp",
    // Kitsunebi 链接的 obfs=none
    "none" to "tcp",
    // v2rayN / 标准链接对 h2 的写法
    "h2" to "http",
    // Kitsunebi 链接的 obfs=websocket
    "websocket" to "ws",
)

// 归一化成规范名，不认识返回 null。空值按 TCP（各格式缺省都是 TCP）；
// 不区分大小写，与 initializeDefaultValues 的 lowercase 一致
fun v2rayTransportOrNull(value: String?): String? {
    if (value.isNullOrBlank()) return "tcp"
    val name = value.lowercase(Locale.ROOT)
    if (name in V2RAY_TRANSPORTS) return name
    return V2RAY_TRANSPORT_ALIASES[name]
}

fun requireV2RayTransport(value: String?): String =
    v2rayTransportOrNull(value) ?: throw UnsupportedTransportException(value)

// Clash（mihomo）节点的 network 字段只认 mihomo 的写法：不写或 tcp 是 TCP，
// h2 / http 都落到 http，ws / grpc 原样；其余（xhttp 等）拒绝。
// httpupgrade 在 Clash 里写作 ws-opts 的 v2ray-http-upgrade，不经这里
private val CLASH_NETWORKS = setOf("tcp", "http", "h2", "ws", "grpc")

fun clashNetworkTransport(network: String?): String {
    if (network.isNullOrBlank()) return "tcp"
    if (network !in CLASH_NETWORKS) throw UnsupportedTransportException(network)
    return requireV2RayTransport(network)
}
