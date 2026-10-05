package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY

// K1 能力表（plan.md「选核策略（完整能力匹配）」）：三个能替节点拨号的核心，各自对节点每项要求的声明，以及组合规则。
// 选核（CoreSelection.kt）只读这里；节点的要求由 CoreRequirements.kt 提取。
//
// 依据（调查报告在 scratchpad/k1/reports/，证据编号沿用报告）：
// - Xray：I1（v26.3.27 源码 S-X*、run -test 实测 T-*、本机回环 L-*）；
// - mihomo：I1（v1.19.31 源码 S-M*、-t 实测 T-M*、回环 L-MA*）；
// - sing-box：I2b（vendored libcore/sing-box，1.14.2 + 1.14.2-neko-1）；
// - 生成器：I2b 的逐字段真值表（buildSingBoxOutbound*、buildXrayOutbound、buildMihomoProxy）。
// uTLS 指纹名单、VLESS flow、ws early data 另读了上游固定 tag 的源码，位置写在各常量上。
//
// 升级任何一个核心都要回到这里逐条复核：DialCore.version 与 buildScript/lib/plugins.sh、vendored sing-box 的版本
// 由单测绑定，不改表测试就不过（CoreCapabilitiesTest）。

// 能替节点拨号的核心。value 与 ProxyEntity.CORE_* 相同；version 是这张表核实时的版本
enum class DialCore(val value: Int, val displayName: String, val version: String) {
    SING_BOX(CORE_SING_BOX, "sing-box", "1.14.2"),
    XRAY(CORE_XRAY, "Xray", "v26.3.27"),
    MIHOMO(CORE_MIHOMO, "mihomo", "v1.19.31");

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

// 本表的声明都没有查到上游的起始版本，一律记核实时的版本
private fun verifiedAt(core: DialCore) = Since(core.version, verifiedOnly = true)

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

// 冲突对应的编辑器字段。key 是 bean 字段名，与编辑器 preference 的 key 相同（全局「允许不安全」是设置项的 key）
enum class ProfileField(val key: String) {
    CORE("profileCore"),
    TRANSPORT("type"),
    HOST("host"),
    PATH("path"),
    SECURITY("security"),
    ALPN("alpn"),
    REALITY_PUBLIC_KEY("realityPubKey"),
    MLDSA65_VERIFY("realityMldsa65Verify"),
    CERTIFICATE_FINGERPRINT("certificateFingerprint"),
    CERTIFICATES("certificates"),
    ALLOW_INSECURE("allowInsecure"),
    GLOBAL_ALLOW_INSECURE("globalAllowInsecure"),
    UTLS_FINGERPRINT("utlsFingerprint"),
    ENABLE_ECH("enableECH"),
    ECH_CONFIG("echConfig"),
    ENABLE_MUX("enableMux"),
    MUX_TYPE("muxType"),
    MUX_PADDING("muxPadding"),
    PACKET_ENCODING("packetEncoding"),

    // VMess 的加密方式，VLESS 的 flow
    ENCRYPTION("encryption"),
    ALTER_ID("alterId"),
    WS_MAX_EARLY_DATA("wsMaxEarlyData"),
    EARLY_DATA_HEADER_NAME("earlyDataHeaderName"),
    CUSTOM_OUTBOUND_JSON("customOutboundJson"),
}

// 冲突的稳定标识，界面层按它映射中英文文案。core 为 null 表示与核心无关（取值非法、不能选核的协议）。
// reason 是给用户看的一句英文原因
enum class CoreConflict(
    val core: DialCore?,
    val kind: GapKind,
    val fields: List<ProfileField>,
    val reason: String,
) {
    // ---- 协议
    XRAY_ANYTLS(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.CORE),
        "Xray has no AnyTLS outbound",
    ),
    MIHOMO_V2RAY_PROTOCOL(
        DialCore.MIHOMO, GapKind.GENERATOR, listOf(ProfileField.CORE),
        "This app cannot run VMess, VLESS or Trojan on mihomo",
    ),

