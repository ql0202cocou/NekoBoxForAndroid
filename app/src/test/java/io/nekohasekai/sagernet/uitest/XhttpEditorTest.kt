package io.nekohasekai.sagernet.uitest

import android.content.Intent
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.EditorActivity
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import io.nekohasekai.sagernet.ui.profile.StandardV2RaySettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.uitest.InlineValidationHarness.vmessEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * XHTTP 在节点编辑器里的行为：选 xhttp 才出现主机 / 路径 / 模式 / extra 四行，模式下拉能存，extra 经 JSON 编辑页回来
 * 要过 canonicalXhttpExtra（不合格不存，消息只带键名），保存时再校验一遍、空 path 规范成斜杠。
 * 不截图，所有平台都跑。每个 @Test 是自己的用例。
 */
class XhttpEditorTest : UiTestBase() {

    private fun string(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun EditorActivity.pref(key: String): Preference = child!!.findPreference(key)!!

    private fun EditorActivity.selectNetwork(value: String) {
        // 像用户选菜单项一样：先过变更监听器（updateView 在里面），通过后再写值
        val network = pref("type") as ListPreference
        if (network.callChangeListener(value)) network.value = value
        UiSettle.settle()
    }

    private fun cached(key: String) = EditorCache.profileCacheStore.getString(key)

    private fun latestDialogMessage(): String =
        (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .findViewById<TextView>(android.R.id.message)!!.text.toString()

    private val xhttpKeys = listOf("host", "path", "xhttpMode", "xhttpExtra")

    private fun <T> bg(block: suspend () -> T): T = ui.offMain { runBlocking { block() } }

    private fun savedBean(id: Long) = bg { ProfileRepository.getProfile(id)!!.requireBean() as VMessBean }

    private fun save(activity: EditorActivity) {
        runOnDefaultDispatcher { activity.saveAndExit() }
        UiSettle.settle()
    }

    // ---- 行的显示

    @Test
    fun xhttpRowsAppearOnlyForXhttp() {
        val editor = vmessEditor(ui).get()
        // 夹具是 ws：host / path 显示，mode / extra 隐藏
        assertTrue(editor.pref("host").isVisible)
        assertFalse(editor.pref("xhttpMode").isVisible)
        assertFalse(editor.pref("xhttpExtra").isVisible)

        editor.selectNetwork("xhttp")
        for (key in xhttpKeys) assertTrue("$key 应该可见", editor.pref(key).isVisible)
        assertEquals(string(R.string.xhttp_host), editor.pref("host").title)
        assertEquals(string(R.string.xhttp_path), editor.pref("path").title)
        assertEquals(string(R.string.xhttp_mode), editor.pref("xhttpMode").title)
        assertEquals(string(R.string.xhttp_extra), editor.pref("xhttpExtra").title)
        // 主机、路径、ALPN 的对话框带说明：空主机的含义、IPv6 要方括号、空路径存为斜杠、h3 走 UDP
        assertEquals(string(R.string.xhttp_host_hint), (editor.pref("host") as EditTextPreference).dialogMessage)
        assertEquals(string(R.string.xhttp_path_hint), (editor.pref("path") as EditTextPreference).dialogMessage)
        assertEquals(string(R.string.xhttp_alpn_h3_hint), (editor.pref("alpn") as EditTextPreference).dialogMessage)

        for (other in listOf("tcp", "ws", "grpc", "httpupgrade", "http")) {
            editor.selectNetwork(other)
            assertFalse("$other 下 mode 应该隐藏", editor.pref("xhttpMode").isVisible)
            assertFalse("$other 下 extra 应该隐藏", editor.pref("xhttpExtra").isVisible)
            assertNull("$other 下不该留 xhttp 的说明", (editor.pref("host") as EditTextPreference).dialogMessage)
            assertNull((editor.pref("alpn") as EditTextPreference).dialogMessage)
        }
    }

    @Test
    fun modeDropdownOffersFourModesAndPersists() {
        val editor = vmessEditor(ui).get()
        editor.selectNetwork("xhttp")
        val mode = editor.pref("xhttpMode") as ListPreference
        assertEquals(listOf("auto", "packet-up", "stream-up", "stream-one"), mode.entryValues.map { it.toString() })
        assertEquals("auto", cached("xhttpMode"))
        // 摘要带当前值和「服务端固定了模式时要选同一个」的说明
        assertEquals(string(R.string.xhttp_mode_summary, "auto"), mode.summary)

        mode.value = "stream-up"
        UiSettle.settle()
        assertEquals("stream-up", cached("xhttpMode"))
        assertEquals(string(R.string.xhttp_mode_summary, "stream-up"), mode.summary)
    }

    @Test
    fun conflictTitlesUseXhttpFieldNames() {
        val bean = VMessBean().apply { type = "xhttp" }
        assertEquals(R.string.xhttp_host, io.nekohasekai.sagernet.ui.profile.profileFieldTitle(io.nekohasekai.sagernet.fmt.ProfileField.HOST, bean))
        assertEquals(R.string.xhttp_path, io.nekohasekai.sagernet.ui.profile.profileFieldTitle(io.nekohasekai.sagernet.fmt.ProfileField.PATH, bean))
        val ws = VMessBean().apply { type = "ws" }
        assertEquals(R.string.ws_host, io.nekohasekai.sagernet.ui.profile.profileFieldTitle(io.nekohasekai.sagernet.fmt.ProfileField.HOST, ws))
    }

    // ---- extra

    @Test
    fun extraRowOpensTheJsonEditorWithThePrettySeedAndKeepsDirty() {
        val editor = vmessEditor(ui).get()
        editor.selectNetwork("xhttp")
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, """{"headers":{"X-A":"b"},"xPaddingBytes":"100-500"}""")
        EditorCache.dirty = false
        editor.pref("xhttpExtra").performClick()
        UiSettle.settle()
        val started: Intent = shadowOf(editor).nextStartedActivity
        assertEquals(ConfigEditActivity::class.java.name, started.component!!.className)
        assertEquals(Key.XHTTP_EXTRA_EDIT, started.getStringExtra("key"))
        val seed = cached(Key.XHTTP_EXTRA_EDIT)!!
        assertTrue("种子应该是排版过的 JSON", seed.contains("\n") && seed.contains("xPaddingBytes"))
        assertFalse("放种子不算编辑", EditorCache.dirty)
    }

    @Test
    fun validExtraComesBackCanonicalAndUpdatesSummary() {
        val editor = vmessEditor(ui).get()
        editor.selectNetwork("xhttp")
        assertEquals(string(androidx.preference.R.string.not_set), editor.pref("xhttpExtra").summary)
        // JSON 编辑页写回的是排版过、键序和写法随意的文本
        EditorCache.profileCacheStore.putString(
            Key.XHTTP_EXTRA_EDIT,
            "{\n  \"XMUX\": {\"maxConcurrency\": \"32-16\"},\n  \"headers\": {\"X-Secret-Name\": \"secret-value-zq71\"}\n}",
        )
        (editor as VMessSettingsActivity).applyEditedXhttpExtra()
        UiSettle.settle()
        assertEquals("""{"headers":{"X-Secret-Name":"secret-value-zq71"},"xmux":{"maxConcurrency":"16-32"}}""", cached("xhttpExtra"))
        // 摘要只列顶层键名，不带值
        val summary = editor.pref("xhttpExtra").summary.toString()
        assertEquals("headers, xmux", summary)
        assertFalse(summary.contains("zq71"))
        assertTrue(EditorCache.dirty)

        // 清空文本即去掉 extra
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA_EDIT, "")
        editor.applyEditedXhttpExtra()
        UiSettle.settle()
        assertEquals("", cached("xhttpExtra"))
        assertEquals(string(androidx.preference.R.string.not_set), editor.pref("xhttpExtra").summary)
    }

