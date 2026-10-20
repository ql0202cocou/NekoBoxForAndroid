package io.nekohasekai.sagernet.uitest

import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 库的门：另起一个线程在 [SagerDatabase] 上开一个事务并一直占着。Room 的库是非 WAL 的单连接，门开着时
 * 任何别的线程查库或写库都排队等连接，因此「保存写库在途」是确定的，不取决于谁先到。
 * 门开着时不能 settle（排队的线程在等连接，settle 会超时），出了 [use] 块再 settle。
 * 只挡 [SagerDatabase]：编辑缓存（TempDatabase）与 DataStore（PublicDatabase）不受影响。
 */
private class DbGate : AutoCloseable {
    private val release = CountDownLatch(1)
    private val held = CountDownLatch(1)
    private val holder = thread(name = "ui-test-dbgate") {
        SagerDatabase.instance.runInTransaction(Runnable {
            held.countDown()
            release.await()
        })
    }

    init {
        if (!held.await(10, TimeUnit.SECONDS)) {
            release.countDown()
            error("库的门没能在 10 s 内占上")
        }
    }

    override fun close() {
        release.countDown()
        holder.join(10_000)
    }

    /**
     * 空转主 looper，直到有一个协程 worker 卡在等库连接（保存已走过主线程的校验、正在写库）。
     * 超时抛错并附上线程栈，免得「没卡住」被当成通过。
     */
    fun awaitWriterBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val blocked = Thread.getAllStackTraces().entries.any { (t, stack) ->
                t.name.startsWith("DefaultDispatcher-worker-") && stack.any { it.className.contains("SQLiteConnectionPool") }
            }
            if (blocked) return
            if (System.nanoTime() > deadline) {
                error("前置条件：10 s 内没有协程卡在等库连接\n" + Thread.getAllStackTraces().entries
                    .filter { it.key.name.startsWith("DefaultDispatcher-worker-") }
                    .joinToString("\n") { (t, s) -> t.name + " " + s.take(12).joinToString(" <- ") })
            }
            Thread.sleep(1)
        }
    }
}

/**
 * U4b b4 第二部分：编辑器的保存是进程级的（页面关闭、旋转也要写完），写完之后的界面收尾
 * （提示、对话框、关闭页面）只在页面还活着时做。判定都是：库里的内容是保存后的值、没有未捕获异常
 * （UiTestRule 收集）、已销毁的页面上没有弹出 Toast 或对话框。不截图，所有平台都跑。
 */
class EditorSaveOutlivesPageTest : UiTestBase() {

    private fun editIntent(type: Class<*>, key: String, id: Long) =
        Intent(ApplicationProvider.getApplicationContext(), type).putExtra(key, id)

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    private fun ssEditor(): ActivityController<ShadowsocksSettingsActivity> {
        val fixtures = UiFixtures.install()
        return ui.launch(
            ShadowsocksSettingsActivity::class.java,
            editIntent(ShadowsocksSettingsActivity::class.java, ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[1].id),
        )
    }

    private fun EditorActivity.editAddress() {
        child!!.findPreference<EditTextPreference>(Key.SERVER_ADDRESS)!!.text = EDITED
        UiSettle.settle()
        assertTrue("前置条件：改字段后没有置未保存标记", EditorCache.dirty)
    }

    private fun savedAddress(id: Long) =
        bg { (ProfileRepository.getProfile(id)!!.requireBean() as ShadowsocksBean).serverAddress }

