package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_SMUX
import io.nekohasekai.sagernet.fmt.v2ray.MUX_YAMUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.effectiveUtlsFingerprint
import io.nekohasekai.sagernet.fmt.v2ray.muxProtocolName
import io.nekohasekai.sagernet.fmt.v2ray.resolveWsEarlyData
import io.nekohasekai.sagernet.fmt.v2ray.v2rayTransportOrNull
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

// 节点对核心的要求：从 type、bean 与全局「允许不安全」提取，与核心无关。只描述「生成器会把什么写进配置」，
// 生成器本来就不输出的（vision 流控下的 mux、REALITY 下的 allowInsecure 与 certificates、Trojan 的 packetEncoding）
// 不算要求。纯函数：不读设置、数据库、插件状态

enum class CoreProtocol { VMESS, VLESS, TROJAN, ANYTLS, OTHER }

enum class CoreTransport { TCP, HTTP_HEADER, H2, WS, QUIC, GRPC, HTTPUPGRADE, UNKNOWN }

enum class TlsMode { NONE, TLS, REALITY }

enum class EchMode { INLINE, AUTO_QUERY }

enum class MuxFamily { SING_MUX, MUX_COOL, UNKNOWN }

// 生效的 mux：enableMux 为真且不是 vision 流控
data class MuxRequirement(val family: MuxFamily, val muxType: Int)

enum class PacketEncodingRequirement { PACKETADDR, XUDP, UNKNOWN }

// 生效的 ws early data 怎么携带：headerName 为 null 时 sing-box 拼在路径里
data class WsEarlyDataRequirement(val headerName: String?)

data class CoreRequirements(
    val type: Int,
    val protocol: CoreProtocol,
    // null：协议没有 V2Ray 传输（AnyTLS 与不能选核的协议）
    val transport: CoreTransport? = null,
    val transportName: String? = null,
    val tls: TlsMode = TlsMode.NONE,
    val mldsa65Verify: Boolean = false,
    val certificatePin: Boolean = false,
    val customCa: Boolean = false,
    val allowInsecure: Boolean = false,
    // 生效的 uTLS 指纹（REALITY 没填时生成器补 chrome）；null 表示不用 uTLS
    val utlsFingerprint: String? = null,
    val ech: EchMode? = null,
    val mux: MuxRequirement? = null,
    // 只有 VMess / VLESS 有；none 为 null
    val packetEncoding: PacketEncodingRequirement? = null,
    val packetEncodingValue: Int? = null,
    // 只有 VLESS 有；不用 flow 为 null
    val vlessFlow: String? = null,
    val wsEarlyData: WsEarlyDataRequirement? = null,
) {
    // 能力表的行，按 Requirement 的顺序；不能选核的协议没有行
    fun items(): List<RequirementItem> {
        if (protocol == CoreProtocol.OTHER) return emptyList()
        val items = ArrayList<RequirementItem>()
        items += RequirementItem(
            when (protocol) {
                CoreProtocol.VMESS -> Requirement.PROTOCOL_VMESS
                CoreProtocol.VLESS -> Requirement.PROTOCOL_VLESS
                CoreProtocol.TROJAN -> Requirement.PROTOCOL_TROJAN
                CoreProtocol.ANYTLS -> Requirement.PROTOCOL_ANYTLS
                CoreProtocol.OTHER -> error("can't reach")
            }
        )
        when (transport) {
            null -> Unit
            CoreTransport.TCP -> items += RequirementItem(Requirement.TRANSPORT_TCP)
            CoreTransport.HTTP_HEADER -> items += RequirementItem(Requirement.TRANSPORT_HTTP_HEADER)
            CoreTransport.H2 -> items += RequirementItem(Requirement.TRANSPORT_H2)
            CoreTransport.WS -> items += RequirementItem(Requirement.TRANSPORT_WS)
            CoreTransport.QUIC -> items += RequirementItem(Requirement.TRANSPORT_QUIC)
            CoreTransport.GRPC -> items += RequirementItem(Requirement.TRANSPORT_GRPC)
            CoreTransport.HTTPUPGRADE -> items += RequirementItem(Requirement.TRANSPORT_HTTPUPGRADE)
            CoreTransport.UNKNOWN -> items += RequirementItem(Requirement.TRANSPORT_UNKNOWN, transportName)
        }
        when (tls) {
            TlsMode.NONE -> Unit
            TlsMode.TLS -> items += RequirementItem(Requirement.SECURITY_TLS)
            TlsMode.REALITY -> items += RequirementItem(Requirement.SECURITY_REALITY)
        }
        if (mldsa65Verify) items += RequirementItem(Requirement.MLDSA65_VERIFY)
        if (certificatePin) items += RequirementItem(Requirement.CERTIFICATE_PIN)
        if (customCa) items += RequirementItem(Requirement.CUSTOM_CA)
        if (allowInsecure) items += RequirementItem(Requirement.ALLOW_INSECURE)
        utlsFingerprint?.let { items += RequirementItem(Requirement.UTLS_FINGERPRINT, it) }
        when (ech) {
            null -> Unit
            EchMode.INLINE -> items += RequirementItem(Requirement.ECH_INLINE)
            EchMode.AUTO_QUERY -> items += RequirementItem(Requirement.ECH_AUTO_QUERY)
        }
        mux?.let {
            items += when (it.family) {
                MuxFamily.SING_MUX -> RequirementItem(Requirement.MUX_SING_MUX, muxProtocolName(it.muxType))
                MuxFamily.MUX_COOL -> RequirementItem(Requirement.MUX_COOL)
                MuxFamily.UNKNOWN -> RequirementItem(Requirement.MUX_UNKNOWN, it.muxType.toString())
            }
        }
        when (packetEncoding) {
            null -> Unit
            PacketEncodingRequirement.PACKETADDR -> items += RequirementItem(Requirement.PACKET_ENCODING_PACKETADDR)
            PacketEncodingRequirement.XUDP -> items += RequirementItem(Requirement.PACKET_ENCODING_XUDP)
            PacketEncodingRequirement.UNKNOWN ->
                items += RequirementItem(Requirement.PACKET_ENCODING_UNKNOWN, packetEncodingValue.toString())
        }
        vlessFlow?.let { items += RequirementItem(Requirement.VLESS_FLOW, it) }
        wsEarlyData?.let {
            items += if (it.headerName == null) RequirementItem(Requirement.WS_EARLY_DATA_PATH)
            else RequirementItem(Requirement.WS_EARLY_DATA_HEADER, it.headerName)
        }
        return items
    }
}

