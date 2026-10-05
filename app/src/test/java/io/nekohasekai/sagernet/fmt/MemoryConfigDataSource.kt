package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity

/**
 * JVM 上的内存数据源，查询语义同 DAO：每次都给新对象（节点经 Kryo 字节新建）；getEntities（[profiles]）按 id
 * 升序，同 SQLite 按主键 IN 查询的顺序；getByGroup（[profilesByGroup]）与 enabledRules 按 userOrder（相同时按 id）。
 * [queries] 记下每次查询，测试据此核对采集查了什么；[clear] 清空全部数据。
 */
class MemoryConfigDataSource(
    groups: List<ProxyGroup>,
    profiles: List<ProxyEntity>,
    rules: List<RuleEntity>,
) : ConfigDataSource {

    private val groups = groups.associateTo(LinkedHashMap()) { it.id to it.copy() }
    private val profiles = profiles.associateTo(LinkedHashMap()) { it.id to ProfileRecord.of(it) }
    private val rules = rules.mapTo(ArrayList()) { it.copy() }

    val queries = ArrayList<String>()

    fun clear() {
        groups.clear()
        profiles.clear()
        rules.clear()
    }

    override fun group(id: Long): ProxyGroup? {
        queries += "group($id)"
        return groups[id]?.copy()
    }

    override fun profile(id: Long): ProxyEntity? {
        queries += "profile($id)"
        return profiles[id]?.newEntity()
    }

    override fun profiles(ids: List<Long>): List<ProxyEntity> {
        queries += "profiles(${ids.joinToString(",")})"
        return ids.distinct().sorted().mapNotNull { profiles[it]?.newEntity() }
    }

    override fun profilesByGroup(groupId: Long): List<ProxyEntity> {
        queries += "profilesByGroup($groupId)"
        return profiles.values.filter { it.groupId == groupId }
            .sortedWith(compareBy({ it.userOrder }, { it.id }))
            .map { it.newEntity() }
    }

    override fun enabledRules(): List<RuleEntity> {
        queries += "enabledRules()"
        return rules.filter { it.enabled }.sortedWith(compareBy({ it.userOrder }, { it.id })).map { it.copy() }
    }
}
