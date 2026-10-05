package io.nekohasekai.sagernet.golden.collect

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.Libcore
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.plugin.Plugins
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 由 collect.sh 经 content call 的 extras 传进来的主机侧信息
// 内置核心：版本号与上游二进制的 sha256 取自 plugins.sh；packagedSha256 是 collect.sh 从
// 本次安装的 APK 里取出的同一文件（打包时 AGP 会 strip，与上游文件不再逐字节相同）
class CoreArgs(val version: String, val pinnedSha256: String, val packagedSha256: String)

// 有改动的路径只带总数与排序后的前若干条（命令行长度有限）；code* 是其中不在采集 / 比较工具
// 与文档目录下的那部分，即可能影响配置输出的改动
class DirtyArgs(val count: Int, val top: List<String>, val codeCount: Int, val codeTop: List<String>)

class CollectArgs(
    val commit: String,
    val dirty: DirtyArgs,
    val xray: CoreArgs,
    val mihomo: CoreArgs,
    val allowWipe: Boolean,
)

// 拒绝运行时抛它，消息原样交给 collect.sh 打印
class CollectRefusedException(message: String) : Exception(message)

class CollectSummary(val outDir: File, val scenarios: Int, val counts: Map<String, Int>)

// 一次完整采集：环境检查 → 防误伤检查 → 逐个场景写夹具、跑三种模式、落盘 →
// 地址语料 → manifest → 清掉夹具并恢复原有设置
class GoldenCollector(private val args: CollectArgs) {

    private val outDir = File(app.filesDir, OUTPUT_DIR)

    // 夹具写进数据库前建、全部清掉后删，内容是采集前的设置表：采集中途进程被杀时，下次据它认出
    // 库里的行是采集入口留下的，并按它恢复设置
    private val ownedMarker = File(app.filesDir, OWNED_MARKER)

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    fun collect(): CollectSummary {
        val scenarios = goldenScenarios()
        requireReflectionTargets()
        PackageCache.awaitLoadSync()
        val plugins = checkEnvironment()
        checkOwnership(scenarios)

        val kvDao = PublicDatabase.kvPairDao
        val savedSettings = ownedMarker.takeIf { it.isFile }
            ?.let { runCatching { decodeSettings(it.readText()) }.getOrNull() }
            ?: kvDao.all()
        val counts = linkedMapOf(
            "runOk" to 0, "runError" to 0,
            "testOk" to 0, "testError" to 0,
            "exportOk" to 0, "exportError" to 0,
        )
        ownedMarker.writeText(encodeSettings(savedSettings))
        try {
            outDir.deleteRecursively()
            val preserved = savedSettings.filter { it.key in PRESERVED_SETTINGS }
            for (scenario in scenarios) {
                collectScenario(scenario, plugins, preserved, counts)
            }
            writeJson(File(outDir, "address/corpus.json"), GoldenAddressCorpus.build())
            writeJson(File(outDir, "manifest.json"), manifest(scenarios.size, counts, plugins))
        } finally {
            clearTables()
            kvDao.reset()
            kvDao.insert(savedSettings)
            ownedMarker.delete()
        }
        return CollectSummary(outDir, scenarios.size, counts)
    }

    // 外部插件 app 会改变结果（hysteria 映射的取舍、选择器预检是否跳过），基线按「一个都没装」采集；
    // 设备上的内置核心必须就是 collect.sh 刚装的 APK 里那份（其源文件已在主机侧与 plugins.sh 核对），
    // manifest 里的版本号才可信
    private fun checkEnvironment(): Map<String, String> {
        val plugins = linkedMapOf<String, String>()
        val nativeDir = File(app.applicationInfo.nativeLibraryDir)
        for (id in listOf("xray-plugin", "mihomo-plugin") + PLUGINS_WITHOUT_APP) {
            val external = Plugins.getPluginExternal(id)
            plugins[id] = when {
                external != null -> "external:${external.packageName}"
                BUILTIN_CORES[id]?.let { File(nativeDir, it).canExecute() } == true -> "builtin"
                else -> "missing"
            }
        }
        val unexpected = plugins.filter { (id, state) -> state.startsWith("external:") && id in PLUGINS_WITHOUT_APP }
        if (unexpected.isNotEmpty()) throw CollectRefusedException(
            "external plugin apps are installed (${unexpected.values.joinToString()}); " +
                "the baseline assumes none, uninstall them first"
        )
        for ((id, core) in listOf("xray-plugin" to args.xray, "mihomo-plugin" to args.mihomo)) {
            val file = File(nativeDir, BUILTIN_CORES.getValue(id))
            if (!file.isFile) throw CollectRefusedException("built-in core ${file.name} is missing from the APK")
            val actual = sha256(file)
            if (actual != core.packagedSha256) throw CollectRefusedException(
                "installed ${file.name} has sha256 $actual but the APK collect.sh used carries " +
                    "${core.packagedSha256}; reinstall the APK that was just built"
            )
        }
        return plugins
    }

