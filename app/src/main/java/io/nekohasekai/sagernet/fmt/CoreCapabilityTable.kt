package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

// 能力表本体与组合规则（从 CoreCapabilities.kt 拆出的纯数据，行为不变）。升级核心时的复核清单仍在 CoreCapabilities.kt 顶部。

// 本表的声明都没有查到上游的起始版本，一律记核实时的版本
private fun verifiedAt(core: DialCore) = Since(core.version, verifiedOnly = true)

// ---- 能力表

private fun supported(core: DialCore, note: String? = null) = CapabilityCell.Supported(verifiedAt(core), note)

private fun unsupported(core: DialCore, conflict: CoreConflict): CapabilityCell.Unsupported {
    check(conflict.core == null || conflict.core == core) { "$conflict 不属于 $core" }
    return CapabilityCell.Unsupported(verifiedAt(core), conflict)
}

private fun values(
    core: DialCore,
    names: CoreNames,
    outside: CoreConflict,
    note: String? = null,
    shownOutside: CoreNames? = null,
) = CapabilityCell.SupportedValues(verifiedAt(core), names, outside, note, shownOutside)

private val NA = CapabilityCell.NotApplicable

private fun row(sb: CapabilityCell, xray: CapabilityCell) =
    mapOf(DialCore.SING_BOX to sb, DialCore.XRAY to xray)

private val SB = DialCore.SING_BOX
private val XR = DialCore.XRAY

