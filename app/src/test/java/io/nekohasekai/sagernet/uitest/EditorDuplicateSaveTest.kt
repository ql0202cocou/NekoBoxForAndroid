package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.shadows.ShadowDialog

class EditorDuplicateSaveTest : UiTestBase() {
    @Test
    fun `新分组保存期间旋转后自动关闭且重复保存只插入一次`() {
        saveGroupAcrossRotation(completeInBackground = false)
    }

    @Test
    fun `旋转后的分组页面在后台错过保存完成通知仍在恢复时关闭`() {
        saveGroupAcrossRotation(completeInBackground = true)
    }

    private fun saveGroupAcrossRotation(completeInBackground: Boolean) {
        val controller = ui.launch(GroupSettingsActivity::class.java)
        val page = controller.get()
        EditorCache.groupName = "Synthetic unique group"
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) { reached.complete(Unit); release.await() }
            override suspend fun groupUpdated(group: ProxyGroup) {}
            override suspend fun groupRemoved(groupId: Long) {}
            override suspend fun groupUpdated(groupId: Long) {}
        }
        GroupRepository.addListener(listener)
        val first = runOnDefaultDispatcher { page.saveAndExit() }
        try {
            ui.bg { withTimeout(10_000) { reached.await() } }
            ui.bg { page.saveAndExit() }
            assertEquals(1, ui.bg { SagerDatabase.groupDao.allGroups().count { it.name == "Synthetic unique group" } })
            controller.rotate()
            assertNotSame("旋转必须重建 Activity", page, controller.get())
            assertFalse("保存尚未完成时不应关闭重建页面", controller.get().isFinishing)
            ui.bg { controller.get().saveAndExit() }
            if (completeInBackground) controller.pause().stop()
        } finally {
            release.complete(Unit)
            GroupRepository.removeListener(listener)
        }
        ui.viaWorker { runBlocking { withTimeout(10_000) { first.join() } } }
        if (completeInBackground) {
            assertFalse("后台页面应等到恢复显示再收尾", controller.get().isFinishing)
            controller.restart().start().resume().visible()
            UiSettle.settle()
        }
        assertTrue("保存完成后应自动关闭重建页面，无需再次保存", controller.get().isFinishing)
        ui.viaWorker { runBlocking { controller.get().saveAndExit() } }
        assertEquals(1, ui.bg { SagerDatabase.groupDao.allGroups().count { it.name == "Synthetic unique group" } })
    }

    @Test
    fun `空路由保存失败后旋转仍可修改并重新保存`() {
        UiFixtures.install()
        val controller = ui.launch(RouteSettingsActivity::class.java)
        saveEditor(controller.get())
        assertFalse("校验失败不能结束编辑会话", controller.get().isFinishing)
        assertNotNull("空路由必须被校验拦截", ShadowDialog.getLatestDialog())
        ShadowDialog.getLatestDialog().dismiss()

        val previous = controller.get()
        controller.rotate()
        assertNotSame(previous, controller.get())
        assertFalse("未成功保存的会话重建后仍可编辑", controller.get().isFinishing)
        EditorCache.routeDomain = "rotation.example"
        saveEditor(controller.get())

        assertTrue("修正后保存应关闭页面", controller.get().isFinishing)
        assertEquals(1, ui.bg { RuleRepository.getRules().count { it.domains == "rotation.example" } })
    }
}
