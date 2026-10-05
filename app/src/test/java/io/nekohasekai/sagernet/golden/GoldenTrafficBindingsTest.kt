package io.nekohasekai.sagernet.golden

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.TrafficTotals
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

// 构建结果的流量统计关联与基线对接：全部场景的 run / test 两种模式（导出不跑统计）走纯构建入口，
// tag → 节点 id 等于 result.json 的 trafficMap，初始累计取自 input.json，放行 root uid 的判断与旧表达式
// （trafficMap 涉及的节点里有 hysteria faketcp）一致，规则出站等于下面按规则逐场景写出的期望
class GoldenTrafficBindingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseline by lazy { GoldenBaseline.load() }

    // 每个节点的计数改成各不相同的值（基线里都是 0），看初始累计有没有按 id 带对
    private fun rx(id: Long) = id * 10000 + 3
    private fun tx(id: Long) = id * 1000 + 7

    private fun input(id: String): GoldenScenarioInput {
        val orig = baseline.input(id)
        val json = JsonParser.parseString(File(orig.dir, "input.json").readText()).asJsonObject
        for (p in json.getAsJsonArray("profiles")) {
            val o = p.asJsonObject
            o.addProperty("rx", rx(o["id"].asLong))
            o.addProperty("tx", tx(o["id"].asLong))
        }
        return GoldenScenarioInput(id, orig.dir, json)
    }

    private fun build(input: GoldenScenarioInput, mode: ConfigBuildMode): ConfigBuildResult {
        val scenario = GoldenJvmScenario(input, GoldenAddressCorpus.of(baseline), tmp.root)
        val source = input.dataSource()
        return buildConfig(scenario.configInput(mode, scenario.main(source), source, scenario.platform(tmp.newFolder()))) {
            LocalSocksAuth.generate(Random(7))
        }
    }

    private fun result(id: String, mode: ConfigBuildMode): JsonObject =
        JsonParser.parseString(baseline.root.resolve("scenarios/$id/${mode.dir}/result.json").readText()).asJsonObject

    @Test
    fun `全部场景：统计关联与基线的 trafficMap、输入的累计、旧的 root uid 判断一致，规则出站符合期望`() {
        var checked = 0
        var rootUid = 0
        val ruleChecked = HashSet<String>()
        for (id in baseline.scenarioIds) for (mode in listOf(ConfigBuildMode.RUN, ConfigBuildMode.TEST)) {
            val expected = result(id, mode)
            if (expected["status"].asString != "ok") continue
            val at = "$id/${mode.dir}"
            val input = input(id)
            val config = build(input, mode)
            val traffic = config.traffic
            val trafficMap = expected.getAsJsonObject("build").getAsJsonObject("trafficMap").entrySet()
                .associate { (tag, ids) -> tag to ids.asJsonArray.map { it.asLong } }
            assertEquals(at, trafficMap, traffic.tags)
            val ids = traffic.tags.values.flatten().toSet()
            assertEquals(at, ids, traffic.initial.keys)
            for (pid in ids) assertEquals("$at 节点 $pid", TrafficTotals(rx = rx(pid), tx = tx(pid)), traffic.initial[pid])
            // 旧表达式：VpnService 遍历 trafficMap 里的每个节点实体
            val source = input.dataSource()
            val old = traffic.tags.values.any { list ->
                list.any { source.profile(it)?.hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP }
            }
            assertEquals(at, old, config.needsRootUidBypass)
            if (old) rootUid++
            val rules = if (mode == ConfigBuildMode.TEST) {
                emptySet<String>() to "测速不带路由规则"
            } else {
                expectedRuleTags(id, input).also { if (it.second != NO_RULE) ruleChecked += id }
            }
            assertEquals("$at：${rules.second}", rules.first, traffic.ruleTags)
            checked++
        }
        assertTrue("应有 faketcp 场景", rootUid >= 3)
        assertTrue("checked $checked", checked > 400)
        // 名单上的场景都真的核对过（没有过期条目）
        assertEquals(EXPECTED_RULE_TAGS.keys, EXPECTED_RULE_TAGS.keys.intersect(ruleChecked))
    }

    private fun expectedRuleTags(id: String, input: GoldenScenarioInput): Pair<Set<String>, String> {
        EXPECTED_RULE_TAGS[id]?.let { return it }
        val hasRules = JsonParser.parseString(File(input.dir, "input.json").readText()).asJsonObject
            .getAsJsonArray("rules").any { it.asJsonObject["enabled"].asBoolean }
        if (id.startsWith("settings-") && hasRules) return setOf("g-2") to SETTINGS_RULES
        check(!hasRules) { "$id 有启用的规则，要在 EXPECTED_RULE_TAGS 里写出期望" }
        return emptySet<String>() to NO_RULE
    }

    companion object {
        private const val NO_RULE = "没有启用的规则；自定义配置里的 route.final 只指向 proxy / direct（custom-full-config、" +
            "custom-node-config-json、selector-main-full-config），外核链规则（inbound 是映射入站）不算"

        private const val SETTINGS_RULES = "settings-* 共用同一组三条规则：→ 节点 2（单节点，分组没有前置 / 落地，" +
            "出站 g-2 登记统计）、→ 直连（bypass，不记给节点）、→ 拦截（reject，没有出站）"

        // 有启用规则的场景（settings-* 除外）逐个写出期望与理由
        private val EXPECTED_RULE_TAGS: Map<String, Pair<Set<String>, String>> = mapOf(
            "chain-shared-node" to (setOf("c-5-4") to "规则 → 链 5（出口是节点 4），链的出站 c-5-4 登记统计"),
            "group-dns" to (setOf("g-2", "g-3", "g-4") to "三条规则 → 单节点 2、3、4，各自的 g-<id>"),
            "multi-chains-shared-nonfirst" to (setOf("c-6-5") to "规则 → 链 6（出口 5）"),
            "multi-chains-shared-swapped" to (setOf("c-7-4") to "规则 → 链 7（出口 4）"),
            "multi-rules-front-groups" to (setOf("c-2-2", "c-3-3", "c-4-4") to
                "三条规则 → 节点 2、3、4，所在分组带前置 10，各建成两跳链，出站是 c-<id>-<id>"),
            "multi-rules-targets" to (setOf("g-2", "g-3", "g-4", "g-5", "g-6") to "五条规则 → 单节点 2–6"),
            "rules-apps-not-installed" to (emptySet<String>() to "规则 → 代理（proxy），且应用都没装、整条跳过"),
            "rules-apps-partly-installed" to (emptySet<String>() to "规则 → 拦截，没有出站"),
            "rules-apps-proxy-mode" to (emptySet<String>() to "规则 → 代理（proxy 每轮本来就查）"),
            "rules-apps-vpn" to (emptySet<String>() to "规则 → 代理与直连，都不是节点出站"),
            "rules-to-broken-node" to (emptySet<String>() to "目标节点预检失败被跳过，规则因出站不存在没有应用"),
            "rules-to-chain" to (setOf("c-4-3") to "规则 → 链 4（出口 3）"),
            "rules-to-full-config" to (emptySet<String>() to "完整配置节点不能做出站，被跳过，规则没有应用"),
            "rules-to-main" to (emptySet<String>() to "规则 → 主节点，出站是 proxy"),
            "rules-to-missing" to (emptySet<String>() to "目标节点不存在，规则没有应用"),
            "rules-to-node-mihomo" to (setOf("g-2") to "规则 → 单节点 2（mihomo，经本机 socks 出站 g-2）"),
            "rules-to-node-sb" to (setOf("g-2") to "规则 → 单节点 2"),
            "rules-to-node-with-front" to (setOf("c-2-2") to "规则 → 节点 2，所在分组带前置，建成两跳链 c-2-2"),
            "rules-to-node-xray" to (setOf("g-2") to "规则 → 单节点 2（Xray，经本机 socks 出站 g-2）"),
            "rules-to-plugin-node" to (emptySet<String>() to "目标要的插件没装，预检跳过，规则没有应用"),
            "rules-two-to-same-node" to (setOf("g-2") to "两条规则 → 同一节点 2，出站只算一次"),
            "rules-types" to (emptySet<String>() to "规则只指向代理 / 直连 / 拦截；规则自带的 JSON 只加条件、不改出站"),
            "rules-types-dns-routing-off" to (emptySet<String>() to "同 rules-types"),
            "rules-types-fakedns-off" to (emptySet<String>() to "同 rules-types"),
            "rules-types-proxy-mode" to (emptySet<String>() to "同 rules-types"),
            "selector-rule-targets" to (setOf("golden-selr-xray", "golden-selr-outside", "golden-selr-outside-mihomo") to
                "选择器：规则 → 未选中的成员 2（它的选择器出站登记统计）、组外节点 10、11（选择器模式下出站以节点名为 tag）；" +
                "→ 主节点 1 的规则出站是 proxy，不算"),
        )
    }
}
