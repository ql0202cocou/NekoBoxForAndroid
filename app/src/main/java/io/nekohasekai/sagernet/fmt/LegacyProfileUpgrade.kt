package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_MIHOMO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_SING_BOX
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_XRAY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_COOL
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.v2rayTransportOrNull

// K1 之前写下的节点（数据库、旧备份、旧通用链接）的一次性升级标注。只看节点本身与全局「允许不安全」，
// 判断「当时实际由哪个核心承载」一律用冻结的 LegacyCoreSelection，标注之后在 K1 的能力表下照原样运行：
//
// a. mux 协议族（plan.md D15）：VMess / VLESS 开了 mux、当时走 Xray 的，实际跑的是 Mux.Cool，muxType 标为 MUX_COOL。
//    没开 mux 的、Trojan（当时一律 sing-box）、core 为 mihomo 或非法值的 VMess（当时实际走 sing-box）都不动。
// b. WebSocket early data 的携带方式：当时走 Xray、开了 early data、没有填头名、路径里也没有内嵌 ed= 的 ws 节点，
//    Xray 把 early data 放在 Sec-WebSocket-Protocol 头里，sing-box 对同样的字段却拼在路径后面。
//    把 earlyDataHeaderName 标为 Sec-WebSocket-Protocol，换到 sing-box 之后线上格式不变。
// c. 手动核心值规范化：当时只有 VMess / VLESS 与 AnyTLS 读 core，不是 Xray（VMess）或 mihomo（AnyTLS）的手动值
//    一律落到 sing-box。规范成该协议能选的值（其余协议改回自动），以后「手动值与协议不匹配就报错」时，
//    存量节点照原样运行。
// d. uTLS 指纹补 firefox（维护者 2026-10-06 决定）：当时走 Xray、规范化后为自动选核、开了 TLS 且不是 REALITY、
//    没填 uTLS 指纹，并且经 a–c 改写之后 K1 的选核（decideCore）会把它换到 sing-box 的节点。这类节点在 Xray 上
//    没填指纹时用 Xray 默认的 chrome，换到 sing-box 之后会变成 Go 标准 TLS；迁移时补写 firefox，保持浏览器样式的
//    指纹。只对真的会换到 sing-box 的节点做：留在 Xray 的（带证书指纹、Mux.Cool、手动 Xray、只有 Xray 认的指纹
//    或 flow）不动；REALITY 节点两个核心没填时都按 chrome，也不动。
//    这条规则依赖 K1 的能力表（decideCore），与 a–c 只看冻结规则不同：8 → 9 的数据库迁移用的是 K1 当时的表，
//    以后能力表变了不会重跑这次迁移（备份恢复、通用链接导入则按导入时的表判断）。
// e. 越界取值规范化（VMess / VLESS / Trojan）：被旧版读错位又写回的行里，packetEncoding、muxType 可以是任意整数
//    （StandardV2RayBean 的反序列化注释）。1.8.0-a3 对这些值的实际效果：sing-box 生成器把不认识的 packetEncoding
//    省略、Xray 生成器只认 2，即 none；muxProtocolName 把 0–2 以外的值一律当 h2mux（含 3，当时 3 还不是 Mux.Cool）。
//    K1 的能力表对越界值两个核心都拒绝，3 则会被当成 Mux.Cool 悄悄换到 Xray。所以 packetEncoding 不在 0–2 之内的
//    改为 0；开了 mux、muxType 不在 0–2 之内、当时走 sing-box 的改为 h2mux（当时走 Xray 的已由规则 a 标为 Mux.Cool）。
//    没开 mux 的 muxType 不动（两个核心都不读）。HTTP、ShadowTLS 也是 StandardV2Ray 系，但不能选核、判定不看这两个
//    字段，不动；数据库迁移也只读 VMess / Trojan 行的 bean。
//
// a、b、c、e 都按改写前的节点判断承载（冻结规则不看 e 改的两个字段），改写不互相影响；d 按 a、b、c、e 改写之后的
// 节点判断。对已经标注过的节点再调用不会再改（幂等）。
// bean 原地修改：调用方传入自己持有的副本（数据库迁移、备份恢复读出来的 bean，或通用链接刚解析出的 bean），
// changed 为真时写回。通用链接没有 core 列，传 CORE_AUTO，忽略返回的 core

enum class LegacyProfileChange {
    // 规则 a：muxType 改为 MUX_COOL
    MUX_COOL,

    // 规则 b：earlyDataHeaderName 改为 Sec-WebSocket-Protocol
    WS_EARLY_DATA_HEADER,

