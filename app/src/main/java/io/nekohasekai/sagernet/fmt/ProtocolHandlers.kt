package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_MIERU
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NAIVE
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SHADOWTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SSH
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN_GO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TUIC
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_WG
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.canUseSingBox
import io.nekohasekai.sagernet.fmt.hysteria.getFirstPort
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaPorts
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.naive.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayConfig
import io.nekohasekai.sagernet.fmt.v2ray.buildXrayOutbound
import io.nekohasekai.sagernet.fmt.v2ray.effectiveUtlsFingerprint
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.muxProtocolName
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import io.nekohasekai.sagernet.fmt.v2ray.xrayLacksAllowInsecure
import io.nekohasekai.sagernet.fmt.v2ray.xrayLacksTransport
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildMihomoConfig
import moe.matsuri.nb4a.proxy.anytls.buildMihomoProxy
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import java.io.File

// Central per-protocol dispatch. Every `when (type)` on ProxyEntity.TYPE_* and
// every per-bean-class `when (bean)` moved from ProxyEntity / ConfigBuilder /
// Protocols lives here, so adding a protocol touches one file. All mappings
// were moved verbatim from their call sites; a missing branch is intentional
// unless proven otherwise — do not "complete" a table on sight.

// type -> bean 字段，从 kryo 字节反序列化。分享链接与备份记录走这里，
// 严格解析：字节损坏直接抛异常，不导入半截 bean（Room 列转换器仍是宽松的）。
// 未知类型刻意忽略，没有 else 分支
fun ProxyEntity.putByteArray(byteArray: ByteArray) {
    fun <T : Serializable> load(bean: T): T? =
        if (byteArray.isEmpty()) null else KryoConverters.deserialize(bean, byteArray)
    when (type) {
        TYPE_SOCKS -> socksBean = load(SOCKSBean())
        TYPE_HTTP -> httpBean = load(HttpBean())
        TYPE_SS -> ssBean = load(ShadowsocksBean())
        TYPE_VMESS -> vmessBean = load(VMessBean())
        TYPE_TROJAN -> trojanBean = load(TrojanBean())
        TYPE_TROJAN_GO -> trojanGoBean = load(TrojanGoBean())
        TYPE_MIERU -> mieruBean = load(MieruBean())
        TYPE_NAIVE -> naiveBean = load(NaiveBean())
        TYPE_HYSTERIA -> hysteriaBean = load(HysteriaBean())
        TYPE_SSH -> sshBean = load(SSHBean())
        TYPE_WG -> wgBean = load(WireGuardBean())
        TYPE_TUIC -> tuicBean = load(TuicBean())
        TYPE_SHADOWTLS -> shadowTLSBean = load(ShadowTLSBean())
        TYPE_ANYTLS -> anyTLSBean = load(AnyTLSBean())
        TYPE_CHAIN -> chainBean = load(ChainBean())
        TYPE_NEKO -> nekoBean = load(NekoBean())
        TYPE_CONFIG -> configBean = load(ConfigBean())
    }
}

// type -> 协议显示名。bean 字段为 null（损坏数据）时退化为通用名而不是抛 NPE：
// requireBean() 的错误消息靠它定位类型
fun ProxyEntity.displayType(): String = when (type) {
    TYPE_SOCKS -> socksBean?.protocolName() ?: "SOCKS"
    TYPE_HTTP -> if (httpBean?.isTLS() == true) "HTTPS" else "HTTP"
    TYPE_SS -> "Shadowsocks"
    TYPE_VMESS -> if (vmessBean?.isVLESS == true) "VLESS" else "VMess"
    TYPE_TROJAN -> "Trojan"
    TYPE_TROJAN_GO -> "Trojan-Go"
    TYPE_MIERU -> "Mieru"
    TYPE_NAIVE -> "Naïve"
    TYPE_HYSTERIA -> hysteriaBean?.let { "Hysteria" + it.protocolVersion } ?: "Hysteria"
    TYPE_SSH -> "SSH"
    TYPE_WG -> "WireGuard"
    TYPE_TUIC -> "TUIC"
    TYPE_SHADOWTLS -> "ShadowTLS"
    TYPE_ANYTLS -> "AnyTLS"
    TYPE_CHAIN -> ProxyEntity.chainName
    TYPE_NEKO -> nekoBean?.displayType() ?: "Neko"
    TYPE_CONFIG -> configBean?.displayType() ?: "Custom"
    else -> "Undefined type $type"
}

