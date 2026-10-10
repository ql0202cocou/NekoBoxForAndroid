package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutDialogInputBinding
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.isIntegerInRange
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.liftAncestorAppBar
import io.nekohasekai.sagernet.widget.padForSystemBars
import moe.matsuri.nb4a.ui.*
import io.nekohasekai.sagernet.bg.ServiceRegistry

/**
 * 设置页的列表。一级页（rootKey 为空）只有六个分类入口和页面入口；
 * 分类页用分类 key（[sections]）作 rootKey 加载同一份 XML，只给本页存在的行接监听。
 */
class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreferenceCompat

    private lateinit var globalCustomConfig: EditConfigPreference


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        listView.padForSystemBars(bottomExtra = mainBottomClearance())
        listView.liftAncestorAppBar()
        // 从二级页面返回一级页：恢复进入前的滚动位置（页面入口在列表末尾）
        if (arguments?.getString(ARG_PREFERENCE_ROOT) == null) {
            (activity as? MainActivity)?.takeSettingsListState()?.let {
                listView.layoutManager?.onRestoreInstanceState(it)
            }
        }
    }

    /** 一级列表当前的滚动状态，由 MainActivity 经 SettingsFragment 在进入二级页面前保存 */
    fun listState(): Parcelable? = view?.let { listView.layoutManager?.onSaveInstanceState() }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        setPreferencesFromResource(R.xml.global_preferences, rootKey)

        // 整份 XML 都被解析，但 rootKey 之外的行不在本页：只给本页的行接监听
        when (rootKey) {
            null -> setupHub()
            KEY_SERVICE -> setupService()
            KEY_INTERFACE -> setupInterface()
            KEY_ROUTE -> setupRoute()
            KEY_DNS -> setupDns()
            KEY_INBOUND -> setupInbound()
            KEY_ADVANCED -> setupAdvanced()
        }
    }

    // 一级页：末尾的页面入口点击打开 MainActivity 的对应页面；仪表板只在开启 Clash API 时显示
    // （开关在「高级」页，回到一级页时重建列表，这里读取最新值）
    private fun setupHub() {
        for ((key, page) in navEntries) {
            findPreference<Preference>(key)!!.setOnPreferenceClickListener {
                (activity as? MainActivity)?.displayFragmentWithId(page)
                true
            }
        }
        findPreference<Preference>(KEY_NAV_DASHBOARD)!!.isVisible = DataStore.enableClashAPI
    }

    private fun setupService() {
        findPreference<Preference>(Key.SERVICE_MODE)!!.setOnPreferenceChangeListener { _, _ ->
            if (ServiceRegistry.state.started) SagerNet.stopService()
            true
        }
        findPreference<Preference>(Key.TUN_IMPLEMENTATION)!!.onPreferenceChangeListener = reloadListener
        findPreference<MTUPreference>(Key.MTU)!!.onPreferenceChangeListener = reloadListener
        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        findPreference<Preference>(Key.ACQUIRE_WAKE_LOCK)!!.onPreferenceChangeListener = reloadListener
    }

    private fun setupInterface() {
        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)!!
        appTheme.setOnPreferenceChangeListener { _, newTheme ->
            if (ServiceRegistry.state.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        findPreference<SwitchPreferenceCompat>(Key.HIDE_FROM_RECENTS)!!.setOnPreferenceChangeListener { _, newValue ->
            // 回调早于写入，直接用新值
            SagerNet.setExcludeFromRecents(newValue as Boolean)
            true
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreferenceCompat>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }
        findPreference<SwitchPreferenceCompat>(Key.SHOW_DIRECT_SPEED)!!.onPreferenceChangeListener = reloadListener
    }

    private fun setupRoute() {
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            newValue as Boolean
        }
        findPreference<SwitchPreferenceCompat>(Key.BYPASS_LAN)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreferenceCompat>(Key.BYPASS_LAN_IN_CORE)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreferenceCompat>(Key.RESOLVE_DESTINATION)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.IPV6_MODE)!!.onPreferenceChangeListener = reloadListener
    }

    private fun setupDns() {
        findPreference<EditTextPreference>(Key.REMOTE_DNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.DIRECT_DNS)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreferenceCompat>(Key.ENABLE_DNS_ROUTING)!!.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreferenceCompat>(Key.ENABLE_FAKEDNS)!!.onPreferenceChangeListener = reloadListener
    }

    private fun setupInbound() {
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        // 范围同 DataStore.mixedPort 的 parsePort：越界值会被静默换成 2080 + 用户偏移，
        // 界面却显示原值，所以在保存前拦下
        mixedPort.setOnPreferenceChangeListener { _, newValue ->
            if (!isIntegerInRange(newValue, 1025, 65535)) {
                Toast.makeText(
                    requireContext(), getString(R.string.integer_range_error, 1025, 65535), Toast.LENGTH_LONG
                ).show()
                false
            } else {
                needReload()
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(Key.APPEND_HTTP_PROXY)!!.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ALLOW_ACCESS)!!.onPreferenceChangeListener = reloadListener
    }

    private fun setupAdvanced() {
        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val binding = LayoutDialogInputBinding.inflate(layoutInflater)
            binding.inputLayout.hint = getString(R.string.log_buffer_size)
            binding.edit.apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.log_buffer_size)
                .setView(binding.root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = binding.edit.text.toString().toIntOrNull() ?: 0
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // 仪表板入口的显隐在一级页重建时读取，这里只需重载
        findPreference<SwitchPreferenceCompat>(Key.ENABLE_CLASH_API)!!.onPreferenceChangeListener = reloadListener
        globalCustomConfig.onPreferenceChangeListener = reloadListener
    }

    internal companion object {
        const val KEY_NAV_DASHBOARD = "navDashboard"

        const val KEY_SERVICE = "sectionService"
        const val KEY_INTERFACE = "sectionInterface"
        const val KEY_ROUTE = "sectionRoute"
        const val KEY_DNS = "sectionDns"
        const val KEY_INBOUND = "sectionInbound"
        const val KEY_ADVANCED = "sectionAdvanced"

        // 设置一级页的六个分类（global_preferences.xml 开头的六个 PreferenceScreen）→ 标题；
        // 顺序同 XML，二级页的顶部栏标题与入口行是同一字符串
        val sections = listOf(
            KEY_SERVICE to R.string.settings_service,
            KEY_INTERFACE to R.string.settings_interface,
            KEY_ROUTE to R.string.settings_route,
            KEY_DNS to R.string.settings_dns,
            KEY_INBOUND to R.string.settings_inbound,
            KEY_ADVANCED to R.string.settings_advanced,
        )

        // 设置页末尾的页面入口（global_preferences.xml 的最后一组）→ 页面 id
        val navEntries = listOf(
            KEY_NAV_DASHBOARD to R.id.nav_traffic,
            "navLogs" to R.id.nav_logcat,
            "navTools" to R.id.nav_tools,
            "navAbout" to R.id.nav_about,
        )
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
    }

}
