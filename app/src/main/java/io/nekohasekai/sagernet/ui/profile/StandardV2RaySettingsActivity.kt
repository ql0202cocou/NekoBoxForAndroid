package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.coreSelectable
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.InvalidXhttpExtraException
import io.nekohasekai.sagernet.fmt.v2ray.MUX_H2MUX
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.canonicalXhttpExtra
import io.nekohasekai.sagernet.fmt.v2ray.isRealityMldsa65Verify
import io.nekohasekai.sagernet.fmt.v2ray.isRealityPublicKey
import io.nekohasekai.sagernet.fmt.v2ray.isRealityShortId
import io.nekohasekai.sagernet.fmt.v2ray.normalizedXhttpPath
import io.nekohasekai.sagernet.fmt.v2ray.requireXhttpMode
import io.nekohasekai.sagernet.fmt.v2ray.v2rayTransportOrNull
import io.nekohasekai.sagernet.fmt.v2ray.xhttpParametersLost
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.userMessage
import kotlinx.coroutines.Dispatchers
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import org.json.JSONObject

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
    private val xhttpMode = pbm.add(PreferenceBinding(Type.Text, "xhttpMode"))
    private val xhttpExtra = pbm.add(PreferenceBinding(Type.Text, Key.XHTTP_EXTRA))
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

    // XHTTP 的 mode 与 extra 只在传输方式是 xhttp 时有意义（其它传输方式下这两行隐藏）。
    // 导入或旧版本留下的值没经过输入时的校验，保存时再跑一遍同一组校验器；消息只带键名，不带值
    private fun xhttpError(): String? {
        if (v2rayTransportOrNull(type.readStringFromCache()) != "xhttp") return null
        // 载入时就是残缺节点、path 仍是空：不猜测、不替用户补斜杠，要用户自己填（填什么都行，包括 /）
        if (xhttpLoadedLost() && path.readStringFromCache().isBlank()) {
            return getString(R.string.core_conflict_xhttp_parameters_lost)
        }
        return try {
            requireXhttpMode(xhttpMode.readStringFromCache())
            canonicalXhttpExtra(xhttpExtra.readStringFromCache())
            null
        } catch (e: InvalidXhttpExtraException) {
            e.userMessage
        }
    }

    override fun validateEditor(): String? {
        xhttpError()?.let { return it }
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

    private fun xhttpLoadedLost() = EditorCache.profileCacheStore.getBoolean(Key.XHTTP_LOST, false)

    override fun StandardV2RayBean.init() {
        EditorCache.profileCacheStore.putBoolean(Key.XHTTP_LOST, xhttpParametersLost())
        if (this is TrojanBean) {
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
        // xhttp 的空 path 存储里表示「参数已丢失」。新建的节点、或在本次编辑里才改成 xhttp 的节点，空 path 规范成斜杠；
        // 载入时就是残缺的节点不在此列：path 仍为空时由 validateEditor 拦下（要求用户自己填），不替用户猜
        if (v2rayTransportOrNull(type) == "xhttp" && !xhttpLoadedLost()) path = normalizedXhttpPath(path)
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

        // init() 只在首次创建时运行，重建（旋转、进程死亡）后要在这里重新定出 bean：这里只用它区分协议（类与
        // isVLESS）。首次创建时 fragment 在节点载入后才提交，用库里那一行；重建时 fragment 由 super.onCreate 恢复，
        // 节点还在后台载入、主线程不能查库，改用新 bean：类由编辑器决定，VLESS 看 init 写进缓存的 alterId
        // （VLESS 的 alterId 输入框隐藏，编辑期间不会变；进程死亡后缓存为空，恢复的 fragment 随即被重新初始化替换）
        val bean = (proxyEntity?.requireBean() ?: createEntity().also {
            if (it is VMessBean && alterId.readStringToIntFromCache() == -1) it.alterId = -1
        }) as StandardV2RayBean
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
        (xhttpMode.preference as SimpleMenuPreference).summaryProvider =
            Preference.SummaryProvider<ListPreference> {
                getString(R.string.xhttp_mode_summary, it.value ?: "auto")
            }
        xhttpExtra.preference.apply {
            summary = xhttpExtraSummary()
            setOnPreferenceClickListener {
                launchXhttpExtraEditor()
                true
            }
        }
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

        // 核心选项：只在能选核的协议上显示（VMess / VLESS / Trojan，D10），HTTP 不能选核
        findPreference<ListPreference>(Key.PROFILE_CORE)!!.apply {
            isVisible = coreSelectable(ProxyEntity().putBean(bean).type, bean)
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

    // extra 行的摘要：只列顶层键名（白名单里的名字），不带值（headers 的值可能是凭据）
    private fun xhttpExtraSummary(): String {
        val text = xhttpExtra.readStringFromCache()
        return try {
            val canonical = canonicalXhttpExtra(text)
            if (canonical.isEmpty()) getString(androidx.preference.R.string.not_set)
            else JSONObject(canonical).keys().asSequence().joinToString(", ")
        } catch (e: InvalidXhttpExtraException) {
            e.userMessage
        }
    }

    // extra 复用 JSON 编辑页：临时的一份放在 Key.XHTTP_EXTRA_EDIT（页内保存时就过校验器，不合格留在编辑页），
    // 回来后再跑一遍 canonicalXhttpExtra，通过才写回 Key.XHTTP_EXTRA。放种子不算编辑：脏标记保持原样
    private val xhttpExtraEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        applyEditedXhttpExtra()
    }

    // JSON 编辑页返回后取回那份临时内容（拆出来便于 Robolectric 直接调用，不必模拟 ActivityResult）
    internal fun applyEditedXhttpExtra() = launchInSession(context = Dispatchers.Main.immediate) {
        val edited = EditorCache.profileCacheStore.getString(Key.XHTTP_EXTRA_EDIT) ?: return@launchInSession
        val canonical = try {
            canonicalXhttpExtra(edited)
        } catch (e: InvalidXhttpExtraException) {
            MaterialAlertDialogBuilder(this@StandardV2RaySettingsActivity).setTitle(R.string.error_title)
                .setMessage(e.userMessage)
                .setPositiveButton(android.R.string.ok, null).show()
            return@launchInSession
        }
        if (canonical != xhttpExtra.readStringFromCache()) {
            EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA, canonical)
            xhttpExtra.preference.summary = xhttpExtraSummary()
        }
    }

    private fun launchXhttpExtraEditor() {
        val current = xhttpExtra.readStringFromCache()
        val seed = if (current.isBlank()) "" else runCatching { JSONObject(current).toStringPretty() }.getOrDefault(current)
        val dirty = EditorCache.dirty
        EditorCache.profileCacheStore.putString(Key.XHTTP_EXTRA_EDIT, seed)
        EditorCache.dirty = dirty
        xhttpExtraEditor.launch(Intent(this, ConfigEditActivity::class.java).putExtra("key", Key.XHTTP_EXTRA_EDIT))
    }

    private fun updateView(network: String) {
        host.preference.isVisible = false
        path.preference.isVisible = false
        xhttpMode.preference.isVisible = false
        xhttpExtra.preference.isVisible = false
        wsCategory.isVisible = false
        // 只有 xhttp 的主机、路径、ALPN 带说明；换到别的传输方式要撤掉
        (host.preference as EditTextPreference).dialogMessage = null
        (path.preference as EditTextPreference).dialogMessage = null
        (alpn.preference as EditTextPreference).dialogMessage = null

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

            "xhttp" -> {
                host.preference.setTitle(R.string.xhttp_host)
                path.preference.setTitle(R.string.xhttp_path)
                (host.preference as EditTextPreference).dialogMessage = getString(R.string.xhttp_host_hint)
                (path.preference as EditTextPreference).dialogMessage = getString(R.string.xhttp_path_hint)
                (alpn.preference as EditTextPreference).dialogMessage = getString(R.string.xhttp_alpn_h3_hint)
                host.preference.isVisible = true
                path.preference.isVisible = true
                xhttpMode.preference.isVisible = true
                xhttpExtra.preference.isVisible = true
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