    // ---- 传输
    XRAY_TRANSPORT_H2(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.SECURITY),
        "Xray removed the HTTP/2 (h2) transport",
    ),
    XRAY_TRANSPORT_QUIC(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.TRANSPORT),
        "Xray removed the QUIC transport",
    ),
    XRAY_REALITY_TRANSPORT(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.REALITY_PUBLIC_KEY),
        "Xray only runs REALITY over TCP or gRPC",
    ),
    SING_BOX_QUIC_WITHOUT_TLS(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.SECURITY),
        "sing-box requires TLS for the QUIC transport",
    ),
    SING_BOX_QUIC_UTLS(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.UTLS_FINGERPRINT),
        "sing-box cannot use a uTLS fingerprint over QUIC: every connection fails",
    ),
    SING_BOX_QUIC_REALITY(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.REALITY_PUBLIC_KEY),
        "sing-box cannot run REALITY over QUIC: every connection fails",
    ),

    // ---- TLS / REALITY
    SING_BOX_MLDSA65_VERIFY(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.MLDSA65_VERIFY),
        "sing-box cannot verify REALITY mldsa65Verify",
    ),
    SING_BOX_CERTIFICATE_PIN(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.CERTIFICATE_FINGERPRINT),
        "sing-box cannot pin a whole-certificate SHA-256 (it only pins public keys)",
    ),
    SING_BOX_REALITY_ECH(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.ENABLE_ECH, ProfileField.REALITY_PUBLIC_KEY),
        "sing-box cannot combine REALITY with ECH",
    ),
    XRAY_ALLOW_INSECURE(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.ALLOW_INSECURE, ProfileField.GLOBAL_ALLOW_INSECURE),
        "Xray removed allowInsecure; pin the certificate fingerprint instead",
    ),
    XRAY_ECH_AUTO_QUERY(
        DialCore.XRAY, GapKind.GENERATOR, listOf(ProfileField.ENABLE_ECH, ProfileField.ECH_CONFIG),
        "This app cannot make Xray look up the ECH config; fill in the ECH config",
    ),
    SING_BOX_UTLS_FINGERPRINT(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.UTLS_FINGERPRINT),
        "sing-box does not know this uTLS fingerprint",
    ),
    XRAY_UTLS_FINGERPRINT(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.UTLS_FINGERPRINT),
        "Xray does not know this uTLS fingerprint",
    ),
    XRAY_REALITY_UTLS_FINGERPRINT(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.UTLS_FINGERPRINT, ProfileField.REALITY_PUBLIC_KEY),
        "Xray REALITY needs a uTLS fingerprint (not unsafe or hellogolang)",
    ),
    MIHOMO_UTLS_FINGERPRINT(
        DialCore.MIHOMO, GapKind.CORE, listOf(ProfileField.UTLS_FINGERPRINT),
        "mihomo does not know this uTLS fingerprint and would silently use plain Go TLS",
    ),

    // ---- mux
    SING_BOX_MUX_COOL(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE),
        "sing-box has no Mux.Cool (Xray's multiplexing)",
    ),
    XRAY_SING_MUX(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE),
        "Xray only has Mux.Cool, not sing-mux (h2mux / smux / yamux)",
    ),

    // ---- UDP
    XRAY_PACKETADDR(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.PACKET_ENCODING),
        "Xray has no packetaddr packet encoding",
    ),

    // ---- VLESS flow
    SING_BOX_VLESS_FLOW(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.ENCRYPTION),
        "sing-box only supports the xtls-rprx-vision flow",
    ),
    XRAY_VLESS_FLOW(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.ENCRYPTION),
        "Xray does not support this VLESS flow",
    ),

    // ---- WebSocket early data
    XRAY_WS_EARLY_DATA_PATH(
        DialCore.XRAY, GapKind.SEMANTICS, listOf(ProfileField.WS_MAX_EARLY_DATA, ProfileField.EARLY_DATA_HEADER_NAME),
        "Without a header name, early data goes in the WebSocket path on sing-box but in the " +
            "Sec-WebSocket-Protocol header on Xray; set the header name to Sec-WebSocket-Protocol",
    ),
    XRAY_WS_EARLY_DATA_HEADER(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.EARLY_DATA_HEADER_NAME),
        "Xray only sends early data in the Sec-WebSocket-Protocol header",
    ),

    // ---- 取值非法（与核心无关）
    TRANSPORT_UNKNOWN(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.TRANSPORT),
        "This app does not support the transport",
    ),
    MUX_TYPE_UNKNOWN(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.MUX_TYPE),
        "Unknown mux type",
    ),
    PACKET_ENCODING_UNKNOWN(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.PACKET_ENCODING),
        "Unknown packet encoding",
    ),
    CORE_VALUE_UNKNOWN(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.CORE),
        "Unknown core",
    ),
    CORE_NOT_SELECTABLE(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.CORE),
        "The core cannot be chosen for this protocol",
    ),

    // ---- 不能选核的协议：沿用现有的两条跨协议检查
    PROTOCOL_CERTIFICATE_PIN(
        null, GapKind.CORE, listOf(ProfileField.CERTIFICATE_FINGERPRINT),
        "The core for this protocol cannot pin certificates",
    ),
    PROTOCOL_MLDSA65_VERIFY(
        null, GapKind.CORE, listOf(ProfileField.MLDSA65_VERIFY),
        "Only Xray supports REALITY mldsa65Verify, and this protocol cannot use Xray",
    ),
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
    TRANSPORT_UNKNOWN("传输：本应用不认识的值", listOf(ProfileField.TRANSPORT)),
    SECURITY_TLS("TLS", listOf(ProfileField.SECURITY)),
    SECURITY_REALITY("REALITY", listOf(ProfileField.SECURITY, ProfileField.REALITY_PUBLIC_KEY)),
    MLDSA65_VERIFY("REALITY mldsa65Verify", listOf(ProfileField.MLDSA65_VERIFY)),
    CERTIFICATE_PIN("整证书 SHA-256 固定（certificateFingerprint）", listOf(ProfileField.CERTIFICATE_FINGERPRINT)),
    CUSTOM_CA("自定义 CA（certificates，非 REALITY）", listOf(ProfileField.CERTIFICATES)),
    ALLOW_INSECURE(
        "生效的 allowInsecure（节点或全局，非 REALITY、没有证书指纹）",
        listOf(ProfileField.ALLOW_INSECURE, ProfileField.GLOBAL_ALLOW_INSECURE),
    ),
    UTLS_FINGERPRINT("uTLS 指纹（取值）", listOf(ProfileField.UTLS_FINGERPRINT)),
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

    // 只支持 names 里的取值，之外报 outside
    class SupportedValues(
        val since: Since,
        val names: CoreNames,
        val outside: CoreConflict,
        val note: String? = null,
    ) : CapabilityCell()

    class Unsupported(val since: Since, val conflict: CoreConflict) : CapabilityCell()

    // 这个核心承载不了该协议（协议行已报冲突），这一行无从谈起
    object NotApplicable : CapabilityCell()
}

