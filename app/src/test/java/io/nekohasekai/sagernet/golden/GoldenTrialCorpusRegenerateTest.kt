package io.nekohasekai.sagernet.golden

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.SingBoxTrialKind
import io.nekohasekai.sagernet.fmt.buildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * sing-box 试建语料（golden-jvm/singbox-trial-corpus.json）的检查与回填。
 *
 * 语料只记失败的输入，category / path / note 是手写的（依据设计稿与 sing-box 的行为，由 libcore 的 Go 测试
 * TestTrialCorpus 用真实的 vendored sing-box 核对），kind / json 是构建实际发出的试建 JSON。试建 JSON 的生成器改了
 * （见 SingBoxTrial.kt 的 singBoxTrialJson）时，只重新回填 kind / json，不动手写的部分，再看 Go 测试是否仍然通过：
 *   TRIAL_CORPUS_OUT=$PWD/app/src/test/resources/golden-jvm/singbox-trial-corpus.json \
 *     ./gradlew app:testOssDebugUnitTest --tests '*GoldenTrialCorpusRegenerateTest*' --rerun
 * （在仓库根目录执行，TRIAL_CORPUS_OUT 要写绝对路径，文件必须已存在：条目的手写部分从它读，回填后写回同一个文件。
 * 没设时 regenerate 跳过。新增条目时先手写 scenario / server / category / path / note，kind 与 json 留空）。
 *
 * 回填的做法：对条目的场景，用「全部通过」的试建平台跑三种模式，从构建实际发出的试建 JSON 里找含这个 server 的那一份；
 * 找不到、或同一个 server 在三种模式下发出了不同的 JSON，都报错，不猜。
 */
class GoldenTrialCorpusRegenerateTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `语料每条的场景在基线里，server 出现在 json 里`() {
        val baseline = GoldenBaseline.load()
        val corpus = GoldenTrialCorpus.load()
        assertTrue("试建语料是空的（文件丢了或内容被清空）", corpus.entries.isNotEmpty())
        for (entry in corpus.entries) {
            assertTrue("语料条目 $entry 的场景不在基线里", entry.scenario in baseline.scenarioIds)
            assertTrue("语料条目 $entry 的 json 里没有 server", containsString(entry.json, entry.server))
        }
        // 每个 server 在语料里只出现一次：它是回填时找节点的唯一依据
        assertEquals(corpus.entries.size, corpus.entries.map { it.server }.distinct().size)
    }

    @Test
    fun regenerate() {
        val out = System.getenv("TRIAL_CORPUS_OUT")
        assumeTrue("TRIAL_CORPUS_OUT 未设置，不回填试建语料", !out.isNullOrEmpty())
        val file = File(out!!)
        assertTrue("$out 不存在：条目的手写部分从它读", file.isFile)
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val baseline = GoldenBaseline.load()
        val addresses = GoldenAddressCorpus.of(baseline)
        val tempRoot = temp.newFolder()

        val entries = JsonArray()
        for (element in root.getAsJsonArray("entries")) {
            val old = element.asJsonObject
            val scenarioId = old["scenario"].asString
            val server = old["server"].asString
            val input = baseline.input(scenarioId)
            val scenario = GoldenJvmScenario(input, addresses, tempRoot, GoldenTrialCorpus.empty())
            val found = LinkedHashSet<Pair<SingBoxTrialKind, JsonElement>>()
            for (mode in ConfigBuildMode.entries) {
                val tempDir = tempRoot.resolve("$scenarioId-${mode.dir}").apply { mkdirs() }
                val source = input.dataSource()
                val platform = scenario.platform(tempDir)
                val configInput = scenario.configInput(mode, scenario.main(source), source, platform)
                // 全部通过的平台上构建不该失败；失败时已发出的查询照样有效，所以不在这里断言
                runCatching { buildConfig(configInput) { LocalSocksAuth.generate(Random(1)) } }
                platform.trialQueries.map { (kind, json) -> kind to JsonParser.parseString(json) }
                    .filterTo(found) { (_, json) -> containsString(json, server) }
            }
            check(found.size == 1) { "$scenarioId 的 server $server 对应 ${found.size} 份试建 JSON，要正好 1 份：$found" }
            val (kind, json) = found.single()
            val entry = JsonObject()
            entry.addProperty("scenario", scenarioId)
            entry.addProperty("server", server)
            entry.addProperty("kind", kind.wire)
            entry.add("json", json)
            entry.add("category", old["category"])
            old["path"]?.let { entry.add("path", it) }
            old["note"]?.let { entry.add("note", it) }
            entries.add(entry)
        }
        root.add("entries", entries)
        file.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n")
    }

    // 任何层级上有字符串值等于 text
    private fun containsString(element: JsonElement, text: String): Boolean = when {
        element.isJsonPrimitive -> element.asJsonPrimitive.isString && element.asString == text
        element.isJsonArray -> element.asJsonArray.any { containsString(it, text) }
        element.isJsonObject -> element.asJsonObject.entrySet().any { containsString(it.value, text) }
        else -> false
    }
}
