package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import java.io.File

// 外核运行计划（plan.md K0 做法 1）：一次构建里全部的外核跳实例，以及它们按核心分成的组。
// 计划只从构建结果里登记的外核跳数据（ConfigBuildResult.externalChains）得到，不读 bean 上的任何可变状态。
// 运行（BoxInstance）、测速（TestInstance）、导出（ProxyEntity.exportConfig）都经 assemble 从计划生成
// 外核配置，ConfigBuild.precheck 也经它试生成单个节点。只用纯 Kotlin 类型，JVM 单测可以手工构造

/** 外核跳实例的拨号目标：外核自己的出站连到哪里。 */
sealed interface ExternalDialTarget {
    /**
     * 经映射：外核拨本机 [LOCALHOST] 上 sing-box 的映射入站（port），sing-box 再从这个入站以已 protect 的
     * socket 连服务器。
     */
    data class Mapped(val port: Int) : ExternalDialTarget

    /**
     * 不映射：外核自己拨节点的服务器（hysteria 1 走 Matsuri exe 插件时最先拨号的一跳、不能映射的节点、预检的
     * 试生成）。地址端口怎么取由各生成器按协议决定，hysteria 1 按 serverPorts 拨号。
     */
    data object Direct : ExternalDialTarget
}

/**
 * 按节点 serverPort 拨号的核心（Xray、mihomo、Trojan-Go、Mieru、Naive）连的地址：经映射时是本机，不映射时是节点的
 * 服务器地址。
 */
fun ExternalDialTarget.dialAddress(bean: AbstractBean): String? = when (this) {
    is ExternalDialTarget.Mapped -> LOCALHOST
    ExternalDialTarget.Direct -> bean.serverAddress
}

/**
 * 同 [dialAddress] 的端口：经映射时是映射入站的端口，不映射时是节点的 serverPort。hysteria 1 不映射时按
 * serverPorts 拨号，这个值对它没有意义（只用于黄金基线的 finalPort 记录）。
 */
fun ExternalDialTarget.dialPort(bean: AbstractBean): Int = when (this) {
    is ExternalDialTarget.Mapped -> port
    ExternalDialTarget.Direct -> bean.serverPort
}

/**
 * 构建登记的一个走外核（needExternal）的节点：sing-box 一侧的 socks 出站连 localPort，带 localAuth（核心的入站
 * 不认证时为 null）；target 是外核自己的拨号目标。core 为 null 的节点（NekoBean）没有外核条目，计划跳过它，
 * 但它照样让导出的文件名成为 profiles.txt（见 [exportConfigText]）。bean 是构建自己的拷贝，构建不改写它。
 */
class ExternalHopRecord(
    val profileId: Long,
    val bean: AbstractBean,
    val core: ExternalCore?,
    val localPort: Int,
    val target: ExternalDialTarget,
    val localAuth: LocalSocksAuth?,
)

/**
 * 构建登记的一条链（buildChain 每调用一次一条，按调用顺序）：hops 是链上走外核的节点，按 buildHop 的顺序；
 * 没有外核节点的链也有一条，hops 为空，它照样占一个链序号。
 */
class ExternalChainRecord(val hops: List<ExternalHopRecord>)

/**
 * 一个外核跳实例：某条链上一个走外核的节点。同一个节点出现在不同链里是不同的跳实例，
 * 各有各的本机端口与拨号目标，不能按节点 id 合并。
 */
class ExternalHop(
    /** 计划内序号：按 externalChains 的遍历顺序从 0 起；配置里的标识由它生成。 */
    val index: Int,
    /** 所在链在 externalChains 里的序号。 */
    val chainIndex: Int,
    val profileId: Long,
    val bean: AbstractBean,
    /** sing-box 的 socks 出站连到的本机端口，外核在这里开 socks 入站。 */
    val localPort: Int,
    /** 外核出站的拨号目标：经映射时拨本机的映射入站，不映射时自己拨服务器。 */
    val target: ExternalDialTarget,
    val core: ExternalCore = requireNotNull(externalCore(bean)) {
        "${bean.javaClass.simpleName} does not run on an external core"
    },
    /**
     * 外核在 localPort 上的 socks 入站要求的凭据，null 表示不认证。核心声明入站支持认证（[ExternalCore.inboundAuth]）
     * 时必须有，否则必须没有，由 [ExternalRunPlan] 检查；sing-box 一侧的 socks 出站带的是同一个值。
     */
    val localAuth: LocalSocksAuth? = null,
) {
    val pluginId get() = core.pluginId

    /** 合并配置里这个跳实例的入站 tag（Xray）/ listener 名（mihomo）。插件核心的配置沿用单节点格式，不带标识。 */
    val inboundTag = "in-$index"

    /** 合并配置里这个跳实例的出站 tag（Xray）/ 代理名（mihomo）。 */
    val outboundTag = "out-$index"
}

