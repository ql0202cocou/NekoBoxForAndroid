package io.nekohasekai.sagernet.golden.collect

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

// 写进 configuration.db 的 Clash API secret 假值。不预先写好的话，requireClashApiSecret()
// 会随机生成一个并写回，两次采集就对不上
const val GOLDEN_CLASH_API_SECRET = "c1a5ba9e5ec4e7ab0de0f0ab1ec7a5e0"

// SingBoxOptionsUtil.domainStrategy 直接按这三个键读 configurationStore
const val KEY_DOMAIN_STRATEGY_REMOTE = "domain_strategy_for_remote"
const val KEY_DOMAIN_STRATEGY_DIRECT = "domain_strategy_for_direct"
const val KEY_DOMAIN_STRATEGY_SERVER = "domain_strategy_for_server"

// 一个场景构建时会读到的全部设置。null 表示不写这个键（构建按 DataStore 的默认值走），
// 其余一律显式写入。每个场景开始前先清空整张 KeyValuePair 表再写，没列在这里的键
// 也只会取代码里的默认值，不会受上一个场景或设备原有设置影响。
// 键的来源（沿调用链核对过）：ConfigBuilder 直接读的 17 项；SingBoxOptionsUtil.domainStrategy
// 读的三个 domain_strategy 键；TlsFields.effectiveAllowInsecure 读 globalAllowInsecure
// （也经 xrayLacksAllowInsecure → coreForType → resolvedCore 影响选核）；Xray / mihomo /
// Trojan-Go / naive / mieru 生成器与 hysteria 的 ExternalCore.launch 读 logLevel，
// Trojan-Go 生成器另读 ipv6Mode
data class GoldenSettings(
    val serviceMode: String? = Key.MODE_VPN,
    val allowAccess: Boolean? = false,
    val bypassLanInCore: Boolean? = false,
    val remoteDns: String? = "https://dns.example.net/dns-query",
    val directDns: String? = "https://192.0.2.53/dns-query",
    val enableDnsRouting: Boolean? = true,
    val enableFakeDns: Boolean? = true,
    val trafficSniffing: Int? = 1,
    val resolveDestination: Boolean? = false,
    val ipv6Mode: Int? = IPv6Mode.DISABLE,
    val logLevel: Int? = 0,
    val mixedPort: Int? = 2080,
    val mtu: Int? = 9000,
    val tunImplementation: Int? = TunImplementation.GVISOR,
    val globalCustomConfig: String? = "",
    val enableClashAPI: Boolean? = false,
    val clashApiSecret: String? = GOLDEN_CLASH_API_SECRET,
    val globalAllowInsecure: Boolean? = false,
    val domainStrategyRemote: String? = "auto",
    val domainStrategyDirect: String? = "auto",
    val domainStrategyServer: String? = "auto",
) {

    // 经 DataStore 的属性写入，存储类型（布尔 / 字符串化的整数等）与设置页写出的一致
    fun write() {
        val store = DataStore.configurationStore
        serviceMode?.let { DataStore.serviceMode = it }
        allowAccess?.let { DataStore.allowAccess = it }
        bypassLanInCore?.let { DataStore.bypassLanInCore = it }
        remoteDns?.let { DataStore.remoteDns = it }
        directDns?.let { DataStore.directDns = it }
        enableDnsRouting?.let { DataStore.enableDnsRouting = it }
        enableFakeDns?.let { DataStore.enableFakeDns = it }
        trafficSniffing?.let { DataStore.trafficSniffing = it }
        resolveDestination?.let { DataStore.resolveDestination = it }
        ipv6Mode?.let { DataStore.ipv6Mode = it }
        logLevel?.let { DataStore.logLevel = it }
        mixedPort?.let { DataStore.mixedPort = it }
        mtu?.let { DataStore.mtu = it }
        tunImplementation?.let { DataStore.tunImplementation = it }
        globalCustomConfig?.let { DataStore.globalCustomConfig = it }
        enableClashAPI?.let { DataStore.enableClashAPI = it }
        clashApiSecret?.let { store.putString(Key.CLASH_API_SECRET, it) }
        globalAllowInsecure?.let { DataStore.globalAllowInsecure = it }
        domainStrategyRemote?.let { store.putString(KEY_DOMAIN_STRATEGY_REMOTE, it) }
        domainStrategyDirect?.let { store.putString(KEY_DOMAIN_STRATEGY_DIRECT, it) }
        domainStrategyServer?.let { store.putString(KEY_DOMAIN_STRATEGY_SERVER, it) }
    }
}