// ---- 取值名单

// sing-box 1.14.2 的 uTLS 指纹，区分大小写，名单外在加载配置时报 unknown uTLS fingerprint
// （libcore/sing-box/common/tls/utls_client.go 的 uTLSClientHelloID；空串在那里等于 chrome，但生成器不写空值）
val SING_BOX_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
    ),
    ignoreCase = false,
)

// Xray v26.3.27 的 fingerprint：infra/conf/transport_internet.go 先转小写，再查 transport/internet/tls/tls.go 的
// PresetFingerprints / ModernFingerprints / OtherFingerprints，名单外 run -test 报 unknown "fingerprint"。
// unsafe 表示 Go 标准 TLS；REALITY 另外拒绝 unsafe 与 hellogolang（见组合规则）
val XRAY_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        // PresetFingerprints
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn", "unsafe",
        // ModernFingerprints
        "hellofirefox_99", "hellofirefox_102", "hellofirefox_105", "hellofirefox_120",
        "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102",
        "hellochrome_106_shuffle", "hellochrome_120", "hellochrome_131",
        "helloios_13", "helloios_14", "helloedge_85", "helloedge_106", "hellosafari_16_0",
        "hello360_11_0", "helloqq_11_1",
        // OtherFingerprints
        "hellogolang", "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn",
        "hellofirefox_auto", "hellofirefox_55", "hellofirefox_56", "hellofirefox_63", "hellofirefox_65",
        "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70", "hellochrome_72",
        "helloios_auto", "helloios_11_1", "helloios_12_1", "helloandroid_11_okhttp",
        "helloedge_auto", "hellosafari_auto", "hello360_auto", "hello360_7_5", "helloqq_auto",
        "hellochrome_100_psk", "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf",
        "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq",
    ),
    ignoreCase = true,
)

