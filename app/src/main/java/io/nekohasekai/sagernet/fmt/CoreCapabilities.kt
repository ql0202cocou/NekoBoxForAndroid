package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY

// K1 能力表：两个能替节点拨号的核心，各自对节点每项要求的声明，以及组合规则。
// 选核（CoreSelection.kt）只读这里；节点的要求由 CoreRequirements.kt 提取。
//
// 核实依据是固定版本源码、run -test 与真实核心回环；可复用工具在 experiments/core-probe/。
// 当前行为及源码位置直接写在本表、组合规则、已知差异和相关测试中。
// 测试配置生成成功不等于核心回环通过；MISMATCH / ANY 的判读边界见工具 README。
// sing-box 以 vendored libcore/sing-box、NEKO.md 与 libcore/go.mod 为准；生成器以 buildSingBoxOutbound*、
// buildXrayOutbound 及其测试为准。uTLS 指纹名单、VLESS flow、ws early data 的上游源码位置写在各常量上。
//
// 升级任何一个核心都要回到这里逐条复核：DialCore.version 与 buildScript/lib/plugins.sh、vendored sing-box 的版本
// 由单测绑定，不改表测试就不过（CoreCapabilitiesTest）。
//
// 升级核心时必须重测的条目（用 run -test 与本机回环验证，结论写回本表、组合规则、已知差异
// CoreKnownDifferences.kt 与相关生成器）：
//
// Xray：
//  1. Trojan 出站的写法：servers 与扁平写法、写 flow 被拒绝、只许一个 server、弃用警告不影响退出码。
//  2. REALITY 能用哪些传输（现在是 tcp / grpc / xhttp）及其报错原文；h2、quic、http 已移除时的报错原文。
//  3. realitySettings 客户端字段的校验：publicKey / password、shortId、fingerprint 的禁用名与默认值、mldsa65Verify
//     的长度、spiderX；客户端上报的版本号。服务端（v26.9.8 起）要求 ClientHello 带 X25519MLKEM768 且排在 X25519
//     之前，minClientVer 不能放宽：用新旧服务端 × 指纹的分进程矩阵验证（客户端、服务端各一个进程）。
//     v26.10.31 / v26.11.9 的分进程矩阵已复核默认、chrome、133 / 155 指纹；旧客户端不识别 155，其他组合均可连。
//  4. allowInsecure：v26.6.22 起无条件拒绝（不再看设备时钟），原文「… migrated to "pinnedPeerCertSha256"(pcs) and
//     "verifyPeerCertByName"(vcn). …」；写 false 不报错。
//  5. pinnedPeerCertSha256 的格式（十六进制、冒号、逗号）与匹配语义（命中叶子跳过校验；命中 IsCA 的链上证书按
//     serverName 与有效期校验；其余不算命中），用回环验证；旧的固定字段是否仍被静默忽略。
//  6. certificates 是追加到信任库还是替换；run -test 是否解析 PEM。v26.9.30 前的底座是 Go 的系统根（「追加」已实测：
//     certificates 只放另一张 CA，用 SSL_CERT_FILE 把服务端证书的 CA 放进 Go 的系统根时能通，加 disableSystemRoot
//     或不设 SSL_CERT_FILE 时不通）；v26.10.10 起默认底座是 Xray 内置的根证书包，tlsSettings.useSystemCa 为真才用
//     系统库（加载失败也退回内置包），certificates 追加在所选池的副本上，disableSystemRoot 仍是只信任自带证书。
//     v26.10.10 起的根池来源切换按源码确认，未用 SSL_CERT_FILE 专门重跑新旧默认信任集合的握手对照。
//  7. fingerprint 的名单（XRAY_UTLS_FINGERPRINTS）、空值的默认（现在是 chrome，v26.11.9 为 Chrome 155）、不认识的名字是否报错；uTLS 下
//     alpn 是否生效：预设模板盖掉用户的 alpn，randomized 系列用用户写的 alpn；ws 外层缺省 http/1.1，写
//     h2,http/1.1 时保留。
//  8. echConfigList 支持的形式；echForceQuery 已删（写了被静默忽略），查询失败一律不通（用拒绝连接的本机 DoH
//     地址验证）；查询从哪里发出（Xray 进程自己）。
//  9. MuxConfig 的字段与缺省值、handler 的分派顺序。v26.11.9 的 handler 把 concurrency 0–128 都按 128，负数仍关闭；
//     TCP / XUDP 的单连接累计复用上限从 128 改成 1024，xudpConcurrency 不抬高；xudpProxyUDP443 缺省 reject，生成器写 allow。
//     上游 #7375 已将最低 128 限于 XDRIVE，但不在 v26.11.9 内；升级包含该修复的版本时复核 XRAY_MUX_CONCURRENCY。
// 10. VMess / VLESS 的 UDP 默认走 cone XUDP；vision 与 mux 同开时的处理。
// 11. 未知 JSON 键是否仍被静默忽略（XRAY_JSON_STRICT=true 也不拒绝）。键名大小写不敏感，只有拼错的键才丢，
//     所以生成器里每个键名的拼写要靠测试保证。
// 12. run -test 的退出码与「Failed to start: … with tag out-N > …」的报错格式（ExternalCoreStartup.kt 依赖它）。
//     JSON 解码错误由 Go 1.27 改成「Config.outbounds.<下标>.…」（下标把首位的 block 也算在内），仍没有 tag。
//     v26.10.17 起 run 只接受 .json / .jsonc / .yml / .yaml / .toml / .pb / .protobuf 扩展名的配置文件（本应用写 .json），
//     v26.10.31 起按传入的文件名判断，不再解析符号链接。
// 13. 公网明文禁令（v26.7.11 起）：不带 TLS 的 VLESS（没有 VLESS Encryption）/ Trojan 出站，服务端地址不算私有时
//     run -test 报错。v26.10.31 起 IP 字面量只看 IsPrivate / IsLoopback / IsLinkLocalUnicast（127/8、::1、RFC 1918、
//     fc00::/7、链路本地），203.0.113.0/24、198.51.100.0/24 等文档地址段与 100.64.0.0/10 都算公网；域名仍查私有域名名单
//     （localhost、无点域名、.internal）。本应用的 Xray 跳一律拨 127.0.0.1，确认仍不触发。
// 14. socks 入站的 UDP ASSOCIATE 语义（K0b）：入站端口本身是否收 UDP；每次通过认证的 ASSOCIATE 是否另开临时端口、
//     只收声明的来源 IP、由第一个包锁定来源端口、随 TCP 控制连接关闭（v26.9.30 都是）。用回环逐项验证，并对照
//     sing 的 SOCKS5 客户端在 ASSOCIATE 里写的地址。
// 15. 顶层新键（env 改进程环境变量、geodata 会定时下载文件）：生成器不写，ExternalCoreInvariants 的 XRAY_TOP 把
//     它们列为禁止项；再有新的顶层键同样列进去。
//
// sing-box（vendored，libcore/sing-box）：
// 16. common/tls/reality_client.go 的 REALITY 客户端：自报的客户端版本（SessionId 前三字节，现在固定 1.8.1）与
//     去掉 X25519MLKEM768 的过滤是否还在、「reality verification failed」原文是否不变；对照最新 Xray 服务端对
//     ClientHello 的要求（第 3 条），复核 NEKO.md「Accepted upstream behavior」的 REALITY 一条与测速提示
//     （withRealityHint）。libcore/reality_client_canary_test.go 在这些内容变化时先红。顺带看它有没有加 mldsa65Verify
//     （抗量子校验，现在只有 Xray 有，能力表按 Requirement 记）。
// 17. common/tls/utls_client.go 的 uTLS 指纹名单（uTLSClientHelloID，SING_BOX_UTLS_FINGERPRINTS），区分大小写、
//     名单外加载配置时报错这两点是否不变。
// 18. SingBoxValueSets.kt 的四个名单（Shadowsocks 加密方式与插件名、VMess 加密方式、TUIC 拥塞控制）：升级 vendored
//     sing-box 或 sing-shadowsocks2 / sing-vmess / sing-quic 时逐项对照上游源码复核（取值、大小写、空串的处理），
//     改名单与 SING_BOX_VALUE_SETS_VERSION / SING_BOX_VALUE_SET_MODULES。CoreCapabilitiesTest 的版本绑定会先红。
//
// Clash 订阅导入（K3a）：
// 19. 对照 mihomo 更新时核对 group/ClashImportFields.kt 的键表（adapter/outbound/*.go 的 proxy 标签）、clashRejection 的
//     生效条件（各 *Options.Parse 返回 nil 的条件）与 CLASH_NO_EFFECT_KEYS。
//
// XHTTP（K2）：
// 20. Xray 的 SplitHTTPConfig（infra/conf/transport_method.go）：客户端键是否有增减，fmt/v2ray/XhttpExtra.kt 的白名单与
//     数值上限按它重对；mode 的取值（auto / packet-up / stream-up / stream-one）、extra 存在时是否仍只保留外层的
//     host / path / mode、headers 不许含 host、xmux 里 maxConnections 与 maxConcurrency 互斥是否不变。
//     XHTTP 与 Vision 流控（proxy/vless/outbound 要求 TLS / REALITY 直连）、与 Mux.Cool（新版服务端只接受纯 XUDP）的
//     冲突是否仍成立；REALITY 是否仍能配 xhttp；单值 ALPN h3 的行为。sing-box 一侧：vendored 版本有了 XHTTP 传输时，
//     改 TRANSPORT_XHTTP 一行与 SING_BOX_TRANSPORT_XHTTP。
//     v26.10.31 新增的 h2Params 只有服务端的 ListenXH 读，客户端写了不生效（XL-98 / 99），白名单不收，extra 里写了按未知键拒绝；
//     同版本服务端把不带 Access-Control-Request-Method 的 OPTIONS 当上行（XL-96 / 97），uplinkHTTPMethod 的校验本来就放行。
//     v26.11.9 已按源码复核并补齐这四条回环；TLS / REALITY 客户端连错误类型的 h2Params 也忽略，OPTIONS 上行在明文 / TLS 下可用。
//     v26.10.17 的回环实测（experiments/core-probe/xray/xhttp/，v26.10.21、v26.10.31、v26.11.9 重跑相同）：Vision 每次连接都报「XTLS only supports TLS and
//     REALITY directly for now」；Mux.Cool 的 TCP 子连接被服务端拒（common/mux: unexpected network TCP），XUDP 承载的
//     UDP 正常；单值 h3 的客户端改拨 UDP（QUIC），经 TCP+UDP 转发入口可用、只转发 TCP 的入口不通 —— 映射入站
//     （ConfigBuilder.addMappingInbound）没有 network 限制，两者都收，所以 h3 不记冲突；将来若给映射入站加 network 限制，
//     必须重新判定。客户端 mode=auto 的实际选择：无 TLS 与 TLS 为 packet-up，REALITY 为 stream-one；Xray 服务端若固定
//     了 mode，客户端要显式写同一个 mode（auto 解析成 packet-up，服务端固定 stream-up / stream-one 会拒）。
//     host 头：host 为空时 Xray 在无 TLS 下取拨号地址（经映射是 127.0.0.1），TLS / REALITY 下取 serverName；IPv6 字面量必须带
//     方括号，裸写会让客户端构造不出请求。
//
// Vision 流控的传输（XRAY_VISION_TRANSPORT / SING_BOX_VISION_TRANSPORT）：
// 21. Xray 的 proxy/vless/outbound（与 inbound）是否仍只认直接的 TLS / REALITY 连接（VLESS Encryption 例外，本应用不支持）；
//     sing-vmess 的 NewVisionConn 是否仍只认 TLS 连接本身（tlsRegistry），httpupgrade 是否仍把 TLS 连接原样交出。用
//     experiments/core-probe/xray/vision/ 的 gen-transports.py + run-transports.sh 验证；v26.11.9 / 1.14.4-neko-2 为 27 条。
//     VT-11 的旧 PASS 预期已按 v26.10.31 / v26.11.9 差分修正：sing-box 的 REALITY 缺少 X25519MLKEM768，两个 Xray 服务端均拒绝；
//     VT-27 同核对照证明 sing-box 的 RAW + REALITY + Vision 可用。此跨核边界不作为 Vision 传输冲突。
//     Xray 客户端在 ws / grpc / httpupgrade / http 伪装 / 不带 TLS 上每次连接都失败，UDP 由 Xray 服务端拒收；sing-box 在 ws / grpc /
//     h2 / quic / http 伪装 / 不带 TLS 上初始化 Vision 失败，httpupgrade 对 sing-box 服务端能通、对 Xray 服务端被拒。