    // 规则 c：core 改为该协议能选的值
    CORE_NORMALIZED,

    // 规则 d：utlsFingerprint 补为 firefox
    UTLS_FIREFOX,

    // 规则 e：越界的 packetEncoding 改为 0
    PACKET_ENCODING_NORMALIZED,

    // 规则 e：开了 mux、当时走 sing-box、越界的 muxType 改为 h2mux
    MUX_TYPE_NORMALIZED,
}

class LegacyProfileUpgrade(
    // 改后的 core（没改时就是传入的值）
    val core: Int,
    val changes: Set<LegacyProfileChange>,
) {
    val changed: Boolean get() = changes.isNotEmpty()
}

// Xray 的 ws 客户端用这个头携带 early data（transport/internet/websocket/dialer.go，v26.3.27）
const val WS_EARLY_DATA_PROTOCOL_HEADER = "Sec-WebSocket-Protocol"

// 规则 d 补写的 uTLS 指纹，三个核心的名单里都有（CoreCapabilities.kt）
const val LEGACY_UTLS_FINGERPRINT = "firefox"

fun upgradeLegacyProfile(
    type: Int,
    core: Int,
    bean: AbstractBean,
    globalAllowInsecure: Boolean,
): LegacyProfileUpgrade {
    val changes = LinkedHashSet<LegacyProfileChange>()
    val carrier = LegacyCoreSelection.carrier(type, core, bean, globalAllowInsecure)
    val onXray = carrier == LegacyCoreSelection.Carrier.XRAY
    if (onXray && bean is StandardV2RayBean) {
        // carrier 为 Xray 时 type 必是 TYPE_VMESS
        if (bean.enableMux == true && bean.muxType != MUX_COOL) {
            bean.muxType = MUX_COOL
            changes += LegacyProfileChange.MUX_COOL
        }
        if (earlyDataCarriedInPath(bean)) {
            bean.earlyDataHeaderName = WS_EARLY_DATA_PROTOCOL_HEADER
            changes += LegacyProfileChange.WS_EARLY_DATA_HEADER
        }
    }
    if (bean is StandardV2RayBean && (type == TYPE_VMESS || type == TYPE_TROJAN)) {
        if ((bean.packetEncoding ?: 0) !in LEGACY_IN_RANGE_VALUES) {
            bean.packetEncoding = 0
            changes += LegacyProfileChange.PACKET_ENCODING_NORMALIZED
        }
        if (carrier == LegacyCoreSelection.Carrier.SING_BOX && bean.enableMux == true &&
            (bean.muxType ?: MUX_H2MUX) !in LEGACY_IN_RANGE_VALUES
        ) {
            bean.muxType = MUX_H2MUX
            changes += LegacyProfileChange.MUX_TYPE_NORMALIZED
        }
    }
    val normalized = normalizedLegacyCore(type, core)
    if (normalized != core) changes += LegacyProfileChange.CORE_NORMALIZED
    if (onXray && bean is StandardV2RayBean && normalized == CORE_AUTO &&
        movesToSingBoxWithoutFingerprint(type, bean, globalAllowInsecure)
    ) {
        bean.utlsFingerprint = LEGACY_UTLS_FINGERPRINT
        changes += LegacyProfileChange.UTLS_FIREFOX
    }
    return LegacyProfileUpgrade(normalized, changes)
}

// 规则 e：1.8.0-a3 认识的取值，packetEncoding 是 none / packetaddr / xudp，muxType 是 h2mux / smux / yamux
private val LEGACY_IN_RANGE_VALUES = 0..2

// 规则 d 的其余条件：开了 TLS、不是 REALITY、没填指纹，且（a–c 改写之后的）节点自动选核时选中 sing-box
private fun movesToSingBoxWithoutFingerprint(type: Int, bean: StandardV2RayBean, globalAllowInsecure: Boolean): Boolean {
    if (bean.security != "tls" || !bean.realityPubKey.isNullOrBlank()) return false
    if (!bean.utlsFingerprint.isNullOrBlank()) return false
    val decision = decideCore(type, CORE_AUTO, bean, globalAllowInsecure)
    return decision is CoreDecision.Selected && decision.core == DialCore.SING_BOX
}

// 规则 c：K1 之前不读 core 的协议改回自动；VMess / VLESS、AnyTLS 不在下拉框取值内的改成手动 sing-box（当时就落到 sing-box）
internal fun normalizedLegacyCore(type: Int, core: Int): Int = when (type) {
    TYPE_VMESS -> if (core in setOf(CORE_AUTO, CORE_SING_BOX, CORE_XRAY)) core else CORE_SING_BOX
    TYPE_ANYTLS -> if (core in setOf(CORE_AUTO, CORE_SING_BOX, CORE_MIHOMO)) core else CORE_SING_BOX
    else -> CORE_AUTO
}

