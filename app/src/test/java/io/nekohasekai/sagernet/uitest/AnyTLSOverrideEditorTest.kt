package io.nekohasekai.sagernet.uitest

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.CoreTestNodes
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

class AnyTLSOverrideEditorTest : UiTestBase() {
    private val oldJson = """{"network":"tcp"}"""
    private val newJson = """{"min_idle_session":2}"""

    private fun profile(marked: Boolean = true): ProxyEntity {
        val fixture = UiFixtures.install()
        return ui.bg { ProfileRepository.createProfile(fixture.profiles.first().groupId, CoreTestNodes.anytls {
            name = "old-override"
            certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
            certificatePolicySource = AnyTLSBean.POLICY_PUBLISHED
            localSocksOutboundJson = marked
            customOutboundJson = oldJson
        }) }
    }

    private fun saved(profile: ProxyEntity) = ui.bg { ProfileRepository.getProfile(profile.id) }!!.anyTLSBean!!

    private fun AnyTLSSettingsActivity.editOutbound(json: String) {
        assertTrue(onOptionsItemSelected(RoboMenuItem(R.id.action_custom_outbound_json)))
        UiSettle.settle()
        val started = shadowOf(this).nextStartedActivityForResult
        assertNotNull(started)
        EditorCache.serverCustomOutbound = json
        shadowOf(this).receiveResult(started.intent, Activity.RESULT_OK, Intent())
        UiSettle.settle()
    }

    private fun AnyTLSSettingsActivity.confirmation(): AlertDialog {
        val row = child!!.findPreference<Preference>("confirmAnyTlsOverride")!!
        assertTrue(row.isVisible)
        row.performClick()
        UiSettle.settle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun rewrittenOverrideRequiresExplicitConfirmationAndSurvivesRecreation() {
        val profile = profile()
        val controller = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id)
        val editor = controller.get()
        editor.editOutbound(newJson)
        val dialog = editor.confirmation()
        assertTrue(saved(profile).localSocksOutboundJson)
        assertEquals(oldJson, saved(profile).customOutboundJson)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        UiSettle.settle()
        // 确认只改编辑缓存，未保存时数据库仍保留旧目标与 JSON。
        assertTrue(saved(profile).localSocksOutboundJson)
        assertEquals(oldJson, saved(profile).customOutboundJson)
        controller.recreate()
        UiSettle.settle()
        val recreated = controller.get()
        assertFalse(recreated.child!!.findPreference<Preference>("confirmAnyTlsOverride")!!.isVisible)
        saveEditor(recreated)
        val bean = saved(profile)
        assertFalse(bean.localSocksOutboundJson)
        assertEquals(newJson, bean.customOutboundJson)
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, bean.certificatePolicy)
        assertEquals(AnyTLSBean.POLICY_PUBLISHED, bean.certificatePolicySource)
        assertEquals("anytls", buildSingBoxOutboundAnyTLSBean(bean, false).type)
    }

    @Test
    fun ordinaryEditsAndCancelledConfirmationKeepTheOldTarget() {
        val profile = profile()
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        editor.child!!.findPreference<EditTextPreference>("name")!!.text = "edited-name"
        editor.editOutbound(newJson)
        editor.confirmation().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        UiSettle.settle()
        saveEditor(editor)
        assertTrue(latestDialogMessage().contains("Confirm rewritten override"))
        assertTrue(saved(profile).localSocksOutboundJson)
        assertEquals(oldJson, saved(profile).customOutboundJson)
        assertEquals("old-override", saved(profile).name)
    }

    @Test
    fun deletingTheOverrideStillClearsTheMarkerWithoutConfirmation() {
        val profile = profile()
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        editor.editOutbound("")
        saveEditor(editor)
        assertEquals("", saved(profile).customOutboundJson)
        assertFalse(saved(profile).localSocksOutboundJson)
    }

    @Test
    fun nativeOverridesDoNotShowTheLegacyConversionAction() {
        val profile = profile(marked = false)
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        assertFalse(editor.child!!.findPreference<Preference>("confirmAnyTlsOverride")!!.isVisible)
    }

    @Test
    fun discardingAConfirmedRewriteRestoresTheStoredTargetOnReopen() {
        val profile = profile()
        val controller = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id)
        val editor = controller.get()
        editor.editOutbound(newJson)
        editor.confirmation().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        UiSettle.settle()
        editor.finish()
        ui.destroyNow(controller)
        UiSettle.settle()
        val reopened = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        assertTrue(reopened.child!!.findPreference<Preference>("confirmAnyTlsOverride")!!.isVisible)
        assertTrue(saved(profile).localSocksOutboundJson)
        assertEquals(oldJson, saved(profile).customOutboundJson)
    }

    @Test
    fun lateConfirmationCannotChangeAnotherEditorSession() {
        val profile = profile()
        val first = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        first.editOutbound(newJson)
        val dialog = first.confirmation()
        val second = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        UiSettle.settle()
        assertTrue(EditorCache.profileCacheStore.getBoolean("localSocksOutboundJson", false))
        assertTrue(second.child!!.findPreference<Preference>("confirmAnyTlsOverride")!!.isVisible)
        assertTrue(saved(profile).localSocksOutboundJson)
    }
}
