package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic.ProfileSkipped
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic.RuleAppsNotInstalled
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic.RuleNeedsVpn
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic.RuleOutboundMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuildNoticesTest {

    // 代替 Context.getString：用两个资源各自的英文原文
    private fun getString(id: Int, arg: String) = when (id) {
        R.string.route_need_vpn -> "Routing rule $arg relies on the VPN to be in effect, so it is ignored."
        R.string.route_need_vpn_rules -> "These routing rules rely on the VPN to be in effect and were ignored: $arg"
        else -> error("unexpected string resource $id")
    }

    private fun notices(vararg diagnostics: ConfigBuildDiagnostic) =
        configBuildNotices(diagnostics.toList(), ::getString)

    private val selfFailed = ProfileSkipped(1, "日本", "日本: bad uuid", true)
    private val memberFailed = ProfileSkipped(2, "中转链", "香港: plugin naive-plugin is not installed", false)

    @Test
    fun `没有诊断时不提示`() {
        assertTrue(notices().isEmpty())
    }

    @Test
    fun `跳过的节点单条`() {
        assertEquals(
            listOf(ConfigBuildNotice("Warning: these profiles failed to build and were skipped:\n日本: bad uuid", true)),
            notices(selfFailed),
        )
    }

    @Test
    fun `跳过的节点多条逐行列出，链成员出错时带上节点名`() {
        assertEquals(
            listOf(
                ConfigBuildNotice(
                    "Warning: these profiles failed to build and were skipped:\n" +
                            "日本: bad uuid\n中转链 (香港: plugin naive-plugin is not installed)", true
                )
            ),
            notices(selfFailed, memberFailed),
        )
    }

    @Test
    fun `应用都没装单条`() {
        assertEquals(
            listOf(ConfigBuildNotice("Warning: none of the apps are installed, rules skipped: 微信", true)),
            notices(RuleAppsNotInstalled(3, "微信")),
        )
    }

    @Test
    fun `应用都没装多条用逗号拼接`() {
        assertEquals(
            listOf(ConfigBuildNotice("Warning: none of the apps are installed, rules skipped: 微信, Rule 4", true)),
            notices(RuleAppsNotInstalled(3, "微信"), RuleAppsNotInstalled(4, "Rule 4")),
        )
    }

    @Test
    fun `出站不存在单条`() {
        assertEquals(
            listOf(ConfigBuildNotice("Warning: these rules specify a non-existent outbound: 游戏", true)),
            notices(RuleOutboundMissing(5, "游戏", 42)),
        )
    }

    @Test
    fun `出站不存在多条用逗号拼接`() {
        assertEquals(
            listOf(ConfigBuildNotice("Warning: these rules specify a non-existent outbound: 游戏, 流媒体", true)),
            notices(RuleOutboundMissing(5, "游戏", 42), RuleOutboundMissing(6, "流媒体", 43)),
        )
    }

    @Test
    fun `需要 VPN 单条用单数文案`() {
        assertEquals(
            listOf(ConfigBuildNotice("Routing rule 微信 relies on the VPN to be in effect, so it is ignored.", false)),
            notices(RuleNeedsVpn(3, "微信")),
        )
    }

    @Test
    fun `需要 VPN 多条用列表文案`() {
        assertEquals(
            listOf(
                ConfigBuildNotice(
                    "These routing rules rely on the VPN to be in effect and were ignored: 微信, 银行", false
                )
            ),
            notices(RuleNeedsVpn(3, "微信"), RuleNeedsVpn(7, "银行")),
        )
    }

    @Test
    fun `混合出现时每类一条且顺序固定`() {
        // 诊断按收集顺序交错出现，提示仍按「节点 → 应用未装 → 出站不存在 → 需要 VPN」排列
        val result = notices(
            RuleNeedsVpn(3, "微信"),
            RuleOutboundMissing(5, "游戏", 42),
            memberFailed,
            RuleAppsNotInstalled(4, "Rule 4"),
            RuleNeedsVpn(7, "银行"),
            selfFailed,
            RuleOutboundMissing(6, "流媒体", 43),
        )
        assertEquals(
            listOf(
                ConfigBuildNotice(
                    "Warning: these profiles failed to build and were skipped:\n" +
                            "中转链 (香港: plugin naive-plugin is not installed)\n日本: bad uuid", true
                ),
                ConfigBuildNotice("Warning: none of the apps are installed, rules skipped: Rule 4", true),
                ConfigBuildNotice("Warning: these rules specify a non-existent outbound: 游戏, 流媒体", true),
                ConfigBuildNotice(
                    "These routing rules rely on the VPN to be in effect and were ignored: 微信, 银行", false
                ),
            ),
            result,
        )
    }

    @Test
    fun `混合时需要 VPN 只有一条仍用单数文案`() {
        assertEquals(
            listOf(
                ConfigBuildNotice("Warning: none of the apps are installed, rules skipped: Rule 4", true),
                ConfigBuildNotice("Routing rule 微信 relies on the VPN to be in effect, so it is ignored.", false),
            ),
            notices(RuleNeedsVpn(3, "微信"), RuleAppsNotInstalled(4, "Rule 4")),
        )
    }
}
