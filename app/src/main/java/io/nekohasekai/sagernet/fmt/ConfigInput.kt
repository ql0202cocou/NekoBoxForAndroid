package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import java.io.File
import java.net.InetAddress
import java.util.Collections

// 一次配置构建的全部输入（plan.md R1b）：设置、数据快照与平台能力。只用纯 Kotlin 类型，JVM 单测可以直接构造。
// Android 上由外壳采集（ConfigInputAndroid.kt）；四类输入在不同时刻取得，不是跨数据源的原子快照（见 ConfigInput）

/** 构建模式，对应外壳 buildConfig 的 forTest / forExport。 */
enum class ConfigBuildMode {
    RUN,
    TEST,
    EXPORT,
    ;

    companion object {
        /**
         * forTest 与 forExport 不能同时为真：没有调用方这样用，测速与导出的语义也合不到一起（旧代码会得到一份
         * 两头都不是的配置），直接拒绝。
         */
        fun of(forTest: Boolean, forExport: Boolean): ConfigBuildMode {
            require(!(forTest && forExport)) { "a config build is either a test or an export, not both" }
            return when {
                forTest -> TEST
                forExport -> EXPORT
                else -> RUN
            }
        }
    }
}

/**
 * 这次构建要解析 UID 的包名：测速不带规则（快照里也没有），其余取启用规则里出现的包名（按规则顺序去重）。
 * 外壳（captureConfigInput）与 JVM 黄金测试共用。
 */
fun packagesToResolve(mode: ConfigBuildMode, snapshot: ConfigSnapshot): Set<String> =
    if (mode == ConfigBuildMode.TEST) emptySet() else snapshot.enabledRules().flatMapTo(LinkedHashSet()) { it.packages }

/**
 * 这次构建是否要取 Clash API secret：只有运行模式且开了 Clash API（导出与测速的配置不带 secret）。
 * 外壳（ConfigSettings.fromDataStore）与 JVM 黄金测试共用。
 */
fun needsClashApiSecret(mode: ConfigBuildMode, enableClashAPI: Boolean): Boolean =
    mode == ConfigBuildMode.RUN && enableClashAPI

/**
 * 构建查了采集范围之外的输入（快照里没有的 id、没解析的包名）。这是采集算法的缺漏，不是某个节点的数据问题：
 * 构建不能把它当成节点错误吞掉或包上节点名（skipBroken、withProfileName 都原样抛出），整次构建失败。
 */
class ConfigInputScopeException(message: String) : IllegalStateException(message)

/** 构建读到的全部设置。值都是 DataStore 属性的结果（含默认值），采集见 ConfigInputAndroid.kt。 */
data class ConfigSettings(
    val serviceMode: String,
    val allowAccess: Boolean,
    val bypassLanInCore: Boolean,
    // 原文：每行一个服务器，构建里再拆行、去注释
    val remoteDns: String,
    val directDns: String,
    val enableDnsRouting: Boolean,
    val enableFakeDns: Boolean,
    val trafficSniffing: Int,
    val resolveDestination: Boolean,
    // IPv6Mode 原值；测速时构建自己固定为 ENABLE，外核设置仍用原值
    val ipv6Mode: Int,
    val logLevel: Int,
    val mixedPort: Int,
    val mtu: Int,
    val tunImplementation: Int,
    val globalCustomConfig: String,
    val enableClashAPI: Boolean,
    // 只在运行模式且开了 Clash API 时由外壳取得（为空时生成并写回），其余为 null
    val clashApiSecret: String?,
    val globalAllowInsecure: Boolean,
    // domainStrategyOf 对 dns-remote、dns-direct、server 三个 tag 的结果
    val domainStrategyRemote: String,
    val domainStrategyDirect: String,
    val domainStrategyServer: String,
) {
    /** 某个 DNS 服务器 tag 的 domain strategy，分支同 domainStrategyOf：分组 DNS（dns-group-N）等其余 tag 取 server 的。 */
    fun domainStrategy(tag: String): String = when (tag) {
        "dns-remote" -> domainStrategyRemote
        "dns-direct" -> domainStrategyDirect
        else -> domainStrategyServer
    }

    /** 外核生成器与启动参数读到的设置；ipv6Mode 是原值（测速也一样，同以前组装时再读 DataStore）。 */
    val externalCore: ExternalCoreSettings
        get() = ExternalCoreSettings(logLevel = logLevel, ipv6Mode = ipv6Mode, globalAllowInsecure = globalAllowInsecure)

    // 不输出 Clash API secret、DNS 服务器与全局自定义配置的原文：DoH 地址里可能带 token，自定义配置可能带凭据。
    // 只标出有没有值
    override fun toString(): String = "ConfigSettings(serviceMode=$serviceMode, allowAccess=$allowAccess, " +
            "bypassLanInCore=$bypassLanInCore, remoteDns=${masked(remoteDns)}, directDns=${masked(directDns)}, " +
            "enableDnsRouting=$enableDnsRouting, enableFakeDns=$enableFakeDns, trafficSniffing=$trafficSniffing, " +
            "resolveDestination=$resolveDestination, ipv6Mode=$ipv6Mode, logLevel=$logLevel, mixedPort=$mixedPort, " +
            "mtu=$mtu, tunImplementation=$tunImplementation, globalCustomConfig=${masked(globalCustomConfig)}, " +
            "enableClashAPI=$enableClashAPI, clashApiSecret=${clashApiSecret?.let { "***" }}, " +
            "globalAllowInsecure=$globalAllowInsecure, domainStrategyRemote=$domainStrategyRemote, " +
            "domainStrategyDirect=$domainStrategyDirect, domainStrategyServer=$domainStrategyServer)"

    private fun masked(text: String) = if (text.isEmpty()) "" else "***"

    companion object
}