// type -> held bean field, null when the field was never filled
// (was the when in ProxyEntity.requireBean)
fun ProxyEntity.beanForType(): AbstractBean? = when (type) {
    TYPE_SOCKS -> socksBean
    TYPE_HTTP -> httpBean
    TYPE_SS -> ssBean
    TYPE_VMESS -> vmessBean
    TYPE_TROJAN -> trojanBean
    TYPE_TROJAN_GO -> trojanGoBean
    TYPE_MIERU -> mieruBean
    TYPE_NAIVE -> naiveBean
    TYPE_HYSTERIA -> hysteriaBean
    TYPE_SSH -> sshBean
    TYPE_WG -> wgBean
    TYPE_TUIC -> tuicBean
    TYPE_SHADOWTLS -> shadowTLSBean
    TYPE_ANYTLS -> anyTLSBean
    TYPE_CHAIN -> chainBean
    TYPE_NEKO -> nekoBean
    TYPE_CONFIG -> configBean
    else -> error("Undefined type $type")
}

// type -> 该协议是否有分享链接
fun ProxyEntity.haveLink(): Boolean {
    return when (type) {
        TYPE_CHAIN, TYPE_NEKO -> false
        else -> true
    }
}

// type -> core used when ProxyEntity.core is CORE_AUTO (was the when in
// ProxyEntity.resolvedCore)
fun ProxyEntity.coreForType(): Int {
    return when (type) {
        // xray dropped the h2/quic transports and, after 2026-06-01,
        // allowInsecure; those profiles only run on sing-box.
        // 设了证书指纹的 VMess 也优先 Xray：sing-box 不支持这个固定，会拒绝构建
        // （见 buildSingBoxOutboundTLS）
        TYPE_VMESS -> vmessBean!!.let { bean ->
            val preferXray = bean.isVLESS || bean.certificateFingerprint.isNotBlank()
            if (preferXray && !bean.xrayLacksTransport() && !bean.xrayLacksAllowInsecure()) CORE_XRAY
            else CORE_SING_BOX
        }

        TYPE_ANYTLS -> CORE_MIHOMO
        else -> CORE_SING_BOX
    }
}

// 应用保存的是整张证书的 SHA-256 指纹，只有 Xray（VMess / VLESS）与 mihomo（AnyTLS）能按它固定。
// sing-box 有公钥固定（certificate_public_key_sha256，SPKI 哈希），但与整证书指纹不是一回事、
// 不能通用；hysteria v1 插件没有对应选项。唯一判断点：运行 / 测试 / 导出 / 预检都经
// ConfigBuild.requireBuildableHop 按它拒绝，编辑器保存时也按它提前拦下
fun ProxyEntity.certificatePinUnsupported(): Boolean {
    val pin = tlsFields(requireBean())?.certificateFingerprint
    if (pin.isNullOrBlank()) return false
    return when (type) {
        TYPE_VMESS -> resolvedCore() != CORE_XRAY
        TYPE_ANYTLS -> resolvedCore() != CORE_MIHOMO
        else -> true
    }
}

// REALITY 真正生效且配了 mldsa65Verify。编辑器里关掉 TLS 后隐藏的 REALITY 字段仍留在 bean 里，
// 与 buildXrayConfig 一样以 security 开关为准，残留字段不算
fun StandardV2RayBean.realityMldsa65VerifyActive(): Boolean =
    security == "tls" && !realityPubKey.isNullOrBlank() && !realityMldsa65Verify.isNullOrBlank()

