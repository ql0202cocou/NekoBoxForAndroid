package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import io.nekohasekai.sagernet.database.EditorCache

class SocksSettingsActivity : ProfileSettingsActivity<SOCKSBean>() {
    override fun createEntity() = SOCKSBean()

    override fun SOCKSBean.init() {
        EditorCache.profileName = name
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPort = serverPort

        EditorCache.serverProtocolInt = protocol
        EditorCache.serverUsername = username
        EditorCache.serverPassword = password

        EditorCache.profileCacheStore.putBoolean("sUoT", sUoT)
    }

    override fun SOCKSBean.serialize() {
        name = EditorCache.profileName
        serverAddress = EditorCache.serverAddress
        serverPort = EditorCache.serverPort

        protocol = EditorCache.serverProtocolInt
        username = EditorCache.serverUsername
        password = EditorCache.serverPassword

        sUoT = EditorCache.profileCacheStore.getBoolean("sUoT")
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.socks_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.bindPortPreference()
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        val protocol = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!

        fun updateProtocol(version: Int) {
            password.isVisible = version == SOCKSBean.PROTOCOL_SOCKS5
        }

        updateProtocol(EditorCache.serverProtocolInt)
        protocol.setOnPreferenceChangeListener { _, newValue ->
            updateProtocol((newValue as String).toInt())
            true
        }
    }
}
