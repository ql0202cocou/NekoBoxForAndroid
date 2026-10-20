package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem

/**
 * 编辑器协程统一入口（[io.nekohasekai.sagernet.ui.EditorActivity.launchInSession]）的行为：
 * 会话被别的编辑器接管、页面销毁之后，迟到的结果不得写 [EditorCache]、不得碰 View。
 * 上半部分用最小的探针编辑器 + 门闩把协程停在挂起点，确定性地复现「挂起期间被接管」；
 * 下半部分走真实编辑器的选择器与 JSON 子编辑器回调（接管发生在回调之前）。每组都带一个「不接管」的对照，
 * 证明被丢弃的写入在会话正常时确实会发生，不是用例本身没走到。不截图，所有平台都跑。
 */
class EditorSessionEntryTest : UiTestBase() {

    private val foreign = "foreign-name"

    private fun probe(): EditorSessionProbeActivity {
        val controller = ui.launch(EditorSessionProbeActivity::class.java)
        assertTrue("探针编辑器应当持有会话", controller.get().holds())
        return controller.get()
    }

    /** 别的顶层编辑器接管缓存，并写进自己的内容 */
    private fun takeOver() {
        claimEditorSession()
        EditorCache.profileName = foreign
    }

    // ------------------------------------------------------------- 入口本身

    @Test
    fun lateWriteAfterCheckedSuspensionIsDroppedWhenTakenOver() {
        val editor = probe()
        val gate = CompletableDeferred<Unit>()
        val job = editor.probe {
            checked { gate.await() }
            EditorCache.profileName = "late"
        }
        UiSettle.settle()
        takeOver()
        gate.complete(Unit)
        UiSettle.settle()
        assertEquals(foreign, EditorCache.profileName)
        assertTrue("会话失效后协程应当被取消", job.isCancelled)
    }

    @Test
    fun lateWriteAfterCheckedSuspensionHappensWhenSessionHeld() {
        val editor = probe()
        val gate = CompletableDeferred<Unit>()
        val job = editor.probe {
            checked { gate.await() }
            EditorCache.profileName = "late"
        }
        UiSettle.settle()
        gate.complete(Unit)
        UiSettle.settle()
        assertEquals("late", EditorCache.profileName)
        assertFalse(job.isCancelled)
    }

    @Test
    fun onMainDropsBlockWhenTakenOverDuringTheSwitch() {
        val editor = probe()
        val gate = CompletableDeferred<Unit>()
        editor.probe {
            gate.await() // 故意不用 checked：onMain 自己也要在切到主线程时复查
            onMain { EditorCache.profileName = "late-main" }
        }
        UiSettle.settle()
        takeOver()
        gate.complete(Unit)
        UiSettle.settle()
        assertEquals(foreign, EditorCache.profileName)
    }

    @Test
    fun blockDoesNotStartWhenSessionAlreadyLost() {
        val editor = probe()
        takeOver()
        var started = false
        val job = editor.probe { started = true }
        UiSettle.settle()
        assertFalse("会话已被接管，协程体不应开始", started)
        assertTrue(job.isCancelled)
        assertEquals(foreign, EditorCache.profileName)
    }

    @Test
    fun lateWriteAfterPageDestroyedIsDropped() {
        val controller = ui.launch(EditorSessionProbeActivity::class.java)
        val editor = controller.get()
        val gate = CompletableDeferred<Unit>()
        val job = editor.probe {
            checked { gate.await() }
            EditorCache.profileName = "late"
        }
        UiSettle.settle()
        EditorCache.profileName = "before-close"
        ui.destroyNow(controller)
        gate.complete(Unit)
        UiSettle.settle()
        assertEquals("before-close", EditorCache.profileName)
        assertTrue(job.isCancelled)
    }

    // ------------------------------------------------------------- 链编辑器的选择器

