package io.nekohasekai.sagernet.golden

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildDiagnostic
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.ConfigDataSource
import io.nekohasekai.sagernet.fmt.ConfigInput
import io.nekohasekai.sagernet.fmt.ConfigPlatform
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.FakeConfigPlatform
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.ProfileRecord
import io.nekohasekai.sagernet.fmt.assemble
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.dialAddress
import io.nekohasekai.sagernet.fmt.dialPort
import io.nekohasekai.sagernet.fmt.exportConfigText
import io.nekohasekai.sagernet.fmt.packagesToResolve
import java.io.File
import java.io.StringReader
import java.util.IdentityHashMap
import java.util.Random

// JVM 黄金测试的「采集」：输入取基线的 input.json，每种模式走「采集快照 → 纯构建 → 运行计划 → 组装 / 导出」，
// 产物按 app/src/debug 的采集入口（GoldenModes.kt）的格式写出，交给 GoldenCompareTree 与基线比较。
// 与模拟器采集的差别：
// - 不经过 Android 外壳（captureConfigInput 的 DataStore、Room 读取事务、PackageCache），输入由下面的 configInput
//   按 input.json 拼出，与外壳共用的只有两处模式判断（packagesToResolve、needsClashApiSecret）；
// - 平台换成假实现：地址解析查语料、端口从 50001 起递增、本机 socks 凭据用固定种子、测速控制 secret 是固定值，
//   这些都是比较工具按 dynamic 替换的动态值；
// - 组装时不做插件安装确认（BoxInstance 传给 assemble 的 beforeHop），也没有内置核心的启动前校验（只进 manifest）

/** 一种模式的产物（同采集入口的 result.json 与原文文件），以及构建产生的诊断与警告（基线里没有，另行断言）。 */
class GoldenJvmOutput(
    val result: Map<String, Any?>,
    val files: Map<String, String>,
    val diagnostics: List<ConfigBuildDiagnostic>,
    val warnings: List<String>,
)

/** 模式在产物树里的目录名。 */
val ConfigBuildMode.dir: String get() = name.lowercase()

/**
 * 一个场景在 JVM 上的三种模式。每种模式新建数据源、平台假实现与临时目录（建在 tempRoot 下），互不影响；
 * 平台按 input.json 的 plugins 回答插件查询（missing 同生产的「plugin X is not installed」）。
 */
