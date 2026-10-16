package io.nekohasekai.sagernet.golden

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.SING_BOX_TRIAL_DETOUR
import io.nekohasekai.sagernet.fmt.SING_BOX_TRIAL_TAG
import io.nekohasekai.sagernet.fmt.SingBoxTrialKind
import io.nekohasekai.sagernet.fmt.buildConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

// 试建 JSON（singBoxTrialJson）与写进配置的那份只差三处：tag 是占位、有 detour 时取值是占位、
// 不写构建自己绑的 domain_resolver。对基线的每个场景每种模式，在 JVM 上照常构建一次，收集构建实际交给平台试建的
// （种类，JSON）（FakeConfigPlatform.trialQueries，默认全部通过）；最终配置里每个内部核心跳的出站 / 端点做同样的
// 归一后按（种类，JSON 结构）比较：
// - 最终配置里的每一个都试建过（同一份、同一种类），没有不经试建就进配置的内部核心出站；
// - 没有跳过任何根节点（没有 ProfileSkipped）时两边相等，没有试建了却不进配置的。有跳过时，被跳过的根节点在
//   规划里已试建过的跳不进配置，只核对前一条。
class GoldenSingBoxTrialInputTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `构建实际试建的 JSON 与最终配置只差 tag、detour 的取值与构建绑的 domain_resolver`() {
        val baseline = GoldenBaseline.load()
        val corpus = GoldenAddressCorpus.of(baseline)
        val tempRoot = temp.newFolder()
        val failures = ArrayList<String>()
        var compared = 0
        var exact = 0
        for (id in baseline.scenarioIds) {
            val input = baseline.input(id)
            val scenario = GoldenJvmScenario(input, corpus, tempRoot)
            for (mode in ConfigBuildMode.entries) {
                val tempDir = tempRoot.resolve("$id-${mode.dir}").apply { mkdirs() }
                val source = input.dataSource()
                val platform = scenario.platform(tempDir)
                val configInput = scenario.configInput(mode, scenario.main(source), source, platform)
                val diagnostics = ArrayList<ConfigBuildDiagnostic>()
                val result = try {
                    buildConfig(configInput, diagnostics) { LocalSocksAuth.generate(Random(1)) }
                } catch (_: Exception) {
                    // 构建失败的模式没有配置可比
                    continue
                }
                val trial = platform.trialQueries.map { (kind, json) -> kind to JsonParser.parseString(json) }.toSet()
                val proxy = configInput.main.newEntity()
                val final = internalOutbounds(result)
                    .map { (kind, outbound) -> kind to normalize(outbound, "dns-node-${proxy.groupId}") }.toSet()
                compared += final.size
                val untried = final - trial
                if (untried.isNotEmpty()) {
                    failures += "$id/${mode.dir}\n    进了配置却没试建：$untried"
                }
                if (diagnostics.none { it is ConfigBuildDiagnostic.ProfileSkipped }) {
                    exact++
                    if (trial != final) failures += "$id/${mode.dir}\n    试建了却不在配置里：${trial - final}"
                }
            }
        }
        assertTrue("比较的内部核心出站太少（$compared），检查筛选条件", compared > 300)
        assertTrue("逐项相等核对的构建太少（$exact）", exact > 300)
        assertTrue("试建 JSON 与最终配置不一致 ${failures.size} 处：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // 最终配置里内部核心跳的出站 / 端点与它的试建种类：tag 是节点出站（boxTagNames）的，去掉外核跳的本机 socks 出站
    private fun internalOutbounds(result: ConfigBuildResult): List<Pair<SingBoxTrialKind, JsonObject>> {
        val config = JsonParser.parseString(result.config).asJsonObject
        val localPorts = result.externalChains.flatMap { chain -> chain.hops.map { it.localPort } }.toSet()
        return listOf("outbounds" to SingBoxTrialKind.OUTBOUND, "endpoints" to SingBoxTrialKind.ENDPOINT)
            .flatMap { (key, kind) ->
                (config[key] as? JsonArray)?.map { kind to it.asJsonObject }.orEmpty()
            }
            .filter { (_, outbound) -> outbound.string("tag")?.let { it in result.boxTagNames } == true }
            .filterNot { (_, it) ->
                it.string("type") == "socks" && it.string("server") == LOCALHOST &&
                    (it["server_port"] as? JsonPrimitive)?.asInt in localPorts
            }
    }

    // 与试建同样的归一：tag 与 detour 的取值换成占位；构建绑的 domain_resolver（指本组的有序链 DNS）删掉，
    // 自定义出站 JSON 写的不动
    private fun normalize(outbound: JsonObject, groupResolver: String): JsonObject = outbound.deepCopy().apply {
        add("tag", JsonPrimitive(SING_BOX_TRIAL_TAG))
        if (has("detour")) add("detour", JsonPrimitive(SING_BOX_TRIAL_DETOUR))
        val resolver = get("domain_resolver") as? JsonObject
        if (resolver != null && resolver.string("server") == groupResolver &&
            resolver.keySet().all { it == "server" || it == "strategy" }
        ) remove("domain_resolver")
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
}
