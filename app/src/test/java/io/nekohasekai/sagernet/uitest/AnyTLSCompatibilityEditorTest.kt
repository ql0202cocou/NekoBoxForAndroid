package io.nekohasekai.sagernet.uitest

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.TypeMap
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.toUniversalLink
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test

class AnyTLSCompatibilityEditorTest : UiTestBase() {
    @Test
    fun ordinaryEntrySavesSingBoxWithCertificatePin() {
        UiFixtures.install()
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, 0).get()
        val group = EditorCache.editingGroup
        assertNull(editor.child!!.findPreference<Preference>("profileCore"))
        for ((key, value) in mapOf("name" to "formal-anytls", "serverAddress" to "node.example.org",
            "serverPort" to "443", "password" to "fake-password", "certificateFingerprint" to "ab".repeat(32))) {
            editor.child!!.findPreference<EditTextPreference>(key)!!.text = value
        }
        saveEditor(editor)
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(group) }.single { it.requireBean().name == "formal-anytls" }
        assertEquals(ProxyEntity.CORE_AUTO, saved.core)
        assertEquals("ab".repeat(32), saved.anyTLSBean!!.certificateFingerprint)
    }

    @Test
    fun historicalMihomoChoiceSavesAsAuto() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"; compatibilityTest = true
        }, core = ProxyEntity.CORE_MIHOMO) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, entity.id).get()
        assertNull(editor.child!!.findPreference<Preference>("profileCore"))
        saveEditor(editor)
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(entity.groupId) }.single { it.id == entity.id }
        assertEquals(ProxyEntity.CORE_AUTO, saved.core)
    }
    @Test
    fun trustPolicySurvivesEditingAndUsesUniversalLink() {
        val fixtures = UiFixtures.install()
        val entity = ui.bg { ProfileRepository.createProfile(fixtures.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "node.example.org"; certificatePolicy = AnyTLSBean.CERTIFICATE_POLICY_TRUST
            certificatePolicySource = AnyTLSBean.POLICY_PUBLISHED
            customOutboundJson = """{"min_idle_session":2}"""
            utlsFingerprint = "chrome_psk"
        }) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, entity.id).get()
        assertEquals("1", editor.child!!.findPreference<ListPreference>("certificatePolicy")!!.value)
        editor.child!!.findPreference<EditTextPreference>("name")!!.text = "renamed-trust"
        saveEditor(editor)
        val saved = ui.bg { ProfileRepository.getProfilesByGroup(entity.groupId) }.single { it.id == entity.id }
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, saved.anyTLSBean!!.certificatePolicy)
        val bean = saved.anyTLSBean!!
        assertEquals("renamed-trust", bean.name)
        assertEquals("""{"min_idle_session":2}""", bean.customOutboundJson)
        assertEquals(AnyTLSBean.POLICY_PUBLISHED, bean.certificatePolicySource)
        bean.certificatePolicySource = AnyTLSBean.POLICY_LOCAL
        val link = bean.toUri()
        assertEquals(link, bean.toUniversalLink())
        val type = link.removePrefix("sn://").substringBefore('?')
        assertNull(TypeMap[type])
        assertEquals("anytls-v2", type)
        val imported = parseUniversal(link) { false } as AnyTLSBean
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, imported.certificatePolicy)
        assertEquals(AnyTLSBean.POLICY_PUBLISHED, imported.certificatePolicySource)
        val old = KryoSamples.samples.single { it.id == "AnyTLSBean/compat-v3/ordinary" }
        val legacyLink = "sn://anytls?" + Util.b64EncodeUrlSafe(Util.zlibCompress(old.bytes, 9))
        val legacy = parseUniversal(legacyLink) { false } as AnyTLSBean
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_PIN, legacy.certificatePolicy)
        assertEquals(AnyTLSBean.POLICY_MIGRATED, legacy.certificatePolicySource)
        val scoped = AnyTLSBean().apply {
            initializeDefaultValues()
            customOutboundJson = """{"network":"tcp"}"""
            localSocksOutboundJson = true
        }
        val localHop = parseUniversal(scoped.toUri()) { false } as AnyTLSBean
        assertTrue(localHop.localSocksOutboundJson)
        assertEquals(scoped.customOutboundJson, localHop.customOutboundJson)
    }

    @Test
    fun changingPolicyInEditorCreatesLocalOverride() {
        val fixture = UiFixtures.install()
        val profile = ui.bg { ProfileRepository.createProfile(fixture.profiles.first().groupId, AnyTLSBean().apply {
            initializeDefaultValues()
            certificatePolicySource = AnyTLSBean.POLICY_PUBLISHED
        }) }
        val editor = ui.launchEditor(AnyTLSSettingsActivity::class.java, profile.id).get()
        editor.child!!.findPreference<ListPreference>("certificatePolicy")!!.value = "1"
        saveEditor(editor)
        val bean = ui.bg { ProfileRepository.getProfile(profile.id) }!!.anyTLSBean!!
        assertEquals(AnyTLSBean.CERTIFICATE_POLICY_TRUST, bean.certificatePolicy)
        assertEquals(AnyTLSBean.POLICY_LOCAL, bean.certificatePolicySource)
    }
}
