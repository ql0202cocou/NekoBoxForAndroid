package io.nekohasekai.sagernet.golden

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ConfigBuildMode
import io.nekohasekai.sagernet.fmt.ConfigSettings
import io.nekohasekai.sagernet.fmt.ExternalCoreGroup
import io.nekohasekai.sagernet.fmt.ExternalCoreSettings
import io.nekohasekai.sagernet.fmt.ExternalDialTarget
import io.nekohasekai.sagernet.fmt.ExternalHop
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.MemoryConfigDataSource
import io.nekohasekai.sagernet.fmt.needsClashApiSecret
import io.nekohasekai.sagernet.fmt.putByteArray
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
 * 基线里的一份外核配置（一组跳实例）：重新生成它所需的全部输入，加上基线里的配置原文。
 * plan 是这个场景这个模式的完整外核运行计划（按 result.json 的 external 重建），用例要的是其中第 groupIndex 组。
 */
class GoldenExternalCase(
    val scenarioId: String,
    val mode: GoldenBoxMode,
    /** 配置原文的文件名 ext-<n>.<pluginId>.<json|yaml>（n 即组序号），也是比较报告里的文档名。 */
    val file: String,
    val groupIndex: Int,
    val pluginId: String,
    /** 跳实例的 bean 由 input.json 的 Kryo 字节新建，拨号目标按记录还原；用例之间不共享。 */
    val plan: ExternalRunPlan,
    /** 测速时 mihomo 的 Clash API 端口与 secret，其余情况为 null。 */
    val controller: Pair<Int, String>?,
    /** 采集时经 cacheFile 领到、且出现在配置原文里的临时文件路径（hysteria 1 的 CA），比较时按动态路径处理。 */
    val tempFiles: List<String>,
    val settings: ExternalCoreSettings,
    val expected: String,
    val format: GoldenFormat,
) {
    val group: ExternalCoreGroup get() = plan.groups[groupIndex]

    override fun toString() = "$scenarioId / $mode / $file"
}

/** result.json 的 external 里一组的原样记录。 */
class GoldenExternalGroup(
    val file: String,
    val pluginId: String,
    val controller: Pair<Int, String>?,
    val tempFiles: List<String>,
    val hops: List<GoldenExternalHop>,
)