    private fun chainEditor(): Pair<ChainSettingsActivity, Long> {
        val fixtures = UiFixtures.install()
        val chain = ui.offMain {
            runBlocking { ProfileRepository.createProfile(fixtures.normalGroup.id, ChainBean().apply { name = "Chain A" }) }
        }
        val intent = Intent(ApplicationProvider.getApplicationContext(), ChainSettingsActivity::class.java)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, chain.id)
        return ui.launch(ChainSettingsActivity::class.java, intent).get() to fixtures.profiles[0].id
    }

    private fun deliverPick(editor: ChainSettingsActivity, profileId: Long) {
        editor.selectProfileForAdd.launch(Intent(editor, ProfileSelectActivity::class.java))
        val started = shadowOf(editor).peekNextStartedActivityForResult()
        assertNotNull("选择器应当已启动", started)
        shadowOf(editor).receiveResult(
            started.intent, Activity.RESULT_OK, Intent().putExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, profileId),
        )
        UiSettle.settle()
    }

    @Test
    fun chainPickerCallbackWritesCacheWhenSessionHeld() {
        val (editor, picked) = chainEditor()
        deliverPick(editor, picked)
        assertEquals(picked.toString(), EditorCache.serverProtocol)
        assertTrue(EditorCache.dirty)
    }

    @Test
    fun chainPickerCallbackAfterTakeoverWritesNothing() {
        val (editor, picked) = chainEditor()
        takeOver()
        val before = EditorCache.serverProtocol
        val dirtyBefore = EditorCache.dirty // 接管方写缓存时编辑器的监听已经置过脏标记
        deliverPick(editor, picked)
        assertEquals("被接管后选择器结果不得写进别人的缓存", before, EditorCache.serverProtocol)
        assertEquals(dirtyBefore, EditorCache.dirty)
        assertEquals(foreign, EditorCache.profileName)
    }

    // ------------------------------------------------------------- 分组编辑器的选择器（profilePicker）

    private fun groupEditor() = ui.launch(GroupSettingsActivity::class.java).get()

    private fun deliverGroupPick(editor: GroupSettingsActivity, profileId: Long) {
        editor.selectProfileForAddFront.launch(Intent(editor, ProfileSelectActivity::class.java))
        val started = shadowOf(editor).peekNextStartedActivityForResult()
        shadowOf(editor).receiveResult(
            started.intent, Activity.RESULT_OK, Intent().putExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, profileId),
        )
        UiSettle.settle()
    }

    @Test
    fun groupPickerCallbackWritesCacheWhenSessionHeld() {
        val fixtures = UiFixtures.install()
        val editor = groupEditor()
        deliverGroupPick(editor, fixtures.profiles[0].id)
        assertEquals(fixtures.profiles[0].id, EditorCache.frontProxy)
    }

    @Test
    fun groupPickerCallbackAfterTakeoverWritesNothing() {
        val fixtures = UiFixtures.install()
        val editor = groupEditor()
        takeOver()
        val before = EditorCache.frontProxy
        deliverGroupPick(editor, fixtures.profiles[0].id)
        assertEquals(before, EditorCache.frontProxy)
    }

    // ------------------------------------------------------------- 节点编辑器的 JSON 子编辑器往返

    private fun ssEditor(): ShadowsocksSettingsActivity {
        val fixtures = UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShadowsocksSettingsActivity::class.java)
            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtures.profiles[1].id)
        return ui.launch(ShadowsocksSettingsActivity::class.java, intent).get()
    }

    private fun ShadowsocksSettingsActivity.openJsonEditor() {
        assertTrue(onOptionsItemSelected(RoboMenuItem(R.id.action_custom_config_json)))
        UiSettle.settle()
    }

    private fun ShadowsocksSettingsActivity.customJson() = proxyEntity!!.requireBean().customConfigJson

    @Test
    fun jsonEditorRoundTripAppliesWhenSessionHeld() {
        val editor = ssEditor()
        editor.openJsonEditor()
        val started = shadowOf(editor).peekNextStartedActivityForResult()
        assertNotNull("JSON 子编辑器应当已启动", started)
        EditorCache.serverCustom = UiScenarios.SAVED_JSON
        shadowOf(editor).receiveResult(started.intent, Activity.RESULT_OK, Intent())
        UiSettle.settle()
        assertEquals(UiScenarios.SAVED_JSON, editor.customJson())
    }

    @Test
    fun jsonEditorResultAfterTakeoverIsNotAppliedToBean() {
        val editor = ssEditor()
        editor.openJsonEditor()
        val started = shadowOf(editor).peekNextStartedActivityForResult()
        assertNotNull("JSON 子编辑器应当已启动", started)
        val before = editor.customJson()
        takeOver()
        EditorCache.serverCustom = "{\"foreign\":true}"
        shadowOf(editor).receiveResult(started.intent, Activity.RESULT_OK, Intent())
        UiSettle.settle()
        assertEquals("被接管后别人缓存里的 JSON 不得写进本编辑器的节点", before, editor.customJson())
    }

    @Test
    fun jsonEditorDoesNotLaunchOrSeedCacheAfterTakeover() {
        val editor = ssEditor()
        takeOver()
        EditorCache.serverCustom = "{\"foreign\":true}"
        editor.openJsonEditor()
        assertNull("被接管后不应再启动子编辑器", shadowOf(editor).peekNextStartedActivityForResult())
        assertEquals("{\"foreign\":true}", EditorCache.serverCustom)
    }
}