// mldsa65Verify（REALITY 后量子校验）只有 Xray 支持，sing-box 会把它悄悄丢掉。最终核心不是 Xray
// 时返回拒绝原因，null 表示放行。core 只在字段生效时才取（resolvedCore 间接读设置）。
// 判断点同 certificatePinUnsupported：ConfigBuild.requireBuildableHop 与编辑器保存
fun mldsa65VerifyUnsupported(type: Int, bean: AbstractBean, core: () -> Int): String? {
    if (bean !is StandardV2RayBean || !bean.realityMldsa65VerifyActive()) return null
    return when (type) {
        TYPE_VMESS -> if (core() == CORE_XRAY) null
        else "REALITY mldsa65Verify only works on the Xray core; switch this profile to the Xray core or clear mldsa65Verify"

        // Trojan 目前没有 Xray 路径，只能清空
        else -> "REALITY mldsa65Verify only works on the Xray core, which this protocol cannot use; clear mldsa65Verify"
    }
}

fun ProxyEntity.mldsa65VerifyUnsupported(): String? =
    mldsa65VerifyUnsupported(type, requireBean()) { resolvedCore() }

// type -> 该节点是否跑在外部核心进程上
fun ProxyEntity.needExternal(): Boolean {
    return when (type) {
        TYPE_TROJAN_GO -> true
        TYPE_MIERU -> true
        TYPE_NAIVE -> true
        TYPE_VMESS -> resolvedCore() == CORE_XRAY
        TYPE_HYSTERIA -> !hysteriaBean!!.canUseSingBox()
        TYPE_ANYTLS -> resolvedCore() == CORE_MIHOMO
        TYPE_NEKO -> true
        else -> false
    }
}

// type -> sing-box 多路复用选项，不支持 mux 的协议为 null
fun ProxyEntity.singMux(): MultiplexOptions? {
    return when (type) {
        // vmess/vless/trojan share the StandardV2RayBean mux fields. Vision flow
        // doesn't support mux: vendored sing-box silently clears the flow when
        // multiplex is enabled (the Xray path guards this in XrayConfig); only
        // VLESS can carry the flow, so the check is a no-op for trojan.
        TYPE_VMESS, TYPE_TROJAN -> (requireBean() as StandardV2RayBean).let { bean ->
            if (bean.isVisionFlow) null else MultiplexOptions().apply {
                enabled = bean.enableMux
                padding = bean.muxPadding
                max_streams = bean.muxConcurrency
                protocol = muxProtocolName(bean.muxType)
            }
        }

        else -> null
    }
}

// bean 类 -> type 常量并写入对应 bean 字段；先清空所有 bean 字段再设置
fun ProxyEntity.putBean(bean: AbstractBean): ProxyEntity {
    socksBean = null
    httpBean = null
    ssBean = null
    vmessBean = null
    trojanBean = null
    trojanGoBean = null
    mieruBean = null
    naiveBean = null
    hysteriaBean = null
    sshBean = null
    wgBean = null
    tuicBean = null
    shadowTLSBean = null
    anyTLSBean = null
    chainBean = null
    configBean = null
    nekoBean = null

    when (bean) {
        is SOCKSBean -> {
            type = TYPE_SOCKS
            socksBean = bean
        }

        is HttpBean -> {
            type = TYPE_HTTP
            httpBean = bean
        }

        is ShadowsocksBean -> {
            type = TYPE_SS
            ssBean = bean
        }

        is VMessBean -> {
            type = TYPE_VMESS
            vmessBean = bean
        }

        is TrojanBean -> {
            type = TYPE_TROJAN
            trojanBean = bean
        }

        is TrojanGoBean -> {
            type = TYPE_TROJAN_GO
            trojanGoBean = bean
        }

        is MieruBean -> {
            type = TYPE_MIERU
            mieruBean = bean
        }

        is NaiveBean -> {
            type = TYPE_NAIVE
            naiveBean = bean
        }

        is HysteriaBean -> {
            type = TYPE_HYSTERIA
            hysteriaBean = bean
        }

        is SSHBean -> {
            type = TYPE_SSH
            sshBean = bean
        }

        is WireGuardBean -> {
            type = TYPE_WG
            wgBean = bean
        }

        is TuicBean -> {
            type = TYPE_TUIC
            tuicBean = bean
        }

        is ShadowTLSBean -> {
            type = TYPE_SHADOWTLS
            shadowTLSBean = bean
        }

        is AnyTLSBean -> {
            type = TYPE_ANYTLS
            anyTLSBean = bean
        }

        is ChainBean -> {
            type = TYPE_CHAIN
            chainBean = bean
        }

        is NekoBean -> {
            type = TYPE_NEKO
            nekoBean = bean
        }

        is ConfigBean -> {
            type = TYPE_CONFIG
            configBean = bean
        }

        else -> error("Undefined type $type")
    }
    return this
}

