package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.shownSingBoxValue
import java.util.Locale

// StandardV2RayBean.type 认哪些值、别名怎么映射，只在这个文件里定义。
// 导入（分享链接、Clash 订阅、通用链接）与构建（sing-box、Xray）都经这里归一化：
// 不认识的值抛 UnsupportedTransportException，不再静默按 TCP 运行。
// XHTTP（旧名 splithttp，统一归一为 xhttp）只有 Xray 能跑，而且带 host / path / mode / extra 四项参数：
// 能携带这些参数的入口（标准链接、v2rayN JSON、通用链接、Clash 的 VLESS）读完四项再收下；
// 带不了的旧格式入口必须用 requireV2RayTransportWithoutXhttp，否则会导入出参数全丢的节点。

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
val V2RAY_TRANSPORTS = setOf("tcp", "http", "ws", "quic", "grpc", "httpupgrade", "xhttp")

private val V2RAY_TRANSPORT_ALIASES = mapOf(
    // Xray 新版对 tcp 的叫法，分享链接里会出现 type=raw
    "raw" to "tcp",
    // Kitsunebi 链接的 obfs=none
    "none" to "tcp",
    // v2rayN / 标准链接对 h2 的写法
    "h2" to "http",
    // Kitsunebi 链接的 obfs=websocket
    "websocket" to "ws",
    // XHTTP 的旧名（Xray 1.x 时期叫 SplitHTTP）；我们从不导出这个名字，导入一律归一为 xhttp
    "splithttp" to "xhttp",
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

// 给携带不了 XHTTP 参数的入口用：老的 vmess 标准链接、Kitsunebi、Quantumult 的 CSV 行。
// xhttp（含别名 splithttp）按不支持拒绝，抛的异常和未知值一样，批量导入照常逐条跳过
fun requireV2RayTransportWithoutXhttp(value: String?): String {
    val name = requireV2RayTransport(value)
    if (name == "xhttp") throw UnsupportedTransportException(value)
    return name
}

// 新入口给 xhttp 节点的 path 规范成非空：空 path 在存储里表示「参数已丢失」（见 xhttpParametersLost）
fun normalizedXhttpPath(path: String?): String = if (path.isNullOrBlank()) "/" else path

// 旧版本（Kryo 8 之前）只存了 xhttp 的 type，host / path / mode / extra 从未落库；所有新入口都把 path 规范成非空，
// 所以 xhttp 节点的 path 为空就是旧版本留下的残缺节点，参数无从还原，不猜测、不迁移。
// 构建、保存与 sn:// 导入遇到它就报错，让用户重新导入或更新订阅
fun StandardV2RayBean.xhttpParametersLost(): Boolean =
    v2rayTransportOrNull(type) == "xhttp" && path.isNullOrBlank()

// 残缺的 xhttp 节点。继承 UnsupportedTransportException，批量导入照常逐条跳过
class XhttpParametersLostException :
    UnsupportedTransportException("xhttp", "xhttp parameters were lost; re-import the node or update the subscription")

// Clash（mihomo）节点的 network 字段只认 mihomo 的写法：不写或 tcp 是 TCP，
// h2 / http 都落到 http，ws / grpc 原样；其余拒绝。xhttp 在这里也是拒绝：mihomo 只有 VLESS 认 xhttp，
// 由 VLESS 的调用方（RawUpdaterClash 的 clashV2RayTransport）先行处理，VMess 走到这里就被拒；Trojan 不经这里（其它值按 TCP）。
// httpupgrade 在 Clash 里写作 ws-opts 的 v2ray-http-upgrade，不经这里
private val CLASH_NETWORKS = setOf("tcp", "http", "h2", "ws", "grpc")

fun clashNetworkTransport(network: String?): String {
    if (network.isNullOrBlank()) return "tcp"
    if (network !in CLASH_NETWORKS) throw UnsupportedTransportException(network)
    return requireV2RayTransport(network)
}
