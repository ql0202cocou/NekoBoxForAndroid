package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteriaPorts
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.isHysteria1PluginHopInterval
import io.nekohasekai.sagernet.fmt.hysteria.isHysteria1PluginWindow
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import io.nekohasekai.sagernet.database.EditorCache

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun validateEditor(): String? {
        if (runCatching { parseHysteriaPorts(EditorCache.serverPorts) }.isFailure) {
            return getString(R.string.hysteria_ports_error)
        }
        certificateFingerprintError(EditorCache.serverCertificateFingerprint)?.let { return it }
        // faketcp / wechat-video run on the hysteria 1 plugin, which enforces its own
        // minimums; the sing-box path accepts any non-negative value
        if (EditorCache.protocolVersion != 1 || EditorCache.serverProtocolInt == HysteriaBean.PROTOCOL_UDP) {
            return null
        }
        return when {
            !isHysteria1PluginWindow(EditorCache.serverStreamReceiveWindow) ||
                !isHysteria1PluginWindow(EditorCache.serverConnectionReceiveWindow) ->
                getString(R.string.hysteria_receive_window_error)
            !isHysteria1PluginHopInterval(EditorCache.serverHopInterval) ->
                getString(R.string.hysteria_hop_interval_error)
            else -> null
        }
    }

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        EditorCache.profileName = name
        EditorCache.protocolVersion = protocolVersion
        EditorCache.serverAddress = serverAddress
        EditorCache.serverPorts = serverPorts
        EditorCache.serverObfs = obfuscation
        EditorCache.serverAuthType = authPayloadType
        EditorCache.serverProtocolInt = protocol
        EditorCache.serverPassword = authPayload
        EditorCache.serverSNI = sni
        EditorCache.serverALPN = alpn
        EditorCache.serverCertificates = caText
        EditorCache.serverCertificateFingerprint = certificateFingerprint
        EditorCache.serverAllowInsecure = allowInsecure
        EditorCache.serverUploadSpeed = uploadMbps
        EditorCache.serverDownloadSpeed = downloadMbps
        EditorCache.serverStreamReceiveWindow = streamReceiveWindow
        EditorCache.serverConnectionReceiveWindow = connectionReceiveWindow
        EditorCache.serverDisableMtuDiscovery = disableMtuDiscovery
        EditorCache.serverHopInterval = hopInterval
    }

    override fun HysteriaBean.serialize() {
        name = EditorCache.profileName
        protocolVersion = EditorCache.protocolVersion
        serverAddress = EditorCache.serverAddress
        serverPorts = EditorCache.serverPorts
        obfuscation = EditorCache.serverObfs
        authPayloadType = EditorCache.serverAuthType
        authPayload = EditorCache.serverPassword
        protocol = EditorCache.serverProtocolInt
        sni = EditorCache.serverSNI
        alpn = EditorCache.serverALPN
        caText = EditorCache.serverCertificates
        certificateFingerprint = EditorCache.serverCertificateFingerprint
        allowInsecure = EditorCache.serverAllowInsecure
        uploadMbps = EditorCache.serverUploadSpeed
        downloadMbps = EditorCache.serverDownloadSpeed
        streamReceiveWindow = EditorCache.serverStreamReceiveWindow
        connectionReceiveWindow = EditorCache.serverConnectionReceiveWindow
        disableMtuDiscovery = EditorCache.serverDisableMtuDiscovery
        hopInterval = EditorCache.serverHopInterval
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria_preferences)
        findPreference<EditTextPreference>("serverPorts")!!
            .bindValidatedPreference(R.string.hysteria_ports_error) {
                runCatching { parseHysteriaPorts(it) }.isSuccess
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
                // hy2 没有 protocol 选项：复位残留的 faketcp / wechat 值，否则
                // serialize() 会写回非法的 hy2+faketcp bean（canUseSingBox() 为
                // false，转去 hysteria1 插件，报 "error version: 2"）。设置
                // preference 同时写进 EditorCache
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
        updateVersion(EditorCache.protocolVersion)

        // Empty windows are stored as zero and omitted by the Hysteria 1 builder.
        findPreference<EditTextPreference>(Key.SERVER_STREAM_RECEIVE_WINDOW)!!
            .bindIntegerPreference(allowEmpty = true)
        findPreference<EditTextPreference>(Key.SERVER_CONNECTION_RECEIVE_WINDOW)!!
            .bindIntegerPreference(allowEmpty = true)

        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.bindPasswordPreference()
        findPreference<EditTextPreference>(Key.SERVER_OBFS)!!.bindPasswordPreference()

        findPreference<EditTextPreference>(Key.SERVER_HOP_INTERVAL)!!.bindIntegerPreference()

        findPreference<EditTextPreference>(Key.SERVER_CERTIFICATE_FINGERPRINT)!!
            .bindCertificateFingerprintPreference()
    }

}