    // 防误伤：debug 包可能装在开发者自己的手机上。库里有不是采集入口建的节点 / 分组 / 规则时拒绝，
    // 除非触发时显式带了 allowWipe
    private fun checkOwnership(scenarios: List<Scenario>) {
        val groups = SagerDatabase.groupDao.allGroups()
        val profiles = SagerDatabase.proxyDao.getAll()
        val rules = SagerDatabase.rulesDao.allRules()
        if (groups.isEmpty() && profiles.isEmpty() && rules.isEmpty()) return
        // 上次采集中途被打断时留下的夹具：有标记文件，且所有 id 都在场景表用过的范围内
        val ours = ownedMarker.exists() &&
            groups.all { g -> scenarios.any { s -> s.groups.any { it.id == g.id } } } &&
            profiles.all { p -> scenarios.any { s -> s.profiles.any { it.id == p.id } } } &&
            rules.all { r -> scenarios.any { s -> s.rules.any { it.id == r.id } } }
        if (ours) return
        if (args.allowWipe) {
            Logs.w("golden collect: wiping ${groups.size} groups, ${profiles.size} profiles, ${rules.size} rules")
            return
        }
        throw CollectRefusedException(
            "the database holds ${groups.size} groups, ${profiles.size} profiles and ${rules.size} rules " +
                "that the collector did not create; collecting replaces all of them. " +
                "Re-run with --allow-wipe if this device holds nothing worth keeping"
        )
    }

