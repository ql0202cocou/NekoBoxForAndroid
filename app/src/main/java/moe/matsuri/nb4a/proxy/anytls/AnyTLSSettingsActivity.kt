package moe.matsuri.nb4a.proxy.anytls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.bindPasswordPreference
import io.nekohasekai.sagernet.ui.profile.bindPortPreference
import io.nekohasekai.sagernet.ui.profile.bindValidatedPreference
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    override fun validateEditor(): String? {
        val value = certificateFingerprint.readStringFromCache()
        return if (value.isBlank() || isCertificateFingerprint(value)) null
        else getString(R.string.certificate_fingerprint_error)
    }

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certificateFingerprint =
        pbm.add(PreferenceBinding(Type.Text, "certificateFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)

    }

    override fun AnyTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.anytls_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.bindPortPreference()
        findPreference<EditTextPreference>("password")!!.bindPasswordPreference()
        // mihomo's only consumer of this value hex-decodes it into 32 bytes
        findPreference<EditTextPreference>("certificateFingerprint")!!
            .bindValidatedPreference(R.string.certificate_fingerprint_error) {
                it.isBlank() || isCertificateFingerprint(it)
            }
    }
}
