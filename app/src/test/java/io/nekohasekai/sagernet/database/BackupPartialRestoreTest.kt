package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test

/** 部分恢复不推测节点身份、不隐式改写未勾选的表；既有悬空规则由构建跳过。 */
class BackupPartialRestoreTest : UiTestBase() {
    @Test
    fun `只恢复节点保留本地规则并清除失效的选中节点和分组`() = ui.bg {
        val old = GroupRepository.createGroup(ProxyGroup(name = "Old group"))
        val node = ProfileRepository.createProfile(old.id, SOCKSBean().apply {
            serverAddress = "192.0.2.1"; serverPort = 1080
        })
        val rule = RuleEntity(id = 1, name = "Local rule", outbound = node.id, domains = "example.org")
        SagerDatabase.rulesDao.insert(listOf(rule))
        DataStore.selectedProxy = node.id
        DataStore.selectedGroup = old.id
        BackupRestore.commit(BackupRestore.Decoded(emptyList(), emptyList(), null, null, 0), null)
        assertEquals(listOf(rule), SagerDatabase.rulesDao.allRules())
        assertEquals(0L, DataStore.selectedProxy)
        assertTrue(DataStore.selectedGroup <= 0)
    }

    @Test
    fun `只恢复规则保留节点数据及世代号且不按名字重绑定出站`() = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "Local group"))
        val node = ProfileRepository.createProfile(group.id, SOCKSBean().apply {
            serverAddress = "192.0.2.1"; serverPort = 1080
        })
        val generation = SagerDatabase.dataGeneration()
        val rules = listOf(
            RuleEntity(id = 1, name = "Existing target", outbound = node.id, domains = "example.org"),
            RuleEntity(id = 2, name = "Missing target", outbound = node.id + 100, domains = "example.net"),
        )
        BackupRestore.commit(BackupRestore.Decoded(null, null, rules, null, 0), null)
        assertEquals(rules, SagerDatabase.rulesDao.allRules())
        assertEquals(listOf(node), SagerDatabase.proxyDao.getAll())
        assertEquals(generation, SagerDatabase.dataGeneration())
        assertTrue(DataStore.rulesFirstCreate)
    }
}