// 规则 b 的条件：ws、wsMaxEarlyData > 0、没有头名、路径里没有内嵌 ed=。此时 1.8.0-a3 的 resolveWsEarlyData 给出
// 「有上限、无头名」，sing-box 生成器不写 early_data_header_name，early data 拼在路径后面；Xray 生成器写 ?ed=N，
// 由 Xray 放进 Sec-WebSocket-Protocol 头。内嵌 ed= 的识别照当时的 resolveWsEarlyData 复制（冻结，不随它修改）
private fun earlyDataCarriedInPath(bean: StandardV2RayBean): Boolean {
    if (v2rayTransportOrNull(bean.type) != "ws") return false
    if ((bean.wsMaxEarlyData ?: 0) <= 0) return false
    if (!bean.earlyDataHeaderName.isNullOrBlank()) return false
    return !pathEmbedsEarlyData(bean.path.orEmpty())
}

private fun pathEmbedsEarlyData(path: String): Boolean {
    val queryIndex = path.indexOf('?').takeIf { it >= 0 } ?: path.indexOf("&ed=")
    val parameters = if (queryIndex >= 0) path.substring(queryIndex + 1).split('&') else emptyList()
    return parameters.any { it.startsWith("ed=") }
}

// 数据库 8 → 9 迁移（SagerDatabase）对一行 proxy_entities 的处理，纯函数。8 版数据库里的节点都是 K1 之前的实现写的
// （旧版写库一律写 StandardV2Ray v6 及以下），所以不看 legacyUnlabeled，每行都按存量数据标注。
// 只有 VMess / VLESS 行（vmessBean 列）与 Trojan 行（trojanBean 列）读 bean：规则 a、b、d 只落在当时走 Xray 的
// 节点上，而当时只有 VMess 类型会走 Xray；规则 e 另外要看 Trojan。其余行（以及没有 bean 字节的行）只做规则 c，
// 不读 bean。bean 用严格的反序列化读，读不出就不改 bean 列（返回 Unreadable，由调用方记日志），不把宽松读出的
// 半截 bean 写回去；规则 c 不看 bean，core 列照样规范。
// bean 传该行类型对应的列（VMess 传 vmessBean、Trojan 传 trojanBean，其余类型不读）。
// globalAllowInsecure 只在读到 bean 时取值
sealed class LegacyRowUpgrade {
    object Unchanged : LegacyRowUpgrade()

    // bean 为 null 表示 bean 列不改（只改了 core）；不为 null 时写回该行类型对应的 bean 列
    class Changed(val core: Int, val bean: ByteArray?, val changes: Set<LegacyProfileChange>) : LegacyRowUpgrade()

    // bean 读不出：bean 列不动；core 为 null 表示 core 列也不改，否则只写 core（规则 c 规范后的值）
    class Unreadable(val error: Exception, val core: Int?) : LegacyRowUpgrade()
}

// upgradeLegacyRow 会读 bean 的行类型
private fun legacyRowReadsBean(type: Int): Boolean = type == TYPE_VMESS || type == TYPE_TROJAN

fun upgradeLegacyRow(type: Int, core: Int, bean: ByteArray?, globalAllowInsecure: () -> Boolean): LegacyRowUpgrade {
    val normalized = normalizedLegacyCore(type, core)
    if (!legacyRowReadsBean(type) || bean == null || bean.isEmpty()) {
        return if (normalized == core) LegacyRowUpgrade.Unchanged
        else LegacyRowUpgrade.Changed(normalized, null, setOf(LegacyProfileChange.CORE_NORMALIZED))
    }
    val decoded = try {
        KryoConverters.deserialize(if (type == TYPE_VMESS) VMessBean() else TrojanBean(), bean)
    } catch (e: Exception) {
        return LegacyRowUpgrade.Unreadable(e, normalized.takeIf { it != core })
    }
    val upgrade = upgradeLegacyProfile(type, core, decoded, globalAllowInsecure())
    if (!upgrade.changed) return LegacyRowUpgrade.Unchanged
    val beanChanged = upgrade.changes.any { it != LegacyProfileChange.CORE_NORMALIZED }
    return LegacyRowUpgrade.Changed(upgrade.core, if (beanChanged) KryoConverters.serialize(decoded) else null, upgrade.changes)
}
