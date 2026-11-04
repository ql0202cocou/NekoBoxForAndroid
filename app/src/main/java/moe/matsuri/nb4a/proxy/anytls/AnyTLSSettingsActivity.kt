package moe.matsuri.nb4a.proxy.anytls

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.bindCertificateFingerprintPreference
import io.nekohasekai.sagernet.ui.profile.bindPasswordPreference
import io.nekohasekai.sagernet.ui.profile.certificateFingerprintError
import kotlinx.coroutines.Dispatchers
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    companion object {
        const val EXTRA_COMPATIBILITY_TEST = "anytlsCompatibilityTest"
    }

    // 基类只在新建分支（editingId 为 0）用 createEntity() 填缓存；编辑已有节点时缓存来自库里的节点，保存与选核判定
    // 再由 serialize() 从缓存覆盖这个字段，所以带上这个 extra 也改不了已有节点
    // （AnyTLSCompatibilityEditorTest.intentCannotConvertExistingOrdinaryNode）
    override fun createEntity() = AnyTLSBean().applyDefaultValues().apply {
        compatibilityTest = intent.getBooleanExtra(EXTRA_COMPATIBILITY_TEST, false)
    }

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
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certificateFingerprint =
        pbm.add(PreferenceBinding(Type.Text, "certificateFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))
    private val compatibilityTest = pbm.add(PreferenceBinding(Type.Bool, "compatibilityTest"))
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
        // 进程死亡后恢复的 Fragment 会在缓存重新填好之前走到这里，所以等初始化完成再读标记；正常路径上缓存已就绪，
        // Main.immediate 让切换在同一帧里完成
        launchInSession(lifecycleScope, Dispatchers.Main.immediate) {
            checked { awaitEditorReady() }
            val testing = compatibilityTest.readBoolFromCache()
            findPreference<Preference>("compatibilityTestInfo")?.isVisible = testing
            findPreference<Preference>(Key.PROFILE_CORE)?.isVisible = !testing
        }

        findPreference<EditTextPreference>("password")!!.bindPasswordPreference()
        findPreference<EditTextPreference>("certificateFingerprint")!!
            .bindCertificateFingerprintPreference()
    }
}