// mihomo v1.19.31 的 client-fingerprint（component/tls/utls.go 的 GetFingerprint 与 fingerprints），区分大小写。
// none 表示 Go 标准 TLS；名单外 -t 不报错，运行时只打 warning 并改用 Go 标准 TLS
val MIHOMO_UTLS_FINGERPRINTS = CoreNames(
    setOf(
        "none", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random",
        "chrome120", "firefox120", "safari16",
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk", "randomized",
    ),
    ignoreCase = false,
)

// Xray REALITY 拒绝的 fingerprint（infra/conf/transport_internet.go，REALITY 分支）
val XRAY_REALITY_FORBIDDEN_FINGERPRINTS = CoreNames(setOf("unsafe", "hellogolang"), ignoreCase = true)

// VLESS flow：sing-vmess v0.2.8 vless/client.go 的 NewClient；Xray v26.3.27 infra/conf/vless.go 的出站 users 校验
val SING_BOX_VLESS_FLOWS = CoreNames(setOf("xtls-rprx-vision"), ignoreCase = false)
val XRAY_VLESS_FLOWS = CoreNames(setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443"), ignoreCase = false)

// Xray 的 ws 客户端只用这个头携带 early data（transport/internet/websocket/dialer.go）；HTTP 头名不区分大小写
val XRAY_WS_EARLY_DATA_HEADERS = CoreNames(setOf(WS_EARLY_DATA_PROTOCOL_HEADER), ignoreCase = true)

// ---- 能力表

private fun supported(core: DialCore, note: String? = null) = CapabilityCell.Supported(verifiedAt(core), note)

private fun unsupported(core: DialCore, conflict: CoreConflict): CapabilityCell.Unsupported {
    check(conflict.core == null || conflict.core == core) { "$conflict 不属于 $core" }
    return CapabilityCell.Unsupported(verifiedAt(core), conflict)
}

private fun values(core: DialCore, names: CoreNames, outside: CoreConflict, note: String? = null) =
    CapabilityCell.SupportedValues(verifiedAt(core), names, outside, note)

private val NA = CapabilityCell.NotApplicable

private fun row(sb: CapabilityCell, xray: CapabilityCell, mihomo: CapabilityCell) =
    mapOf(DialCore.SING_BOX to sb, DialCore.XRAY to xray, DialCore.MIHOMO to mihomo)

private val SB = DialCore.SING_BOX
private val XR = DialCore.XRAY
private val MH = DialCore.MIHOMO

// 每一行都必须给三个核心各一格（CoreCapabilitiesTest 检查）。mihomo 只承载 AnyTLS，Xray 不承载 AnyTLS：
// 协议行已经报了冲突的格子写 NotApplicable
val CAPABILITY_TABLE: Map<Requirement, Map<DialCore, CapabilityCell>> = linkedMapOf(
    // I2b §2.1；I1 §7 矩阵（vmess / vless / trojan 三行）。mihomo 二进制有这三种协议（S-M11），本应用没有对应生成器
    Requirement.PROTOCOL_VMESS to row(
        supported(SB), supported(XR), unsupported(MH, CoreConflict.MIHOMO_V2RAY_PROTOCOL),
    ),
    Requirement.PROTOCOL_VLESS to row(
        supported(SB), supported(XR), unsupported(MH, CoreConflict.MIHOMO_V2RAY_PROTOCOL),
    ),
    // D10：Xray 的 Trojan 出站经 run -test 与回环验证（I1 §2）；本应用的 Xray 生成器要随 K1 补上
    Requirement.PROTOCOL_TROJAN to row(
        supported(SB),
        supported(XR, "Xray 的 Trojan 出站（servers 只放一项、不写 flow），生成器随 D10 补"),
        unsupported(MH, CoreConflict.MIHOMO_V2RAY_PROTOCOL),
    ),
    // Xray 没有 anytls 出站（实测 unknown config id: anytls，I1 §7）
    Requirement.PROTOCOL_ANYTLS to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_ANYTLS), supported(MH),
    ),

    // 传输：I1 §2.2、S-X4；I2b §1.2.1、§2.6
    Requirement.TRANSPORT_TCP to row(supported(SB), supported(XR), NA),
    Requirement.TRANSPORT_HTTP_HEADER to row(
        supported(SB, "HTTP/1.1 传输：只发 Host 头、要求回 200（见已知差异 HTTP_HEADER_CAMOUFLAGE）"),
        supported(XR, "tcp + header.type = http"),
        NA,
    ),
    Requirement.TRANSPORT_H2 to row(supported(SB), unsupported(XR, CoreConflict.XRAY_TRANSPORT_H2), NA),
    Requirement.TRANSPORT_WS to row(supported(SB), supported(XR), NA),
    Requirement.TRANSPORT_QUIC to row(
        supported(SB, "必须带 TLS，不能配 uTLS 指纹或 REALITY（见组合规则）"),
        unsupported(XR, CoreConflict.XRAY_TRANSPORT_QUIC),
        NA,
    ),
    Requirement.TRANSPORT_GRPC to row(supported(SB, "lite 实现，路径固定为 /<service_name>/Tun"), supported(XR), NA),
    Requirement.TRANSPORT_HTTPUPGRADE to row(supported(SB), supported(XR), NA),
    Requirement.TRANSPORT_UNKNOWN to row(
        unsupported(SB, CoreConflict.TRANSPORT_UNKNOWN), unsupported(XR, CoreConflict.TRANSPORT_UNKNOWN), NA,
    ),

    // 安全层：AnyTLS 总是 TLS
    Requirement.SECURITY_TLS to row(supported(SB), supported(XR), supported(MH)),
    Requirement.SECURITY_REALITY to row(
        // I2b §2.3、§2.4：没有 mldsa65；固定自报 1.8.1、去掉 X25519MLKEM768
        supported(SB, "固定自报客户端版本 1.8.1，去掉 X25519MLKEM768；不能配 ECH"),
        // I1 §3.1：只限 tcp / grpc（xhttp 本应用尚不支持）
        supported(XR, "只能配 tcp / grpc"),
        NA,
    ),
    // I1 §3.3、L-X21/23/24；I2b §2.3（sing-box 的 reality 选项没有 mldsa65）
    Requirement.MLDSA65_VERIFY to row(
        unsupported(SB, CoreConflict.SING_BOX_MLDSA65_VERIFY), supported(XR), NA,
    ),
    // I1 §4.1（pinnedPeerCertSha256）、§6.3（mihomo fingerprint）；I2b §2.3（sing-box 只有 SPKI 固定）。D11 若给
    // sing-box 补整证书固定补丁，改这一格
    Requirement.CERTIFICATE_PIN to row(
        unsupported(SB, CoreConflict.SING_BOX_CERTIFICATE_PIN),
        supported(XR, "pinnedPeerCertSha256；REALITY 下不写（见已知差异）"),
        supported(MH, "fingerprint"),
    ),
    // I1 §4.2（Xray 追加到系统根证书）；I2b §2.3（sing-box 替换系统根证书）；I1 §6.3（mihomo 换算成第一张证书的固定）
    Requirement.CUSTOM_CA to row(
        supported(SB, "只信任这些证书"),
        supported(XR, "追加到系统根证书"),
        supported(MH, "换算成第一张证书的 SHA-256 固定，含义不同（见已知差异 ANYTLS_CERTIFICATES）"),
    ),
    // I1 §4.1：Xray 在 2026-06-01 之后的 run -test 就拒绝 allowInsecure（按设备时钟判断）
    Requirement.ALLOW_INSECURE to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_ALLOW_INSECURE), supported(MH, "skip-cert-verify"),
    ),
    Requirement.UTLS_FINGERPRINT to row(
        values(SB, SING_BOX_UTLS_FINGERPRINTS, CoreConflict.SING_BOX_UTLS_FINGERPRINT),
        values(XR, XRAY_UTLS_FINGERPRINTS, CoreConflict.XRAY_UTLS_FINGERPRINT),
        values(MH, MIHOMO_UTLS_FINGERPRINTS, CoreConflict.MIHOMO_UTLS_FINGERPRINT),
    ),
    // I1 §4.3、S-M3；I2b §2.3
    Requirement.ECH_INLINE to row(
        supported(SB, "转成 PEM"), supported(XR, "echConfigList（标准 base64）；REALITY 下不写"), supported(MH),
    ),
    Requirement.ECH_AUTO_QUERY to row(
        supported(SB, "经 sing-box 自己的 DNS 路由查 HTTPS 记录"),
        // 二进制能用查询写法表达，但必须给查询服务器，且查询由 Xray 进程直接发出（I1 §4.3）
        unsupported(XR, CoreConflict.XRAY_ECH_AUTO_QUERY),
        supported(MH, "mihomo 自己的解析器查询"),
    ),
    // D15；I1 §5.3（Xray 没有任何 sing-mux）；I2b §2.2（sing-box 没有 Mux.Cool）
    Requirement.MUX_SING_MUX to row(supported(SB), unsupported(XR, CoreConflict.XRAY_SING_MUX), NA),
    Requirement.MUX_COOL to row(unsupported(SB, CoreConflict.SING_BOX_MUX_COOL), supported(XR), NA),
    Requirement.MUX_UNKNOWN to row(
        unsupported(SB, CoreConflict.MUX_TYPE_UNKNOWN), unsupported(XR, CoreConflict.MUX_TYPE_UNKNOWN), NA,
    ),
    // I1 §5.2（Xray 没有 packetaddr）；I2b §2.1
    Requirement.PACKET_ENCODING_PACKETADDR to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_PACKETADDR), NA,
    ),
    Requirement.PACKET_ENCODING_XUDP to row(
        supported(SB), supported(XR, "mux.xudpConcurrency，TCP 不复用（concurrency -1）"), NA,
    ),
    Requirement.PACKET_ENCODING_UNKNOWN to row(
        unsupported(SB, CoreConflict.PACKET_ENCODING_UNKNOWN), unsupported(XR, CoreConflict.PACKET_ENCODING_UNKNOWN), NA,
    ),
    Requirement.VLESS_FLOW to row(
        values(SB, SING_BOX_VLESS_FLOWS, CoreConflict.SING_BOX_VLESS_FLOW),
        values(XR, XRAY_VLESS_FLOWS, CoreConflict.XRAY_VLESS_FLOW),
        NA,
    ),
    // sing-box：transport/v2raywebsocket/conn.go 头名为空时 early data 拼在路径后；Xray：dialer.go 只放进
    // Sec-WebSocket-Protocol 头，生成器把上限写成 ?ed=N
    Requirement.WS_EARLY_DATA_PATH to row(
        supported(SB), unsupported(XR, CoreConflict.XRAY_WS_EARLY_DATA_PATH), NA,
    ),
    Requirement.WS_EARLY_DATA_HEADER to row(
        supported(SB, "任意头名"),
        values(XR, XRAY_WS_EARLY_DATA_HEADERS, CoreConflict.XRAY_WS_EARLY_DATA_HEADER),
        NA,
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
    // I1 §3.1（S-X3，T-B5 / T-A2 实测）：REALITY only supports RAW, XHTTP and gRPC。h2、quic 在传输行已报
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_REALITY_TRANSPORT, verifiedAt(DialCore.XRAY),
        "REALITY + ws / httpupgrade",
    ) { it.tls == TlsMode.REALITY && it.transport in setOf(CoreTransport.WS, CoreTransport.HTTPUPGRADE) },
    // I1 §3.3：REALITY 的 fingerprint 不能是 unsafe / hellogolang
    CombinationRule(
        DialCore.XRAY, CoreConflict.XRAY_REALITY_UTLS_FINGERPRINT, verifiedAt(DialCore.XRAY),
        "REALITY + fingerprint unsafe / hellogolang",
    ) { it.tls == TlsMode.REALITY && XRAY_REALITY_FORBIDDEN_FINGERPRINTS.accepts(it.utlsFingerprint) },
    // I2b §2.3：utls_client.go「Reality is conflict with ECH」，加载配置时报错
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_REALITY_ECH, verifiedAt(DialCore.SING_BOX),
        "REALITY + ECH",
    ) { it.tls == TlsMode.REALITY && it.ech != null },
    // I2b §2.6：transport/v2ray/transport.go，quic 不带 TLS 加载报 TLS required
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_WITHOUT_TLS, verifiedAt(DialCore.SING_BOX),
        "quic 不带 TLS",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.NONE },
    // I2b §2.6 / 结论 5：QUIC 取 STDConfig()，uTLS 与 REALITY 的 STDConfig() 直接报错，每次拨号都失败
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_REALITY, verifiedAt(DialCore.SING_BOX),
        "quic + REALITY",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.REALITY },
    CombinationRule(
        DialCore.SING_BOX, CoreConflict.SING_BOX_QUIC_UTLS, verifiedAt(DialCore.SING_BOX),
        "quic + uTLS 指纹（非 REALITY）",
    ) { it.transport == CoreTransport.QUIC && it.tls == TlsMode.TLS && it.utlsFingerprint != null },
)

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
        when (val cell = CAPABILITY_TABLE.getValue(item.requirement).getValue(core)) {
            is CapabilityCell.Supported -> Unit
            is CapabilityCell.SupportedValues -> if (!cell.names.accepts(item.value)) {
                conflicts += Conflict(core, cell.outside, item.value)
            }

            is CapabilityCell.Unsupported -> conflicts += Conflict(core, cell.conflict, item.value)
            CapabilityCell.NotApplicable -> error("能力表缺少 ${item.requirement} 在 $core 上的声明")
        }
    }
    for (rule in COMBINATION_RULES) {
        if (rule.core == core && rule.applies(requirements)) conflicts += Conflict(core, rule.conflict)
    }
    return conflicts
}

// 一条冲突：哪个核心（null 表示与核心无关）、哪一种、触发它的取值
data class Conflict(val core: DialCore?, val id: CoreConflict, val value: String? = null) {
    val fields: List<ProfileField> get() = id.fields

    // 面向用户的一句英文说明；界面层有中文文案时按 id 映射
    fun message(): String = buildString {
        core?.let { append(it.displayName).append(": ") }
        append(id.reason)
        value?.let { append(" (").append(it).append(')') }
    }
}