    @Test
    fun invalidExtraIsRejectedWithKeyOnlyMessageAndNotStored() {
        val editor = vmessEditor(ui).get()
        editor.selectNetwork("xhttp")
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, """{"noSSEHeader":true}""")
        EditorCache.profileCacheStore.putString(
            Key.XHTTP_EXTRA_EDIT, """{"headers":{"Authorization":"Bearer-secret-zq72"},"downloadSettings":{"address":"secret-dl.example.com"}}""",
        )
        (editor as StandardV2RaySettingsActivity).applyEditedXhttpExtra()
        UiSettle.settle()
        assertEquals("不合格的 extra 不能写进缓存", """{"noSSEHeader":true}""", cached("xhttpExtra"))
        val message = latestDialogMessage()
        assertEquals(string(R.string.xhttp_extra_invalid, "xhttp extra rejected at downloadSettings: downloadSettings is not allowed"), message)
        assertFalse(message.contains("zq72"))
        assertFalse(message.contains("secret-dl"))
    }

    // ---- 保存

    @Test
    fun savingNormalizesAnEmptyPathAndStoresXhttpFields() {
        val editor = vmessEditor(ui).get()
        val id = editor.proxyEntity!!.id
        editor.selectNetwork("xhttp")
        (editor.pref("path") as EditTextPreference).text = ""
        (editor.pref("host") as EditTextPreference).text = "cdn.example.com"
        (editor.pref("xhttpMode") as ListPreference).value = "stream-one"
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, """{"noSSEHeader":true}""")
        UiSettle.settle()
        save(editor)
        val bean = savedBean(id)
        assertEquals("xhttp", bean.type)
        assertEquals("/", bean.path)
        assertEquals("cdn.example.com", bean.host)
        assertEquals("stream-one", bean.xhttpMode)
        assertEquals("""{"noSSEHeader":true}""", bean.xhttpExtra)
    }

    @Test
    fun savingRejectsAnExtraThatBypassedTheInputCheck() {
        val editor = vmessEditor(ui).get()
        val id = editor.proxyEntity!!.id
        editor.selectNetwork("xhttp")
        // 导入（通用链接、备份）带进来的值没过输入校验：直接写缓存模拟
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, """{"downloadSettings":{"address":"secret-dl.example.com"}}""")
        ShadowToast.reset()
        save(editor)
        val toast = ShadowToast.getTextOfLatestToast()
        assertEquals(string(R.string.xhttp_extra_invalid, "xhttp extra rejected at downloadSettings: downloadSettings is not allowed"), toast)
        assertEquals("不合格的 extra 不能存库", "ws", savedBean(id).type)
        assertNotNull(editor.child)
    }

    @Test
    fun savingRejectsAnUnknownMode() {
        val editor = vmessEditor(ui).get()
        val id = editor.proxyEntity!!.id
        editor.selectNetwork("xhttp")
        EditorCache.profileCacheStore.putString("xhttpMode", "Fast-Mode")
        ShadowToast.reset()
        save(editor)
        assertEquals(string(R.string.xhttp_extra_invalid, "xhttp mode rejected: unsupported mode"), ShadowToast.getTextOfLatestToast())
        assertEquals("ws", savedBean(id).type)
    }

    @Test
    fun otherTransportsIgnoreXhttpValuesOnSave() {
        val editor = vmessEditor(ui).get()
        val id = editor.proxyEntity!!.id
        // 切到 xhttp 又切回去：xhttp 的值留在缓存里，但已经隐藏，不能拦住保存
        editor.selectNetwork("xhttp")
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, """{"downloadSettings":{}}""")
        editor.selectNetwork("grpc")
        save(editor)
        assertEquals("grpc", savedBean(id).type)
    }

    @Test
    fun vlessVisionAndMuxCoolConflictsAreShownForXhttp() {
        val editor = vmessEditor(ui).get()
        editor.selectNetwork("xhttp")
        // Mux.Cool 与 XHTTP 冲突：保存时弹出选核被拒的对话框，其中有 XHTTP 的字段名与原因
        EditorCache.profileCacheStore.putBoolean("enableMux", true)
        EditorCache.profileCacheStore.putString("muxType", "3")
        save(editor)
        val message = latestDialogMessage()
        assertTrue(message, message.contains(string(R.string.core_conflict_xray_xhttp_mux_cool)))
    }

    // ---- 旧版本留下的残缺 xhttp 节点（path 从未落库）：不猜测、不替用户补斜杠

    private fun staleEditor(): ActivityController<VMessSettingsActivity> {
        val fixtures = UiFixtures.install()
        val bean = VMessBean().apply {
            name = "stale xhttp"
            serverAddress = "192.0.2.90"
            serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"
            alterId = 0
            type = "xhttp"
            path = ""
        }
        val entity = bg { ProfileRepository.createProfile(fixtures.normalGroup.id, bean) }
        val intent = Intent(ApplicationProvider.getApplicationContext(), VMessSettingsActivity::class.java)
            .putExtra(io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.EXTRA_PROFILE_ID, entity.id)
        return ui.launch(VMessSettingsActivity::class.java, intent)
    }

    @Test
    fun staleNodeSavedUntouchedIsBlockedWithParametersLost() {
        val editor = staleEditor().get()
        val id = editor.proxyEntity!!.id
        assertEquals("xhttp", cached("type"))
        ShadowToast.reset()
        save(editor)
        assertEquals(string(R.string.core_conflict_xhttp_parameters_lost), ShadowToast.getTextOfLatestToast())
        assertTrue("不能替用户补斜杠", savedBean(id).path.isNullOrEmpty())
        assertEquals("xhttp", savedBean(id).type)
    }

    @Test
    fun staleNodeSavesOnceThePathIsTyped() {
        val editor = staleEditor().get()
        val id = editor.proxyEntity!!.id
        (editor.pref("path") as EditTextPreference).text = "/"
        UiSettle.settle()
        save(editor)
        assertEquals("/", savedBean(id).path)
        assertEquals("xhttp", savedBean(id).type)
    }

    @Test
    fun staleNodeSwitchedToAnotherTransportSavesNormally() {
        val editor = staleEditor().get()
        val id = editor.proxyEntity!!.id
        editor.selectNetwork("grpc")
        save(editor)
        assertEquals("grpc", savedBean(id).type)
    }

    @Test
    fun staleFlagSurvivesRecreation() {
        val controller = staleEditor()
        val id = controller.get().proxyEntity!!.id
        controller.recreate()
        UiSettle.settle()
        val recreated = controller.get()
        ShadowToast.reset()
        save(recreated)
        assertEquals(string(R.string.core_conflict_xhttp_parameters_lost), ShadowToast.getTextOfLatestToast())
        assertTrue(savedBean(id).path.isNullOrEmpty())
    }

    @Test
    fun newXhttpNodeWithEmptyPathSavesAsSlash() {
        UiFixtures.install()
        val intent = Intent(ApplicationProvider.getApplicationContext(), VMessSettingsActivity::class.java)
        val editor = ui.launch(VMessSettingsActivity::class.java, intent).get()
        UiSettle.settle()
        (editor.pref("serverAddress") as EditTextPreference).text = "198.51.100.77"
        (editor.pref("uuid") as EditTextPreference).text = "00000000-0000-4000-8000-000000000002"
        editor.selectNetwork("xhttp")
        (editor.pref("path") as EditTextPreference).text = ""
        UiSettle.settle()
        save(editor)
        val created = bg {
            ProfileRepository.getProfilesByGroup(EditorCache.editingGroup).first { it.requireBean().serverAddress == "198.51.100.77" }
        }
        val bean = created.requireBean() as VMessBean
        assertEquals("xhttp", bean.type)
        assertEquals("/", bean.path)
    }

    // ---- JSON 编辑页的页内校验

    private fun jsonEditor(text: String): ActivityController<ConfigEditActivity> {
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA_EDIT, text)
        val intent = Intent(ApplicationProvider.getApplicationContext(), ConfigEditActivity::class.java)
            .putExtra("key", Key.XHTTP_EXTRA_EDIT)
        return ui.launch(ConfigEditActivity::class.java, intent)
    }

    @Test
    fun jsonEditorKeepsTheUserOnThePageWhenExtraIsInvalid() {
        val controller = jsonEditor("""{"headers": {"Authorization": "Bearer-secret-zq73"}}""")
        val page = controller.get()
        page.binding.editor.setTextContent("""{"downloadSettings": {"address": "secret-dl.example.com"}}""")
        UiSettle.settle()
        page.saveAndExit()
        UiSettle.settle()
        assertFalse("校验不过不能关闭页面", page.isFinishing)
        assertFalse("校验不过不能写回缓存", cached(Key.XHTTP_EXTRA_EDIT)!!.contains("downloadSettings"))
        val message = latestDialogMessage()
        assertEquals(string(R.string.xhttp_extra_invalid, "xhttp extra rejected at downloadSettings: downloadSettings is not allowed"), message)
        assertFalse(message.contains("secret-dl"))

        // 改成合法内容后保存：写回缓存并关闭
        page.binding.editor.setTextContent("""{"xPaddingBytes": "100-500"}""")
        UiSettle.settle()
        page.saveAndExit()
        UiSettle.settle()
        assertTrue(page.isFinishing)
        assertTrue(cached(Key.XHTTP_EXTRA_EDIT)!!.contains("xPaddingBytes"))
    }

    @Test
    fun otherJsonKeysAreNotValidatedAsXhttpExtra() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ConfigEditActivity::class.java)
            .putExtra("key", Key.SERVER_CUSTOM)
        EditorCache.profileCacheStore.putString(Key.SERVER_CUSTOM, "{}")
        val page = ui.launch(ConfigEditActivity::class.java, intent).get()
        page.binding.editor.setTextContent("""{"anything": {"downloadSettings": 1}}""")
        UiSettle.settle()
        page.saveAndExit()
        UiSettle.settle()
        assertTrue(page.isFinishing)
    }
}
