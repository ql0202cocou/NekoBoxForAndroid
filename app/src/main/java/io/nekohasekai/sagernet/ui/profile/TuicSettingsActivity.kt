package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.database.EditorCache

class TuicSettingsActivity : ProfileSettingsActivity<TuicBean>() {

    override fun createEntity() = TuicBean().applyDefaultValues()

    override fun validateEditor(): String? {
        return certificateFingerprintError(EditorCache.serverCertificateFingerprint)
    }

    override fun TuicBean.init() {
        EditorCache.profileName = name
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPort = serverPort
        EditorCache.serverUsername = uuid
        EditorCache.serverPassword = token
        EditorCache.serverALPN = alpn
        EditorCache.serverCertificates = caText
        EditorCache.serverCertificateFingerprint = certificateFingerprint
        EditorCache.serverUDPRelayMode = udpRelayMode
        EditorCache.serverCongestionController = congestionController
        EditorCache.serverDisableSNI = disableSNI
        EditorCache.serverSNI = sni
        EditorCache.serverReduceRTT = reduceRTT
        EditorCache.serverAllowInsecure = allowInsecure
    }

    override fun TuicBean.serialize() {
        name = EditorCache.profileName
        serverAddress = EditorCache.serverAddress
        serverPort = EditorCache.serverPort
        uuid = EditorCache.serverUsername
        token = EditorCache.serverPassword
        alpn = EditorCache.serverALPN
        caText = EditorCache.serverCertificates
        certificateFingerprint = EditorCache.serverCertificateFingerprint
        udpRelayMode = EditorCache.serverUDPRelayMode
        congestionController = EditorCache.serverCongestionController
        disableSNI = EditorCache.serverDisableSNI
        sni = EditorCache.serverSNI
        reduceRTT = EditorCache.serverReduceRTT
        allowInsecure = EditorCache.serverAllowInsecure
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.tuic_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.bindPortPreference()

        val disableSNI = findPreference<SwitchPreference>(Key.SERVER_DISABLE_SNI)!!
        val sni = findPreference<EditTextPreference>(Key.SERVER_SNI)!!
        sni.isEnabled = !disableSNI.isChecked
        disableSNI.setOnPreferenceChangeListener { _, newValue ->
            sni.isEnabled = !(newValue as Boolean)
            true
        }

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()

        findPreference<EditTextPreference>(Key.SERVER_CERTIFICATE_FINGERPRINT)!!
            .bindCertificateFingerprintPreference()
    }

}