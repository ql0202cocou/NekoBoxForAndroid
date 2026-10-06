package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Conflict
import io.nekohasekai.sagernet.fmt.CoreConflict
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.DialCore
import io.nekohasekai.sagernet.fmt.ProfileField
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean

// 选核被拒（D14，编辑器保存时）在对话框里的文案：每条冲突一行「字段名：原因」，自动选核按核心分组。
// 字段名取编辑器里该字段的标题，原因按冲突标识映射；两个 when 都穷尽、不写 else，能力表新增冲突标识或
// 字段时这里编译期就报缺（CoreConflictTextTest 再查一遍每项都有文案）

// 冲突的原因
@StringRes
fun coreConflictReason(id: CoreConflict): Int = when (id) {
    CoreConflict.XRAY_ANYTLS -> R.string.core_conflict_xray_anytls
    CoreConflict.MIHOMO_V2RAY_PROTOCOL -> R.string.core_conflict_mihomo_v2ray_protocol
    CoreConflict.XRAY_TRANSPORT_H2 -> R.string.core_conflict_xray_transport_h2
    CoreConflict.XRAY_TRANSPORT_QUIC -> R.string.core_conflict_xray_transport_quic
    CoreConflict.XRAY_REALITY_TRANSPORT -> R.string.core_conflict_xray_reality_transport
    CoreConflict.SING_BOX_QUIC_WITHOUT_TLS -> R.string.core_conflict_sing_box_quic_without_tls
    CoreConflict.SING_BOX_QUIC_UTLS -> R.string.core_conflict_sing_box_quic_utls
    CoreConflict.SING_BOX_QUIC_REALITY -> R.string.core_conflict_sing_box_quic_reality
    CoreConflict.SING_BOX_MLDSA65_VERIFY -> R.string.core_conflict_sing_box_mldsa65_verify
    CoreConflict.SING_BOX_CERTIFICATE_PIN -> R.string.core_conflict_sing_box_certificate_pin
    CoreConflict.SING_BOX_REALITY_ECH -> R.string.core_conflict_sing_box_reality_ech
    CoreConflict.XRAY_ALLOW_INSECURE -> R.string.core_conflict_xray_allow_insecure
    CoreConflict.XRAY_ECH_AUTO_QUERY -> R.string.core_conflict_xray_ech_auto_query
    CoreConflict.SING_BOX_UTLS_FINGERPRINT -> R.string.core_conflict_sing_box_utls_fingerprint
    CoreConflict.XRAY_UTLS_FINGERPRINT -> R.string.core_conflict_xray_utls_fingerprint
    CoreConflict.XRAY_REALITY_UTLS_FINGERPRINT -> R.string.core_conflict_xray_reality_utls_fingerprint
    CoreConflict.MIHOMO_UTLS_FINGERPRINT -> R.string.core_conflict_mihomo_utls_fingerprint
    CoreConflict.MIHOMO_UTLS_HANDSHAKE_FAILS -> R.string.core_conflict_mihomo_utls_handshake_fails
    CoreConflict.SING_BOX_MUX_COOL -> R.string.core_conflict_sing_box_mux_cool
    CoreConflict.XRAY_SING_MUX -> R.string.core_conflict_xray_sing_mux
    CoreConflict.XRAY_PACKETADDR -> R.string.core_conflict_xray_packetaddr
    CoreConflict.SING_BOX_VLESS_FLOW -> R.string.core_conflict_sing_box_vless_flow
    CoreConflict.XRAY_VLESS_FLOW -> R.string.core_conflict_xray_vless_flow
    CoreConflict.XRAY_WS_EARLY_DATA_PATH -> R.string.core_conflict_xray_ws_early_data_path
    CoreConflict.XRAY_WS_EARLY_DATA_HEADER -> R.string.core_conflict_xray_ws_early_data_header
    CoreConflict.TRANSPORT_UNKNOWN -> R.string.core_conflict_transport_unknown
    CoreConflict.MUX_TYPE_UNKNOWN -> R.string.core_conflict_mux_type_unknown
    CoreConflict.PACKET_ENCODING_UNKNOWN -> R.string.core_conflict_packet_encoding_unknown
    CoreConflict.CORE_VALUE_UNKNOWN -> R.string.core_conflict_core_value_unknown
    CoreConflict.CORE_NOT_SELECTABLE -> R.string.core_conflict_core_not_selectable
    CoreConflict.PROTOCOL_CERTIFICATE_PIN -> R.string.core_conflict_protocol_certificate_pin
    CoreConflict.PROTOCOL_MLDSA65_VERIFY -> R.string.core_conflict_protocol_mldsa65_verify
}

