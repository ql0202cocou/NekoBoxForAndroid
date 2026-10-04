package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R

// 构建中值得告诉用户、但不让构建失败的情况。构建只记录，不碰界面；
// 提示文案由调用方经 configBuildNotices 生成，新增类型时同步那里
sealed class ConfigBuildDiagnostic {

    // 选择器成员 / 路由规则目标预检失败，已跳过。reason 是异常的可读消息；
    // reasonNamesProfile 为真表示出错的就是这个节点本身，reason 已以它的名字开头，
    // 否则是链里别的成员出错
    data class ProfileSkipped(
        val profileId: Long,
        val profileName: String,
        val reason: String,
        val reasonNamesProfile: Boolean,
    ) : ConfigBuildDiagnostic()

    // 规则填了应用，但一个都没装（或包名无效），整条规则已跳过
    data class RuleAppsNotInstalled(val ruleId: Long, val ruleName: String) : ConfigBuildDiagnostic()

    // 规则指向的出站不存在（节点已删除，或作为路由目标预检失败被跳过），规则未生效
    data class RuleOutboundMissing(
        val ruleId: Long,
        val ruleName: String,
        val outboundId: Long,
    ) : ConfigBuildDiagnostic()

    // 按应用分流的规则只在 VPN 模式下生效
    data class RuleNeedsVpn(val ruleId: Long, val ruleName: String) : ConfigBuildDiagnostic()
}

// 一条提示；long 对应 Toast.LENGTH_LONG，否则 LENGTH_SHORT
data class ConfigBuildNotice(val text: String, val long: Boolean)

// 诊断 → 提示文案。每类最多一条：逐条提示的话，规则一多会排上十几秒。
// getString 按资源 id 取带一个参数的字符串（运行时即 Context.getString(id, arg)）
fun configBuildNotices(
    diagnostics: List<ConfigBuildDiagnostic>,
    getString: (resId: Int, arg: String) -> String,
): List<ConfigBuildNotice> {
    val skipped = diagnostics.filterIsInstance<ConfigBuildDiagnostic.ProfileSkipped>()
    val appsNotInstalled = diagnostics.filterIsInstance<ConfigBuildDiagnostic.RuleAppsNotInstalled>()
    val outboundMissing = diagnostics.filterIsInstance<ConfigBuildDiagnostic.RuleOutboundMissing>()
    val needVpn = diagnostics.filterIsInstance<ConfigBuildDiagnostic.RuleNeedsVpn>()
    val notices = ArrayList<ConfigBuildNotice>()
    // 每条原因都可能含逗号，一行一条
    if (skipped.isNotEmpty()) notices += ConfigBuildNotice(
        "Warning: these profiles failed to build and were skipped:\n" + skipped.joinToString("\n") {
            if (it.reasonNamesProfile) it.reason else "${it.profileName} (${it.reason})"
        }, true
    )
    if (appsNotInstalled.isNotEmpty()) notices += ConfigBuildNotice(
        "Warning: none of the apps are installed, rules skipped: " +
                appsNotInstalled.joinToString(", ") { it.ruleName }, true
    )
    if (outboundMissing.isNotEmpty()) notices += ConfigBuildNotice(
        "Warning: these rules specify a non-existent outbound: " +
                outboundMissing.joinToString(", ") { it.ruleName }, true
    )
    // 单条沿用各语言已有的单数文案；多条用不分单复数的新文案
    if (needVpn.size == 1) {
        notices += ConfigBuildNotice(getString(R.string.route_need_vpn, needVpn[0].ruleName), false)
    } else if (needVpn.size > 1) {
        notices += ConfigBuildNotice(
            getString(R.string.route_need_vpn_rules, needVpn.joinToString(", ") { it.ruleName }), false
        )
    }
    return notices
}
