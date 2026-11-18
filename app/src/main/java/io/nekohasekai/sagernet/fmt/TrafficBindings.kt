package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import java.util.Collections

// 流量统计关联：构建结果里只读的纯数据，运行时由流量聚合（bg/proto/TrafficAccounting）按它记账。
// sing-box 只按连接被路由到的那个出站记字节：经选择器的字节记在 proxy 上（成员自己的 tag 上没有），
// 链的 detour 不经路由、中间各跳不记，路由规则直接指向某个节点出站的字节记在那个出站上

/** 一个节点的流量累计（字节）。 */
data class TrafficTotals(val rx: Long, val tx: Long)

/** 一个节点自上次落库以来新增的流量（字节），落库时加到库里的 rx / tx 上，不是绝对值。 */
data class TrafficIncrement(val id: Long, val rx: Long, val tx: Long, val epoch: Long = 0)

/**
 * 一次构建的流量统计关联，构造时复制传入的集合，之后不再改变。
 *
 * - [tags]：登记了统计的节点出站 tag → 这条出站链上的节点 id（落地…, 节点或链的各成员, …前置；内容与顺序同构建时
 *   的列表）。sing-box 的统计服务登记的就是这些 tag 加上 proxy、bypass。
 * - [initial]：每个节点的初始累计，取自构建用的节点；[tags] 里出现的每个 id 都必须有。
 * - [ruleTags]：用户路由规则直接指向的、以及 route.final 指向的登记了统计的节点出站（不含 proxy、bypass），选择器
 *   模式下每轮额外查询它们；取值见 ruleTrafficTags。
 */
class TrafficBindings(
    tags: Map<String, List<Long>>,
    initial: Map<Long, TrafficTotals>,
    ruleTags: Set<String>,
    excludedInbounds: Set<String> = emptySet(),
    clearEpochs: Map<Long, Long> = emptyMap(),
) {
    // 与初始累计取自同一个节点快照，只记录清零过的节点。
    val clearEpochs: Map<Long, Long> = Collections.unmodifiableMap(LinkedHashMap(clearEpochs))
    val tags: Map<String, List<Long>> = Collections.unmodifiableMap(
        tags.entries.associateTo(LinkedHashMap()) { (tag, ids) -> tag to Collections.unmodifiableList(ids.toList()) }
    )
    val initial: Map<Long, TrafficTotals> = Collections.unmodifiableMap(LinkedHashMap(initial))
    val ruleTags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ruleTags))
    // 只排除本次构建生成且最终配置仍存在的映射入站；这些是已计过的外核连接回流。
    val excludedInbounds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(excludedInbounds))

    init {
        // 缺初始值的节点若从 0 记起，落库会把它的累计清零
        val missing = this.tags.values.flatten().filterNot { it in this.initial }
        require(missing.isEmpty()) { "traffic bindings without initial totals: $missing" }
        require(this.ruleTags.all { it in this.tags }) { "rule traffic tags must be bound tags" }
    }
}

// 构建登记的 tag → 节点列表（ConfigBuild.trafficMap）换算成统计关联。列表内容与顺序原样保留；初始累计取每个 id
// 第一次出现的那份节点（同一 id 的各份取自同一份快照，只有主链里的主节点是外壳交来的那份）。configMap 是最终配置
// （合并过自定义配置），为 null 时没有规则出站（完整配置节点）
internal fun trafficBindingsOf(
    chains: Map<String, List<ProxyEntity>>, configMap: Map<String, Any?>?,
    mappingInbounds: Set<String> = emptySet(),
): TrafficBindings {
    val initial = LinkedHashMap<Long, TrafficTotals>()
    val epochs = LinkedHashMap<Long, Long>()
    for (list in chains.values) for (ent in list) initial.getOrPut(ent.id) {
        if (ent.trafficEpoch != 0L) epochs[ent.id] = ent.trafficEpoch
        TrafficTotals(rx = ent.rx, tx = ent.tx)
    }
    val excluded = (configMap?.get("inbounds") as? List<*>)
        ?.mapNotNull { (it as? Map<*, *>)?.get("tag") as? String }
        ?.filterTo(LinkedHashSet()) { it in mappingInbounds } ?: emptySet()
    return TrafficBindings(
        chains.mapValues { (_, list) -> list.map { it.id } },
        initial,
        configMap?.let { ruleTrafficTags(it, chains.keys, excluded) } ?: emptySet(),
        excluded, epochs,
    )
}

/**
 * 规则出站：最终配置里 route.rules 以 outbound 直接路由到的、登记了统计（在 [bound] 里）的节点出站，加上 route.final
 * 指向的登记出站；去掉 proxy（每轮本来就查）与 bypass（不记给节点）。
 *
 * - 只看最终配置，所以被跳过、没有应用的规则（应用都没装、条件为空、目标被跳过或不存在）不算；规则自带的自定义 JSON
 *   与全局 / 节点自定义配置改出来的出站按改后的算。
 * - 构建为外核链生成的规则（inbound 全是本次构建的映射入站）不是用户规则，不算：它们路由的是同一条连接的第二段，
 *   查询没有必要：统计 tracker 已按本次构建的映射入站集合排除回流，即使目标同时有用户规则也只计用户入口。
 * - 指向主节点的规则的出站是 proxy，经 proxy 计；指向当前选中成员自己的出站的规则另记在那个出站上，与 proxy 记给
 *   同一个集合，各算各的字节，不重复。
 * - 没有登记统计的自定义出站查不到字节，不算；复用全局出站的成员 / 规则目标在构建时补登记。
 */
internal fun ruleTrafficTags(
    configMap: Map<String, Any?>, bound: Set<String>, mappingInbounds: Set<String> = emptySet(),
): Set<String> {
    val route = configMap["route"] as? Map<*, *> ?: return emptySet()
    val targets = LinkedHashSet<String>()
    for (rule in route["rules"] as? List<*> ?: emptyList<Any?>()) {
        if (rule !is Map<*, *>) continue
        val outbound = rule["outbound"] as? String ?: continue
        val inbound = when (val value = rule["inbound"]) {
            is String -> listOf(value)
            is List<*> -> value
            else -> emptyList()
        }
        if (inbound.isNotEmpty() && inbound.all { it in mappingInbounds }) continue
        targets += outbound
    }
    (route["final"] as? String)?.let { targets += it }
    return targets.filterTo(LinkedHashSet()) { it in bound && it != TAG_PROXY && it != TAG_BYPASS }
}

// 链里任一位置有 hysteria faketcp 节点（插件以 root 运行，VpnService 要放行 root uid）。看的是统计关联的同一批节点：
// 有前置 / 落地代理时它不在链的第一个
internal fun needsRootUidBypass(chains: Map<String, List<ProxyEntity>>): Boolean = chains.values.any { chain ->
    chain.any { it.hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP }
}
