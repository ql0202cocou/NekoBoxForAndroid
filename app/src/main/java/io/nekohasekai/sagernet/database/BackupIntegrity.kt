package io.nekohasekai.sagernet.database

// 备份内部的引用完整性（BackupRestore.decode 在写库之前调用）。纯函数：只看解码后的数据，不读库、
// 不依赖 Android 框架。
//
// 恢复时各表整表替换且 id 保持备份里的原值，所以「恢复后会存在的 id 集合」就是备份解出来的节点 / 分组
// 的 id。跨实体的 id 引用只有这几处（Kryo 与 Room 的列里再没有别的）：
//   - ProxyEntity.groupId → 分组 id
//   - ProxyGroup.frontProxy / landingProxy → 节点 id，空值是 -1（库里的清理 SQL 以 > 0 判有值）
//   - ChainBean.proxies → 节点 id
//   - RuleEntity.outbound → 0 代理、-1 绕过、-2 阻断，其余（> 0）是节点 id
// 设置里的当前节点 / 当前分组不在此列：它们不属于备份内部的引用，恢复后由 fixDanglingSelections 兜底。
//
// 两类引用的失效在正常数据里并不少见，不能一律当作损坏：
//   - 分组 id、前置 / 落地：应用自己维护。删分组连带删节点，删节点（含订阅更新淘汰节点）时同事务清掉
//     别处的前置 / 落地引用，正常的备份里不会有失效。
//   - 链成员、规则出站：删节点不清理这两处（构建时按成员 / 目标跳过，见 ConfigBuild 的 planOrSkip），
//     订阅更新换掉节点后留下失效的 id 是常态。
// 后两类只拒绝因丢记录而可能产生的断链。能读出被跳过节点的 id 时，排除无关的旧引用。
object BackupIntegrity {

    enum class Kind {
        // 节点所属的分组不在备份里
        PROFILE_GROUP,

        // 分组的前置节点不在备份里
        GROUP_FRONT_PROXY,

        // 分组的落地节点不在备份里
        GROUP_LANDING_PROXY,

        // 链式节点的成员不在备份里（仅在丢过节点记录时）
        CHAIN_MEMBER,

        // 规则的出站节点不在备份里（仅在丢过节点记录时）
        RULE_OUTBOUND,
    }

    // 某一类引用失效了几处；只有类别与数量，不带 id 与字段值
    class Break(val kind: Kind, val count: Int) {
        override fun toString() = "$kind=$count"
    }

    /**
     * 返回所有失效的引用，按 [Kind] 的声明顺序、每类至多一项；空表示完整。
     *
     * @param profiles 解出的节点；null 表示这次不恢复节点（此时分组也为 null），没有可对的 id 集合，
     *   节点相关的检查整体跳过，规则出站同样跳过（那是对着本机现有节点，不属于备份内部）
     * @param groups 解出的分组
     * @param rules 解出的规则；null 表示这次不恢复规则
     * @param skippedProfileIds 被跳过的节点 id；空集表示没丢记录，null 表示至少有一条无法确定 id。
     */
    fun check(
        profiles: List<ProxyEntity>?,
        groups: List<ProxyGroup>?,
        rules: List<RuleEntity>?,
        skippedProfileIds: Set<Long>?,
    ): List<Break> {
        if (profiles == null || groups == null) return emptyList()
        val profileIds = profiles.mapTo(HashSet()) { it.id }
        val groupIds = groups.mapTo(HashSet()) { it.id }
        val counts = LinkedHashMap<Kind, Int>()
        fun miss(kind: Kind) {
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        for (profile in profiles) {
            if (profile.groupId !in groupIds) miss(Kind.PROFILE_GROUP)
        }
        for (group in groups) {
            if (group.frontProxy > 0 && group.frontProxy !in profileIds) miss(Kind.GROUP_FRONT_PROXY)
            if (group.landingProxy > 0 && group.landingProxy !in profileIds) miss(Kind.GROUP_LANDING_PROXY)
        }
        if (skippedProfileIds == null || skippedProfileIds.isNotEmpty()) {
            fun possiblyLost(id: Long) = id > 0 && id !in profileIds && (skippedProfileIds == null || id in skippedProfileIds)
            for (profile in profiles) {
                if (profile.type != ProxyEntity.TYPE_CHAIN) continue
                val members = profile.chainBean?.proxies ?: continue
                // 成员 id 只会是正数；0 与负数是编辑器写不出来的值，当作没有引用
                for (member in members) {
                    if (possiblyLost(member)) miss(Kind.CHAIN_MEMBER)
                }
            }
            for (rule in rules.orEmpty()) {
                if (possiblyLost(rule.outbound)) miss(Kind.RULE_OUTBOUND)
            }
        }
        return Kind.values().mapNotNull { kind -> counts[kind]?.let { Break(kind, it) } }
    }
}

// decode 发现断链时抛出：调用方（界面）据此显示类别与数量，不写库
class BackupIntegrityException(val breaks: List<BackupIntegrity.Break>) :
    IllegalStateException("Backup references are broken: ${breaks.joinToString()}")