/** 一组里一个跳实例的记录；插件核心的配置不带标识，两个 tag 为 null；入站不认证的 localAuth 为 null。 */
class GoldenExternalHop(
    val index: Int,
    val chainIndex: Int,
    val profileId: Long,
    val port: Int,
    val finalAddress: String,
    val finalPort: Int,
    val inboundTag: String?,
    val outboundTag: String?,
    val localAuth: LocalSocksAuth?,
)

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

    /** 被选中运行的节点。 */
    val mainProfileId: Long get() = json.long("mainProfileId", "$id/input.json")

    /** 六个外核插件 id 的状态：builtin、missing 或 external:<包名>。 */
    val plugins: Map<String, String>
        get() = json.obj("plugins", "$id/input.json").entrySet().associateTo(LinkedHashMap()) { (key, value) ->
            key to (value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: error("$id/input.json：plugins.$key 不是字符串"))
        }

    /** 规则里出现的包名 → 采集时 PackageCache 解析出的 UID（未安装为 null）。 */
    val packageUids: Map<String, Int?>
        get() {
            val uids = json.obj("packageUids", "$id/input.json")
            return uids.keySet().associateWithTo(LinkedHashMap()) { key ->
                if (uids[key].isJsonNull) null else uids.int(key, "$id/input.json packageUids")
            }
        }

    /**
     * effectiveSettings 是构建读到的设置（DataStore 属性的值）；mode 决定 Clash API secret 取不取，与生产的
     * ConfigSettings.fromDataStore 共用同一个判断（needsClashApiSecret）。
     */
    fun configSettings(mode: ConfigBuildMode): ConfigSettings {
        val where = "$id/input.json effectiveSettings"
        val e = json.obj("effectiveSettings", "$id/input.json")
        val strategy = e.obj("domainStrategy", where)
        val enableClashAPI = e.bool("enableClashAPI", where)
        return ConfigSettings(
            serviceMode = e.string("serviceMode", where),
            allowAccess = e.bool("allowAccess", where),
            bypassLanInCore = e.bool("bypassLanInCore", where),
            remoteDns = e.string("remoteDns", where),
            directDns = e.string("directDns", where),
            enableDnsRouting = e.bool("enableDnsRouting", where),
            enableFakeDns = e.bool("enableFakeDns", where),
            trafficSniffing = e.int("trafficSniffing", where),
            resolveDestination = e.bool("resolveDestination", where),
            ipv6Mode = e.int("ipv6Mode", where),
            logLevel = e.int("logLevel", where),
            mixedPort = e.int("mixedPort", where),
            mtu = e.int("mtu", where),
            tunImplementation = e.int("tunImplementation", where),
            globalCustomConfig = e.string("globalCustomConfig", where),
            enableClashAPI = enableClashAPI,
            clashApiSecret = if (needsClashApiSecret(mode, enableClashAPI)) e.string("clashApiSecret", where) else null,
            globalAllowInsecure = e.bool("globalAllowInsecure", where),
            domainStrategyRemote = strategy.string("dns-remote", "$where domainStrategy"),
            domainStrategyDirect = strategy.string("dns-direct", "$where domainStrategy"),
            domainStrategyServer = strategy.string("server", "$where domainStrategy"),
        )
    }

    /** groups / profiles / rules 三张表重建的内存数据源，查询语义同 DAO；每次调用都是新的一份。 */
    fun dataSource(): MemoryConfigDataSource {
        val groups = json.array("groups", "$id/input.json").map { it.asJsonObject }.map { g ->
            val where = "$id/input.json 分组"
            ProxyGroup(
                id = g.long("id", where),
                userOrder = g.long("userOrder", where),
                ungrouped = g.bool("ungrouped", where),
                name = g.optString("name", where),
                type = g.int("type", where),
                order = g.int("order", where),
                isSelector = g.bool("isSelector", where),
                frontProxy = g.long("frontProxy", where),
                landingProxy = g.long("landingProxy", where),
                proxyServerNameserver = g.string("proxyServerNameserver", where),
            )
        }
        val profiles = json.array("profiles", "$id/input.json").map { it.asJsonObject }.map { p ->
            val where = "$id/input.json 节点 ${p.long("id", "$id/input.json 节点")}"
            ProxyEntity(
                id = p.long("id", where),
                groupId = p.long("groupId", where),
                type = p.int("type", where),
                userOrder = p.long("userOrder", where),
                tx = p.long("tx", where),
                rx = p.long("rx", where),
                status = p.int("status", where),
                ping = p.int("ping", where),
                uuid = p.string("uuid", where),
                error = p.optString("error", where),
                core = p.int("core", where),
            ).apply { putByteArray(Base64.getDecoder().decode(p.string("beanKryoBase64", where))) }
        }
        val rules = json.array("rules", "$id/input.json").map { it.asJsonObject }.map { r ->
            val where = "$id/input.json 规则"
            RuleEntity(
                id = r.long("id", where),
                name = r.string("name", where),
                config = r.string("config", where),
                userOrder = r.long("userOrder", where),
                enabled = r.bool("enabled", where),
                domains = r.string("domains", where),
                ip = r.string("ip", where),
                port = r.string("port", where),
                sourcePort = r.string("sourcePort", where),
                network = r.string("network", where),
                source = r.string("source", where),
                protocol = r.string("protocol", where),
                outbound = r.long("outbound", where),
                packages = r.array("packages", where).mapTo(LinkedHashSet()) { it.asString },
            )
        }
        return MemoryConfigDataSource(groups, profiles, rules)
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
     * 某个外核在基线里的全部配置（run 与 test 两种模式），按场景 id、模式、组的顺序排列。
     * 只有 status 为 ok 的模式有外核配置。每个用例都按 result.json 重建一份完整的计划，bean 都是新建的。
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
                val groups = externalGroups(result, where)
                checkExternalFiles(modeDir, groups, where)
                groups.forEachIndexed { groupIndex, group ->
                    if (group.pluginId != pluginId) return@forEachIndexed
                    cases += externalCase(input, mode, modeDir, groups, groupIndex, where)
                }
            }
        }
        return cases
    }

    /** 一个模式的 result.json 里 external 的原样记录（一组一项）。 */
    fun externalGroups(scenarioId: String, mode: GoldenBoxMode): List<GoldenExternalGroup> {
        val where = "$scenarioId/$mode/result.json"
        val result = readObject(root.resolve("scenarios/$scenarioId/${mode.dir}/result.json"), where)
        if (result.string("status", where) != "ok") return emptyList()
        return externalGroups(result, where)
    }

    private fun externalGroups(result: JsonObject, where: String): List<GoldenExternalGroup> =
        result.array("external", where).mapIndexed { n, element ->
            val entry = element.asJsonObject
            val file = entry.string("file", where)
            val at = "$where external $file"
            GoldenExternalGroup(
                file = file,
                pluginId = entry.string("pluginId", at),
                controller = entry["controller"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let {
                    it.int("port", "$at controller") to it.string("secret", "$at controller")
                },
                tempFiles = entry.array("tempFiles", at).map { it.asString },
                hops = entry.array("hops", at).map { it.asJsonObject }.map { hop ->
                    GoldenExternalHop(
                        index = hop.int("index", at),
                        chainIndex = hop.int("chainIndex", at),
                        profileId = hop.long("profileId", at),
                        port = hop.int("port", at),
                        finalAddress = hop.string("finalAddress", at),
                        finalPort = hop.int("finalPort", at),
                        inboundTag = hop.optString("inboundTag", at),
                        outboundTag = hop.optString("outboundTag", at),
                        localAuth = hop.localAuth(at),
                    )
                }.also { check(it.isNotEmpty()) { "$at：第 $n 组没有跳实例" } },
            )
        }

    // 按 result.json 重建这个模式的完整计划：跳实例按 index 排好，bean 从 input.json 新建，拨号目标按记录的
    // finalAddress / finalPort 还原（本机地址是经映射，端口即映射端口；其余是不映射，记录的必须正是节点的
    // serverAddress 与 serverPort），本机 socks 凭据用记录的值（计划自己检查哪些核心必须有、一个计划只有一组）。
    // 计划自己分出的组必须与记录的一致
    private fun rebuildPlan(input: GoldenScenarioInput, groups: List<GoldenExternalGroup>, where: String): ExternalRunPlan {
        val hops = groups.flatMap { it.hops }.sortedBy { it.index }
        check(hops.map { it.index } == hops.indices.toList()) { "$where：跳实例序号 ${hops.map { it.index }} 不连续" }
        val plan = ExternalRunPlan(hops.map { hop ->
            val bean = input.newBean(hop.profileId)
            // 服务器地址本身就是本机时分不清是否经映射（记录的两个值可能相同）：基线里不该有这样的节点
            check(bean.serverAddress != LOCALHOST) { "$where：跳实例 ${hop.index} 的服务器地址是本机，分不清是否经映射" }
            val target = if (hop.finalAddress == LOCALHOST) {
                ExternalDialTarget.Mapped(hop.finalPort)
            } else {
                check(hop.finalAddress == bean.serverAddress && hop.finalPort == bean.serverPort) {
                    "$where：跳实例 ${hop.index} 不映射，记录的 ${hop.finalAddress}:${hop.finalPort} 却不是节点的服务器"
                }
                ExternalDialTarget.Direct
            }
            ExternalHop(hop.index, hop.chainIndex, hop.profileId, bean, hop.port, target, localAuth = hop.localAuth)
        })
        val planned = plan.groups.map { group -> group.pluginId to group.hops.map { it.index } }
        val recorded = groups.map { group -> group.pluginId to group.hops.map { it.index } }
        check(planned == recorded) { "$where：计划分出的组 $planned 与记录的 $recorded 不一致" }
        // 记录的标识就是计划给的（插件核心记为 null）
        for (hop in hops) {
            val planHop = plan.hops[hop.index]
            val tags = if (planHop.core.merged) planHop.inboundTag to planHop.outboundTag else null to null
            check(hop.inboundTag to hop.outboundTag == tags) { "$where：跳实例 ${hop.index} 记录的标识与计划不一致" }
        }
        return plan
    }

    private fun externalCase(
        input: GoldenScenarioInput,
        mode: GoldenBoxMode,
        modeDir: File,
        groups: List<GoldenExternalGroup>,
        groupIndex: Int,
        resultWhere: String,
    ): GoldenExternalCase {
        val group = groups[groupIndex]
        val format = when (group.file.substringAfterLast('.')) {
            "json" -> GoldenFormat.JSON
            "yaml" -> GoldenFormat.YAML
            else -> error("$resultWhere external ${group.file}：不认识的文件扩展名")
        }
        return GoldenExternalCase(
            scenarioId = input.id,
            mode = mode,
            file = group.file,
            groupIndex = groupIndex,
            pluginId = group.pluginId,
            plan = rebuildPlan(input, groups, resultWhere),
            controller = group.controller,
            tempFiles = group.tempFiles,
            settings = input.externalCoreSettings,
            expected = File(modeDir, group.file).readText(),
            format = format,
        )
    }

    // external 列出的文件与目录里的 ext-* 文件必须一一对应，且按 ext-0、ext-1……的顺序
    private fun checkExternalFiles(modeDir: File, groups: List<GoldenExternalGroup>, where: String) {
        val listed = groups.map { it.file }
        groups.forEachIndexed { n, group ->
            check(group.file.startsWith("ext-$n.${group.pluginId}.")) { "$where：第 $n 组的文件名是 ${group.file}" }
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
        private fun locate(): GoldenBaseline? {
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

        /**
         * 同 [locate]，找不到基线时直接失败：基线已经入库，跳过会让目录被挪走或改名之后测试仍然是绿的。
         */
        fun load(): GoldenBaseline =
            checkNotNull(locate()) { "找不到黄金测试基线（app/src/test/resources/golden/，要有 scenarios/*/input.json）" }

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

// 格式 3 起每个跳实例都有 localAuth：{username, password}，入站不认证时为 null
private fun JsonObject.localAuth(where: String): LocalSocksAuth? {
    val value = get("localAuth") ?: error("$where：缺少字段 localAuth")
    if (value.isJsonNull) return null
    val auth = value.takeIf { it.isJsonObject }?.asJsonObject ?: error("$where：localAuth 不是对象")
    return LocalSocksAuth(auth.string("username", "$where localAuth"), auth.string("password", "$where localAuth"))
}

private fun JsonObject.optString(key: String, where: String): String? =
    get(key)?.takeUnless { it.isJsonNull }?.let { value ->
        value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: error("$where：$key 不是字符串")
    }

private fun JsonObject.bool(key: String, where: String): Boolean =
    field(key, where).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        ?: error("$where：$key 不是布尔值")

private fun JsonObject.long(key: String, where: String): Long =
    field(key, where).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
        ?: error("$where：$key 不是整数")

private fun JsonObject.int(key: String, where: String): Int =
    long(key, where).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else error("$where：$key 超出 Int 范围") }