// bean class -> sing-box outbound/endpoint for internally-served protocols
// (was the when in ConfigBuilder.buildChain)
fun buildSingBoxOutbound(bean: AbstractBean): SingBoxOption = when (bean) {
    is ConfigBean -> CustomSingBoxOption(bean.config)

    is ShadowTLSBean -> // before StandardV2RayBean
        buildSingBoxOutboundShadowTLSBean(bean)

    is StandardV2RayBean -> // http/trojan/vmess/vless
        buildSingBoxOutboundStandardV2RayBean(bean)

    is HysteriaBean ->
        buildSingBoxOutboundHysteriaBean(bean)

    is TuicBean ->
        buildSingBoxOutboundTuicBean(bean)

    is SOCKSBean ->
        buildSingBoxOutboundSocksBean(bean)

    is ShadowsocksBean ->
        buildSingBoxOutboundShadowsocksBean(bean)

    is WireGuardBean ->
        buildSingBoxEndpointWireGuardBean(bean)

    is SSHBean ->
        buildSingBoxOutboundSSHBean(bean)

    is AnyTLSBean ->
        buildSingBoxOutboundAnyTLSBean(bean)

    else -> throw IllegalStateException("can't reach")
}

// HysteriaBean keeps the real port in serverPorts; serverPort is a stale default
// (shared by ConfigBuilder's mapping inbound and Protocols.Deduplication)
fun effectiveServerPort(bean: AbstractBean): Int = when (bean) {
    is HysteriaBean -> getFirstPort(bean.serverPorts)
    else -> bean.serverPort
}

// 订阅 / 分享入口的统一端点校验。必须跑在 initializeDefaultValues() 之前：
// 它会把缺失的 address/port 填成 127.0.0.1:1080，之后缺失就验不出来了。
// hysteria 的端口在 serverPorts（支持端口跳跃），单独验；ConfigBean 的
// 端点写在 config JSON 里，bean 的 address/port 字段无意义，豁免
fun AbstractBean.requireValidEndpoint() {
    if (this is ConfigBean) return
    if (serverAddress.isNullOrBlank()) error("missing server")
    if (this is HysteriaBean) {
        parseHysteriaPorts(serverPorts ?: error("missing hysteria port"))
    } else {
        val port = serverPort
        if (port == null || port !in 1..65535) error("invalid port")
    }
}

// 各订阅入口共用的「坏节点丢弃，不拖垮整批」过滤，同样必须早于
// initializeDefaultValues
fun List<AbstractBean>.filterValidEndpoint(): List<AbstractBean> = filter { bean ->
    runCatching { bean.requireValidEndpoint() }
        .onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
        .isSuccess
}

// type -> color attribute for the profile list (was Protocols.getProtocolColor)
fun protocolColorAttr(type: Int): Int {
    return when (type) {
        TYPE_NEKO -> android.R.attr.textColorPrimary
        else -> R.attr.accentOrTextSecondary
    }
}

// bean class -> whether a standard (protocol-specific) share link exists
// (was ProxyEntity.haveStandardLink)
fun hasStandardLink(bean: AbstractBean): Boolean = when (bean) {
    is SSHBean -> false
    is WireGuardBean -> false
    is ShadowTLSBean -> false
    is NekoBean -> false
    is ConfigBean -> false
    else -> true
}

// bean class -> standard share link, the universal link for the rest
// (was ProxyEntity.toStdLink)
fun standardLink(bean: AbstractBean): String {
    // 与导入同一条规则（parseProxies 的 filterValidEndpoint）：端点无效的坏数据
    // （入口加固前导入、从备份恢复）直接报错，不能导出一条看似正常、端口却不对、
    // 导回来也会被拒的链接。报错带节点名，分享入口直接拿它提示用户
    try {
        bean.requireValidEndpoint()
    } catch (e: Exception) {
        throw IllegalArgumentException(
            app.getString(R.string.share_invalid_endpoint, bean.displayName()), e
        )
    }
    return when (bean) {
        is SOCKSBean -> bean.toUri()
        is HttpBean -> bean.toUri()
        is ShadowsocksBean -> bean.toUri()
        is VMessBean -> bean.toUriVMessVLESSTrojan(false)
        is TrojanBean -> bean.toUriVMessVLESSTrojan(true)
        is TrojanGoBean -> bean.toUri()
        is NaiveBean -> bean.toUri()
        is HysteriaBean -> bean.toUri()
        is TuicBean -> bean.toUri()
        is AnyTLSBean -> bean.toUri()
        else -> bean.toUniversalLink()
    }
}

