package io.nekohasekai.sagernet.uitest

import androidx.lifecycle.SavedStateHandle
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ConfigurationViewModel
import io.nekohasekai.sagernet.ui.GroupViewModel
import io.nekohasekai.sagernet.ui.ProfileListViewModel
import io.nekohasekai.sagernet.ui.RouteViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** 让旧读先从真实 Room 取完快照，再扣住它返回主线程的任务，直到新读完成提交。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListReloadOrderTest : UiTestBase() {
    private class QueuedMain : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        fun next(): Runnable = checkNotNull(tasks.poll(10, TimeUnit.SECONDS)) { "没有收到主线程任务" }
        fun runNext() = next().run()
    }

    private fun withQueuedMain(block: QueuedMain.() -> Unit) {
        val main = QueuedMain()
        Dispatchers.setMain(main)
        try {
            main.block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun groupAndConfigurationListsKeepTheNewSnapshot() {
        val group = UiFixtures.install().normalGroup
        withQueuedMain {
            val groups = GroupViewModel(SavedStateHandle())
            val config = ConfigurationViewModel(SavedStateHandle())
            fun checkReload(reload: () -> Unit, names: () -> List<String?>, newName: String) {
                runNext()
                val old = next()
                ui.offMain {
                    group.name = newName
                    SagerDatabase.groupDao.updateGroup(group)
                }
                reload()
                runNext()
                runNext()
                val expected = ui.offMain { SagerDatabase.groupDao.allGroups() }.map { it.name }
                assertEquals(expected, names())
                old.run()
                assertEquals(expected, names())
            }
            checkReload(groups::reload, { groups.groupList.map { it.name } }, "Groups updated")
            config.reload()
            checkReload({ config.reload() }, { config.groupList.map { it.name } }, "Tabs updated")
        }
    }

    @Test
    fun deferredPagerUpdateCannotOverwriteAnImmediateReload() {
        val fixtures = UiFixtures.install()
        val group = fixtures.normalGroup
        withQueuedMain {
            val model = ConfigurationViewModel(SavedStateHandle())
            lateinit var deferred: () -> Unit
            var selection = 0L
            model.attach(object : ConfigurationViewModel.Host {
                override fun postWhenAttached(block: () -> Unit) { deferred = block }
                override fun applyGroups(groups: ArrayList<ProxyGroup>, selectedGroup: Long) {
                    selection = selectedGroup
                    model.commitGroups(groups)
                }
                override fun groupInserted(group: ProxyGroup) = Unit
                override fun groupRemovedAt(index: Int) = Unit
                override fun groupRenamed(index: Int, group: ProxyGroup) = Unit
            })
            model.reload()
            runNext()
            runNext()
            ui.offMain {
                group.name = "Updated before pager attach"
                SagerDatabase.groupDao.updateGroup(group)
            }
            model.reload(now = true)
            runNext()
            runNext()
            deferred()
            assertEquals(group.name, model.groupList.single { it.id == group.id }.name)
            model.reload()
            runNext()
            runNext()
            model.selectedGroupId = fixtures.subscriptionGroup.id
            deferred()
            assertEquals(fixtures.subscriptionGroup.id, selection)
        }
    }

    @Test
    fun profileListKeepsTheNewSnapshot() {
        val fixtures = UiFixtures.install()
        val group = fixtures.normalGroup.id
        withQueuedMain {
            val model = ProfileListViewModel(SavedStateHandle(mapOf(ProfileListViewModel.ARG_GROUP_ID to group)))
            ui.offMain { model.reloadProfiles() }
            val old = next()
            val id = fixtures.profiles.first().id
            ui.offMain {
                SagerDatabase.proxyDao.updateStatus(id, 1, 271, null)
                model.reloadProfiles()
            }
            runNext()
            assertEquals(271, model.latestEntity(id)!!.ping)
            old.run()
            assertEquals(271, model.latestEntity(id)!!.ping)
        }
    }

    @Test
    fun oldRouteReadCannotReviveAConfirmedDeletion() {
        UiFixtures.install()
        withQueuedMain {
            val model = RouteViewModel()
            runNext()
            runNext()
            model.reload()
            runNext()
            val old = next()
            val deleted = model.removeAt(0)
            ui.offMain { SagerDatabase.rulesDao.deleteById(deleted.id) }
            model.confirmRemoved(listOf(deleted))
            model.reload()
            runNext()
            runNext()
            val expected = UiFixtures.ruleIds()
            assertEquals(expected, model.ruleList.map { it.id })
            old.run()
            assertEquals(expected, model.ruleList.map { it.id })
        }
    }
}
