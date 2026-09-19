package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import io.nekohasekai.sagernet.database.EditorCache

class TrojanGoSettingsActivity : ProfileSettingsActivity<TrojanGoBean>() {

    override fun createEntity() = TrojanGoBean()

    override fun TrojanGoBean.init() {
        EditorCache.profileName = name
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPort = serverPort
        EditorCache.serverPassword = password
        EditorCache.serverSNI = sni
        EditorCache.serverAllowInsecure = allowInsecure
        EditorCache.serverNetwork = type
        EditorCache.serverHost = host
        EditorCache.serverPath = path
        if (encryption.startsWith("ss;")) {
            EditorCache.serverEncryption = "ss"
            EditorCache.serverMethod = encryption.substringAfter(";").substringBefore(":")
            EditorCache.serverPassword1 = encryption.substringAfter(":", "")
        } else {
            EditorCache.serverEncryption = encryption
        }
    }

    override fun TrojanGoBean.serialize() {
        name = EditorCache.profileName
        serverAddress = EditorCache.serverAddress
        serverPort = EditorCache.serverPort
        password = EditorCache.serverPassword
        sni = EditorCache.serverSNI
        allowInsecure = EditorCache.serverAllowInsecure
        type = EditorCache.serverNetwork
        host = EditorCache.serverHost
        path = EditorCache.serverPath
        encryption = when (val security = EditorCache.serverEncryption) {
            "ss" -> {
                "ss;" + EditorCache.serverMethod + ":" + EditorCache.serverPassword1
            }
            else -> {
                security
            }
        }
    }

    lateinit var network: SimpleMenuPreference
    lateinit var encryprtion: SimpleMenuPreference
    lateinit var wsCategory: PreferenceCategory
    lateinit var ssCategory: PreferenceCategory
    lateinit var method: SimpleMenuPreference

    val trojanGoMethods = app.resources.getStringArray(R.array.trojan_go_methods)
    val trojanGoNetworks = app.resources.getStringArray(R.array.trojan_go_networks_value)

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.trojan_go_preferences)
        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.bindPortPreference()
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD1)!!.bindPasswordPreference()
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!
        ssCategory = findPreference(Key.SERVER_SS_CATEGORY)!!
        method = findPreference(Key.SERVER_METHOD)!!

        network = findPreference(Key.SERVER_NETWORK)!!

        if (network.value !in trojanGoNetworks) {
            network.value = trojanGoNetworks[0]
        }

        updateNetwork(network.value)
        network.setOnPreferenceChangeListener { _, newValue ->
            updateNetwork(newValue as String)
            true
        }
        encryprtion = findPreference(Key.SERVER_ENCRYPTION)!!
        updateEncryption(encryprtion.value)
        encryprtion.setOnPreferenceChangeListener { _, newValue ->
            updateEncryption(newValue as String)
            true
        }
    }

    fun updateNetwork(newNet: String) {
        when (newNet) {
            "ws" -> {
                wsCategory.isVisible = true
            }
            else -> {
                wsCategory.isVisible = false
            }
        }
    }

    fun updateEncryption(encryption: String) {
        when (encryption) {
            "ss" -> {
                ssCategory.isVisible = true

                if (method.value !in trojanGoMethods) {
                    method.value = trojanGoMethods[0]
                }
            }
            else -> {
                ssCategory.isVisible = false
            }
        }
    }

}