// 字段在编辑器里的标题。主机、路径的标题随传输方式变，encryption 在 VLESS 下是 flow，与
// StandardV2RaySettingsActivity 的 updateView / createPreferences 一致；全局「允许不安全」不在编辑器里，单独一条
@StringRes
fun profileFieldTitle(field: ProfileField, bean: AbstractBean): Int {
    val network = (bean as? StandardV2RayBean)?.type
    return when (field) {
        ProfileField.CORE -> R.string.proxy_core
        ProfileField.TRANSPORT -> R.string.network
        ProfileField.HOST -> when (network) {
            "ws" -> R.string.ws_host
            "httpupgrade" -> R.string.http_upgrade_host
            else -> R.string.http_host
        }

        ProfileField.PATH -> when (network) {
            "ws" -> R.string.ws_path
            "grpc" -> R.string.grpc_service_name
            "httpupgrade" -> R.string.http_upgrade_path
            else -> R.string.http_path
        }

        ProfileField.SECURITY -> R.string.security
        ProfileField.ALPN -> R.string.alpn
        ProfileField.REALITY_PUBLIC_KEY -> R.string.reality_public_key
        ProfileField.MLDSA65_VERIFY -> R.string.reality_mldsa65_verify
        ProfileField.CERTIFICATE_FINGERPRINT -> R.string.certificate_fingerprint
        ProfileField.CERTIFICATES -> R.string.certificates
        ProfileField.ALLOW_INSECURE -> R.string.allow_insecure
        ProfileField.GLOBAL_ALLOW_INSECURE -> R.string.core_conflict_field_global_allow_insecure
        ProfileField.UTLS_FINGERPRINT -> R.string.utls_fingerprint
        ProfileField.ENABLE_ECH -> R.string.enable_ech
        ProfileField.ECH_CONFIG -> R.string.ech_config
        ProfileField.ENABLE_MUX -> R.string.enable_mux
        ProfileField.MUX_TYPE -> R.string.mux_type
        ProfileField.MUX_PADDING -> R.string.padding
        ProfileField.PACKET_ENCODING -> R.string.packet_encoding
        ProfileField.ENCRYPTION -> if (bean is StandardV2RayBean && bean.isVLESS) R.string.xtls_flow else R.string.encryption
        ProfileField.ALTER_ID -> R.string.alter_id
        ProfileField.UUID -> R.string.uuid
        ProfileField.WS_MAX_EARLY_DATA -> R.string.ws_max_early_data
        ProfileField.EARLY_DATA_HEADER_NAME -> R.string.early_data_header_name
        ProfileField.CUSTOM_OUTBOUND_JSON -> R.string.custom_outbound_json
    }
}

// 对话框里的一行：涉及的字段（标题资源，按能力表的顺序）、触发它的取值、原因
data class CoreConflictLine(val fields: List<Int>, val value: String?, @param:StringRes val reason: Int)

// 一组冲突。core 为 null 时不写组标题：手动选核（只评估所选核心）与不能选核的协议都只有一组
data class CoreConflictGroup(val core: DialCore?, val lines: List<CoreConflictLine>)

// 对话框的第一句：手动选核、自动选核、不能选核的协议（冲突都与核心无关）各一种说法
@StringRes
fun coreRejectionLead(rejected: CoreDecision.Rejected): Int = when {
    rejected.manual -> R.string.core_rejected_manual
    rejected.conflicts.all { it.core == null } -> R.string.core_rejected_fixed
    else -> R.string.core_rejected_auto
}

// 自动选核按核心分组（顺序同 decideCore 的偏好顺序），手动选核不分组。同一行内容重复出现时只留一次
fun coreConflictGroups(rejected: CoreDecision.Rejected, bean: AbstractBean): List<CoreConflictGroup> {
    fun lines(conflicts: List<Conflict>) = conflicts.map { conflict ->
        CoreConflictLine(
            conflict.fields.map { profileFieldTitle(it, bean) }.distinct(),
            conflict.value,
            coreConflictReason(conflict.id),
        )
    }.distinct()
    if (rejected.manual) return listOf(CoreConflictGroup(null, lines(rejected.conflicts)))
    return rejected.byCore().map { (core, conflicts) -> CoreConflictGroup(core, lines(conflicts)) }
}

// 对话框正文
fun Context.coreRejectionMessage(rejected: CoreDecision.Rejected, bean: AbstractBean): String = buildString {
    append(getString(coreRejectionLead(rejected)))
    for (group in coreConflictGroups(rejected, bean)) {
        append("\n\n")
        group.core?.let { append(it.displayName).append('\n') }
        group.lines.joinTo(this, "\n") { line ->
            var field = line.fields.joinToString(" / ") { getString(it) }
            line.value?.let { field = getString(R.string.core_conflict_field_value, field, it) }
            "• " + getString(R.string.core_conflict_line, field, getString(line.reason))
        }
    }
}
