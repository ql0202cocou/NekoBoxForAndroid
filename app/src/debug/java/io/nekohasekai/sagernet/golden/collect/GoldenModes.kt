package io.nekohasekai.sagernet.golden.collect

import android.content.pm.ProviderInfo
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.bg.proto.BoxInstance
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.IdentityHashMap

// 要另装插件 app 的外部核心。模拟器上都没装，BoxInstance.init() 的 initPlugin 会对它们抛
// 「插件未安装」；运行 / 测速模式预先把占位结果放进 pluginPath 绕过（生成配置用不到插件路径）
val PLUGINS_WITHOUT_APP = listOf("trojan-go-plugin", "naive-plugin", "mieru-plugin", "hysteria-plugin")

private const val MIHOMO_PLUGIN = "mihomo-plugin"

// 一种模式的产物：result.json 的内容与其它原文文件（文件名 → 内容）
class ModeOutput(val result: Map<String, Any?>, val files: Map<String, String>) {
    val ok get() = result["status"] == "ok"
}

// TestInstance 的 buildConfig / mihomoTestController 是 protected，测速模式经反射调用真实实现，
// 不在这里复刻。采集开始前先解析一次，签名变了直接让整次采集失败
private val boxBuildConfig: Method by lazy {
    BoxInstance::class.java.getDeclaredMethod("buildConfig").apply { isAccessible = true }
}
private val boxMihomoTestController: Method by lazy {
    BoxInstance::class.java.getDeclaredMethod("mihomoTestController").apply { isAccessible = true }
}

fun requireReflectionTargets() {
    boxBuildConfig
    boxMihomoTestController
}

// 反射调用抛出的业务异常原样交给场景记录，不能被 InvocationTargetException 包一层
private fun Method.invokeUnwrapped(target: Any): Any? = try {
    invoke(target)
} catch (e: InvocationTargetException) {
    throw e.targetException
}

// 运行 / 测速模式直接跑真实的 BoxInstance.init()：buildConfig 后建外核运行计划，经共用的组装入口
// 逐个跳实例 initPlugin 并生成每组的配置。这里只把 loadConfig 换成空操作（不建 libcore box、不启动
// 任何东西），另按 PLUGINS_WITHOUT_APP 预填 pluginPath。测速模式的 buildConfig 与
// mihomoTestController 转给一个真实的 TestInstance
private class GoldenBoxInstance(profile: ProxyEntity, private val test: TestInstance?) : BoxInstance(profile) {

    var controller: Pair<Int, String>? = null
        private set

    init {
        for (id in PLUGINS_WITHOUT_APP) pluginPath[id] = PluginManager.InitResult("", ProviderInfo())
    }

    override fun buildConfig() {
        if (test == null) {
            super.buildConfig()
            return
        }
        boxBuildConfig.invokeUnwrapped(test)
        config = test.config
    }

    override fun mihomoTestController(): Pair<Int, String>? {
        if (test == null) return super.mihomoTestController()
        @Suppress("UNCHECKED_CAST")
        return (boxMihomoTestController.invokeUnwrapped(test) as Pair<Int, String>?).also { controller = it }
    }

    override suspend fun loadConfig() {
    }
}

fun runBoxMode(profile: ProxyEntity, forTest: Boolean): ModeOutput {
    // link / timeout 只影响测速请求本身，不进配置
    val test = if (forTest) TestInstance(profile, CONNECTION_TEST_URL, 3000) else null
    val instance = GoldenBoxInstance(profile, test)
    val cacheDir = app.cacheDir
    val before = cacheFiles(cacheDir)
    try {
        try {
            runBlocking { instance.init() }
        } catch (e: Exception) {
            return errorOutput(e)
        }
        // 组装外核配置时经 cacheFile 领到的临时文件（hysteria 的 CA）落在 cacheDir 根下，
        // 文件名随机；按「init 前后新增」找出来，再按路径是否出现在配置原文里归到各外核
        val created = (cacheFiles(cacheDir) - before).sorted()
        val config = instance.config
        val files = LinkedHashMap<String, String>()
        files["sing-box.json"] = config.config
        val external = ArrayList<Map<String, Any?>>()
        val paths = ArrayList<String>()
        // 一组一个文件，顺序同 BoxInstance.init 组装出的外核进程（即计划的分组顺序）
        instance.externalProcesses.forEachIndexed { n, process ->
            val group = process.group
            val mihomo = group.pluginId == MIHOMO_PLUGIN
            val file = "ext-$n.${group.pluginId}.${if (mihomo) "yaml" else "json"}"
            files[file] = process.config
            val tempFiles = created.filter { process.config.contains(it) }
            paths += tempFiles
            // 测速控制端口只进 mihomo 的配置
            val controller = instance.controller?.takeIf { mihomo }
            external += linkedMapOf(
                "file" to file,
                "pluginId" to group.pluginId,
                "controller" to controller?.let { linkedMapOf("port" to it.first, "secret" to it.second) },
                "tempFiles" to tempFiles,
                "hops" to group.hops.map { hop ->
                    linkedMapOf(
                        "index" to hop.index,
                        "chainIndex" to hop.chainIndex,
                        "profileId" to hop.profileId,
                        "port" to hop.localPort,
                        "finalAddress" to hop.finalAddress,
                        "finalPort" to hop.finalPort,
                        // 插件核心的配置沿用单节点格式，不带标识
                        "inboundTag" to hop.inboundTag.takeIf { group.core.merged },
                        "outboundTag" to hop.outboundTag.takeIf { group.core.merged },
                    )
                },
            )
        }
        val dynamicPorts = LinkedHashSet<Int>()
        dynamicPorts += structuralPorts(parseFirstJson(config.config))
        dynamicPorts += instance.externalPlan.hops.map { it.localPort }
        instance.controller?.let { dynamicPorts += it.first }
        val result = linkedMapOf<String, Any?>(
            "status" to "ok",
            "build" to linkedMapOf(
                "mainEntId" to config.mainEntId,
                "selectorGroupId" to config.selectorGroupId,
                "profileTagMap" to config.profileTagMap.entries.sortedBy { it.key }
                    .associateTo(LinkedHashMap()) { it.key.toString() to it.value },
                "trafficMap" to config.trafficMap.entries.sortedBy { it.key }
                    .associateTo(LinkedHashMap()) { (tag, list) -> tag to list.map { it.id } },
                "boxIndexNames" to config.boxIndexNames.toSortedMap(),
                "boxTagNames" to config.boxTagNames.toSortedMap(),
            ),
            "external" to external,
            "dynamic" to dynamic(
                dynamicPorts.toList(),
                paths.distinct(),
                listOfNotNull(instance.controller?.second),
            ),
        )
        return ModeOutput(result, files)
    } finally {
        // 从不抛出；删掉 init 期间建的临时文件
        instance.close()
    }
}