// bean class -> sing-box udp_over_tcp for the outbound (was a reflective
// "sUoT" field lookup in ConfigBuilder.buildChain)
fun udpOverTcp(bean: AbstractBean): Boolean = when (bean) {
    is ShadowsocksBean -> bean.sUoT == true
    is SOCKSBean -> bean.sUoT == true
    is NaiveBean -> bean.sUoT == true
    else -> false
}

// bean class -> whether GroupUpdater may replace serverAddress with a resolved
// IP (was the when in GroupUpdater.resolveAddresses)
fun supportsAddressRewrite(bean: AbstractBean): Boolean = when (bean) {
    is NaiveBean -> false // SNI rewrite unsupported
    else -> true
}

// bean class -> keep the TLS SNI on the hostname before serverAddress is
// rewritten to an IP (was the when in GroupUpdater.rewriteAddress). http,
// trojan, vmess/vless and shadowtls all go through the StandardV2RayBean
// branch, gated on TLS being enabled.
fun fillSniFromServerAddress(bean: AbstractBean) {
    when (bean) {
        is StandardV2RayBean -> if (bean.isTLS() && bean.sni.isBlank()) bean.sni = bean.serverAddress
        is TrojanGoBean -> if (bean.sni.isBlank()) bean.sni = bean.serverAddress
        is HysteriaBean -> if (bean.sni.isBlank()) bean.sni = bean.serverAddress
        is TuicBean -> if (bean.sni.isNullOrBlank()) bean.sni = bean.serverAddress
        is AnyTLSBean -> if (bean.sni.isNullOrBlank()) bean.sni = bean.serverAddress
    }
}

// bean class -> TLS settings for buildSingBoxOutboundTLS, null when the
// protocol (or this profile's security setting) has no TLS
fun tlsFields(bean: AbstractBean): TlsFields? = when (bean) {
    is StandardV2RayBean -> if (!bean.isTLS()) null else TlsFields(
        sni = bean.sni,
        alpn = bean.alpn,
        certificate = bean.certificates,
        allowInsecure = bean.allowInsecure,
        certificateFingerprint = bean.certificateFingerprint,
        utlsFingerprint = bean.effectiveUtlsFingerprint(),
        enableECH = bean.enableECH == true,
        echConfig = bean.echConfig,
        realityPublicKey = bean.realityPubKey,
        realityShortId = bean.realityShortId,
    )

    is HysteriaBean -> TlsFields(
        sni = bean.sni,
        alpn = bean.alpn,
        certificate = bean.caText,
        allowInsecure = bean.allowInsecure,
        certificateFingerprint = bean.certificateFingerprint,
    )

    is TuicBean -> TlsFields(
        sni = bean.sni,
        alpn = bean.alpn,
        certificate = bean.caText,
        allowInsecure = bean.allowInsecure,
        certificateFingerprint = bean.certificateFingerprint,
    )

    is AnyTLSBean -> TlsFields(
        sni = bean.sni,
        alpn = bean.alpn,
        certificate = bean.certificates,
        allowInsecure = bean.allowInsecure,
        certificateFingerprint = bean.certificateFingerprint,
        utlsFingerprint = bean.utlsFingerprint,
        // a pinned config enables ECH on its own (mihomo parity)
        enableECH = bean.enableECH == true || !bean.echConfig.isNullOrBlank(),
        echConfig = bean.echConfig,
    )

    else -> null
}

// 外核配置生成器与 launch 的生产设置：取 DataStore 当前值
fun ExternalCoreSettings.Companion.fromDataStore() = ExternalCoreSettings(
    logLevel = DataStore.logLevel,
    ipv6Mode = DataStore.ipv6Mode,
    globalAllowInsecure = DataStore.globalAllowInsecure,
)