/**
 * 一个节点行的不可变记录：标量字段加 bean 的 Kryo 字节。[newEntity] 每次都新建实体与 bean（严格反序列化，
 * 会走 initializeDefaultValues，所以 finalAddress / finalPort 总是服务器本身），与 DAO 每次查询给新对象一致。
 * bean 为 null 或类型未知的损坏行照样记录，报错留给构建原来的位置。
 */
class ProfileRecord private constructor(
    val id: Long,
    val groupId: Long,
    val type: Int,
    val userOrder: Long,
    val tx: Long,
    val rx: Long,
    val status: Int,
    val ping: Int,
    val uuid: String,
    val error: String?,
    val core: Int,
    // 空数组表示 bean 为 null；只在 newEntity 里读，不外传
    private val beanBytes: ByteArray,
) {
    fun newEntity(): ProxyEntity = ProxyEntity(
        id = id, groupId = groupId, type = type, userOrder = userOrder, tx = tx, rx = rx,
        status = status, ping = ping, uuid = uuid, error = error, core = core,
    ).apply { putByteArray(beanBytes) }

    companion object {
        fun of(entity: ProxyEntity) = ProfileRecord(
            entity.id, entity.groupId, entity.type, entity.userOrder, entity.tx, entity.rx,
            entity.status, entity.ping, entity.uuid, entity.error, entity.core,
            KryoConverters.serialize(entity.storedBean()),
        )
    }
}

// 节点存的 bean；bean 为 null 或类型未知（损坏数据）时为 null，不抛异常
private fun ProxyEntity.storedBean(): AbstractBean? = try {
    beanForType()
} catch (_: IllegalStateException) {
    null
}

// 链节点的成员 id，不是链（含 bean 损坏）时为 null；采集只管可达性，不在这里报错
private fun ProxyEntity.chainMembers(): List<Long>? = (storedBean() as? ChainBean)?.proxies

/** 规则指向的节点 id（排除内置出站与主节点）；构建与采集共用，保证查的是同一组 id。 */
internal fun ruleTargetIds(rules: List<RuleEntity>, mainId: Long): List<Long> =
    rules.mapNotNull { rule -> rule.outbound.takeIf { it > 0 && it != mainId } }.toHashSet().toList()

/**
 * 构建要读的数据，与 DAO 一一对应（groupDao.getById、proxyDao.getById / getEntities / getByGroup、
 * rulesDao.enabledRules）。生产实现只在采集用的 Room 读取事务里用；每次查询都给新对象。
 */
interface ConfigDataSource {
    fun group(id: Long): ProxyGroup?
    fun profile(id: Long): ProxyEntity?
    fun profiles(ids: List<Long>): List<ProxyEntity>
    fun profilesByGroup(groupId: Long): List<ProxyEntity>
    fun enabledRules(): List<RuleEntity>
}

/**
 * 一次构建的引用闭包（[collect] 采集）：主分组，选择器成员（运行模式且主分组是选择器），规则与规则目标（测速
 * 以外），以及这些根节点各自分组的前置 / 落地和逐层展开到底的链成员。查询的语义同 DAO：每次都给新的深拷贝，
 * 一次批量查询里同一 id 只给一个对象，已知缺失的返回 null（批量查询里省略）。查采集范围之外的 id 抛
 * [ConfigInputScopeException]：那是闭包算法的缺漏，不能静默。
 */
