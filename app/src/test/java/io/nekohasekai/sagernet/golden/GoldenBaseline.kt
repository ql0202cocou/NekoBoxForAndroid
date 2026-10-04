package io.nekohasekai.sagernet.golden

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.putByteArray
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Base64

// 读模拟器采集的基线（app/src/test/resources/golden/，格式见其中的 README.md），给 JVM 黄金测试当输入与预期。
// 基线内容有问题（缺文件、字段不对、bean 解不开）一律抛异常，不静默跳过：读少了会让黄金测试空过

/** 有外核配置的两种模式，name 即基线里的目录名。 */
enum class GoldenBoxMode(val dir: String) {
    RUN("run"),
    TEST("test"),
    ;

    override fun toString() = dir
}

/**
 * 基线里的一份外核配置：调用 `externalCore(bean).config(port, cacheFile, controller, settings)` 所需的全部输入，
 * 加上基线里的配置原文。
 */
class GoldenExternalCase(
    val scenarioId: String,
    val mode: GoldenBoxMode,
    /** 配置原文的文件名 ext-<n>.<pluginId>.<json|yaml>，也是比较报告里的文档名。 */
    val file: String,
    val chainIndex: Int,
    val profileId: Long,
    val pluginId: String,
    /** 由 input.json 的 Kryo 字节新建的实例，已按 result.json 写好 finalAddress / finalPort；用例之间不共享。 */
    val bean: AbstractBean,
    /** 外核的本机 socks 端口。 */
    val port: Int,
    /** 测速时 mihomo 的 Clash API 端口与 secret，其余情况为 null。 */
    val controller: Pair<Int, String>?,
    /** 采集时经 cacheFile 领到、且出现在配置原文里的临时文件路径（hysteria 1 的 CA），比较时按动态路径处理。 */
    val tempFiles: List<String>,
    val settings: ExternalCoreSettings,
    val expected: String,
    val format: GoldenFormat,
) {
    override fun toString() = "$scenarioId / $mode / $file"
}

/** 一个场景的 input.json。 */
class GoldenScenarioInput(val id: String, val dir: File, private val json: JsonObject) {

    /** effectiveSettings 里外核生成器读到的设置。 */
    val externalCoreSettings: ExternalCoreSettings by lazy {
        val effective = json.obj("effectiveSettings", "$id/input.json")
        ExternalCoreSettings(
            logLevel = effective.int("logLevel", "$id/input.json effectiveSettings"),
            ipv6Mode = effective.int("ipv6Mode", "$id/input.json effectiveSettings"),
            globalAllowInsecure = effective.bool("globalAllowInsecure", "$id/input.json effectiveSettings"),
        )
    }

    /** 用生产代码（与 GoldenInputTest 同一条路径）从 Kryo 字节新建节点 bean；每次调用都是新实例。 */
    fun newBean(profileId: Long): AbstractBean {
        val where = "$id/input.json 节点 $profileId"
        val row = json.array("profiles", "$id/input.json")
            .map { it.asJsonObject }
            .singleOrNull { it.long("id", where) == profileId }
            ?: error("$where：profiles 里没有这个节点或有重复")
        val entity = ProxyEntity(type = row.int("type", where))
        entity.putByteArray(Base64.getDecoder().decode(row.string("beanKryoBase64", where)))
        return entity.requireBean()
    }
}

class GoldenBaseline private constructor(val root: File) {

    /** 全部场景 id，按名字排序。 */
    val scenarioIds: List<String> by lazy {
        root.resolve("scenarios").listFiles().orEmpty()
            .filter { File(it, "input.json").isFile }
            .map { it.name }
            .sorted()
    }

    fun input(scenarioId: String): GoldenScenarioInput {
        val dir = root.resolve("scenarios/$scenarioId")
        val json = readObject(File(dir, "input.json"), "$scenarioId/input.json")
        val id = json.string("id", "$scenarioId/input.json")
        check(id == scenarioId) { "$scenarioId/input.json 的 id 是 $id" }
        return GoldenScenarioInput(scenarioId, dir, json)
    }

    /**
     * 某个外核在基线里的全部配置（run 与 test 两种模式），按场景 id、模式、生成顺序排列。
     * 只有 status 为 ok 的模式有外核配置。每个用例的 bean 都是新建的。
     */
    fun externalCases(pluginId: String): List<GoldenExternalCase> {
        val cases = ArrayList<GoldenExternalCase>()
        for (scenarioId in scenarioIds) {
            val input = input(scenarioId)
            for (mode in GoldenBoxMode.entries) {
                val where = "$scenarioId/$mode/result.json"
                val modeDir = File(input.dir, mode.dir)
                val result = readObject(File(modeDir, "result.json"), where)
                if (result.string("status", where) != "ok") continue
                val external = result.array("external", where).map { it.asJsonObject }
                checkExternalFiles(modeDir, external, where)
                for (entry in external) {
                    if (entry.string("pluginId", where) != pluginId) continue
                    cases += externalCase(input, mode, entry, where)
                }
            }
        }
        return cases
    }

