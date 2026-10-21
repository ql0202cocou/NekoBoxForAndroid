package io.nekohasekai.sagernet.fmt

// 冲突的字段与稳定标识（从 CoreCapabilities.kt 拆出的纯数据，行为不变）。升级核心时的复核清单仍在 CoreCapabilities.kt 顶部。

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

    // VMess / VLESS 的 id。只用于已知差异；它是凭据，不能作为冲突的字段，取值也不能写进任何报错
    UUID("uuid"),
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
        "Xray only runs REALITY over TCP, gRPC or XHTTP",
    ),
    SING_BOX_TRANSPORT_XHTTP(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.TRANSPORT),
        "sing-box has no XHTTP transport",
    ),
    XRAY_XHTTP_VISION(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.ENCRYPTION),
        "Xray cannot run the Vision flow over XHTTP",
    ),
    XRAY_XHTTP_MUX_COOL(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.TRANSPORT, ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE),
        "Xray cannot combine Mux.Cool with XHTTP; turn mux off (the XUDP packet encoding is fine)",
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

    // ---- TLS 与 REALITY
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
    MIHOMO_UTLS_HANDSHAKE_FAILS(
        DialCore.MIHOMO, GapKind.CORE, listOf(ProfileField.UTLS_FINGERPRINT),
        "mihomo's TLS handshake fails (always, or for most process seeds) with this fingerprint",
    ),

    // ---- 多路复用（mux）
    SING_BOX_MUX_COOL(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE),
        "sing-box has no Mux.Cool (Xray's multiplexing)",
    ),
    XRAY_SING_MUX(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.ENABLE_MUX, ProfileField.MUX_TYPE),
        "Xray only has Mux.Cool, not sing-mux (h2mux / smux / yamux)",
    ),

    // ---- UDP 相关
    XRAY_PACKETADDR(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.PACKET_ENCODING),
        "Xray has no packetaddr packet encoding",
    ),

    // ---- VLESS 的 flow
    SING_BOX_VLESS_FLOW(
        DialCore.SING_BOX, GapKind.CORE, listOf(ProfileField.ENCRYPTION),
        "sing-box only supports the xtls-rprx-vision flow",
    ),
    XRAY_VLESS_FLOW(
        DialCore.XRAY, GapKind.CORE, listOf(ProfileField.ENCRYPTION),
        "Xray does not support this VLESS flow",
    ),

    // ---- WebSocket 的 early data
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
    // 旧版本只存了 xhttp 的 type（host / path / mode / extra 从未落库），参数无从还原：不迁移不猜测，要求重新导入
    XHTTP_PARAMETERS_LOST(
        null, GapKind.INVALID_VALUE, listOf(ProfileField.TRANSPORT, ProfileField.PATH),
        "XHTTP parameters were lost; re-import the node or update the subscription",
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