class ConfigSnapshot private constructor(
    // 采集过的分组，null 表示库里没有；不保留订阅信息（构建不读）
    private val groups: Map<Long, ProxyGroup?>,
    // 采集过的节点，null 表示已知缺失
    private val profiles: Map<Long, ProfileRecord?>,
    // 选择器分组 → 成员 id，按数据源返回的顺序（userOrder）
    private val groupMembers: Map<Long, List<Long>>,
    // 启用的规则，按数据源返回的顺序（userOrder）；测速不采集，为 null
    private val rules: List<RuleEntity>?,
    // 规则目标查询用的 id 集合与查到的节点 id（数据源返回的顺序）；测速不采集，为 null
    private val ruleTargetQuery: Set<Long>?,
    private val ruleTargetOrder: List<Long>,
) {
    /** 采集到的节点 id（含已知缺失的），给测试核对采集范围用。 */
    internal val profileIds: Set<Long> get() = profiles.keys

    /** 采集到的分组 id（含库里没有的）。 */
    internal val groupIds: Set<Long> get() = groups.keys

    fun group(id: Long): ProxyGroup? {
        if (id !in groups) throw ConfigInputScopeException("group $id is not in the config snapshot")
        return groups[id]?.copy()
    }

    fun profile(id: Long): ProxyEntity? {
        if (id !in profiles) throw ConfigInputScopeException("profile $id is not in the config snapshot")
        return profiles[id]?.newEntity()
    }

    /** 同 proxyDao.getEntities：查到的节点各一个新对象，按 id 升序（SQLite 按主键 IN 查询的顺序），缺失的省略。 */
    fun profiles(ids: List<Long>): List<ProxyEntity> = ids.distinct().sorted().mapNotNull { profile(it) }

    /** 同 proxyDao.getByGroup：只有采集过成员的选择器分组可查，顺序同采集时数据源返回的。 */
    fun profilesByGroup(groupId: Long): List<ProxyEntity> {
        val members = groupMembers[groupId]
            ?: throw ConfigInputScopeException("members of group $groupId are not in the config snapshot")
        return members.map { profiles.getValue(it)!!.newEntity() }
    }

    /** 同 rulesDao.enabledRules，每次都是新拷贝。 */
    fun enabledRules(): List<RuleEntity> =
        (rules ?: throw ConfigInputScopeException("rules are not in the config snapshot")).map { it.copy() }

    /**
     * 规则目标：ids 必须就是采集时查的那组（[ruleTargetIds]），按采集时数据源返回的顺序给新对象。
     * 构建按这个顺序建规则目标的出站，所以不重新排序。
     */
    fun ruleTargets(ids: List<Long>): List<ProxyEntity> {
        val query = ruleTargetQuery ?: throw ConfigInputScopeException("rule targets are not in the config snapshot")
        if (ids.toSet() != query) {
            throw ConfigInputScopeException("rule targets ${ids.sorted()} differ from the collected ${query.sorted()}")
        }
        return ruleTargetOrder.map { profiles.getValue(it)!!.newEntity() }
    }

    companion object {
        /**
         * 从数据源采集一次构建的引用闭包。生产上在一个 Room 读取事务里调用。只管可达性、不做校验：
         * 循环引用、空链、损坏的 bean 都照样采集（循环靠已展开集合截断），报错留给构建原来的位置。
         * main 是调用方传入节点的记录（不回读数据库），它的链成员总按它自己的 bean 展开。
         */
        fun collect(source: ConfigDataSource, main: ProfileRecord, mode: ConfigBuildMode): ConfigSnapshot {
            val groups = HashMap<Long, ProxyGroup?>()
            val profiles = HashMap<Long, ProfileRecord?>()
            val groupMembers = HashMap<Long, List<Long>>()
            // 已展开过成员的节点（数据源取来的）；同一 id 再遇到不再展开，循环引用也就此截断
            val expanded = HashSet<Long>()

            fun group(id: Long): ProxyGroup? {
                if (id !in groups) groups[id] = source.group(id)?.copy(subscription = null)
                return groups[id]
            }

            fun record(entity: ProxyEntity) {
                if (entity.id !in profiles) profiles[entity.id] = ProfileRecord.of(entity)
            }

            fun expandMembers(entity: ProxyEntity) {
                val members = entity.chainMembers() ?: return
                val found = source.profiles(members).associateBy { it.id }
                for (id in members) {
                    val member = found[id]
                    if (member == null) {
                        if (id !in profiles) profiles[id] = null
                        continue
                    }
                    record(member)
                    if (expanded.add(id)) expandMembers(member)
                }
            }

            // 根节点（构建对它调 resolveChain）：所在分组、分组的前置 / 落地（-1 也按 id 查，得 null），三者各自的链成员。
            // 主节点按调用方给的内容展开（可能与库里那一行不同），不占已展开集合：库里那一行被引用到时照样展开
            fun root(entity: ProxyEntity, isMain: Boolean) {
                val rootGroup = group(entity.groupId)
                if (rootGroup != null) {
                    for (id in listOf(rootGroup.frontProxy, rootGroup.landingProxy)) {
                        if (id !in profiles) profiles[id] = source.profile(id)?.let(ProfileRecord::of)
                        // 构建每次都重新查前置 / 落地，展开的是数据库里的那一行。已展开过的不再新建实体：
                        // 选择器成员多时每个根都会遇到同一组前置 / 落地，免得在独占的读取事务里重复反序列化
                        val hop = profiles[id] ?: continue
                        if (expanded.add(id)) expandMembers(hop.newEntity())
                    }
                }
                if (isMain || expanded.add(entity.id)) expandMembers(entity)
            }

            val mainEntity = main.newEntity()
            val mainGroup = group(mainEntity.groupId)
            val rules = if (mode == ConfigBuildMode.TEST) null else source.enabledRules().map {
                it.copy(packages = Collections.unmodifiableSet(LinkedHashSet(it.packages)))
            }
            val roots = ArrayList<ProxyEntity>()
            // 同 ConfigBuild.selectorGroup：只有运行模式按选择器构建
            if (mode == ConfigBuildMode.RUN && mainGroup != null && mainGroup.isSelector) {
                val members = source.profilesByGroup(mainGroup.id)
                members.forEach(::record)
                groupMembers[mainGroup.id] = members.map { it.id }
                roots += members
            }
            var ruleTargetQuery: Set<Long>? = null
            val ruleTargetOrder = ArrayList<Long>()
            if (rules != null) {
                val ids = ruleTargetIds(rules, mainEntity.id)
                val found = source.profiles(ids).distinctBy { it.id }
                ruleTargetQuery = ids.toSet()
                found.forEach(::record)
                found.mapTo(ruleTargetOrder) { it.id }
                for (id in ids) if (id !in profiles) profiles[id] = null
                roots += found
            }
            root(mainEntity, isMain = true)
            roots.forEach { root(it, isMain = false) }
            return ConfigSnapshot(groups, profiles, groupMembers, rules, ruleTargetQuery, ruleTargetOrder)
        }
    }
}