// 能替节点拨号的核心。value 与 ProxyEntity.CORE_* 相同；version 是这张表核实时的版本
enum class DialCore(val value: Int, val displayName: String, val version: String) {
    SING_BOX(CORE_SING_BOX, "sing-box", "1.14.4"),
    XRAY(CORE_XRAY, "Xray", "v26.11.9");

    companion object {
        fun of(value: Int): DialCore? = entries.firstOrNull { it.value == value }
    }
}

// 核心版本号拆成整数比较；v 前缀与 -neko-N 之类的后缀不参与
object CoreVersion {
    fun parse(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toInt() }

    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}

// 一条声明（能力或限制）从哪个核心版本起成立。verifiedOnly 为真表示没查到起始版本，填的是核实时的版本（「核实于」）
class Since(val version: String, val verifiedOnly: Boolean) {
    override fun toString() = if (verifiedOnly) "核实于 $version" else version
}

// 不支持的性质
enum class GapKind {
    // 核心本身做不到，换生成器也表达不了
    CORE,

    // 核心做得到，本应用的生成器没有实现这项转换（以后可以补）
    GENERATOR,

    // 两个核心对同一字段含义不同：生成器会把它写成该核心里的另一种行为，不能当作等价
    SEMANTICS,

    // 不属于上面三种：取值本身非法，或手动核心值不能用于这个协议
    INVALID_VALUE,
}