fun runExportMode(profile: ProxyEntity): ModeOutput {
    val (text, name) = try {
        profile.exportConfig()
    } catch (e: Exception) {
        return errorOutput(e)
    }
    // 导出路径拿不到 externalIndex：本机端口从 sing-box 配置的结构里找，临时文件路径
    // （导出时已删除，但路径留在配置里）按 cacheDir 前缀从原文里找
    val pathPattern = Regex(Regex.escape(app.cacheDir.absolutePath) + "/[A-Za-z0-9_.-]+")
    val result = linkedMapOf<String, Any?>(
        "status" to "ok",
        "exportName" to name,
        "dynamic" to dynamic(
            structuralPorts(parseFirstJson(text)),
            pathPattern.findAll(text).map { it.value }.distinct().toList(),
            emptyList(),
        ),
    )
    return ModeOutput(result, linkedMapOf("export.txt" to text))
}

private fun cacheFiles(dir: File): Set<String> =
    dir.listFiles()?.filter { it.isFile }?.map { it.absolutePath }?.toSet() ?: emptySet()

private fun dynamic(ports: List<Int>, paths: List<String>, secrets: List<String>) = linkedMapOf(
    "ports" to ports,
    "paths" to paths,
    "secrets" to secrets,
)

private fun errorOutput(e: Throwable): ModeOutput {
    val causes = ArrayList<Map<String, Any?>>()
    val seen = IdentityHashMap<Throwable, Unit>()
    seen[e] = Unit
    var cause = e.cause
    while (cause != null && seen.put(cause, Unit) == null) {
        causes += linkedMapOf("class" to cause.javaClass.name, "message" to cause.message)
        cause = cause.cause
    }
    return ModeOutput(
        linkedMapOf(
            "status" to "error",
            "error" to linkedMapOf(
                "class" to e.javaClass.name,
                "message" to e.message,
                "causes" to causes,
            ),
            "dynamic" to dynamic(emptyList(), emptyList(), emptyList()),
        ),
        emptyMap(),
    )
}

// 文本开头的第一个 JSON 值（导出原文在 sing-box 配置后面还接着外核配置）；解析不了返回 null
private fun parseFirstJson(text: String): JsonObject? = try {
    JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }) as? JsonObject
} catch (e: Exception) {
    null
}

// mkPort() 分到的端口在 sing-box 配置里的位置：连本机的 socks 出站（外核节点的本地端口）与
// 映射入站（tag 形如 <链 tag>-mapping-<节点 id>）。先出站后入站，各按数组顺序。
// 夹具里不出现指向 127.0.0.1 的用户节点，所以这两类都只可能来自构建
private fun structuralPorts(config: JsonObject?): List<Int> {
    if (config == null) return emptyList()
    val ports = ArrayList<Int>()
    fun JsonElement.primitive(key: String) = (this as? JsonObject)?.get(key) as? JsonPrimitive
    fun JsonElement.string(key: String) = primitive(key)?.takeIf { it.isString }?.asString
    fun JsonElement.int(key: String) = primitive(key)?.takeIf { it.isNumber }?.asInt
    (config.get("outbounds") as? JsonArray)?.forEach {
        if (it.string("type") == "socks" && it.string("server") == LOCALHOST) it.int("server_port")?.let(ports::add)
    }
    (config.get("inbounds") as? JsonArray)?.forEach {
        if (it.string("type") == "direct" && it.string("listen") == LOCALHOST &&
            it.string("tag")?.contains("-mapping-") == true
        ) it.int("listen_port")?.let(ports::add)
    }
    return ports.distinct()
}
