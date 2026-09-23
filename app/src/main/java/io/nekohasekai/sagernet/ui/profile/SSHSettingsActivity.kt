package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import io.nekohasekai.sagernet.database.EditorCache

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        EditorCache.profileName = name
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPort = serverPort
        EditorCache.serverUsername = username
        EditorCache.serverAuthType = authType
        EditorCache.serverPassword = password
        EditorCache.serverPrivateKey = privateKey
        EditorCache.serverPassword1 = privateKeyPassphrase
        EditorCache.serverCertificates = publicKey
    }

    override fun SSHBean.serialize() {
        name = EditorCache.profileName
        serverAddress = EditorCache.serverAddress
        serverPort = EditorCache.serverPort
        username = EditorCache.serverUsername
        authType = EditorCache.serverAuthType
        when (authType) {
            SSHBean.AUTH_TYPE_NONE -> {
            }
            SSHBean.AUTH_TYPE_PASSWORD -> {
                password = EditorCache.serverPassword
            }
            SSHBean.AUTH_TYPE_PRIVATE_KEY -> {
                privateKey = EditorCache.serverPrivateKey
                privateKeyPassphrase = EditorCache.serverPassword1
            }
        }
        publicKey = EditorCache.serverCertificates
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.ssh_preferences)
        val password = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        val privateKey = findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!
        val privateKeyPassphrase = findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.bindPasswordPreference()
        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        fun updateAuthType(type: Int = EditorCache.serverAuthType) {
            password.isVisible = type == SSHBean.AUTH_TYPE_PASSWORD
            privateKey.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
            privateKeyPassphrase.isVisible = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
        }
        updateAuthType()
        authType.setOnPreferenceChangeListener { _, newValue ->
            updateAuthType((newValue as String).toInt())
            true
        }
    }

}