/**
 * 一组跳实例：一份外核配置、一个进程。合并核心（Xray、mihomo）一个计划里每种只有一组，
 * 其余（要装插件 app 的核心）每个跳实例单独一组。
 */
class ExternalCoreGroup(val core: ExternalCore, val hops: List<ExternalHop>) {
    val pluginId get() = core.pluginId
}

class ExternalRunPlan(val hops: List<ExternalHop>) {

    init {
        hops.forEachIndexed { position, hop ->
            require(hop.index == position) { "external hop ${hop.index} is listed at position $position" }
            require(hop.localPort in 1..65535) { "external hop ${hop.index} has local port ${hop.localPort}" }
        }
        // Xray、mihomo 对重复的监听端口都不报错，只有一个监听生效，另一个跳实例的流量无处可去：由计划保证
        hops.groupBy { it.localPort }.values.firstOrNull { it.size > 1 }?.let { same ->
            throw IllegalStateException(
                "external hops ${same.joinToString { it.index.toString() }} share local port ${same[0].localPort}"
            )
        }
        // 入站认证跟着核心的声明走：要认证的跳实例拿不到凭据就不建计划（不会生成不认证的入站），
        // 不认证的核心也不带凭据（sing-box 一侧不会出示）；一个计划只有一组凭据
        for (hop in hops) {
            if (hop.core.inboundAuth) {
                checkNotNull(hop.localAuth) {
                    "external hop ${hop.index} (${hop.pluginId}) needs local socks credentials, but the build has none"
                }
            } else {
                check(hop.localAuth == null) {
                    "external hop ${hop.index} (${hop.pluginId}) does not take local socks credentials"
                }
            }
        }
        check(hops.mapNotNull { it.localAuth }.distinct().size <= 1) {
            "external hops of one plan carry different local socks credentials"
        }
    }

    /** 按核心分组；组的顺序按各组第一个跳实例在计划里的顺序，组内按计划顺序。 */
    val groups: List<ExternalCoreGroup> = hops
        .groupBy { if (it.core.merged) it.pluginId else "${it.pluginId}#${it.index}" }
        .values.map { ExternalCoreGroup(it.first().core, it) }

    /** 按合并配置里的标识（入站 / 出站 tag、listener / 代理名）找回跳实例，外核报错时据此对回节点。 */
    fun hopByTag(tag: String): ExternalHop? =
        hops.firstOrNull { it.core.merged && (it.inboundTag == tag || it.outboundTag == tag) }

    companion object {
        /**
         * 从构建结果得到计划：按 externalChains 的顺序（链的顺序，链内按 buildHop 登记的顺序），本机端口、拨号目标与
         * 本机 socks 凭据都取构建登记的值（凭据与 sing-box 一侧的 socks 出站同一个；核心要认证而登记里没有时
         * 建计划即抛错）。没有外核条目的节点（NekoBean）跳过、不占序号，与以前一致。
         */
        fun from(result: ConfigBuildResult): ExternalRunPlan {
            val hops = ArrayList<ExternalHop>()
            result.externalChains.forEachIndexed { chainIndex, chain ->
                for (record in chain.hops) {
                    val core = record.core ?: continue
                    hops += ExternalHop(
                        hops.size, chainIndex, record.profileId, record.bean, record.localPort, record.target, core,
                        record.localAuth,
                    )
                }
            }
            return ExternalRunPlan(hops)
        }
    }
}

/** 一个外核进程：一组跳实例与它们合成的一份配置。 */
class ExternalCoreProcess(val group: ExternalCoreGroup, val config: String) {

    // writeCacheFile(prefix, ext, content) 落盘配置及进程要读的其它文件；settings 取构建结果带出的那份
    // （ConfigBuildResult.externalCoreSettings），与组装时相同
    fun launch(
        pluginPath: String,
        writeCacheFile: (String, String, String) -> File,
        settings: ExternalCoreSettings,
    ): ExternalCoreLaunch = group.core.launch(pluginPath, config, writeCacheFile, settings)
}

/**
 * 运行、测速、导出共用的组装入口：计划 → 每组一份外核配置，顺序同 [ExternalRunPlan.groups]。
 * 结果只取决于计划、设置与 mihomoController，与调用方无关。
 *
 * 先按计划顺序逐个跳实例生成它在配置里的部分，出错时带上该节点名，报的是遍历顺序上第一个出错的节点
 * （与合并前逐节点生成时相同）；beforeHop 在每个跳实例之前调用（运行 / 测速用它确认插件已安装）。
 * cacheFile(prefix, ext) 分配配置可能引用的临时文件（hysteria 的 CA）；mihomoController 是测速时
 * mihomo 的 Clash API 端口与 secret，其余情况为 null。settings 没有默认值：运行、测速、导出传构建结果带出的
 * 那份（ConfigBuildResult.externalCoreSettings），预检传构建自己的设置快照，测试传固定值。
 */