// 由插件二进制承载的节点的外核：用哪个插件、跳实例怎样写进配置、怎样启动（外核运行计划见
// ExternalRunPlan.kt，按 ExternalRunPlan.assemble 组装）。没有条目的节点（NekoBean）由调用方跳过，与以前一致。
// 设置经 settings 传入；调用方不传时取 DataStore（生产实现），测试可传固定值
sealed class ExternalCore(
    val pluginId: String,
    private val buildLaunch: (
        settings: ExternalCoreSettings,
        pluginPath: String,
        config: String,
        writeCacheFile: (String, String, String) -> File,
    ) -> ExternalCoreLaunch,
    // 启动前校验入口（见 ExternalCoreStartup.kt），只有 Xray、mihomo 有。只在用的是 APK 内置的二进制时生效：
    // 内置的才核实过校验入口、报错格式与就绪时的 SOCKS5 握手，外部插件 app 提供的同名核心照旧不校验
    val check: ExternalCoreCheck? = null,
) {
    // 合并核心：同一个计划里这个插件的全部跳实例共用一份配置、一个进程
    val merged get() = this is Merged

    // writeCacheFile(prefix, ext, content) 落盘配置及进程要读的其它文件
    fun launch(
        pluginPath: String,
        config: String,
        writeCacheFile: (String, String, String) -> File,
        settings: ExternalCoreSettings = ExternalCoreSettings.fromDataStore(),
    ): ExternalCoreLaunch = buildLaunch(settings, pluginPath, config, writeCacheFile)

    // Xray、mihomo：先逐个跳实例生成出站（Xray）/ 代理（mihomo），再把一组合成一份配置，标识在合成时写入
    class Merged(
        pluginId: String,
        private val buildEntry: (settings: ExternalCoreSettings, hop: ExternalHop) -> Map<String, Any?>,
        private val buildConfig: (
            settings: ExternalCoreSettings,
            hops: List<ExternalHop>,
            entries: List<Map<String, Any?>>,
            mihomoController: Pair<Int, String>?,
        ) -> String,
        buildLaunch: (ExternalCoreSettings, String, String, (String, String, String) -> File) -> ExternalCoreLaunch,
        check: ExternalCoreCheck,
    ) : ExternalCore(pluginId, buildLaunch, check) {

        fun entry(hop: ExternalHop, settings: ExternalCoreSettings): Map<String, Any?> = buildEntry(settings, hop)

        // entries 与 hops 一一对应；mihomoController 是测速时的 Clash API 端口与 secret，只有 mihomo 用
        fun config(
            hops: List<ExternalHop>,
            entries: List<Map<String, Any?>>,
            mihomoController: Pair<Int, String>?,
            settings: ExternalCoreSettings,
        ): String = buildConfig(settings, hops, entries, mihomoController)
    }

    // 要装插件 app 的核心：每个跳实例一份配置、一个进程，配置与 K0 之前相同
    class PerHop(
        pluginId: String,
        private val buildConfig: (settings: ExternalCoreSettings, port: Int, cacheFile: (String, String) -> File) -> String,
        buildLaunch: (ExternalCoreSettings, String, String, (String, String, String) -> File) -> ExternalCoreLaunch,
    ) : ExternalCore(pluginId, buildLaunch) {

        // cacheFile(prefix, ext) 分配配置可能引用的临时文件（hysteria 的 CA）
        fun config(port: Int, cacheFile: (String, String) -> File, settings: ExternalCoreSettings): String =
            buildConfig(settings, port, cacheFile)
    }
}

class ExternalCoreLaunch(val commands: List<String>, val env: Map<String, String> = emptyMap())

