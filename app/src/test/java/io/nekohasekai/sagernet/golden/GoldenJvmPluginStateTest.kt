package io.nekohasekai.sagernet.golden

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.FakeConfigPlatform
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.ProfileBuildException
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.buildConfig
import moe.matsuri.nb4a.plugin.Plugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

// 基线在没有插件 app 的模拟器上采集，插件已安装、装了外部插件 app 这几条分支没有设备基线。这里取现有场景的输入，
// 换一个平台假实现，按代码逻辑断言具体结果（不与基线比较）
class GoldenJvmPluginStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseline by lazy { GoldenBaseline.load() }

    // 运行模式构建一次（不组装外核配置）
    private fun build(id: String, platform: FakeConfigPlatform, diagnostics: MutableList<ConfigBuildDiagnostic>) =
        GoldenJvmScenario(baseline.input(id), GoldenAddressCorpus.of(baseline), tmp.root).let { scenario ->
            val source = baseline.input(id).dataSource()
            buildConfig(
                scenario.configInput(ConfigBuildMode.RUN, scenario.main(source), source, platform), diagnostics,
            ) { LocalSocksAuth.generate(Random(1)) }
        }

    private fun corpusParse() = GoldenAddressCorpus.of(baseline)::parse

    @Test
    fun `插件都已安装时选择器成员全部保留并进入选择器`() {
        // 选择器分组 1：1 VMess（选中）、2 Trojan-Go、3 Naive、4 Mieru、5 Hysteria 1 wechat-video、6 Hysteria 1 faketcp。
        // 基线里 2–6 因插件未安装被预检跳过；插件都可用、没有外部插件 app 时预检全部通过
        val platform = FakeConfigPlatform(parse = corpusParse())
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build("selector-plugin-members", platform, diagnostics)

        assertEquals(emptyList<ConfigBuildDiagnostic>(), diagnostics)
        assertEquals(emptyList<String>(), platform.warnings)
        // 每个成员单独成链，链出口的 tag 是选择器里的显示名（selectorName：不撞内置 tag、不像生成的 tag，原样用）
        val names = mapOf(
            1L to "golden-selp-vmess",
            2L to "golden-selp-trojan-go",
            3L to "golden-selp-naive",
            4L to "golden-selp-mieru",
            5L to "golden-selp-hy1-wechat",
            6L to "golden-selp-hy1-faketcp",
        )
        assertEquals(names, result.profileTagMap)
        val selector = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }.single { it["type"].asString == "selector" }
        assertEquals(TAG_PROXY, selector["tag"].asString)
        assertEquals("golden-selp-vmess", selector["default"].asString)
        assertEquals(names.values.toSet(), selector.getAsJsonArray("outbounds").map { it.asString }.toSet())
        assertEquals(1L, result.selectorGroupId)

        // 每个插件在一次构建里只查一次：成员按顺序预检，2、3、4 各查一次插件；5 先查外部插件 app（最先拨号的一跳能映射），
        // 再查插件；正式构建 5 时 mapExternalHop 再判断免映射用记住的结果；6 不能映射，插件结果已记住
        assertEquals(
            listOf(
                "pluginError(trojan-go-plugin)",
                "pluginError(naive-plugin)",
                "pluginError(mieru-plugin)",
                "pluginExternalAuthority(hysteria-plugin)",
                "pluginError(hysteria-plugin)",
            ),
            platform.pluginQueries,
        )

        // 2–6 各是一个外核跳实例；Trojan-Go、Naive、Mieru 经映射，hysteria 1 的 wechat-video（没有外部插件 app，
        // 免映射）与 faketcp（不能映射）直接拨服务器
        val hops = ExternalRunPlan.from(result).hops
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), hops.map { it.profileId })
        for (hop in hops) {
            if (hop.profileId in 2L..4L) {
                assertTrue("节点 ${hop.profileId}", hop.target is ExternalDialTarget.Mapped)
            } else {
                assertEquals("节点 ${hop.profileId}", ExternalDialTarget.Direct, hop.target)
            }
        }
    }

    @Test
    fun `外部插件 app 的 authority 是 Matsuri exe 前缀时 Hysteria 1 免映射`() {
        // 链 1：3 Hysteria 1 wechat-video（最先拨号）→ 2 VMess（出口）
        val platform = FakeConfigPlatform(
            parse = corpusParse(),
            externalAuthorities = mapOf("hysteria-plugin" to Plugins.AUTHORITIES_PREFIX_NEKO_EXE + "hysteria"),
        )
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        val result = build("chain-hy1-wechat-first", platform, diagnostics)

        assertEquals(emptyList<ConfigBuildDiagnostic>(), diagnostics)
        // 主节点不预检：只有 mapExternalHop 查一次外部插件 app
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), platform.pluginQueries)
        // 免映射：外核直接拨服务器，sing-box 里没有这一跳的映射入站
        val hop = ExternalRunPlan.from(result).hops.single()
        assertEquals(3L, hop.profileId)
        assertEquals(ExternalDialTarget.Direct, hop.target)
        val inbounds = JsonParser.parseString(result.config).asJsonObject.getAsJsonArray("inbounds")
            .map { it.asJsonObject["tag"].asString }
        assertTrue("不该有映射入站：$inbounds", inbounds.none { it.contains("-mapping-") })
    }

    @Test
    fun `外部插件 app 的 authority 不是 Matsuri exe 前缀时报插件不受支持`() {
        val platform = FakeConfigPlatform(
            parse = corpusParse(),
            externalAuthorities = mapOf("hysteria-plugin" to "com.example.golden.hysteria"),
        )
        val e = assertThrows(ProfileBuildException::class.java) {
            build("chain-hy1-wechat-first", platform, ArrayList())
        }
        // hysteriaSkipsMapping 在 mapExternalHop 里抛出，buildHop 的 withProfileName 包上节点名
        assertEquals("golden-hy1-first", e.profileName)
        assertEquals(
            "golden-hy1-first: You are using an unsupported hysteria-plugin, please download the correct plugin.",
            e.message,
        )
        assertEquals(listOf("pluginExternalAuthority(hysteria-plugin)"), platform.pluginQueries)
    }
}
