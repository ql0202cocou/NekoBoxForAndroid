package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.genReservedList
import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardKey
import io.nekohasekai.sagernet.fmt.wireguard.isWireGuardLocalAddressList
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun validateEditor(): String? {
        if (!isWireGuardLocalAddressList(localAddress.readStringFromCache())) {
            return getString(R.string.wireguard_address_error)
        }
        for ((binding, optional) in listOf(privateKey to false, peerPublicKey to false, peerPreSharedKey to true)) {
            if (!isWireGuardKey(binding.readStringFromCache(), optional)) {
                return "${binding.preference.title}: ${getString(R.string.wireguard_key_error)}"
            }
        }
        val value = reserved.readStringFromCache()
        if (!(value.isBlank() || genReservedList(value) != null)) {
            return getString(R.string.wireguard_reserved_error)
        }
        return if (isWireGuardLocalAddressList(peerAllowedIps.readStringFromCache())) null
        else getString(R.string.wireguard_address_error)
    }

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val peerKeepalive = pbm.add(PreferenceBinding(Type.TextToInt, "peerKeepalive"))
    private val peerAllowedIps = pbm.add(PreferenceBinding(Type.Text, "peerAllowedIps"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (privateKey.preference as EditTextPreference).bindPasswordPreference()
        (mtu.preference as EditTextPreference).bindIntegerPreference()
        (localAddress.preference as EditTextPreference)
            .bindValidatedPreference(R.string.wireguard_address_error, ::isWireGuardLocalAddressList)
        (peerPreSharedKey.preference as EditTextPreference).bindPasswordPreference()
        for ((binding, optional) in listOf(privateKey to false, peerPublicKey to false, peerPreSharedKey to true)) {
            (binding.preference as EditTextPreference)
                .bindValidatedPreference(R.string.wireguard_key_error) { isWireGuardKey(it, optional) }
        }
        (reserved.preference as EditTextPreference)
            .bindValidatedPreference(R.string.wireguard_reserved_error) {
                it.isBlank() || genReservedList(it) != null
            }
        (peerKeepalive.preference as EditTextPreference).bindIntegerPreference()
        (peerAllowedIps.preference as EditTextPreference)
            .bindValidatedPreference(R.string.wireguard_address_error, ::isWireGuardLocalAddressList)
    }

}