/**
 * 构建要用的平台能力。每次构建新建一个实例（Android 实现见 ConfigInputAndroid.kt），JVM 测试给假实现。
 * 插件状态在构建中按需查询，分两种：外部插件 app（[pluginExternalAuthority]）与插件可用性（[pluginError]）。
 * ConfigBuild 按插件分别记住两种查询的结果，同一次构建里同一插件的同一种查询只做一次；hysteria 插件两种都会用到，
 * 各查一次。
 */
interface ConfigPlatform {
    /** 一个空闲的本机端口：外核跳实例的 socks 入站、映射入站各一个。 */
    fun newPort(): Int

    /** 数字地址字面量（IPv4 / IPv6）解析成地址，不是数字地址时返回 null；不查 DNS。 */
    fun parseNumericAddress(text: String): InetAddress?

    /** 预检试生成外核配置时要的临时文件（hysteria 1 的 CA），用完即删。 */
    fun createTempFile(prefix: String, ext: String): File

    /** 外部插件 app 的 provider authority，没有外部插件 app 时为 null。 */
    fun pluginExternalAuthority(pluginId: String): String?

    /** 插件不可用的原因，可用时为 null。 */
    fun pluginError(pluginId: String): Exception?

    /** 构建中的警告（跳过的成员、缺失的引用等），只记日志。 */
    fun warn(message: String, error: Throwable? = null)
}

/**
 * 一次构建的输入。main 是调用方传入节点的记录，构建从它新建主节点、不改调用方的对象；data 只能是快照，
 * 不能是 DAO。设置、Room 数据、包名 UID、插件状态在不同时刻取得（设置在 Room 事务之前，UID 在事务之后，
 * 插件在构建中），这与以前逐项读取的语义相同，不是跨数据源的原子快照。
 */
class ConfigInput(
    val mode: ConfigBuildMode,
    val main: ProfileRecord,
    val settings: ConfigSettings,
    val data: ConfigSnapshot,
    // 启用规则里出现的包名 → UID（未安装为 null）；没有带应用的规则时为空
    packageUids: Map<String, Int?>,
    val platform: ConfigPlatform,
) {
    val packageUids: Map<String, Int?> = Collections.unmodifiableMap(LinkedHashMap(packageUids))
}