class GoldenJvmScenario(
    private val input: GoldenScenarioInput,
    private val corpus: GoldenAddressCorpus,
    private val tempRoot: File,
) {
    val id get() = input.id

    // 生产上缺插件时 AndroidConfigPlatform 给的异常；基线在没有外部插件 app 的模拟器上采集
    private val pluginErrors: Map<String, Exception> = input.plugins.mapNotNull { (pluginId, state) ->
        when (state) {
            "builtin" -> null
            "missing" -> pluginId to IllegalStateException("plugin $pluginId is not installed")
            else -> error("${input.id}/input.json：插件 $pluginId 的状态 $state 在 JVM 上没有对应的假实现")
        }
    }.toMap()

    /** 按基线条件（input.json 的 plugins、地址语料）新建的平台假实现，临时文件建在 tempDir。 */
    fun platform(tempDir: File) = FakeConfigPlatform(parse = corpus::parse, pluginErrors = pluginErrors, tempDir = tempDir)

    /**
     * 按外壳 captureConfigInput 的步骤拼输入：主节点的记录取自 main（调用方的对象），引用闭包从 source 采集，
     * 要解析的包名与外壳同一个判断（packagesToResolve），UID 取 input.json 的 packageUids。
     */
    fun configInput(mode: ConfigBuildMode, main: ProxyEntity, source: ConfigDataSource, platform: ConfigPlatform): ConfigInput {
        val record = ProfileRecord.of(main)
        val snapshot = ConfigSnapshot.collect(source, record, mode)
        val uids = input.packageUids
        val packageUids = packagesToResolve(mode, snapshot).associateWith { pkg ->
            check(pkg in uids) { "${input.id}/input.json：packageUids 里没有 $pkg" }
            uids[pkg]
        }
        return ConfigInput(mode, record, input.configSettings(mode), snapshot, packageUids, platform)
    }

    /** 主节点：同采集入口，每次从数据源重新取。 */
    fun main(source: ConfigDataSource): ProxyEntity =
        source.profile(input.mainProfileId) ?: error("${input.id}：主节点 ${input.mainProfileId} 不在表里")

    fun run(mode: ConfigBuildMode): GoldenJvmOutput {
        val source = input.dataSource()
        val main = main(source)
        val tempDir = File(tempRoot, "${input.id}-${mode.dir}").apply { mkdirs() }
        val platform = platform(tempDir)
        val diagnostics = ArrayList<ConfigBuildDiagnostic>()
        // 本机 socks 凭据按场景与模式固定种子，失败时可复现
        val random = Random(31L * input.id.hashCode() + mode.ordinal)
        fun build() = buildConfig(configInput(mode, main, source, platform), diagnostics) { LocalSocksAuth.generate(random) }

        val (result, files) = try {
            if (mode == ConfigBuildMode.EXPORT) export(main.requireBean().displayName(), ::build, tempDir)
            else box(mode, ::build, platform, tempDir)
        } catch (e: Exception) {
            errorResult(e) to emptyMap()
        }
        return GoldenJvmOutput(result, files, diagnostics.toList(), platform.warnings.toList())
    }

    // run / test：同 GoldenModes.runBoxMode（BoxInstance.init 的构建、计划与组装）
    private fun box(
        mode: ConfigBuildMode,
        build: () -> ConfigBuildResult,
        platform: FakeConfigPlatform,
        tempDir: File,
    ): Pair<Map<String, Any?>, Map<String, String>> {
        val config = build()
        val plan = ExternalRunPlan.from(config)
        var controller: Pair<Int, String>? = null
        val created = ArrayList<String>()
        val processes = if (plan.hops.isEmpty()) emptyList() else plan.assemble(
            { prefix, ext -> File.createTempFile(prefix + "_", ".$ext", tempDir).also { created += it.absolutePath } },
            // 同 TestInstance.mihomoController：条件取构建结果，端口来自平台（生产是 mkPort），secret 固定
            if (mode == ConfigBuildMode.TEST && config.delayTestOnMihomo) {
                (platform.newPort() to TEST_CONTROLLER_SECRET).also { controller = it }
            } else null,
            config.requireExternalCoreSettings(),
        )
        val files = LinkedHashMap<String, String>()
        files["sing-box.json"] = config.config
        val external = ArrayList<Map<String, Any?>>()
        val paths = ArrayList<String>()
        processes.forEachIndexed { n, process ->
            val group = process.group
            val mihomo = group.pluginId == MIHOMO_PLUGIN
            val file = "ext-$n.${group.pluginId}.${if (mihomo) "yaml" else "json"}"
            files[file] = process.config
            val tempFiles = created.filter { process.config.contains(it) }
            paths += tempFiles
            val ctl = controller?.takeIf { mihomo }
            external += linkedMapOf(
                "file" to file,
                "pluginId" to group.pluginId,
                "controller" to ctl?.let { linkedMapOf("port" to it.first, "secret" to it.second) },
                "tempFiles" to tempFiles,
                "hops" to group.hops.map { hop ->
                    linkedMapOf(
                        "index" to hop.index,
                        "chainIndex" to hop.chainIndex,
                        "profileId" to hop.profileId,
                        "port" to hop.localPort,
                        // 基线格式 v3 的两个键：经映射时是本机与映射端口，不映射时是节点的 serverAddress 与 serverPort
                        // （hysteria 1 不映射时按 serverPorts 拨号，这里的端口不参与拨号）
                        "finalAddress" to hop.target.dialAddress(hop.bean),
                        "finalPort" to hop.target.dialPort(hop.bean),
                        "inboundTag" to hop.inboundTag.takeIf { group.core.merged },
                        "outboundTag" to hop.outboundTag.takeIf { group.core.merged },
                        "localAuth" to hop.localAuth?.let {
                            linkedMapOf("username" to it.username, "password" to it.password)
                        },
                    )
                },
            )
        }
        val ports = LinkedHashSet<Int>()
        ports += structuralPorts(parseFirstJson(config.config))
        ports += plan.hops.map { it.localPort }
        controller?.let { ports += it.first }
        val secrets = LinkedHashSet<String>()
        controller?.let { secrets += it.second }
        for (auth in listOfNotNull(config.localAuth) + plan.hops.mapNotNull { it.localAuth }) {
            secrets += auth.username
            secrets += auth.password
        }
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
            "dynamic" to dynamic(ports.toList(), paths.distinct(), secrets.toList()),
        )
        return result to files
    }

    // export：同 GoldenModes.runExportMode（ProxyEntity.exportConfig 的构建与 exportConfigText）
    private fun export(
        profileName: String,
        build: () -> ConfigBuildResult,
        tempDir: File,
    ): Pair<Map<String, Any?>, Map<String, String>> {
        val config = build()
        val tempFiles = ArrayList<File>()
        val (text, name) = try {
            exportConfigText(config, profileName) { prefix, ext ->
                File.createTempFile(prefix + "_", ".$ext", tempDir).also { tempFiles += it }
            }
        } finally {
            tempFiles.forEach { it.delete() }
        }
        val pathPattern = Regex(Regex.escape(tempDir.absolutePath) + "/[A-Za-z0-9_.-]+")
        val singBox = parseFirstJson(text)
        val result = linkedMapOf<String, Any?>(
            "status" to "ok",
            "exportName" to name,
            "dynamic" to dynamic(
                structuralPorts(singBox),
                pathPattern.findAll(text).map { it.value }.distinct().toList(),
                structuralLocalAuth(singBox),
            ),
        )
        return result to linkedMapOf("export.txt" to text)
    }

    companion object {
        const val MIHOMO_PLUGIN = "mihomo-plugin"
        const val TEST_CONTROLLER_SECRET = "0123456789abcdef0123456789abcdef"

        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

        /** 按采集入口的目录结构写一种模式的产物（result.json 与原文文件）。 */
        fun write(modeDir: File, output: GoldenJvmOutput) {
            modeDir.mkdirs()
            File(modeDir, "result.json").writeText(gson.toJson(output.result) + "\n")
            for ((name, content) in output.files) File(modeDir, name).writeText(content)
        }

        private fun dynamic(ports: List<Int>, paths: List<String>, secrets: List<String>) = linkedMapOf(
            "ports" to ports,
            "paths" to paths,
            "secrets" to secrets,
        )

        // 同 GoldenModes.errorOutput：异常类全名、message 与 cause 链
        fun errorResult(e: Throwable): Map<String, Any?> {
            val causes = ArrayList<Map<String, Any?>>()
            val seen = IdentityHashMap<Throwable, Unit>()
            seen[e] = Unit
            var cause = e.cause
            while (cause != null && seen.put(cause, Unit) == null) {
                causes += linkedMapOf("class" to cause.javaClass.name, "message" to cause.message)
                cause = cause.cause
            }
            return linkedMapOf(
                "status" to "error",
                "error" to linkedMapOf("class" to e.javaClass.name, "message" to e.message, "causes" to causes),
                "dynamic" to dynamic(emptyList(), emptyList(), emptyList()),
            )
        }

        // 以下同 GoldenModes：按 sing-box 配置的结构找动态值

        private fun parseFirstJson(text: String): JsonObject? = try {
            JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true }) as? JsonObject
        } catch (e: Exception) {
            null
        }

        private fun JsonElement.primitive(key: String) = (this as? JsonObject)?.get(key) as? JsonPrimitive
        private fun JsonElement.string(key: String) = primitive(key)?.takeIf { it.isString }?.asString
        private fun JsonElement.int(key: String) = primitive(key)?.takeIf { it.isNumber }?.asInt

        private fun localSocksOutbounds(config: JsonObject): List<JsonElement> =
            (config.get("outbounds") as? JsonArray)
                ?.filter { it.string("type") == "socks" && it.string("server") == LOCALHOST }.orEmpty()

        private fun structuralPorts(config: JsonObject?): List<Int> {
            if (config == null) return emptyList()
            val ports = ArrayList<Int>()
            localSocksOutbounds(config).forEach { it.int("server_port")?.let(ports::add) }
            (config.get("inbounds") as? JsonArray)?.forEach {
                if (it.string("type") == "direct" && it.string("listen") == LOCALHOST &&
                    it.string("tag")?.contains("-mapping-") == true
                ) it.int("listen_port")?.let(ports::add)
            }
            return ports.distinct()
        }

        private fun structuralLocalAuth(config: JsonObject?): List<String> {
            if (config == null) return emptyList()
            return localSocksOutbounds(config).flatMap { listOfNotNull(it.string("username"), it.string("password")) }
                .distinct()
        }
    }
}
