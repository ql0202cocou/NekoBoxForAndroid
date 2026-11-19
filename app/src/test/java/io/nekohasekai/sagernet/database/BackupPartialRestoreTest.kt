package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.uitest.UiTestBase
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ui.BackupFragment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 部分恢复不推测节点身份、不隐式改写未勾选的表；既有悬空规则由构建跳过。 */
class BackupPartialRestoreTest : UiTestBase() {
    @Test
    fun exportOnlyProducesBackupsWithinTheImportLimit() = ui.bg {
        val fragment = BackupFragment()
        val domains = (1..16_000).joinToString("\n") { "subdomain$it.example" }
        val rules = (1L..100L).map { RuleEntity(id = it, name = "Rule $it", domains = domains) }
        val dao = SagerDatabase.rulesDao
        dao.reset()
        dao.insert(rules.take(1))
        val json = fragment.doBackup(false, true, false).byteInputStream().use { it.readBytesLimited() }
        val decoded = BackupRestore.decode(JSONObject(json.toString(Charsets.UTF_8)), false, true, false)
        assertEquals(rules.take(1), decoded.rules)
        dao.insert(rules.drop(1))
        assertThrows(BackupFragment.BackupTooLargeException::class.java) { fragment.doBackup(false, true, false) }
        Unit
    }

    @Test
    fun unselectedOrMissingSectionsCannotStartARestore() {
        val backup = JSONObject().put("version", 1).put("rules", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { BackupRestore.decode(backup, true, false, true) }
        assertThrows(IllegalArgumentException::class.java) { BackupFragment().doBackup(false, false, false) }
        assertEquals(emptyList<RuleEntity>(), BackupRestore.decode(backup, false, true, false).rules)
    }

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
    fun `服务写选择时只接受当前实例及恢复世代`() = ui.bg {
        val group = GroupRepository.createGroup(ProxyGroup(name = "Original"))
        val member = ProfileRepository.createProfile(group.id, SOCKSBean().apply {
            serverAddress = "192.0.2.1"
            serverPort = 1080
        })
        DataStore.selectedProxy = member.id
        val generation = ProfileManager.selectedProfileForRun()!!.second
        DataStore.selectedProxy = 0L
        DataStore.currentProfile = 0L
        assertNull(ProfileManager.recordRunningProfile(member.id, generation, selectorChange = true) { false })
        val started = ProfileManager.recordRunningProfile(member.id, generation, selectorChange = false) { true }
        assertEquals(0L, started!!.second)
        assertEquals(0L, DataStore.selectedProxy)
        assertEquals(member.id, DataStore.currentProfile)
        val selected = ProfileManager.recordRunningProfile(member.id, generation, selectorChange = true) { true }
        assertEquals(member.id, selected!!.second)
        assertEquals(member.id, DataStore.selectedProxy)

        BackupRestore.commit(BackupRestore.Decoded(emptyList(), emptyList(), null, null, 0), null)
        assertNull(ProfileManager.recordRunningProfile(member.id, generation, selectorChange = true) { true })
        assertEquals(0L, DataStore.selectedProxy)
        member.requireBean().serverAddress = "192.0.2.2"
        BackupRestore.commit(BackupRestore.Decoded(listOf(member), listOf(group), null, null, 0), null)
        DataStore.currentProfile = 0L
        for (selector in listOf(false, true)) {
            assertNull(ProfileManager.recordRunningProfile(member.id, generation, selectorChange = selector) { true })
        }
        assertEquals(0L, DataStore.selectedProxy)
        assertEquals(0L, DataStore.currentProfile)
        DataStore.selectedProxy = member.id
        val afterProfiles = ProfileManager.selectedProfileForRun()!!.second
        BackupRestore.commit(BackupRestore.Decoded(null, null, null, emptyList(), 0), null)
        assertEquals(afterProfiles.profiles, SagerDatabase.dataGeneration())
        assertNull(ProfileManager.recordRunningProfile(member.id, afterProfiles, selectorChange = true) { true })
        assertEquals(0L, DataStore.selectedProxy)
        assertEquals(0L, DataStore.currentProfile)
        DataStore.selectedProxy = member.id
        val afterSettings = ProfileManager.selectedProfileForRun()!!.second
        val restored = ProfileManager.recordRunningProfile(member.id, afterSettings, selectorChange = true) { true }
        assertEquals("192.0.2.2", restored!!.first.requireBean().serverAddress)
        assertEquals(member.id, DataStore.selectedProxy)
        assertEquals(member.id, DataStore.currentProfile)
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