data class RequirementItem(val requirement: Requirement, val value: String? = null)

fun coreRequirements(type: Int, bean: AbstractBean, globalAllowInsecure: Boolean): CoreRequirements = when {
    type == TYPE_VMESS && bean is VMessBean -> standardRequirements(
        type, bean, if (bean.isVLESS) CoreProtocol.VLESS else CoreProtocol.VMESS, globalAllowInsecure,
    )

    type == TYPE_TROJAN && bean is TrojanBean -> standardRequirements(type, bean, CoreProtocol.TROJAN, globalAllowInsecure)
    type == TYPE_ANYTLS && bean is AnyTLSBean -> anyTlsRequirements(type, bean, globalAllowInsecure)

    // 不能选核的协议只留两条跨协议检查要用的：整证书固定（与 tlsFields 的取法相同）和生效的 mldsa65Verify
    else -> CoreRequirements(
        type = type,
        protocol = CoreProtocol.OTHER,
        mldsa65Verify = bean is StandardV2RayBean && bean.realityMldsa65VerifyActive(),
        certificatePin = !tlsFields(bean)?.certificateFingerprint.isNullOrBlank(),
    )
}

private fun standardRequirements(
    type: Int,
    bean: StandardV2RayBean,
    protocol: CoreProtocol,
    globalAllowInsecure: Boolean,
): CoreRequirements {
    // 与两个生成器一样以 security 开关为准：编辑器里关掉 TLS 后隐藏的字段仍留在 bean 里，不算
    val tlsOn = bean.security == "tls"
    val tls = when {
        !tlsOn -> TlsMode.NONE
        !bean.realityPubKey.isNullOrBlank() -> TlsMode.REALITY
        else -> TlsMode.TLS
    }
    val transport = when (v2rayTransportOrNull(bean.type)) {
        "tcp" -> CoreTransport.TCP
        // http 带 TLS 是 h2，不带是 tcp 的伪 HTTP 头（两个生成器都按 security 开关区分）
        "http" -> if (tlsOn) CoreTransport.H2 else CoreTransport.HTTP_HEADER
        "ws" -> CoreTransport.WS
        "quic" -> CoreTransport.QUIC
        "grpc" -> CoreTransport.GRPC
        "httpupgrade" -> CoreTransport.HTTPUPGRADE
        else -> CoreTransport.UNKNOWN
    }
    val certificatePin = tlsOn && !bean.certificateFingerprint.isNullOrBlank()
    val reality = tls == TlsMode.REALITY
    val v2rayFamily = protocol == CoreProtocol.VMESS || protocol == CoreProtocol.VLESS
    val packetEncodingValue = bean.packetEncoding ?: 0
    return CoreRequirements(
        type = type,
        protocol = protocol,
        transport = transport,
        transportName = bean.type,
        tls = tls,
        mldsa65Verify = bean.realityMldsa65VerifyActive(),
        certificatePin = certificatePin,
        // REALITY 下两个核心都不用 certificates（见已知差异）
        customCa = tlsOn && !reality && !bean.certificates.isNullOrBlank(),
        // REALITY 下 allowInsecure 不起作用；有证书指纹时固定优先，生成器不写 allowInsecure
        allowInsecure = tlsOn && !reality && !certificatePin &&
            effectiveAllowInsecure(bean.allowInsecure, globalAllowInsecure),
        utlsFingerprint = if (tlsOn) bean.effectiveUtlsFingerprint()?.takeIf { it.isNotBlank() } else null,
        ech = if (tlsOn && bean.enableECH == true) {
            if (bean.echConfig.isNullOrBlank()) EchMode.AUTO_QUERY else EchMode.INLINE
        } else null,
        mux = if (bean.enableMux == true && !bean.isVisionFlow) muxRequirement(bean.muxType ?: MUX_H2MUX) else null,
        // Trojan 没有 packet encoding，两个核心都不读这个字段（trojan:// 链接可能带进来）
        packetEncoding = if (!v2rayFamily) null else when (packetEncodingValue) {
            0 -> null
            1 -> PacketEncodingRequirement.PACKETADDR
            2 -> PacketEncodingRequirement.XUDP
            else -> PacketEncodingRequirement.UNKNOWN
        },
        packetEncodingValue = if (v2rayFamily && packetEncodingValue != 0) packetEncodingValue else null,
        // VLESS 的 encryption 存的是 flow，空与 auto 都表示不用（两个生成器相同）
        vlessFlow = if (protocol == CoreProtocol.VLESS) {
            bean.encryption?.takeIf { it.isNotBlank() && it != "auto" }
        } else null,
        wsEarlyData = if (transport == CoreTransport.WS) {
            bean.resolveWsEarlyData().takeIf { it.maxEarlyData != null }?.let { WsEarlyDataRequirement(it.headerName) }
        } else null,
    )
}

private fun muxRequirement(muxType: Int) = MuxRequirement(
    when (muxType) {
        MUX_H2MUX, MUX_SMUX, MUX_YAMUX -> MuxFamily.SING_MUX
        MUX_COOL -> MuxFamily.MUX_COOL
        else -> MuxFamily.UNKNOWN
    },
    muxType,
)

private fun anyTlsRequirements(type: Int, bean: AnyTLSBean, globalAllowInsecure: Boolean): CoreRequirements {
    val certificatePin = !bean.certificateFingerprint.isNullOrBlank()
    return CoreRequirements(
        type = type,
        protocol = CoreProtocol.ANYTLS,
        tls = TlsMode.TLS,
        certificatePin = certificatePin,
        customCa = !bean.certificates.isNullOrBlank(),
        allowInsecure = !certificatePin && effectiveAllowInsecure(bean.allowInsecure, globalAllowInsecure),
        utlsFingerprint = bean.utlsFingerprint?.takeIf { it.isNotBlank() },
        // 与两个生成器相同：填了配置就启用 ECH
        ech = when {
            !bean.echConfig.isNullOrBlank() -> EchMode.INLINE
            bean.enableECH == true -> EchMode.AUTO_QUERY
            else -> null
        },
    )
}
