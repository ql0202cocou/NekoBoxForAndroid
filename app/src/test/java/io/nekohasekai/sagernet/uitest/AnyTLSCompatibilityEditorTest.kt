package io.nekohasekai.sagernet.uitest

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCompatibilityEditorTest : UiTestBase() {
    @Test
    fun ordinaryEntrySavesSingBoxWithCertificatePin() {
        UiFixtures.install()
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, 0).get()
        val group = EditorCache.editingGroup
        assertTrue(editor.child!!.findPreference<Preference>("profileCore")!!.isVisible)
        for ((key, value) in mapOf("name" to "formal-anytls", "serverAddress" to "node.example.org",
            "serverPort" to "443", "password" to "fake-password", "certificateFingerprint" to "ab".repeat(32))) {
            editor.child!!.findPreference<EditTextPreference>(key)!!.text = value
        }
        editor.child!!.findPreference<ListPreference>("profileCore")!!.value = ProxyEntity.CORE_SING_BOX.toString()
        runOnDefaultDispatcher { editor.saveAndExit() }
        UiSettle.settle()
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(group) }.single { it.requireBean().name == "formal-anytls" }
        assertEquals(ProxyEntity.CORE_SING_BOX, saved.core)
        assertEquals("ab".repeat(32), saved.anyTLSBean!!.certificateFingerprint)
    }

    @Test
    fun historicalTestNodeCanChooseMihomo() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"; compatibilityTest = true
        }) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, entity.id).get()
        assertTrue(editor.child!!.findPreference<Preference>("profileCore")!!.isVisible)
        editor.child!!.findPreference<ListPreference>("profileCore")!!.value = ProxyEntity.CORE_MIHOMO.toString()
        runOnDefaultDispatcher { editor.saveAndExit() }
        UiSettle.settle()
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(entity.groupId) }.single { it.id == entity.id }
        assertEquals(ProxyEntity.CORE_MIHOMO, saved.core)
    }
    @Test
    fun trustPolicySurvivesEditingAndUsesUniversalLink() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"; certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
        }) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, entity.id).get()
        assertEquals("1", editor.child!!.findPreference<ListPreference>("certificatePolicy")!!.value)
        editor.child!!.findPreference<EditTextPreference>("name")!!.text = "renamed-trust"
        runOnDefaultDispatcher { editor.saveAndExit() }
        UiSettle.settle()
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(entity.groupId) }.single { it.id == entity.id }
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, saved.anyTLSBean!!.certificatePolicy)
        assertTrue(saved.anyTLSBean!!.toUri().startsWith("sn://"))
    }

}