fun externalCore(bean: AbstractBean): ExternalCore? = when (bean) {
    is TrojanGoBean -> ExternalCore.PerHop(
        "trojan-go-plugin",
        buildConfig = { settings, port, _ -> bean.buildTrojanGoConfig(port, settings) },
        buildLaunch = { _, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("trojan_go", "json", config)
            ExternalCoreLaunch(listOf(pluginPath, "-config", configFile.absolutePath))
        },
    )

    is MieruBean -> ExternalCore.PerHop(
        "mieru-plugin",
        buildConfig = { settings, port, _ -> bean.buildMieruConfig(port, settings) },
        buildLaunch = { _, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("mieru", "json", config)
            ExternalCoreLaunch(
                listOf(pluginPath, "run"),
                mapOf(
                    "MIERU_CONFIG_JSON_FILE" to configFile.absolutePath,
                    "MIERU_PROTECT_PATH" to "protect_path",
                ),
            )
        },
    )

    is NaiveBean -> ExternalCore.PerHop(
        "naive-plugin",
        buildConfig = { settings, port, _ -> bean.buildNaiveConfig(port, settings) },
        buildLaunch = { _, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("naive", "json", config)
            val env = mutableMapOf<String, String>()
            if (bean.certificates.isNotBlank()) {
                env["SSL_CERT_FILE"] = writeCacheFile("naive", "crt", bean.certificates).absolutePath
            }
            ExternalCoreLaunch(listOf(pluginPath, configFile.absolutePath), env)
        },
    )

    is HysteriaBean -> ExternalCore.PerHop(
        "hysteria-plugin",
        // hysteria 1 的配置不读设置，只有启动参数读 logLevel
        buildConfig = { _, port, cacheFile -> bean.buildHysteria1Config(port) { cacheFile("hysteria", "ca") } },
        buildLaunch = { settings, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("hysteria", "json", config)
            val commands = mutableListOf(
                pluginPath,
                "--no-check",
                "--config",
                configFile.absolutePath,
                "--log-level",
                // 与 ConfigBuilder 的 sing-box 档位一致；关闭日志时保留 warn
                when (settings.logLevel) {
                    2 -> "info"
                    3 -> "debug"
                    4 -> "trace"
                    else -> "warn"
                },
                "client"
            )
            if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                commands.addAll(0, listOf("su", "-c"))
            }
            ExternalCoreLaunch(commands)
        },
    )

    // 合并核心的 buildEntry 用的就是这个跳实例自己的 bean（ExternalHop.core 由它的 bean 取得），
    // buildConfig 只合成，不读 bean
    is VMessBean -> ExternalCore.Merged(
        "xray-plugin",
        buildEntry = { settings, hop -> buildXrayOutbound(bean, hop.finalAddress, hop.finalPort, settings) },
        buildConfig = { settings, hops, outbounds, _ -> buildXrayConfig(hops, outbounds, settings) },
        buildLaunch = { _, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("xray", "json", config)
            ExternalCoreLaunch(listOf(pluginPath, "run", "-c", configFile.absolutePath))
        },
        // run -test 只加载配置、不监听不连接；配置有错时退出码 23
        check = ExternalCoreCheck("Xray", { pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("xray", "json", config)
            ExternalCoreLaunch(listOf(pluginPath, "run", "-test", "-c", configFile.absolutePath))
        }, ::xrayCheckErrors),
    )

    is AnyTLSBean -> ExternalCore.Merged(
        "mihomo-plugin",
        buildEntry = { settings, hop -> buildMihomoProxy(bean, hop.finalAddress, hop.finalPort, settings) },
        buildConfig = { settings, hops, proxies, controller ->
            buildMihomoConfig(hops, proxies, settings, controller?.first, controller?.second ?: "")
        },
        buildLaunch = { _, pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("mihomo", "yaml", config)
            ExternalCoreLaunch(
                listOf(pluginPath, "-d", app.noBackupFilesDir.absolutePath, "-f", configFile.absolutePath)
            )
        },
        // -t 只解析配置、不监听不连接，失败时退出码 1。-f 指向的文件不存在时它会自己写一份默认配置并返回 0，
        // 所以先写好文件；不带 -d 会在 $HOME/.config/mihomo 建目录，-d 与启动时相同
        check = ExternalCoreCheck("mihomo", { pluginPath, config, writeCacheFile ->
            val configFile = writeCacheFile("mihomo", "yaml", config)
            ExternalCoreLaunch(
                listOf(pluginPath, "-t", "-d", app.noBackupFilesDir.absolutePath, "-f", configFile.absolutePath)
            )
        }, ::mihomoCheckErrors),
    )

    else -> null
}
