package moe.matsuri.nb4a.proxy.anytls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.bindCertificateFingerprintPreference
import io.nekohasekai.sagernet.ui.profile.bindPasswordPreference
import io.nekohasekai.sagernet.ui.profile.certificateFingerprintError
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    override fun validateEditor(): String? {
        return certificateFingerprintError(certificateFingerprint.readStringFromCache())
    }

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificatePolicy = pbm.add(PreferenceBinding(Type.TextToInt, "certificatePolicy"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certificateFingerprint =
        pbm.add(PreferenceBinding(Type.Text, "certificateFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))
    private val localSocksOverride = pbm.add(PreferenceBinding(Type.Bool, "localSocksOutboundJson"))

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)

    }

    override fun AnyTLSBean.serialize() {
        val priorPolicy = this.certificatePolicy
        pbm.fromCacheAll(this)
        if (this.certificatePolicy != priorPolicy) certificatePolicySource = AnyTLSBean.POLICY_LOCAL
        localSocksOutboundJson = !customOutboundJson.isNullOrBlank() && localSocksOutboundJson
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.anytls_preferences)
        findPreference<EditTextPreference>("password")!!.bindPasswordPreference()
        findPreference<EditTextPreference>("certificateFingerprint")!!
            .bindCertificateFingerprintPreference()
        findPreference<Preference>("confirmAnyTlsOverride")!!.apply {
            isVisible = localSocksOverride.readBoolFromCache()
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(this@AnyTLSSettingsActivity)
                    .setTitle(R.string.anytls_override_rewritten)
                    .setMessage(R.string.anytls_override_rewritten_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        launchInSession {
                            // 确认只属于当前编辑会话；旋转保留缓存，放弃编辑不改数据库。
                            onMain {
                                EditorCache.profileCacheStore.putBoolean(localSocksOverride.cacheName, false)
                                EditorCache.dirty = true
                                isVisible = false
                            }
                        }
                    }.show()
                true
            }
        }
    }
}