    /** 节点编辑器：保存写库在途时销毁页面，写库照常完成，收尾不碰已销毁的页面 */
    @Test
    fun profileSaveCompletesAfterPageDestroyed() {
        val editor = ssEditor()
        val activity = editor.get()
        val id = activity.proxyEntity!!.id
        activity.editAddress()
        ShadowToast.reset()
        DbGate().use { gate ->
            runOnDefaultDispatcher { activity.saveAndExit() }
            gate.awaitWriterBlocked()
            ui.destroyNow(editor)
        }
        UiSettle.settle()
        assertEquals("页面销毁后保存没有写完", EDITED, savedAddress(id))
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    /**
     * 节点编辑器：页面先销毁、保存后到（例如保存途中旋转，旧实例已销毁）。偏好屏幕已经没了，
     * 但保存是进程级的，缓存里的内容照样写库，不能悄悄跳过
     */
    @Test
    fun profileSaveAfterPageDestroyedStillWrites() {
        val editor = ssEditor()
        val activity = editor.get()
        val id = activity.proxyEntity!!.id
        activity.editAddress()
        ui.destroyNow(editor)
        ShadowToast.reset()
        runOnDefaultDispatcher { activity.saveAndExit() }
        UiSettle.settle()
        assertEquals("页面已销毁时保存被悄悄跳过", EDITED, savedAddress(id))
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    /**
     * 节点编辑器：页面已销毁时看不到地址项，但有地址项的编辑器只要缓存里的地址不合法就不存
     *（输入时会被拦下，这里直接写缓存模拟导入或旧版本留下的值），库里保持原值
     */
    @Test
    fun profileSaveAfterPageDestroyedRejectsInvalidAddress() {
        val editor = ssEditor()
        val activity = editor.get()
        val id = activity.proxyEntity!!.id
        val original = savedAddress(id)
        activity.editAddress()
        EditorCache.serverAddress = ""
        ui.destroyNow(editor)
        ShadowToast.reset()
        runOnDefaultDispatcher { activity.saveAndExit() }
        UiSettle.settle()
        assertEquals("页面已销毁时把不合法的地址写进了库", original, savedAddress(id))
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    /** 保存时节点已被删掉：页面活着要提示「已删除」（对照），保存途中页面销毁了就不提示 */
    @Test
    fun profileSaveOfDeletedEntityToastsOnlyWhileAlive() {
        // 对照：页面活着
        val alive = ssEditor()
        val aliveActivity = alive.get()
        aliveActivity.editAddress()
        bg { ProfileRepository.deleteProfile(aliveActivity.proxyEntity!!.groupId, aliveActivity.proxyEntity!!.id) }
        ShadowToast.reset()
        runOnDefaultDispatcher { aliveActivity.saveAndExit() }
        UiSettle.settle()
        assertEquals("对照组：页面还在却没有提示节点已删除", 1, ShadowToast.shownToastCount())
    }

    @Test
    fun profileSaveOfDeletedEntityDoesNotToastAfterDestroy() {
        val editor = ssEditor()
        val activity = editor.get()
        activity.editAddress()
        bg { ProfileRepository.deleteProfile(activity.proxyEntity!!.groupId, activity.proxyEntity!!.id) }
        ShadowToast.reset()
        DbGate().use { gate ->
            runOnDefaultDispatcher { activity.saveAndExit() }
            gate.awaitWriterBlocked()
            ui.destroyNow(editor)
        }
        UiSettle.settle()
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    /** 分组编辑器：保存途中销毁页面，改名照常写库 */
    @Test
    fun groupSaveCompletesAfterPageDestroyed() {
        val fixtures = UiFixtures.install()
        val editor = ui.launch(
            GroupSettingsActivity::class.java,
            editIntent(GroupSettingsActivity::class.java, GroupSettingsActivity.EXTRA_GROUP_ID, fixtures.normalGroup.id),
        )
        val activity = editor.get()
        EditorCache.groupName = "Renamed group"
        EditorCache.dirty = true
        ShadowToast.reset()
        DbGate().use { gate ->
            runOnDefaultDispatcher { activity.saveAndExit() }
            gate.awaitWriterBlocked()
            ui.destroyNow(editor)
        }
        UiSettle.settle()
        assertEquals("页面销毁后分组没有保存", "Renamed group", bg { GroupRepository.getGroup(fixtures.normalGroup.id)!!.name })
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    /** 路由编辑器：保存途中销毁页面，改名照常写库 */
    @Test
    fun routeSaveCompletesAfterPageDestroyed() {
        UiFixtures.install()
        val rule = bg { RuleRepository.getRules().first() }
        val editor = ui.launch(
            RouteSettingsActivity::class.java,
            editIntent(RouteSettingsActivity::class.java, RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id),
        )
        val activity = editor.get()
        EditorCache.routeName = "Renamed rule"
        EditorCache.dirty = true
        DbGate().use { gate ->
            runOnDefaultDispatcher { activity.saveAndExit() }
            gate.awaitWriterBlocked()
            ui.destroyNow(editor)
        }
        UiSettle.settle()
        assertEquals("页面销毁后规则没有保存", "Renamed rule", bg { RuleRepository.getRule(rule.id)!!.name })
    }

    /** 新建一条什么都没填的路由：页面活着弹「空路由」对话框（对照），页面已销毁就不弹 */
    @Test
    fun emptyRouteDialogShownOnlyWhileAlive() {
        UiFixtures.install()
        val editor = ui.launch(
            RouteSettingsActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), RouteSettingsActivity::class.java),
        )
        ShadowDialog.reset()
        runOnDefaultDispatcher { editor.get().saveAndExit() }
        UiSettle.settle()
        assertTrue("对照组：页面还在却没有弹出空路由对话框", ShadowDialog.getLatestDialog() != null)
    }

    @Test
    fun emptyRouteDialogSkippedAfterDestroy() {
        UiFixtures.install()
        val editor = ui.launch(
            RouteSettingsActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), RouteSettingsActivity::class.java),
        )
        val activity = editor.get()
        ui.destroyNow(editor)
        ShadowDialog.reset()
        runOnDefaultDispatcher { activity.saveAndExit() }
        UiSettle.settle()
        assertNull("已销毁的页面上弹了对话框", ShadowDialog.getLatestDialog())
    }

    /** 缓存被别的编辑器接管：页面活着提示并关闭（对照），页面已销毁则什么也不弹 */
    @Test
    fun sessionLostToastShownOnlyWhileAlive() {
        val editor = ssEditor()
        claimEditorSession()
        ShadowToast.reset()
        runOnDefaultDispatcher { editor.get().saveAndExit() }
        UiSettle.settle()
        assertEquals("对照组：页面还在却没有提示会话已失效", 1, ShadowToast.shownToastCount())
        assertTrue("对照组：会话失效后页面没有关闭", editor.get().isFinishing)
    }

    @Test
    fun sessionLostToastSkippedAfterDestroy() {
        val editor = ssEditor()
        val activity = editor.get()
        ui.destroyNow(editor)
        claimEditorSession()
        ShadowToast.reset()
        runOnDefaultDispatcher { activity.saveAndExit() }
        UiSettle.settle()
        assertEquals("已销毁的页面上弹了 Toast", 0, ShadowToast.shownToastCount())
    }

    private companion object {
        const val EDITED = "192.0.2.99"
    }
}
