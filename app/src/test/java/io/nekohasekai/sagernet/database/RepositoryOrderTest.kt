package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.uitest.UiTestBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryOrderTest : UiTestBase() {
    @Test
    fun concurrentCreatesAllocateDistinctGroupAndRuleOrders() = ui.bg {
        suspend fun createTogether(create: suspend (Int) -> Long) = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val tasks = (1..12).map { i -> async(Dispatchers.IO) { start.await(); create(i) } }
            start.complete(Unit)
            tasks.awaitAll().sorted()
        }
        assertEquals((1L..12L).toList(), createTogether { GroupRepository.createGroup(ProxyGroup(name = "g$it")).userOrder })
        assertEquals((1L..12L).toList(), createTogether { RuleRepository.createRule(RuleEntity(name = "r$it")).userOrder })
    }

    @Test
    fun historicalTiesCanBeMovedAndStaleEditorCannotReintroduceThem() = ui.bg {
        DataStore.rulesFirstCreate = true
        repeat(3) {
            SagerDatabase.groupDao.createGroup(ProxyGroup(name = "g$it", userOrder = 7))
            SagerDatabase.rulesDao.createRule(RuleEntity(name = "r$it", userOrder = 7))
        }
        val stale = SagerDatabase.rulesDao.allRules().first()
        val groups = GroupRepository.getAllGroups()
        val rules = RuleRepository.getRules()
        assertEquals(listOf("g0", "g1", "g2"), groups.map { it.name })
        assertEquals(listOf("r0", "r1", "r2"), rules.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), groups.map { it.userOrder })
        assertEquals(listOf(1L, 2L, 3L), rules.map { it.userOrder })
        groups.first().userOrder = 3
        groups.last().userOrder = 1
        rules.first().userOrder = 3
        rules.last().userOrder = 1
        GroupRepository.updateUserOrders(groups)
        RuleRepository.updateRuleOrders(rules)
        RuleRepository.updateRule(stale)
        assertEquals(listOf("g2", "g1", "g0"), SagerDatabase.groupDao.allGroups().map { it.name })
        assertEquals(listOf("r2", "r1", "r0"), SagerDatabase.rulesDao.allRules().map { it.name })
    }
}
