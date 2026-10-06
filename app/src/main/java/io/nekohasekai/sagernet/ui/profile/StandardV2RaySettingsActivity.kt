package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isRealityMldsa65Verify
import io.nekohasekai.sagernet.fmt.v2ray.isRealityPublicKey
import io.nekohasekai.sagernet.fmt.v2ray.isRealityShortId
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val uuid = pbm.add(PreferenceBinding(Type.Text, "uuid"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val alterId = pbm.add(PreferenceBinding(Type.TextToInt, "alterId"))
    private val encryption = pbm.add(PreferenceBinding(Type.Text, "encryption"))
    private val type = pbm.add(PreferenceBinding(Type.Text, "type"))
    private val host = pbm.add(PreferenceBinding(Type.Text, "host"))
    private val path = pbm.add(PreferenceBinding(Type.Text, "path"))
    private val packetEncoding = pbm.add(PreferenceBinding(Type.TextToInt, "packetEncoding"))
    private val wsMaxEarlyData = pbm.add(PreferenceBinding(Type.TextToInt, "wsMaxEarlyData"))
    private val earlyDataHeaderName = pbm.add(PreferenceBinding(Type.Text, "earlyDataHeaderName"))
    private val security = pbm.add(PreferenceBinding(Type.Text, "security"))
    private val sni = pbm.add(PreferenceBinding(Type.Text, "sni"))
    private val alpn = pbm.add(PreferenceBinding(Type.Text, "alpn"))
    private val certificates = pbm.add(PreferenceBinding(Type.Text, "certificates"))
    private val certificateFingerprint =
        pbm.add(PreferenceBinding(Type.Text, "certificateFingerprint"))
    private val allowInsecure = pbm.add(PreferenceBinding(Type.Bool, "allowInsecure"))
    private val utlsFingerprint = pbm.add(PreferenceBinding(Type.Text, "utlsFingerprint"))
    private val realityPubKey = pbm.add(PreferenceBinding(Type.Text, "realityPubKey"))
    private val realityShortId = pbm.add(PreferenceBinding(Type.Text, "realityShortId"))
    private val realityMldsa65Verify =
        pbm.add(PreferenceBinding(Type.Text, "realityMldsa65Verify"))

    private val enableECH = pbm.add(PreferenceBinding(Type.Bool, "enableECH"))
    private val echConfig = pbm.add(PreferenceBinding(Type.Text, "echConfig"))

    private val enableMux = pbm.add(PreferenceBinding(Type.Bool, "enableMux"))
    private val muxPadding = pbm.add(PreferenceBinding(Type.Bool, "muxPadding"))
    private val muxType = pbm.add(PreferenceBinding(Type.TextToInt, "muxType"))
    private val muxConcurrency = pbm.add(PreferenceBinding(Type.TextToInt, "muxConcurrency"))

    override fun validateEditor(): String? {
        // REALITY and pinning fields stay hidden and unused while TLS is off
        if (security.readStringFromCache() != "tls") return null
        certificateFingerprintError(certificateFingerprint.readStringFromCache())?.let { return it }
        // an empty REALITY key means no REALITY
        val publicKey = realityPubKey.readStringFromCache()
        if (publicKey.isEmpty()) return null
        return when {
            !isRealityPublicKey(publicKey) -> getString(R.string.reality_public_key_error)
            !isRealityShortId(realityShortId.readStringFromCache()) ->
                getString(R.string.reality_short_id_error)
            !isRealityMldsa65Verify(realityMldsa65Verify.readStringFromCache()) ->
                getString(R.string.reality_mldsa65_error)
            // sing-box rejects the pair ("Reality is conflict with ECH"), Xray drops ECH
            enableECH.readBoolFromCache() -> getString(R.string.reality_ech_conflict_error)
            else -> null
        }
    }

    override fun StandardV2RayBean.init() {
        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
    }

    private lateinit var securityCategory: PreferenceCategory
    private lateinit var tlsCamouflageCategory: PreferenceCategory
    private lateinit var wsCategory: PreferenceCategory
    private lateinit var echCategory: PreferenceCategory

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)
        pbm.setPreferenceFragment(this)
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        tlsCamouflageCategory = findPreference(Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)!!
        echCategory = findPreference(Key.SERVER_ECH_CATEORY)!!
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!

        // init() only runs on first creation; after a rotation the bean must
        // be resolved again here (stored entity, or a fresh one for a new
        // profile - the launching intent survives the rotation).
        val bean = (proxyEntity?.requireBean() ?: createEntity()) as StandardV2RayBean
        if (bean is TrojanBean) {
            // Same binding remap as init() (needed there before
            // writeToCacheAll, needed here for saves after a rotation).
            uuid.fieldName = "password"
            password.disable = true
            uuid.preference.title = resources.getString(R.string.password)
        }

        // vmess/vless/http/trojan
        val isHttp = bean is HttpBean
        val isVmess = bean is VMessBean && !bean.isVLESS
        val isVless = bean.isVLESS

        (alterId.preference as EditTextPreference).bindIntegerPreference(0, 65535)
        (wsMaxEarlyData.preference as EditTextPreference).bindIntegerPreference()
        (muxConcurrency.preference as EditTextPreference).bindIntegerPreference()

        (uuid.preference as EditTextPreference).bindPasswordPreference()

        // both cores reject malformed REALITY keys, and sing-box panics on an
        // overlong short ID, so refuse them at input time as well as on save
        (realityPubKey.preference as EditTextPreference)
            .bindValidatedPreference(R.string.reality_public_key_error) {
                it.isEmpty() || isRealityPublicKey(it)
            }
        (realityShortId.preference as EditTextPreference)
            .bindValidatedPreference(R.string.reality_short_id_error, ::isRealityShortId)
        (realityMldsa65Verify.preference as EditTextPreference)
            .bindValidatedPreference(R.string.reality_mldsa65_error, ::isRealityMldsa65Verify)
        (certificateFingerprint.preference as EditTextPreference)
            .bindCertificateFingerprintPreference()

        type.preference.isVisible = !isHttp
        // mux 分类给 VMess / VLESS / Trojan，三者共用 StandardV2RayBean 的 mux 字段：跑在 sing-box 上时由
        // ProxyEntity.singMux() 建 multiplex（h2mux / smux / yamux；VLESS 与 VMess 同属 TYPE_VMESS），跑在 Xray 上时
        // 只能是 Mux.Cool（可选项按核心给，见 MuxTypeChoices）。HTTP 没有 mux
        findPreference<PreferenceCategory>(Key.SERVER_MUX_CATEGORY)!!.isVisible = !isHttp
        uuid.preference.isVisible = !isHttp
        packetEncoding.preference.isVisible = isVmess || isVless
        alterId.preference.isVisible = isVmess
        encryption.preference.isVisible = isVmess || isVless
        username.preference.isVisible = isHttp
        password.preference.isVisible = isHttp

        encryption.preference.apply {
            this as SimpleMenuPreference
            if (isVless) {
                title = resources.getString(R.string.xtls_flow)
                setIcon(R.drawable.ic_baseline_stream_24)
                setEntries(R.array.xtls_flow_value)
                setEntryValues(R.array.xtls_flow_value)
            } else {
                setEntries(R.array.vmess_encryption_value)
                setEntryValues(R.array.vmess_encryption_value)
            }
        }

        // menu with listener

        type.preference.apply {
            updateView(type.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateView(newValue as String)
                true
            }
        }

        security.preference.apply {
            updateTls(security.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateTls(newValue as String)
                true
            }
        }

        // 核心选项：VMess / VLESS / Trojan（D10，Trojan 也能走 Xray），HTTP 不能选核
        findPreference<ListPreference>(Key.PROFILE_CORE)!!.apply {
            isVisible = isVmess || isVless || bean is TrojanBean
            setOnPreferenceChangeListener { _, newValue ->
                val core = (newValue as String).toIntOrNull() ?: CORE_AUTO
                updateMuxType(core, muxTypeForCore(core, muxType.readStringToIntFromCache()))
                true
            }
        }

        // mux 协议的可选项按核心显示（D15）
        updateMuxType(editorCore(), null)
        muxType.preference.setOnPreferenceChangeListener { _, newValue ->
            muxPadding.preference.isVisible =
                muxPaddingVisible(editorCore(), (newValue as String).toIntOrNull() ?: MUX_H2MUX)
            true
        }
        enableMux.preference.setOnPreferenceChangeListener { _, newValue ->
            // 从关切到开：自动选核时按「不开 mux 时会选的核心」挑协议族（监听器先于写入缓存调用，
            // 临时 bean 里的 enableMux 仍是旧值，这里再显式置为假）
            if (newValue == true) {
                val core = editorCore()
                val coreWithoutMux = if (core != CORE_AUTO) null else {
                    val probe = editorBean().apply { enableMux = false }
                    (decideEditorCore(probe, CORE_AUTO) as? CoreDecision.Selected)?.core
                }
                updateMuxType(core, muxTypeOnEnable(core, muxType.readStringToIntFromCache(), coreWithoutMux))
            }
            true
        }
    }

    // 按核心换 muxType 的可选项；value 不为 null 且与当前值不同时改写（经偏好写回编辑缓存）。
    // core 是即将生效的核心取值：核心选项的监听器先于写入缓存调用，不能从缓存读
    private fun updateMuxType(core: Int, value: Int?) {
        val choices = muxTypeChoices(core)
        val preference = muxType.preference as SimpleMenuPreference
        preference.setEntries(choices.entriesRes)
        preference.setEntryValues(choices.valuesRes)
        val current = muxType.readStringToIntFromCache()
        if (value != null && value != current) preference.value = value.toString()
        muxPadding.preference.isVisible = muxPaddingVisible(core, value ?: current)
    }

    private fun updateView(network: String) {
        host.preference.isVisible = false
        path.preference.isVisible = false
        wsCategory.isVisible = false

        when (network) {
            "tcp" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
            }

            "http" -> {
                host.preference.setTitle(R.string.http_host)
                path.preference.setTitle(R.string.http_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }

            "ws" -> {
                host.preference.setTitle(R.string.ws_host)
                path.preference.setTitle(R.string.ws_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
                wsCategory.isVisible = true
            }

            "grpc" -> {
                path.preference.setTitle(R.string.grpc_service_name)
                path.preference.isVisible = true
            }

            "httpupgrade" -> {
                host.preference.setTitle(R.string.http_upgrade_host)
                path.preference.setTitle(R.string.http_upgrade_path)
                host.preference.isVisible = true
                path.preference.isVisible = true
            }
        }
    }

    private fun updateTls(tls: String) {
        val isTLS = "tls" in tls
        securityCategory.isVisible = isTLS
        tlsCamouflageCategory.isVisible = isTLS
        echCategory.isVisible = isTLS
    }

}