// 每一行都必须给两个核心各一格（CoreCapabilitiesTest 检查）。Xray 不承载 AnyTLS：
// 协议行已经报了冲突的格子写 NotApplicable
val CAPABILITY_TABLE: Map<Requirement, Map<DialCore, CapabilityCell>> = linkedMapOf(
    Requirement.PROTOCOL_VMESS to row(supported(SB), supported(XR)),
    Requirement.PROTOCOL_VLESS to row(supported(SB), supported(XR)),
    // D10：Xray 的 Trojan 出站经 run -test 与回环验证；生成器是 buildXrayOutbound 的 Trojan 重载
    Requirement.PROTOCOL_TROJAN to row(
        supported(SB), supported(XR, "Xray 的 Trojan 出站（servers 只放一项、不写 flow）"),
    ),
    // Xray 没有 anytls 出站（实测 unknown config id: anytls）
    Requirement.PROTOCOL_ANYTLS to row(supported(SB), unsupported(XR, CoreConflict.XRAY_ANYTLS)),

    Requirement.ANYTLS_LOCAL_SOCKS_OVERRIDE to row(
        unsupported(SB, CoreConflict.SING_BOX_ANYTLS_LOCAL_SOCKS_OVERRIDE), NA,
    ),
    Requirement.ANYTLS_CERTIFICATE_POLICY_INVALID to row(
        unsupported(SB, CoreConflict.ANYTLS_CERTIFICATE_POLICY_INVALID), NA,
    ),
    // 兼容出站拦下的四个名字见 fingerprint.go；除它们外，名单外的取值不回显
    Requirement.ANYTLS_UTLS to row(
        values(
            SB, ANYTLS_COMPAT_UTLS_FINGERPRINTS, CoreConflict.SING_BOX_ANYTLS_UTLS,
            shownOutside = ANYTLS_COMPAT_UTLS_FINGERPRINTS_BROKEN,
        ),
        NA,
    ),

    // 传输：按两个核心的配置解析与应用生成器逐项核实
    Requirement.TRANSPORT_TCP to row(supported(SB), supported(XR)),
    Requirement.TRANSPORT_HTTP_HEADER to row(
        supported(SB, "HTTP/1.1 传输：只发 Host 头、要求回 200（见已知差异 HTTP_HEADER_CAMOUFLAGE）"),
        supported(XR, "tcp + header.type = http"),
    ),
    Requirement.TRANSPORT_H2 to row(supported(SB), unsupported(XR, CoreConflict.XRAY_TRANSPORT_H2)),
    Requirement.TRANSPORT_WS to row(supported(SB), supported(XR)),
    Requirement.TRANSPORT_QUIC to row(
        supported(SB, "必须带 TLS，不能配 uTLS 指纹或 REALITY（见组合规则）"),
        unsupported(XR, CoreConflict.XRAY_TRANSPORT_QUIC),
    ),
    Requirement.TRANSPORT_GRPC to row(supported(SB, "lite 实现，路径固定为 /<service_name>/Tun"), supported(XR)),
    Requirement.TRANSPORT_HTTPUPGRADE to row(supported(SB), supported(XR)),
    // K2：Xray 的 xhttp（旧名 splithttp）；vendored sing-box 没有这个传输（libcore/sing-box 的 option/v2ray_transport.go 与
    // transport/v2ray 都没有 xhttp / splithttp）。host / path / mode / extra 在 StandardV2RayBean；与 Vision、Mux.Cool 的冲突见组合规则
    Requirement.TRANSPORT_XHTTP to row(
        unsupported(SB, CoreConflict.SING_BOX_TRANSPORT_XHTTP),
        supported(XR, "xhttpSettings（host / path / mode / extra）；REALITY 可用"),
    ),
    // 参数已丢失的残缺节点：不拿它匹配核心，两个核心都报同一条与核心无关的冲突（要求重新导入）
    Requirement.TRANSPORT_XHTTP_LOST to row(
        unsupported(SB, CoreConflict.XHTTP_PARAMETERS_LOST),
        unsupported(XR, CoreConflict.XHTTP_PARAMETERS_LOST),
    ),
    Requirement.TRANSPORT_UNKNOWN to row(
        unsupported(SB, CoreConflict.TRANSPORT_UNKNOWN), unsupported(XR, CoreConflict.TRANSPORT_UNKNOWN),
    ),

    // 安全层：AnyTLS 总是 TLS
    Requirement.SECURITY_TLS to row(supported(SB), supported(XR)),
    Requirement.SECURITY_REALITY to row(
        supported(SB, "固定自报客户端版本 1.8.1，去掉 X25519MLKEM768；不能配 ECH"),
        supported(XR, "只能配 tcp / grpc / xhttp"),
    ),
    Requirement.MLDSA65_VERIFY to row(
        unsupported(SB, CoreConflict.SING_BOX_MLDSA65_VERIFY), supported(XR),
    ),
    // sing-box 只有 SPKI 固定。若给 sing-box 补整证书固定补丁，改这一格
    Requirement.CERTIFICATE_PIN to row(
        unsupported(SB, CoreConflict.SING_BOX_CERTIFICATE_PIN),
        supported(XR, "pinnedPeerCertSha256；REALITY 下不写（见已知差异）"),
    ),
    Requirement.CUSTOM_CA to row(
        supported(SB, "只信任这些证书"),
        supported(XR, "追加到 Xray 内置根证书包（v26.10.10 起；此前追加到系统根证书）"),
    ),
    // Xray 自 v26.6.22 起 run -test 无条件拒绝 allowInsecure（写 false 不报错）
    Requirement.ALLOW_INSECURE to row(supported(SB), unsupported(XR, CoreConflict.XRAY_ALLOW_INSECURE)),
    Requirement.UTLS_FINGERPRINT to row(
        values(SB, SING_BOX_UTLS_FINGERPRINTS, CoreConflict.SING_BOX_UTLS_FINGERPRINT),
        values(XR, XRAY_UTLS_FINGERPRINTS, CoreConflict.XRAY_UTLS_FINGERPRINT),
    ),
    Requirement.ECH_INLINE to row(
        supported(SB, "转成 PEM"), supported(XR, "echConfigList（标准 base64）；REALITY 下不写"),
    ),
    Requirement.ECH_AUTO_QUERY to row(
        supported(SB, "经 sing-box 自己的 DNS 路由查 HTTPS 记录"),
        // 二进制能用查询写法表达，但必须给查询服务器，且查询由 Xray 进程直接发出
        unsupported(XR, CoreConflict.XRAY_ECH_AUTO_QUERY),
    ),
    Requirement.MUX_SING_MUX to row(supported(SB), unsupported(XR, CoreConflict.XRAY_SING_MUX)),
    Requirement.MUX_COOL to row(unsupported(SB, CoreConflict.SING_BOX_MUX_COOL), supported(XR)),
    Requirement.MUX_UNKNOWN to row(
        unsupported(SB, CoreConflict.MUX_TYPE_UNKNOWN), unsupported(XR, CoreConflict.MUX_TYPE_UNKNOWN),
    ),
    Requirement.PACKET_ENCODING_PACKETADDR to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_PACKETADDR),
    ),
    Requirement.PACKET_ENCODING_XUDP to row(
        supported(SB), supported(XR, "mux.xudpConcurrency，TCP 不复用（concurrency -1）"),
    ),
    Requirement.PACKET_ENCODING_UNKNOWN to row(
        unsupported(SB, CoreConflict.PACKET_ENCODING_UNKNOWN), unsupported(XR, CoreConflict.PACKET_ENCODING_UNKNOWN),
    ),
    Requirement.VLESS_FLOW to row(
        values(SB, SING_BOX_VLESS_FLOWS, CoreConflict.SING_BOX_VLESS_FLOW),
        values(XR, XRAY_VLESS_FLOWS, CoreConflict.XRAY_VLESS_FLOW),
    ),
    // sing-box：transport/v2raywebsocket/conn.go 头名为空时 early data 拼在路径后；Xray：dialer.go 只放进
    // Sec-WebSocket-Protocol 头，生成器把上限写成 ?ed=N
    Requirement.WS_EARLY_DATA_PATH to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_WS_EARLY_DATA_PATH),
    ),
    Requirement.WS_EARLY_DATA_HEADER to row(
        supported(SB, "任意头名"),
        values(XR, XRAY_WS_EARLY_DATA_HEADERS, CoreConflict.XRAY_WS_EARLY_DATA_HEADER),
    ),
)