    private fun externalCase(
        input: GoldenScenarioInput,
        mode: GoldenBoxMode,
        entry: JsonObject,
        resultWhere: String,
    ): GoldenExternalCase {
        val file = entry.string("file", resultWhere)
        val where = "$resultWhere external $file"
        val profileId = entry.long("profileId", where)
        // finalAddress / finalPort 是构建期间由 mapExternalHop 写进 bean 的运行期字段，不在 Kryo 字节里
        val bean = input.newBean(profileId).apply {
            finalAddress = entry.string("finalAddress", where)
            finalPort = entry.int("finalPort", where)
        }
        val controller = entry["controller"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let {
            it.int("port", "$where controller") to it.string("secret", "$where controller")
        }
        val format = when (file.substringAfterLast('.')) {
            "json" -> GoldenFormat.JSON
            "yaml" -> GoldenFormat.YAML
            else -> error("$where：不认识的文件扩展名")
        }
        return GoldenExternalCase(
            scenarioId = input.id,
            mode = mode,
            file = file,
            chainIndex = entry.int("chainIndex", where),
            profileId = profileId,
            pluginId = entry.string("pluginId", where),
            bean = bean,
            port = entry.int("port", where),
            controller = controller,
            tempFiles = entry.array("tempFiles", where).map { it.asString },
            settings = input.externalCoreSettings,
            expected = File(input.dir, "${mode.dir}/$file").readText(),
            format = format,
        )
    }

    // external 列出的文件与目录里的 ext-* 文件必须一一对应，且按 ext-0、ext-1……的顺序
    private fun checkExternalFiles(modeDir: File, external: List<JsonObject>, where: String) {
        val listed = external.map { it.string("file", where) }
        listed.forEachIndexed { n, file ->
            val pluginId = external[n].string("pluginId", where)
            check(file.startsWith("ext-$n.$pluginId.")) { "$where：第 $n 项的文件名是 $file" }
        }
        val present = modeDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("ext-") }.sorted()
        check(present == listed.sorted()) { "$where：external 列的文件 $listed 与目录里的 $present 不一致" }
    }

    companion object {

        /**
         * 找基线目录：先按测试 classpath 上的 golden 资源（同 GoldenInputTest），再按工作目录找源码目录
         * （同 GoldenCompareBaselineTest：Gradle 跑单测时工作目录是 app/，IDE 里可能是仓库根目录）。
         * 找不到或没有场景时返回 null。
         */
        fun locate(): GoldenBaseline? {
            val resource = GoldenBaseline::class.java.classLoader?.getResource("golden")
                ?.takeIf { it.protocol == "file" }?.toURI()?.let(::File)
            val candidates = listOfNotNull(
                resource,
                File("src/test/resources/golden"),
                File("app/src/test/resources/golden"),
            )
            return candidates.filter { it.isDirectory }
                .map { GoldenBaseline(it) }
                .firstOrNull { it.scenarioIds.isNotEmpty() }
        }

        /** 同 [locate]，没有基线时用 Assume 让测试跳过。 */
        fun assumeAvailable(): GoldenBaseline {
            val baseline = locate()
            assumeTrue("还没有采集基线", baseline != null)
            return baseline!!
        }

        private fun readObject(file: File, where: String): JsonObject {
            check(file.isFile) { "$where 不存在" }
            val element = JsonParser.parseString(file.readText())
            check(element.isJsonObject) { "$where 顶层不是对象" }
            return element.asJsonObject
        }
    }
}

// 取字段时类型不对或缺失直接报错，并带上位置

private fun JsonObject.field(key: String, where: String): JsonElement =
    get(key)?.takeUnless { it.isJsonNull } ?: error("$where：缺少字段 $key")

private fun JsonObject.obj(key: String, where: String): JsonObject =
    field(key, where).takeIf { it.isJsonObject }?.asJsonObject ?: error("$where：$key 不是对象")

private fun JsonObject.array(key: String, where: String) =
    field(key, where).takeIf { it.isJsonArray }?.asJsonArray ?: error("$where：$key 不是数组")

private fun JsonObject.string(key: String, where: String): String =
    field(key, where).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: error("$where：$key 不是字符串")

private fun JsonObject.bool(key: String, where: String): Boolean =
    field(key, where).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: error("$where：$key 不是布尔值")

private fun JsonObject.long(key: String, where: String): Long =
    field(key, where).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        ?: error("$where：$key 不是整数")

private fun JsonObject.int(key: String, where: String): Int =
    long(key, where).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else error("$where：$key 超出 Int 范围") }
