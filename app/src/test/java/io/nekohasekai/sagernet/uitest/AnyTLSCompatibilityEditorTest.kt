package io.nekohasekai.sagernet.uitest

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCompatibilityEditorTest : UiTestBase() {
    @Test
    fun newTestEntryExplicitlyEnablesCompatibility() {
        UiFixtures.install()
        val intent = ui.editorIntent(AnyTLSSettingsActivity::class.java, 0)
            .putExtra(AnyTLSSettingsActivity.EXTRA_COMPATIBILITY_TEST, true)
        val editor = ui.launch(AnyTLSSettingsActivity::class.java, intent).get()
        assertTrue(EditorCache.profileCacheStore.getBoolean("compatibilityTest") == true)
        assertTrue(editor.child!!.findPreference<Preference>("compatibilityTestInfo")!!.isVisible)
        assertFalse(editor.child!!.findPreference<Preference>("profileCore")!!.isVisible)
        val group = EditorCache.editingGroup
        for ((key, value) in mapOf("name" to "explicit-compat", "serverAddress" to "node.example.org",
            "serverPort" to "443", "password" to "fake-password", "certificateFingerprint" to "ab".repeat(32))) {
            editor.child!!.findPreference<EditTextPreference>(key)!!.text = value
        }
        runOnDefaultDispatcher { editor.saveAndExit() }
        UiSettle.settle()
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(group) }.single { it.requireBean().name == "explicit-compat" }
        assertTrue((saved.requireBean() as AnyTLSBean).compatibilityTest)
        assertEquals(ProxyEntity.CORE_AUTO, saved.core)
    }

    @Test
    fun ordinaryNewEntryRemainsOrdinary() {
        UiFixtures.install()
        ui.launchEditor(AnyTLSSettingsActivity::class.java, 0)
        assertFalse(EditorCache.profileCacheStore.getBoolean("compatibilityTest") == true)
    }

    @Test
    fun intentCannotConvertExistingOrdinaryNode() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"
        }) }
        val intent = ui.editorIntent(AnyTLSSettingsActivity::class.java, entity.id)
            .putExtra(AnyTLSSettingsActivity.EXTRA_COMPATIBILITY_TEST, true)
        val editor = ui.launch(AnyTLSSettingsActivity::class.java, intent).get()
        assertFalse(EditorCache.profileCacheStore.getBoolean("compatibilityTest") == true)
        assertTrue(editor.child!!.findPreference<Preference>("profileCore")!!.isVisible)
    }

    @Test
    fun existingTestNodeKeepsMarkerWithoutIntentExtra() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"; compatibilityTest = true
        }) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, entity.id).get()
        assertTrue(EditorCache.profileCacheStore.getBoolean("compatibilityTest") == true)
        assertFalse(editor.child!!.findPreference<Preference>("profileCore")!!.isVisible)
    }
}
