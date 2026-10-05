package io.nekohasekai.sagernet.fmt

import java.util.Collections

// 流量统计关联：构建结果里只读的纯数据，运行时由流量聚合（bg/proto/TrafficAccounting）按它记账。
// sing-box 只按连接被路由到的那个出站记字节：经选择器的字节记在 proxy 上（成员自己的 tag 上没有），
// 链的 detour 不经路由、中间各跳不记，路由规则直接指向某个节点出站的字节记在那个出站上

/** 一个节点的流量累计（字节）。 */
data class TrafficTotals(val rx: Long, val tx: Long)

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
) {
    val tags: Map<String, List<Long>> = Collections.unmodifiableMap(
        tags.entries.associateTo(LinkedHashMap()) { (tag, ids) -> tag to Collections.unmodifiableList(ids.toList()) }
    )
    val initial: Map<Long, TrafficTotals> = Collections.unmodifiableMap(LinkedHashMap(initial))
    val ruleTags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ruleTags))

    init {
        // 缺初始值的节点若从 0 记起，落库会把它的累计清零
        val missing = this.tags.values.flatten().filterNot { it in this.initial }
        require(missing.isEmpty()) { "traffic bindings without initial totals: $missing" }
        require(this.ruleTags.all { it in this.tags }) { "rule traffic tags must be bound tags" }
    }
}
