package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO

// K1 的选核判定（plan.md「选核策略（完整能力匹配）」）：只读能力表（CoreCapabilities.kt）与节点的要求
// （CoreRequirements.kt）。纯函数，全局「允许不安全」由调用方传入。
//
// - 手动指定：只评估那个核心，有冲突（含「这个核心不能承载该协议」）就拒绝并列出冲突；
// - 自动：按偏好顺序取第一个没有冲突的候选核心，都有冲突就拒绝，按核心列出各自的冲突；
// - 不能选核的协议：承载方式沿用现状（sing-box 或插件），只做两条跨协议检查（整证书固定、mldsa65Verify），
//   结论与 1.8.0-a3 的 certificatePinUnsupported / mldsa65VerifyUnsupported 相同（两者已删，冻结副本见
//   LegacyCoreSelection.kt）。
//
// 用到判定的地方：构建（ProxyEntity.coreDecision → needExternal 决定内核 / 外核，requireBuildableHop 报拒绝）、
// 编辑器保存时的拦截、存量节点的升级标注（LegacyProfileUpgrade.kt 规则 d）

// AnyTLS 带 certificates 时自动选核是否 mihomo 优先。两个核心对这个字段含义不同（sing-box 当自定义 CA，mihomo
// 换算成第一张证书的固定），自动选核不替用户换；手动选哪个都允许。暂定，等维护者定（翻转这一处即可）
const val ANYTLS_CERTIFICATES_PREFER_MIHOMO = true

// 能为该协议选的核心，按默认偏好排列（不能选核的协议为空）。Trojan 走 Xray 即 D10
fun candidateCores(protocol: CoreProtocol): List<DialCore> = when (protocol) {
    CoreProtocol.VMESS, CoreProtocol.VLESS, CoreProtocol.TROJAN -> listOf(DialCore.SING_BOX, DialCore.XRAY)
    CoreProtocol.ANYTLS -> listOf(DialCore.SING_BOX, DialCore.MIHOMO)
    CoreProtocol.OTHER -> emptyList()
}

// 自动选核的偏好顺序：REALITY 或 mldsa65Verify 生效时 Xray 优先（REALITY 由 Xray 定义，sing-box 与 mihomo 都是
// 跟随实现，见 plan.md 选核策略的依据）；AnyTLS 带 certificates 时按上面的暂定规则 mihomo 优先；其余 sing-box 优先
fun preferenceOrder(requirements: CoreRequirements): List<DialCore> {
    val candidates = candidateCores(requirements.protocol)
    val first = when {
        requirements.tls == TlsMode.REALITY || requirements.mldsa65Verify -> DialCore.XRAY
        requirements.protocol == CoreProtocol.ANYTLS && requirements.customCa && ANYTLS_CERTIFICATES_PREFER_MIHOMO ->
            DialCore.MIHOMO

        else -> DialCore.SING_BOX
    }
    return candidates.filter { it == first } + candidates.filter { it != first }
}

sealed class CoreDecision {
    // 能选核的协议：由这个核心完整承载
    data class Selected(val core: DialCore, val manual: Boolean) : CoreDecision()

    // 不能选核的协议，表里没有冲突：承载方式沿用现状
    object Fixed : CoreDecision()

    // 拒绝。手动时只有指定核心的冲突；自动时按偏好顺序列出每个候选核心的冲突；
    // 与核心无关的冲突（取值非法、不能选核的协议）core 为 null
    data class Rejected(val manual: Boolean, val conflicts: List<Conflict>) : CoreDecision() {
        fun byCore(): Map<DialCore?, List<Conflict>> = conflicts.groupBy { it.core }
        val fields: Set<ProfileField> get() = conflicts.flatMap { it.fields }.toSet()

        // 构建时的拒绝原因（英文，一行；requireBuildableHop 抛出，经 withProfileName 带上节点名）。每条冲突写字段键
        // 与英文原因，有取值时放在括号里：手动指定时写明是手动选的哪个核心（core 是实体的 core 列）；自动选核按核心
        // 分组，每组一句、以核心名开头；不能选核的协议只列冲突。界面上的中文文案按冲突标识另行映射
        // （ui/profile/CoreConflictText.kt）
        fun message(core: Int): String = when {
            manual -> "the manually chosen core ${DialCore.of(core)?.displayName ?: core.toString()} " +
                "cannot run this profile: " + conflicts.joinToString("; ") { it.fieldText() }

            conflicts.all { it.core == null } ->
                "this profile cannot run as configured: " + conflicts.joinToString("; ") { it.fieldText() }

            else -> "no core can fully run this profile. " + byCore().entries.joinToString(". ") { (dial, list) ->
                (dial?.displayName ?: "any core") + ": " + list.joinToString("; ") { it.fieldText() }
            }
        }
    }
}

// 一条冲突的「[字段键] 原因 (取值)」，不带核心名
internal fun Conflict.fieldText(): String = buildString {
    append('[').append(fields.joinToString(", ") { it.key }).append("] ").append(id.reason)
    value?.let { append(" (").append(it).append(')') }
}

// core 是实体的 core 列（0 = 自动）
fun decideCore(type: Int, core: Int, bean: AbstractBean, globalAllowInsecure: Boolean): CoreDecision =
    decideCore(coreRequirements(type, bean, globalAllowInsecure), core)

fun decideCore(requirements: CoreRequirements, core: Int): CoreDecision {
    val manual = core != CORE_AUTO
    if (requirements.protocol == CoreProtocol.OTHER) {
        val conflicts = ArrayList<Conflict>()
        if (manual) conflicts += Conflict(null, CoreConflict.CORE_NOT_SELECTABLE, core.toString())
        if (requirements.certificatePin) conflicts += Conflict(null, CoreConflict.PROTOCOL_CERTIFICATE_PIN)
        if (requirements.mldsa65Verify) conflicts += Conflict(null, CoreConflict.PROTOCOL_MLDSA65_VERIFY)
        return if (conflicts.isEmpty()) CoreDecision.Fixed else CoreDecision.Rejected(manual, conflicts)
    }
    if (manual) {
        val dial = DialCore.of(core)
            ?: return CoreDecision.Rejected(true, listOf(Conflict(null, CoreConflict.CORE_VALUE_UNKNOWN, core.toString())))
        // 不是该协议候选的核心，协议行本身就是冲突（例如 AnyTLS 选 Xray）
        val conflicts = coreConflicts(dial, requirements)
        return if (conflicts.isEmpty()) CoreDecision.Selected(dial, true) else CoreDecision.Rejected(true, conflicts)
    }
    val all = ArrayList<Conflict>()
    for (candidate in preferenceOrder(requirements)) {
        val conflicts = coreConflicts(candidate, requirements)
        if (conflicts.isEmpty()) return CoreDecision.Selected(candidate, false)
        all += conflicts
    }
    return CoreDecision.Rejected(false, all)
}
