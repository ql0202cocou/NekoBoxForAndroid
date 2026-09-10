package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaPorts
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.isHysteria1PluginHopInterval
import io.nekohasekai.sagernet.fmt.hysteria.isHysteria1PluginWindow
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun validateEditor(): String? {
        if (runCatching { parseHysteriaPorts(DataStore.serverPorts) }.isFailure) {
            return getString(R.string.hysteria_ports_error)
        }
        val certPin = DataStore.serverCertificateFingerprint
        if (certPin.isNotBlank() && !isCertificateFingerprint(certPin)) {
            return getString(R.string.certificate_fingerprint_error)
        }
        // faketcp / wechat-video run on the hysteria 1 plugin, which enforces its own
        // minimums; the sing-box path accepts any non-negative value
        if (DataStore.protocolVersion != 1 || DataStore.serverProtocolInt == HysteriaBean.PROTOCOL_UDP) {
            return null
        }
        return when {
            !isHysteria1PluginWindow(DataStore.serverStreamReceiveWindow) ||
                !isHysteria1PluginWindow(DataStore.serverConnectionReceiveWindow) ->
                getString(R.string.hysteria_receive_window_error)
            !isHysteria1PluginHopInterval(DataStore.serverHopInterval) ->
                getString(R.string.hysteria_hop_interval_error)
            else -> null
        }
    }

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.protocolVersion = protocolVersion
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverAuthType = authPayloadType
        DataStore.serverProtocolInt = protocol
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverALPN = alpn
        DataStore.serverCertificates = caText
        DataStore.serverCertificateFingerprint = certificateFingerprint
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverStreamReceiveWindow = streamReceiveWindow
        DataStore.serverConnectionReceiveWindow = connectionReceiveWindow
        DataStore.serverDisableMtuDiscovery = disableMtuDiscovery
        DataStore.serverHopInterval = hopInterval
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        protocolVersion = DataStore.protocolVersion
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayloadType = DataStore.serverAuthType
        authPayload = DataStore.serverPassword
        protocol = DataStore.serverProtocolInt
        sni = DataStore.serverSNI
        alpn = DataStore.serverALPN
        caText = DataStore.serverCertificates
        certificateFingerprint = DataStore.serverCertificateFingerprint
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        streamReceiveWindow = DataStore.serverStreamReceiveWindow
        connectionReceiveWindow = DataStore.serverConnectionReceiveWindow
        disableMtuDiscovery = DataStore.serverDisableMtuDiscovery
        hopInterval = DataStore.serverHopInterval
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria_preferences)
        findPreference<EditTextPreference>("serverPorts")!!.setOnPreferenceChangeListener { _, value ->
            val valid = value is String && runCatching { parseHysteriaPorts(value) }.isSuccess
            if (!valid) Toast.makeText(requireContext(), R.string.hysteria_ports_error, Toast.LENGTH_LONG).show()
            valid
        }

        val authType = findPreference<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)!!
        val authPayload = findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!
        authPayload.isVisible = authType.value != "${HysteriaBean.TYPE_NONE}"
        authType.setOnPreferenceChangeListener { _, newValue ->
            authPayload.isVisible = newValue != "${HysteriaBean.TYPE_NONE}"
            true
        }

        val protocol = findPreference<SimpleMenuPreference>(Key.SERVER_PROTOCOL)!!
        val alpn = findPreference<EditTextPreference>(Key.SERVER_ALPN)!!

        fun updateVersion(v: Int) {
            // Hysteria 1 requires bandwidth; Hysteria 2 accepts zero for auto.
            val minSpeed = if (v == 2) 0 else 1
            findPreference<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)!!.bindIntegerPreference(minSpeed)
            findPreference<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)!!.bindIntegerPreference(minSpeed)
            if (v == 2) {
                // hy2 has no protocol option; reset a stale faketcp/wechat
                // value so serialize() cannot write back an illegal
                // hy2+faketcp bean (canUseSingBox() = false would then route
                // to the hysteria1 plugin, which fails with "error version: 2").
                // Setting the preference persists to DataStore as well.
                protocol.value = "${HysteriaBean.PROTOCOL_UDP}"
                authPayload.isVisible = true
                //
                authType.isVisible = false
                protocol.isVisible = false
                alpn.isVisible = false
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    false
                findPreference<SwitchPreference>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    false
                //
                authPayload.title = resources.getString(R.string.password)
            } else {
                authType.isVisible = true
                // keep honoring the auth-type toggle above: TYPE_NONE hides
                // the payload field
                authPayload.isVisible = authType.value != "${HysteriaBean.TYPE_NONE}"
                protocol.isVisible = true
                alpn.isVisible = true
                //
                findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!.isVisible =
                    true
                findPreference<SwitchPreference>(Key.SERVER_DISABLE_MTU_DISCOVERY)!!.isVisible =
                    true
                //
                authPayload.title = resources.getString(R.string.hysteria_auth_payload)
            }
        }
        findPreference<SimpleMenuPreference>(Key.PROTOCOL_VERSION)!!.setOnPreferenceChangeListener { _, newValue ->
            updateVersion(newValue.toString().toIntOrNull() ?: 1)
            true
        }
        updateVersion(DataStore.protocolVersion)

        // Empty windows are stored as zero and omitted by the Hysteria 1 builder.
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!
            .bindIntegerPreference(allowEmpty = true)
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!
            .bindIntegerPreference(allowEmpty = true)

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.bindPasswordPreference()

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.bindIntegerPreference()

        // mihomo's only consumer of this value hex-decodes it into 32 bytes
        findPreference<EditTextPreference>(Key.SERVER_CERTIFICATE_FINGERPRINT)!!
            .bindValidatedPreference(R.string.certificate_fingerprint_error) {
                it.isBlank() || isCertificateFingerprint(it)
            }
    }

}