// 节点对核心的一项要求，即能力表的一行。value 型的行（uTLS 指纹、flow、early data 头名、mux 协议）带取值
enum class Requirement(val label: String, val fields: List<ProfileField>, val isProtocol: Boolean = false) {
    PROTOCOL_VMESS("协议 VMess", listOf(ProfileField.CORE), isProtocol = true),
    PROTOCOL_VLESS("协议 VLESS", listOf(ProfileField.CORE), isProtocol = true),
    PROTOCOL_TROJAN("协议 Trojan", listOf(ProfileField.CORE), isProtocol = true),
    PROTOCOL_ANYTLS("协议 AnyTLS", listOf(ProfileField.CORE), isProtocol = true),
    TRANSPORT_TCP("传输 tcp", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_HTTP_HEADER("传输 http 不带 TLS（tcp 伪 HTTP 头）", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_H2("传输 http 带 TLS（h2）", listOf(ProfileField.TRANSPORT, ProfileField.SECURITY)),
    TRANSPORT_WS("传输 ws", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_QUIC("传输 quic", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_GRPC("传输 grpc", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_HTTPUPGRADE("传输 httpupgrade", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_XHTTP("传输 xhttp（旧名 splithttp）", listOf(ProfileField.TRANSPORT)),
    TRANSPORT_XHTTP_LOST(
        "传输 xhttp：参数已丢失（旧版本只存了 type，path 为空）",
        listOf(ProfileField.TRANSPORT, ProfileField.PATH),
    ),
    TRANSPORT_UNKNOWN("传输：本应用不认识的值", listOf(ProfileField.TRANSPORT)),
    SECURITY_TLS("TLS", listOf(ProfileField.SECURITY)),
    SECURITY_REALITY("REALITY", listOf(ProfileField.SECURITY, ProfileField.REALITY_PUBLIC_KEY)),
    MLDSA65_VERIFY("REALITY mldsa65Verify", listOf(ProfileField.MLDSA65_VERIFY)),
    CERTIFICATE_PIN("整证书 SHA-256 固定（certificateFingerprint）", listOf(ProfileField.CERTIFICATE_FINGERPRINT)),
    CUSTOM_CA("自定义 CA（certificates，非 REALITY）", listOf(ProfileField.CERTIFICATES)),
    ANYTLS_LOCAL_SOCKS_OVERRIDE("AnyTLS 本地 SOCKS 出站覆写", listOf(ProfileField.CUSTOM_OUTBOUND_JSON)),
    ANYTLS_CERTIFICATE_POLICY_INVALID("AnyTLS 证书策略无效", listOf(ProfileField.CERTIFICATE_POLICY)),
    ALLOW_INSECURE(
        "生效的 allowInsecure（节点或全局，非 REALITY、没有证书指纹）",
        listOf(ProfileField.ALLOW_INSECURE, ProfileField.GLOBAL_ALLOW_INSECURE),
    ),
    UTLS_FINGERPRINT("uTLS 指纹（取值）", listOf(ProfileField.UTLS_FINGERPRINT)),
    ANYTLS_UTLS("AnyTLS uTLS 指纹", listOf(ProfileField.UTLS_FINGERPRINT)),
    ECH_INLINE("ECH：内联配置", listOf(ProfileField.ENABLE_ECH, ProfileField.ECH_CONFIG)),
    ECH_AUTO_QUERY("ECH：没填配置，自动查询", listOf(ProfileField.ENABLE_ECH, ProfileField.ECH_CONFIG)),
    MUX_SING_MUX("mux 生效且为 sing-mux（h2mux / smux / yamux）", listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE)),
    MUX_COOL("mux 生效且为 Mux.Cool", listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE)),
    MUX_UNKNOWN("mux 生效、muxType 不认识", listOf(ProfileField.MUX_TYPE)),
    PACKET_ENCODING_PACKETADDR("packet encoding：packetaddr（VMess / VLESS）", listOf(ProfileField.PACKET_ENCODING)),
    PACKET_ENCODING_XUDP("packet encoding：xudp（VMess / VLESS）", listOf(ProfileField.PACKET_ENCODING)),
    PACKET_ENCODING_UNKNOWN("packet encoding：不认识的值", listOf(ProfileField.PACKET_ENCODING)),
    VLESS_FLOW("VLESS flow（取值）", listOf(ProfileField.ENCRYPTION)),
    WS_EARLY_DATA_PATH(
        "ws early data，没有头名（sing-box 拼在路径里）",
        listOf(ProfileField.WS_MAX_EARLY_DATA, ProfileField.EARLY_DATA_HEADER_NAME),
    ),
    WS_EARLY_DATA_HEADER("ws early data，放在指定的头里（取值：头名）", listOf(ProfileField.EARLY_DATA_HEADER_NAME)),
}

// 一组可接受的取值
class CoreNames(val names: Set<String>, val ignoreCase: Boolean) {
    fun accepts(value: String?): Boolean {
        if (value == null) return false
        return if (ignoreCase) names.any { it.equals(value, ignoreCase = true) } else value in names
    }
}

// 能力表的一格：某核心对某项要求的声明
sealed class CapabilityCell {
    // 支持；note 写换算方式或限制（限制本身在组合规则或已知差异里）
    class Supported(val since: Since, val note: String? = null) : CapabilityCell()

    // 只支持 names 里的取值，之外报 outside。shownOutside 非空时，outside 冲突只回显其中的名字：
    // 这一行的取值只该是枚举名，其余的多半是误填进来的凭据，不进诊断
    class SupportedValues(
        val since: Since,
        val names: CoreNames,
        val outside: CoreConflict,
        val note: String? = null,
        val shownOutside: CoreNames? = null,
    ) : CapabilityCell()

    class Unsupported(val since: Since, val conflict: CoreConflict) : CapabilityCell()

    // 这个核心承载不了该协议（协议行已报冲突），这一行无从谈起
    object NotApplicable : CapabilityCell()
}

// 某核心承载这个节点时的全部冲突，空表示可以完整承载。只用于能选核的协议
fun coreConflicts(core: DialCore, requirements: CoreRequirements): List<Conflict> {
    require(requirements.protocol != CoreProtocol.OTHER) { "协议 ${requirements.type} 不能选核" }
    val items = requirements.items()
    val protocolItem = items.first { it.requirement.isProtocol }
    val protocolCell = CAPABILITY_TABLE.getValue(protocolItem.requirement).getValue(core)
    if (protocolCell is CapabilityCell.Unsupported) return listOf(Conflict(core, protocolCell.conflict))
    val conflicts = ArrayList<Conflict>()
    for (item in items) {
        if (item === protocolItem) continue
        if (core == DialCore.XRAY && requirements.tls == TlsMode.REALITY && item.requirement in XRAY_REALITY_IGNORED) {
            continue
        }
        // 按名单判断用原值，记进冲突的取值先过 shownConflictValue
        val shown = shownConflictValue(item.value)
        when (val cell = CAPABILITY_TABLE.getValue(item.requirement).getValue(core)) {
            is CapabilityCell.Supported -> Unit
            is CapabilityCell.SupportedValues -> if (!cell.names.accepts(item.value)) {
                val value = if (cell.shownOutside?.accepts(item.value) == false) CONFLICT_VALUE_NOT_SHOWN else shown
                conflicts += Conflict(core, cell.outside, value)
            }

            is CapabilityCell.Unsupported -> conflicts += Conflict(core, cell.conflict, shown)
            CapabilityCell.NotApplicable -> error("能力表缺少 ${item.requirement} 在 $core 上的声明")
        }
    }
    for (rule in COMBINATION_RULES) {
        if (rule.core == core && rule.applies(requirements)) conflicts += Conflict(core, rule.conflict)
    }
    return conflicts
}

// 冲突里的取值会进编辑器对话框、构建失败诊断与可导出的日志。uTLS 指纹、early data 头名等可由导入链接、订阅原样
// 写成任意字符串（含换行，畸形链接还可能把密码错放进来），所以规则同 shownSingBoxValue：形态合格的原样保留，其余
// 换成占位串，不暴露长度。显示时取值外面会再套一层括号，占位串就是去掉括号的 VALUE_NOT_SHOWN
internal val CONFLICT_VALUE_NOT_SHOWN = VALUE_NOT_SHOWN.removeSurrounding("(", ")")

internal fun shownConflictValue(value: String?): String? = when {
    value == null -> null
    SHOWABLE_VALUE.matches(value) -> value
    else -> CONFLICT_VALUE_NOT_SHOWN
}

// 一条冲突：哪个核心（null 表示与核心无关）、哪一种、触发它的取值（已按 shownConflictValue 处理）
data class Conflict(val core: DialCore?, val id: CoreConflict, val value: String? = null) {
    val fields: List<ProfileField> get() = id.fields

    // 面向用户的一句英文说明；界面层有中文文案时按 id 映射
    fun message(): String = buildString {
        core?.let { append(it.displayName).append(": ") }
        append(id.reason)
        value?.let { append(" (").append(it).append(')') }
    }
}
