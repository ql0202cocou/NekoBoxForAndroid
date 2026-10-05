package io.nekohasekai.sagernet.fmt

import java.io.File

// 外核运行计划（plan.md K0 做法 1）：一次构建里全部的外核跳实例，以及它们按核心分成的组。
// 计划从构建结果得到（externalIndex 加上构建时写进 bean 的 finalAddress / finalPort，以及本机 socks 凭据），
// 不改构建过程。
// 运行（BoxInstance）、测速（TestInstance）、导出（ProxyEntity.exportConfig）都经 assemble 从计划生成
// 外核配置，ConfigBuild.precheck 也经它试生成单个节点。只用纯 Kotlin 类型，JVM 单测可以手工构造

/**
 * 一个外核跳实例：某条链上一个走外核的节点。同一个节点出现在不同链里是不同的跳实例，
 * 各有各的本机端口与映射目标，不能按节点 id 合并。
 */
class ExternalHop(
    /** 计划内序号：按 externalIndex 的遍历顺序从 0 起；配置里的标识由它生成。 */
    val index: Int,
    /** 所在链在 externalIndex 里的序号。 */
    val chainIndex: Int,
    val profileId: Long,
    val bean: AbstractBean,
    /** sing-box 的 socks 出站连到的本机端口，外核在这里开 socks 入站。 */
    val localPort: Int,
    /** 外核出站拨向的地址端口（映射目标）：经映射时是本机与映射入站的端口，否则是服务器本身。 */
    val finalAddress: String,
    val finalPort: Int,
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
         * 从构建结果得到计划：按 externalIndex 的顺序（链的顺序，链内按 buildHop 登记的顺序），映射目标取构建时
         * 写进 bean 的 finalAddress / finalPort，入站支持认证的核心的跳实例取构建结果的本机 socks 凭据
         * （与 sing-box 一侧的 socks 出站同一个值；构建结果里没有时建计划即抛错）。没有外核条目的节点
         * （NekoBean）跳过，与以前一致。
         */
        fun from(result: ConfigBuildResult): ExternalRunPlan {
            val hops = ArrayList<ExternalHop>()
            result.externalIndex.forEachIndexed { chainIndex, entry ->
                for ((port, profile) in entry.chain) {
                    val bean = profile.requireBean()
                    val core = externalCore(bean) ?: continue
                    hops += ExternalHop(
                        hops.size, chainIndex, profile.id, bean, port, bean.finalAddress, bean.finalPort, core,
                        result.localAuth.takeIf { core.inboundAuth },
                    )
                }
            }
            return ExternalRunPlan(hops)
        }
    }
}

/** 一个外核进程：一组跳实例与它们合成的一份配置。 */
class ExternalCoreProcess(val group: ExternalCoreGroup, val config: String) {

    // writeCacheFile(prefix, ext, content) 落盘配置及进程要读的其它文件
    fun launch(
        pluginPath: String,
        writeCacheFile: (String, String, String) -> File,
        settings: ExternalCoreSettings = ExternalCoreSettings.fromDataStore(),
    ): ExternalCoreLaunch = group.core.launch(pluginPath, config, writeCacheFile, settings)
}

/**
 * 运行、测速、导出共用的组装入口：计划 → 每组一份外核配置，顺序同 [ExternalRunPlan.groups]。
 * 结果只取决于计划、设置与 mihomoController，与调用方无关。
 *
 * 先按计划顺序逐个跳实例生成它在配置里的部分，出错时带上该节点名，报的是遍历顺序上第一个出错的节点
 * （与合并前逐节点生成时相同）；beforeHop 在每个跳实例之前调用（运行 / 测速用它确认插件已安装）。
 * cacheFile(prefix, ext) 分配配置可能引用的临时文件（hysteria 的 CA）；mihomoController 是测速时
 * mihomo 的 Clash API 端口与 secret，其余情况为 null。
 */
fun ExternalRunPlan.assemble(
    cacheFile: (String, String) -> File,
    mihomoController: Pair<Int, String>?,
    settings: ExternalCoreSettings = ExternalCoreSettings.fromDataStore(),
    beforeHop: (ExternalHop) -> Unit = {},
): List<ExternalCoreProcess> {
    val entries = HashMap<Int, Map<String, Any?>>()
    val configs = HashMap<Int, String>()
    for (hop in hops) {
        beforeHop(hop)
        withProfileName(hop.bean) {
            when (val core = hop.core) {
                is ExternalCore.Merged -> entries[hop.index] = core.entry(hop, settings)
                is ExternalCore.PerHop -> configs[hop.index] = core.config(hop.localPort, cacheFile, settings)
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