fun ExternalRunPlan.assemble(
    cacheFile: (String, String) -> File,
    mihomoController: Pair<Int, String>?,
    settings: ExternalCoreSettings,
    beforeHop: (ExternalHop) -> Unit = {},
): List<ExternalCoreProcess> {
    val entries = HashMap<Int, Map<String, Any?>>()
    val configs = HashMap<Int, String>()
    for (hop in hops) {
        beforeHop(hop)
        withProfileName(hop.bean) {
            when (val core = hop.core) {
                is ExternalCore.Merged -> entries[hop.index] = core.entry(hop, settings)
                is ExternalCore.PerHop -> configs[hop.index] = core.config(hop, cacheFile, settings)
            }
        }
    }
    return groups.map { group ->
        val config = when (val core = group.core) {
            is ExternalCore.Merged ->
                core.config(group.hops, group.hops.map { entries.getValue(it.index) }, mihomoController, settings)

            is ExternalCore.PerHop -> configs.getValue(group.hops.single().index)
        }
        ExternalCoreProcess(group, config)
    }
}

/**
 * 导出的文本与文件名（ProxyEntity.exportConfig）：sing-box 配置后面按组的顺序接上各外核配置，段间一个空行。
 * 外核配置经运行、测速共用的组装入口生成，本机 socks 凭据是这次导出构建生成的（两侧同一组），设置用构建时采集的
 * 那份。构建登记过走外核的节点（含没有外核条目的 NekoBean）时文件名是 profiles.txt，否则是「profileName.json」。
 * cacheFile 分配外核配置引用的临时文件（hysteria 1 的 CA）；文件由调用方删除，组装中途抛异常时也要删掉已分配的。
 */
fun exportConfigText(
    config: ConfigBuildResult,
    profileName: String,
    cacheFile: (String, String) -> File,
): Pair<String, String> {
    val name = if (config.externalChains.all { it.hops.isEmpty() }) "$profileName.json" else "profiles.txt"
    val text = StringBuilder(config.config)
    val plan = ExternalRunPlan.from(config)
    if (plan.hops.isNotEmpty()) {
        for (process in plan.assemble(cacheFile, null, config.requireExternalCoreSettings())) {
            text.append("\n\n")
            text.append(process.config)
        }
    }
    return text.toString() to name
}

/**
 * 测速时是否让 mihomo 自己测延迟（在它的配置里开 Clash API，见 [assemble] 的 mihomoController）：主节点本身是 AnyTLS
 * 且跑在 mihomo 上（needExternal），并且主节点所在分组的行存在、前置与落地都不大于 0，即主节点自己就是那条链唯一的
 * 一跳；链上的节点仍走 sing-box 测。分组的行不存在、前置 / 落地 id 大于 0 却指向已删除的节点时都为否（构建会忽略
 * 后者，这里照旧按 id 判断）。构建用它算出 [ConfigBuildResult.delayTestOnMihomo]：main 是构建的主节点，mainGroup 是
 * 快照里主节点所在分组的行，globalAllowInsecure 取构建时采集的设置，不读 DataStore、不另查数据库。
 */
internal fun mihomoDelayTestApplies(main: ProxyEntity, mainGroup: ProxyGroup?, globalAllowInsecure: Boolean): Boolean {
    if (main.type != ProxyEntity.TYPE_ANYTLS || !main.needExternal(globalAllowInsecure)) return false
    val group = mainGroup ?: return false
    return group.frontProxy <= 0 && group.landingProxy <= 0
}

// 合并配置的生成器共用：同一份配置里的监听端口与标识各不相同，也不占用核心的保留名。
// 重复的端口两个核心都不报错，重复或保留的标识要么让核心启动失败、要么让流量走错节点，所以在生成时拒绝
internal fun requireDistinctHops(hops: List<ExternalHop>, reserved: Set<String>) {
    require(hops.isNotEmpty()) { "an external core config needs at least one hop" }
    fun requireUnique(what: String, values: List<Any>) {
        values.groupBy { it }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalStateException("duplicate $what ${it[0]} in one external core config")
        }
    }
    requireUnique("local port", hops.map { it.localPort })
    requireUnique("inbound tag", hops.map { it.inboundTag })
    requireUnique("outbound tag", hops.map { it.outboundTag })
    hops.flatMap { listOf(it.inboundTag, it.outboundTag) }.firstOrNull { it in reserved }?.let {
        throw IllegalStateException("tag $it is reserved by the external core")
    }
}

// 合并配置的生成器共用：入站一律要求认证，跳实例没有凭据时不生成配置，而不是写出不认证的入站
internal fun ExternalHop.requireLocalAuth(): LocalSocksAuth =
    localAuth ?: throw IllegalStateException("external hop $index ($pluginId) has no local socks credentials")