class Scenario(
    val id: String,
    val description: String,
    val settings: GoldenSettings,
    val groups: List<ProxyGroup>,
    val profiles: List<ProxyEntity>,
    val rules: List<RuleEntity>,
    val mainProfileId: Long,
    // 用到的、按 UID 稳定性挑过的包名；只为写进 input.json 的包名 → UID 表
    val packages: List<String>,
)

// 场景表的构建器：节点、分组、规则都用显式 id 写入，g-<id> / c-<id>-… 这类 tag 每次一样
class ScenarioBuilder(private val id: String, private val description: String) {

    var main = 1L
    var settings = GoldenSettings()

    // 节点引用、但故意不建的分组（分组已被删除的情形）
    val missingGroups = mutableSetOf<Long>()

    private val groups = LinkedHashMap<Long, ProxyGroup>()
    private val profiles = mutableListOf<ProxyEntity>()
    private val rules = mutableListOf<RuleEntity>()
    private val orderInGroup = HashMap<Long, Long>()

    fun settings(block: GoldenSettings.() -> GoldenSettings) {
        settings = settings.block()
    }

    fun group(
        id: Long,
        name: String = "golden-group-$id",
        selector: Boolean = false,
        front: Long = -1L,
        landing: Long = -1L,
        nameserver: String = "",
    ) {
        require(id !in groups) { "duplicate group $id" }
        groups[id] = ProxyGroup(
            id = id,
            userOrder = id,
            name = name,
            type = GroupType.BASIC,
            isSelector = selector,
            frontProxy = front,
            landingProxy = landing,
            proxyServerNameserver = nameserver,
        )
    }

    // core：0 自动、1 sing-box、2 Xray、3 mihomo（ProxyEntity.CORE_*）
    fun node(id: Long, bean: AbstractBean, group: Long = 1L, core: Int = ProxyEntity.CORE_AUTO): Long {
        require(profiles.none { it.id == id }) { "duplicate profile $id" }
        val order = (orderInGroup[group] ?: 0L) + 1
        orderInGroup[group] = order
        profiles += ProxyEntity(id = id, groupId = group, userOrder = order, core = core)
            .putBean(bean.applyDefaultValues())
        return id
    }

    // 链成员按用户填写的顺序：第一个是最先拨号的一跳，最后一个是出口
    fun chain(id: Long, vararg members: Long, group: Long = 1L, name: String = "golden-chain-$id"): Long =
        node(id, ChainBean().apply {
            this.name = name
            proxies = members.toMutableList()
        }, group)

    fun rule(
        id: Long,
        outbound: Long,
        name: String = "golden-rule-$id",
        enabled: Boolean = true,
        block: RuleEntity.() -> Unit = {},
    ) {
        require(rules.none { it.id == id }) { "duplicate rule $id" }
        rules += RuleEntity(id = id, name = name, userOrder = id, enabled = enabled, outbound = outbound)
            .apply(block)
    }

    fun build(): Scenario {
        // 节点引用了却没声明的分组补成普通分组（missingGroups 除外）
        val referenced = profiles.map { it.groupId }.toSet() - missingGroups
        for (groupId in referenced.sorted()) {
            if (groupId !in groups) group(groupId)
        }
        return Scenario(
            id = id,
            description = description,
            settings = settings,
            groups = groups.values.toList(),
            profiles = profiles.toList(),
            rules = rules.toList(),
            mainProfileId = main,
            packages = rules.flatMap { it.packages }.distinct().sorted(),
        )
    }
}

class ScenarioTable {
    val scenarios = mutableListOf<Scenario>()

    fun scenario(id: String, description: String, block: ScenarioBuilder.() -> Unit) {
        require(SCENARIO_ID.matches(id)) { "bad scenario id $id" }
        require(scenarios.none { it.id == id }) { "duplicate scenario $id" }
        scenarios += ScenarioBuilder(id, description).apply(block).build()
    }

    companion object {
        private val SCENARIO_ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}

fun scenarioTable(block: ScenarioTable.() -> Unit): List<Scenario> =
    ScenarioTable().apply(block).scenarios
