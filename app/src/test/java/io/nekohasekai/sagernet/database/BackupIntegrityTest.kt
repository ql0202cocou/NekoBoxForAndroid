package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.BackupIntegrity.Kind
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 备份内部的引用完整性（BackupIntegrity.check）。decode 本身要 Parcel 与 org.json，JVM 上跑不了；
// 这里直接喂解码后的实体，测 decode 在写库之前调用的这个纯函数
class BackupIntegrityTest {

    private fun node(id: Long, groupId: Long = 1) = ProxyEntity(id = id, groupId = groupId, type = ProxyEntity.TYPE_SOCKS)

    private fun chain(id: Long, vararg members: Long, groupId: Long = 1) = ProxyEntity(
        id = id, groupId = groupId, type = ProxyEntity.TYPE_CHAIN,
        chainBean = ChainBean().apply { proxies = members.toMutableList() },
    )

    private fun group(id: Long, front: Long = -1, landing: Long = -1) =
        ProxyGroup(id = id, frontProxy = front, landingProxy = landing)

    private fun rule(id: Long, outbound: Long) = RuleEntity(id = id, outbound = outbound)

    private fun counts(breaks: List<BackupIntegrity.Break>) = breaks.associate { it.kind to it.count }

    @Test
    fun `完整的备份没有断链`() {
        val profiles = listOf(node(1), node(2, groupId = 2), chain(3, 1, 2))
        val groups = listOf(group(1, front = 1, landing = 2), group(2))
        val rules = listOf(rule(1, 0), rule(2, -1), rule(3, -2), rule(4, 2))
        assertTrue(BackupIntegrity.check(profiles, groups, rules, 0).isEmpty())
        assertTrue(BackupIntegrity.check(profiles, groups, rules, 5).isEmpty())
    }

    @Test
    fun `节点所属分组不在备份里就是断链，不管有没有丢过记录`() {
        // 分组 2 的记录损坏被跳过，它的两个节点成了孤儿
        val profiles = listOf(node(1), node(2, groupId = 2), node(3, groupId = 2))
        val groups = listOf(group(1))
        assertEquals(mapOf(Kind.PROFILE_GROUP to 2), counts(BackupIntegrity.check(profiles, groups, null, 0)))
        assertEquals(mapOf(Kind.PROFILE_GROUP to 2), counts(BackupIntegrity.check(profiles, groups, null, 1)))
    }

    @Test
    fun `分组的前置与落地节点不在备份里就是断链，空值不算`() {
        val profiles = listOf(node(1))
        val groups = listOf(
            group(1, front = 9, landing = 1),
            group(2, front = 1, landing = 8),
            // -1 是默认的空值；0 与更小的负数同样不是引用
            group(3, front = -1, landing = 0),
            group(4, front = -5, landing = -1),
        )
        val result = counts(BackupIntegrity.check(profiles, groups, null, 0))
        assertEquals(mapOf(Kind.GROUP_FRONT_PROXY to 1, Kind.GROUP_LANDING_PROXY to 1), result)
    }

    @Test
    fun `链成员与规则出站失效，只在丢过节点记录时才算断链`() {
        val profiles = listOf(node(1), chain(2, 1, 77, 78))
        val groups = listOf(group(1))
        val rules = listOf(rule(1, 99), rule(2, 1), rule(3, 100))
        // 没丢过记录：失效的 id 是删节点（含订阅更新淘汰节点）留下的老样子，照常恢复
        assertTrue(BackupIntegrity.check(profiles, groups, rules, 0).isEmpty())
        // 丢过记录：失效的 id 可能正是被丢掉的节点
        val result = counts(BackupIntegrity.check(profiles, groups, rules, 1))
        assertEquals(mapOf(Kind.CHAIN_MEMBER to 2, Kind.RULE_OUTBOUND to 2), result)
    }

    @Test
    fun `规则出站的内置值不是节点 id`() {
        val profiles = listOf(node(1))
        val groups = listOf(group(1))
        val rules = listOf(rule(1, 0), rule(2, -1), rule(3, -2))
        assertTrue(BackupIntegrity.check(profiles, groups, rules, 3).isEmpty())
    }

    @Test
    fun `不恢复节点时没有可对的 id 集合，整体跳过`() {
        // 只恢复规则：出站对着本机现有节点，不属于备份内部的引用
        assertTrue(BackupIntegrity.check(null, null, listOf(rule(1, 42)), 0).isEmpty())
        assertTrue(BackupIntegrity.check(null, null, null, 0).isEmpty())
    }

    @Test
    fun `不恢复规则时只检查节点与分组`() {
        val profiles = listOf(node(1, groupId = 5), chain(2, 1, 66))
        val result = counts(BackupIntegrity.check(profiles, listOf(group(1)), null, 1))
        assertEquals(mapOf(Kind.PROFILE_GROUP to 1, Kind.CHAIN_MEMBER to 1), result)
    }

    @Test
    fun `结果按类别声明顺序、每类一项，不带 id`() {
        val profiles = listOf(node(1, groupId = 9), chain(2, 55, groupId = 9))
        val groups = listOf(group(1, front = 40, landing = 41))
        val breaks = BackupIntegrity.check(profiles, groups, listOf(rule(1, 56)), 1)
        assertEquals(
            listOf(
                Kind.PROFILE_GROUP, Kind.GROUP_FRONT_PROXY, Kind.GROUP_LANDING_PROXY,
                Kind.CHAIN_MEMBER, Kind.RULE_OUTBOUND,
            ),
            breaks.map { it.kind },
        )
        assertEquals(2, breaks.first().count)
        assertEquals("Backup references are broken: PROFILE_GROUP=2, GROUP_FRONT_PROXY=1, GROUP_LANDING_PROXY=1, CHAIN_MEMBER=1, RULE_OUTBOUND=1", BackupIntegrityException(breaks).message)
    }
}