// Xray 的 REALITY 分支不写这些字段（XrayConfig.kt；REALITY 不验证服务端证书，ECH 与 REALITY 互斥），
// 在 Xray 上对 REALITY 节点不算要求。见已知差异 XRAY_REALITY_IGNORES_PIN_ECH
val XRAY_REALITY_IGNORED: Set<Requirement> = setOf(
    Requirement.CERTIFICATE_PIN,
    Requirement.ECH_INLINE,
    Requirement.ECH_AUTO_QUERY,
)

// 组合规则：单项都支持、组合起来不行的情形
class CombinationRule(
    val core: DialCore,
    val conflict: CoreConflict,
    val since: Since,
    val description: String,
    val applies: (CoreRequirements) -> Boolean,
)

val COMBINATION_RULES: List<CombinationRule> = listOf(
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_ANYTLS_ECH, verifiedAt(DialCore.SING_BOX),
        "内部 AnyTLS 只接受确定可用的 ECH 指纹组合",
    ) {
        !it.nativeAnyTlsOverrides && it.protocol == CoreProtocol.ANYTLS && it.ech != null &&
            it.utlsFingerprint != null && !ANYTLS_COMPAT_ECH_FINGERPRINTS.accepts(it.utlsFingerprint)
    },
    // Xray 配置检查实测：REALITY only supports RAW, XHTTP and gRPC。h2、quic 在传输行已报；
    // xhttp 在允许之列（K2），所以这条只挡 ws / httpupgrade
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_REALITY_TRANSPORT, verifiedAt(DialCore.XRAY),
        "REALITY + ws / httpupgrade",
    ) { it.tls == TlsMode.REALITY && it.transport in setOf(CoreTransport.WS, CoreTransport.HTTPUPGRADE) },
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_REALITY_UTLS_FINGERPRINT, verifiedAt(DialCore.XRAY),
        "REALITY + fingerprint unsafe / hellogolang",
    ) { it.tls == TlsMode.REALITY && XRAY_REALITY_FORBIDDEN_FINGERPRINTS.accepts(it.utlsFingerprint) },
    // sing-box 的 common/tls/utls_client.go「Reality is conflict with ECH」，加载配置时报错
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_REALITY_ECH, verifiedAt(DialCore.SING_BOX),
        "REALITY + ECH",
    ) { it.tls == TlsMode.REALITY && it.ech != null },
    // sing-box 的 transport/v2ray/transport.go，quic 不带 TLS 加载报 TLS required
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_WITHOUT_TLS, verifiedAt(DialCore.SING_BOX),
        "quic 不带 TLS",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.NONE },
    // sing-box 的 QUIC 取 STDConfig()，uTLS 与 REALITY 的 STDConfig() 直接报错，每次拨号都失败
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_REALITY, verifiedAt(DialCore.SING_BOX),
        "quic + REALITY",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.REALITY },
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_UTLS, verifiedAt(DialCore.SING_BOX),
        "quic + uTLS 指纹（非 REALITY）",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.TLS && it.utlsFingerprint != null },
    // K2：Vision 流控要求出站拿到的是 TLS / REALITY 的直连（proxy/vless/outbound/outbound.go：「XTLS only supports TLS and
    // REALITY directly for now.」），XHTTP 在 HTTP/2、HTTP/3 的请求流上跑，到不了这个条件，每次连接都失败
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_XHTTP_VISION, verifiedAt(DialCore.XRAY),
        "xhttp + Vision 流控（xtls-rprx-vision、xtls-rprx-vision-udp443）",
    ) { it.transport == CoreTransport.XHTTP && it.vlessFlow?.startsWith(StandardV2RayBean.FLOW_VISION) == true },
    // K2：XHTTP 自带多路复用（xmux），Xray 的文档与新版服务端不接受 Mux.Cool 与 XHTTP 同开（只接受纯 XUDP）。
    // 仅用 XUDP 包编码（packetEncoding = 2）不算 Mux.Cool：生成器写的是 xudpConcurrency，TCP 不复用。
    // Vision 流控下 mux 本来就不生效（CoreRequirements 的 mux 为 null），不会和上一条重复报
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_XHTTP_MUX_COOL, verifiedAt(DialCore.XRAY),
        "xhttp + Mux.Cool",
    ) { it.transport == CoreTransport.XHTTP && it.mux?.family == MuxFamily.MUX_COOL },
    // Vision 流控的其余传输（v26.10.21 回环实测，experiments/core-probe/xray/vision/ 的 VT-*）：客户端出站要求底层
    // 直接是 TLS / REALITY（同上「XTLS only supports TLS and REALITY directly for now.」），ws、grpc（含 REALITY）、httpupgrade、
    // tcp 的 http 头伪装（带 TLS 也不行）与不带 TLS 的 tcp 每次连接都失败，run -test 都不报错。xhttp、h2、quic、未知传输各有
    // 自己的冲突，这里不重复报。UDP 由客户端发出、Xray 服务端拒收（服务端入站同样的检查），不单列
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_VISION_TRANSPORT, verifiedAt(DialCore.XRAY),
        "Vision 流控 + RAW 以外的传输（ws / grpc / httpupgrade / http 头伪装）或不带 TLS",
    ) {
        it.vlessFlow?.startsWith(StandardV2RayBean.FLOW_VISION) == true && it.transport !in VISION_OWN_TRANSPORT_CONFLICTS &&
            it.transport != CoreTransport.H2 && it.transport != CoreTransport.QUIC &&
            (it.transport != CoreTransport.TCP || it.tls == TlsMode.NONE)
    },
    // sing-vmess 的 NewVisionConn 只认 TLS 连接本身（「vision: not a valid supported TLS connection: <类型>」），有传输层时拿到的是
    // 传输层的连接：ws、grpc（含 REALITY）、h2、quic、http 头伪装与不带 TLS 的 tcp 在 Vision 初始化时就失败，TCP 与 XUDP 的 UDP
    // 都一样（同上 VT-*，h2 / quic / http 头伪装对的是 sing-box 服务端，去掉 flow 即通）。httpupgrade 升级后就是 TLS 连接本身，
    // 客户端能用，对 sing-box 服务端实测能通，只有 Xray 服务端拒收（服务端的事，不算 sing-box 的缺口）。xtls-rprx-vision-udp443
    // 本来就是 SING_BOX_VLESS_FLOW，这里只看 xtls-rprx-vision
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_VISION_TRANSPORT, verifiedAt(DialCore.SING_BOX),
        "Vision 流控 + RAW / httpupgrade 以外的传输或不带 TLS",
    ) {
        it.vlessFlow == StandardV2RayBean.FLOW_VISION && it.transport !in VISION_OWN_TRANSPORT_CONFLICTS &&
            (it.tls == TlsMode.NONE || it.transport !in setOf(CoreTransport.TCP, CoreTransport.HTTPUPGRADE))
    },
)

// 这些传输各有自己的冲突（XRAY_XHTTP_VISION / SING_BOX_TRANSPORT_XHTTP、XHTTP_PARAMETERS_LOST、TRANSPORT_UNKNOWN），Vision 的两条不再重复报
private val VISION_OWN_TRANSPORT_CONFLICTS = setOf(CoreTransport.XHTTP, CoreTransport.XHTTP_LOST, CoreTransport.UNKNOWN)