    private fun clearTables() {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
        }
    }

    private fun collectScenario(
        scenario: Scenario,
        plugins: Map<String, String>,
        preserved: List<KeyValuePair>,
        counts: MutableMap<String, Int>,
    ) {
        // 设置：整表清空后只写场景声明的键，外加 PRESERVED_SETTINGS
        val kvDao = PublicDatabase.kvPairDao
        kvDao.reset()
        kvDao.insert(preserved)
        scenario.settings.write()
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(scenario.groups)
            SagerDatabase.proxyDao.insert(scenario.profiles)
            SagerDatabase.rulesDao.insert(scenario.rules)
        }

        val dir = File(outDir, "scenarios/${scenario.id}")
        val input = inputJson(scenario, plugins)
        writeJson(File(dir, "input.json"), input)
        val settingsBefore = settingsSnapshot()

        for (mode in MODES) {
            // 构建会改写 bean 的运行期字段（finalAddress / finalPort）：每种模式都从数据库重新取实体
            val profile = SagerDatabase.proxyDao.getById(scenario.mainProfileId)
                ?: error("scenario ${scenario.id}: main profile ${scenario.mainProfileId} is not in the table")
            val output = when (mode) {
                "run" -> runBoxMode(profile, forTest = false)
                "test" -> runBoxMode(profile, forTest = true)
                else -> runExportMode(profile)
            }
            val key = mode + if (output.ok) "Ok" else "Error"
            counts[key] = counts.getValue(key) + 1
            val modeDir = File(dir, mode)
            writeJson(File(modeDir, "result.json"), output.result)
            for ((name, content) in output.files) {
                modeDir.mkdirs()
                File(modeDir, name).writeText(content)
            }
            // 构建不该写设置（例如漏设 Clash API secret 时会随机生成并写回）：一旦写了，
            // 基线就不可复现，整次采集作废
            val settingsAfter = settingsSnapshot()
            check(settingsAfter == settingsBefore) {
                val changed = (settingsBefore.keys + settingsAfter.keys).filter { settingsBefore[it] != settingsAfter[it] }
                "scenario ${scenario.id} mode $mode changed settings ${changed.sorted()}"
            }
        }
        check(inputJson(scenario, plugins) == input) {
            "scenario ${scenario.id}: building changed the database"
        }
    }

    private fun encodeSettings(list: List<KeyValuePair>): String = gson.toJson(list.map {
        linkedMapOf("key" to it.key, "valueType" to it.valueType, "value" to Base64.getEncoder().encodeToString(it.value))
    })

    private fun decodeSettings(text: String): List<KeyValuePair> = JsonParser.parseString(text).asJsonArray.map {
        val row = it.asJsonObject
        KeyValuePair().apply {
            key = row["key"].asString
            valueType = row["valueType"].asInt
            value = Base64.getDecoder().decode(row["value"].asString)
        }
    }

    private fun settingsSnapshot(): Map<String, String> = PublicDatabase.kvPairDao.all()
        .associate { it.key to "${it.valueType}:${Base64.getEncoder().encodeToString(it.value)}" }

    private fun inputJson(scenario: Scenario, plugins: Map<String, String>): Map<String, Any?> {
        val groups = SagerDatabase.groupDao.allGroups().sortedBy { it.id }.map {
            linkedMapOf(
                "id" to it.id,
                "userOrder" to it.userOrder,
                "ungrouped" to it.ungrouped,
                "name" to it.name,
                "type" to it.type,
                "order" to it.order,
                "isSelector" to it.isSelector,
                "frontProxy" to it.frontProxy,
                "landingProxy" to it.landingProxy,
                "proxyServerNameserver" to it.proxyServerNameserver,
            )
        }
        val rules = SagerDatabase.rulesDao.allRules().sortedBy { it.id }.map {
            linkedMapOf(
                "id" to it.id,
                "name" to it.name,
                "config" to it.config,
                "userOrder" to it.userOrder,
                "enabled" to it.enabled,
                "domains" to it.domains,
                "ip" to it.ip,
                "port" to it.port,
                "sourcePort" to it.sourcePort,
                "network" to it.network,
                "source" to it.source,
                "protocol" to it.protocol,
                "outbound" to it.outbound,
                // 存储顺序（StringCollectionConverter 按逗号拼接），不排序
                "packages" to it.packages.toList(),
            )
        }
        return linkedMapOf(
            "formatVersion" to FORMAT_VERSION,
            "id" to scenario.id,
            "description" to scenario.description,
            "mainProfileId" to scenario.mainProfileId,
            "settings" to storedSettings(),
            "effectiveSettings" to effectiveSettings(),
            "groups" to groups,
            "profiles" to profileRows(),
            "rules" to rules,
            "packageUids" to scenario.packages.associateWithTo(LinkedHashMap()) { PackageCache[it] },
            "plugins" to plugins,
        )
    }

    // KeyValuePair 表的原样内容（按键排序）
    private fun storedSettings(): List<Map<String, Any?>> = PublicDatabase.kvPairDao.all().sortedBy { it.key }.map {
        val (type, value) = when (it.valueType) {
            KeyValuePair.TYPE_BOOLEAN -> "boolean" to it.boolean
            KeyValuePair.TYPE_FLOAT -> "float" to it.float
            KeyValuePair.TYPE_LONG -> "long" to it.long
            KeyValuePair.TYPE_STRING -> "string" to it.string
            KeyValuePair.TYPE_STRING_SET -> "stringSet" to it.stringSet?.sorted()
            else -> "type${it.valueType}" to Base64.getEncoder().encodeToString(it.value)
        }
        linkedMapOf("key" to it.key, "type" to type, "value" to value)
    }

    // 构建实际读到的值（未写的键按 DataStore 默认值）。secret 直接读原始键：
    // requireClashApiSecret() 在值为空时会生成并写回
    private fun effectiveSettings(): Map<String, Any?> = linkedMapOf(
        "serviceMode" to DataStore.serviceMode,
        "allowAccess" to DataStore.allowAccess,
        "bypassLanInCore" to DataStore.bypassLanInCore,
        "remoteDns" to DataStore.remoteDns,
        "directDns" to DataStore.directDns,
        "enableDnsRouting" to DataStore.enableDnsRouting,
        "enableFakeDns" to DataStore.enableFakeDns,
        "trafficSniffing" to DataStore.trafficSniffing,
        "resolveDestination" to DataStore.resolveDestination,
        "ipv6Mode" to DataStore.ipv6Mode,
        "logLevel" to DataStore.logLevel,
        "mixedPort" to DataStore.mixedPort,
        "mtu" to DataStore.mtu,
        "tunImplementation" to DataStore.tunImplementation,
        "globalCustomConfig" to DataStore.globalCustomConfig,
        "enableClashAPI" to DataStore.enableClashAPI,
        "clashApiSecret" to DataStore.configurationStore.getString(Key.CLASH_API_SECRET),
        "globalAllowInsecure" to DataStore.globalAllowInsecure,
        "domainStrategy" to linkedMapOf(
            "dns-remote" to SingBoxOptionsUtil.domainStrategy("dns-remote"),
            "dns-direct" to SingBoxOptionsUtil.domainStrategy("dns-direct"),
            "server" to SingBoxOptionsUtil.domainStrategy("server"),
        ),
    )

    // 节点：实体的标量字段，加上 bean 列的原始字节（直接从表里读，与写进数据库的字节相同）
    private fun profileRows(): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>()
        SagerDatabase.instance.openHelper.readableDatabase
            .query("SELECT * FROM proxy_entities ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                    val blobs = cursor.columnNames.filter { it.endsWith("Bean") }.mapNotNull { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        if (cursor.isNull(index)) null else column to cursor.getBlob(index)
                    }.filter { it.second.isNotEmpty() }
                    val (column, bytes) = blobs.singleOrNull()
                        ?: error("profile $id has ${blobs.size} bean columns set")
                    val entity = SagerDatabase.proxyDao.getById(id) ?: error("profile $id vanished")
                    rows += linkedMapOf(
                        "id" to id,
                        "groupId" to entity.groupId,
                        "type" to entity.type,
                        "userOrder" to entity.userOrder,
                        "tx" to entity.tx,
                        "rx" to entity.rx,
                        "status" to entity.status,
                        "ping" to entity.ping,
                        "uuid" to entity.uuid,
                        "error" to entity.error,
                        "core" to entity.core,
                        "beanColumn" to column,
                        "beanClass" to entity.requireBean().javaClass.name,
                        "beanKryoBase64" to Base64.getEncoder().encodeToString(bytes),
                    )
                }
            }
        return rows
    }

    private fun manifest(scenarioCount: Int, counts: Map<String, Int>, plugins: Map<String, String>): Map<String, Any?> {
        val versionBox = Libcore.versionBox()
        val singBoxVersion = versionBox.lineSequence().firstOrNull { it.startsWith("sing-box: ") }
            ?.removePrefix("sing-box: ")
        val nativeDir = File(app.applicationInfo.nativeLibraryDir)
        val system = linkedMapOf<String, Any?>(
            "sdkInt" to Build.VERSION.SDK_INT,
            "sdkIntFull" to if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL else null,
            "release" to Build.VERSION.RELEASE,
            "fingerprint" to Build.FINGERPRINT,
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
            "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
            "nativeLibraryDir" to nativeDir.name,
            "pageSize" to Os.sysconf(OsConstants._SC_PAGESIZE),
            "locale" to Locale.getDefault().toLanguageTag(),
        )
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return linkedMapOf(
            "formatVersion" to FORMAT_VERSION,
            "commit" to args.commit,
            "dirty" to linkedMapOf(
                "count" to args.dirty.count,
                "top" to args.dirty.top,
                "codeCount" to args.dirty.codeCount,
                "codeTop" to args.dirty.codeTop,
            ),
            "app" to linkedMapOf(
                "applicationId" to BuildConfig.APPLICATION_ID,
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "flavor" to BuildConfig.FLAVOR,
                "buildType" to BuildConfig.BUILD_TYPE,
            ),
            "cores" to linkedMapOf(
                "sing-box" to linkedMapOf("version" to singBoxVersion, "versionBox" to versionBox.lines()),
                // version / pinnedSha256 取自 plugins.sh（主机侧已核对 app/executableSo 下的源文件）；
                // installedSha256 是设备上那份文件的实算值（经 AGP strip）
                "xray" to coreManifest(args.xray, File(nativeDir, BUILTIN_CORES.getValue("xray-plugin"))),
                "mihomo" to coreManifest(args.mihomo, File(nativeDir, BUILTIN_CORES.getValue("mihomo-plugin"))),
            ),
            "plugins" to plugins,
            "system" to system,
            "scenarios" to scenarioCount,
            "counts" to counts,
            "collectedAt" to dateFormat.format(Date()),
        )
    }

    private fun coreManifest(core: CoreArgs, file: File) = linkedMapOf(
        "version" to core.version,
        "pinnedSha256" to core.pinnedSha256,
        "installedSha256" to sha256(file),
    )

    private fun writeJson(file: File, value: Any?) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(value) + "\n")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FORMAT_VERSION = 2
        const val OUTPUT_DIR = "golden-out"
        const val OWNED_MARKER = "golden-collect.owned"
        val MODES = listOf("run", "test", "export")

        // 进程启动时会读、缺了就写回的应用状态键（SagerNet.migrateLegacyAssets）。:bg 进程随
        // Room 的多实例失效服务随时可能被拉起，清掉它就会在采集中途被写回；与构建无关，原样保留
        val PRESERVED_SETTINGS = setOf(Key.LEGACY_ASSETS_MIGRATED)

        // 包里内置的核心（PluginManager.initNativeInternal 认的文件名）
        val BUILTIN_CORES = mapOf(
            "xray-plugin" to "libxray.so",
            "mihomo-plugin" to "libmihomo.so",
            "hysteria-plugin" to "libhysteria.so",
        )
    }
}
