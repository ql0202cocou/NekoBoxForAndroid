package moe.matsuri.nb4a.proxy.config

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.ui.EditConfigPreference
import io.nekohasekai.sagernet.database.EditorCache

class ConfigSettingActivity : ProfileSettingsActivity<ConfigBean>() {

    private val isOutboundOnlyKey = "isOutboundOnly"

    override fun createEntity() = ConfigBean()

    override fun ConfigBean.init() {
        // CustomBean to input
        EditorCache.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        EditorCache.profileName = name
        EditorCache.serverConfig = config
    }

    override fun ConfigBean.serialize() {
        // CustomBean from input
        type = if (EditorCache.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        name = EditorCache.profileName
        config = EditorCache.serverConfig
    }

    private lateinit var editConfigPreference: EditConfigPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.config_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

}