package io.nekohasekai.sagernet.golden

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.SingBoxTrialCategory
import io.nekohasekai.sagernet.fmt.SingBoxTrialFailure
import io.nekohasekai.sagernet.fmt.SingBoxTrialKind

/**
 * JVM 黄金测试的 sing-box 逐出站试建语料（golden-jvm/singbox-trial-corpus.json）：只记会失败的输入，
 * 每条是（种类，构建实际发出的试建 JSON）→（类别，字段路径）。按 JSON 结构查（键的次序、空白不计），查不到的一律通过。
 *
 * category / path 是手写的：依据设计稿与 sing-box 的行为，由 libcore 的 Go 测试 TestTrialCorpus 用真实的 vendored
 * sing-box 逐条核对，不从 JVM 的输出回填。kind / json 由 GoldenTrialCorpusRegenerateTest 按 server 从构建实际发出的
 * 试建 JSON 里回填（生成器改了试建 JSON 时重新生成）。
 *
 * hits 记下被查中过的条目：GoldenJvmBuildTest 跑完全部场景后核对「正好是这些」，语料里不能有从不被查到的条目。
 */
class GoldenTrialCorpus(val entries: List<Entry>) {

    class Entry(
        val scenario: String,
        /** 坏节点独有的服务器域名，回填时靠它在构建发出的试建 JSON 里找到这个节点。 */
        val server: String,
        val kind: SingBoxTrialKind,
        val json: JsonElement,
        val category: SingBoxTrialCategory,
        val path: String?,
    ) {
        override fun toString() = "$scenario / $server"
    }

    private val table: Map<Pair<SingBoxTrialKind, JsonElement>, Entry> = run {
        val result = LinkedHashMap<Pair<SingBoxTrialKind, JsonElement>, Entry>()
        for (entry in entries) {
            val previous = result.put(entry.kind to entry.json, entry)
            check(previous == null) { "试建语料里 $previous 与 $entry 的（种类，JSON）相同" }
        }
        result
    }

    /** 被查中过的条目，按首次查中的次序。 */
    val hits = LinkedHashSet<Entry>()

    /** 平台假实现的试建：语料里有就失败（并记一次命中），没有就通过。 */
    fun lookup(kind: SingBoxTrialKind, json: String): SingBoxTrialFailure? {
        val entry = table[kind to JsonParser.parseString(json)] ?: return null
        hits += entry
        return SingBoxTrialFailure(entry.category, entry.path)
    }

    companion object {
        const val RESOURCE = "golden-jvm/singbox-trial-corpus.json"

        /** 没有任何失败条目：所有试建都通过。回填语料时用它拿到不被语料影响的试建 JSON。 */
        fun empty() = GoldenTrialCorpus(emptyList())

        fun load(): GoldenTrialCorpus {
            val text = GoldenTrialCorpus::class.java.classLoader!!.getResourceAsStream(RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("测试资源里没有 $RESOURCE")
            val root = JsonParser.parseString(text).asJsonObject
            check(root["formatVersion"].asInt == 1) { "$RESOURCE 的 formatVersion 不是 1" }
            return GoldenTrialCorpus(root.getAsJsonArray("entries").map { entry(it.asJsonObject) })
        }

        private fun entry(json: JsonObject): Entry {
            fun string(key: String) = json[key]?.takeIf { it.isJsonPrimitive }?.asString
                ?: error("$RESOURCE：条目缺少 $key（kind / json 要用 GoldenTrialCorpusRegenerateTest 回填）：$json")
            val kind = SingBoxTrialKind.entries.singleOrNull { it.wire == string("kind") }
                ?: error("$RESOURCE：不认识的 kind ${string("kind")}")
            val category = SingBoxTrialCategory.entries.singleOrNull { it.name == string("category") }
                ?: error("$RESOURCE：不认识的 category ${string("category")}")
            return Entry(
                scenario = string("scenario"),
                server = string("server"),
                kind = kind,
                json = json["json"]?.takeIf { it.isJsonObject } ?: error("$RESOURCE：条目缺少 json 对象：$json"),
                category = category,
                path = json["path"]?.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null },
            )
        }
    }
}
