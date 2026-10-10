package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.RemoteException
import android.view.KeyEvent
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.safeDrawingTypes
import io.nekohasekai.sagernet.widget.systemBarMargins
import moe.matsuri.nb4a.utils.Util
import io.nekohasekai.sagernet.bg.ServiceRegistry

/**
 * 页面由 MainActivity 托管时，滚动视图底部要为悬浮的 Dock 与状态卡片让出的高度
 * （[MainActivity.bottomControlsClearance]），给 padForSystemBars 的 bottomExtra 用；
 * 其它宿主（选节点、通知里的切换对话框）没有 Dock，为 0
 */
fun Fragment.mainBottomClearance(): () -> Int =
    { (activity as? MainActivity)?.bottomControlsClearance() ?: 0 }

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    lateinit var binding: LayoutMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        binding.dock.onItemSelected = { id ->
            // 已在该页时不重建页面；二级页面（含设置的分类页）上点「设置」回到设置一级页
            if (pageIdOf(currentFragment()) != id) displayFragmentWithId(id)
        }

        // Dock、连接按钮、状态卡片悬浮在导航栏上方，横屏时也避开侧边的导航栏与刘海。插入区在
        // 外层 coordinator 上统一取：它先于所有子视图收到，API 30 以下某个页面（仪表板的 WebView
        // 容器）把插入区清零后，排在后面的兄弟视图就只能收到清零后的值
        val floatingMargins = listOf(binding.dock, binding.fab, binding.stats).map { it.systemBarMargins() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            val safeDrawing = insets.getInsets(safeDrawingTypes)
            for (apply in floatingMargins) apply(safeDrawing)
            insets
        }
        // 列表底部留白含状态卡片的高度（bottomControlsClearance）：卡片高度变了就重新分发插入区
        binding.stats.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(binding.coordinator)
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            // 重建时 FragmentManager 已自行恢复了当前页面，不经过 displayFragment()，
            // Dock 的选中项在这里按恢复出来的页面设置（二级页面选中「设置」）
            binding.dock.select(dockItemOf(currentFragment()))
        }
        // 返回：二级页面回设置页，其它一级页面回配置页，配置页退到后台
        onBackPressedDispatcher.addCallback {
            val fragment = currentFragment()
            when {
                fragment is ConfigurationFragment -> moveTaskToBack(true)
                (fragment as? ToolbarFragment)?.opensFromSettings == true -> {
                    displayFragmentWithId(R.id.nav_settings)
                }

                else -> displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (ServiceRegistry.state.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener { if (ServiceRegistry.state.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // 只认 VIEW 深链：exported 状态下其它 action 带 data 也会走到这里
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!ServiceRegistry.state.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    // 导入链跑在 appScope（深链、配置页的剪贴板 / 文件导入都会进来），解析期间
    // 旋转或退出后 Activity 已销毁：再弹窗会 BadTokenException，提交 Fragment 会抛
    // "Activity has been destroyed"。这时放弃界面部分，已写库的导入不受影响
    private suspend fun onLiveActivity(block: () -> Unit) = onMainDispatcher {
        if (!isFinishing && !isDestroyed) block()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onLiveActivity {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        // 两种形态（?url= 明文与编码的分组）汇合后统一校验：深链由外部网页 / 应用
        // 发起，订阅地址只接受 http(s)。content:// 等本地来源只供用户在应用内自己
        // 选择的文件使用，不能由外部链接指定
        val link = group.subscription?.link
        if (!link.isNullOrBlank()) {
            val scheme = link.toUri().scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                onLiveActivity {
                    alert(getString(R.string.invalid_subscription_url)).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onLiveActivity {

            displayFragmentWithId(R.id.nav_group)

            val message = getString(R.string.subscription_import_message, name)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(if (!link.isNullOrBlank()) "$message\n\n$link" else message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onLiveActivity {
                alert(e.readableMessage).show()
            }
            return
        }

        onLiveActivity {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = GroupManager.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onLiveActivity {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    /**
     * 主界面列表底部在导航栏插入区之外要让出的高度（px）：Dock 区（底边距、Dock、8dp 间隔），
     * 已连接、状态卡片允许出现时再加卡片高度与 8dp 间隔。连接状态或卡片高度变化时
     * 重新分发插入区，各列表的 padForSystemBars(bottomExtra) 随之更新
     */
    fun bottomControlsClearance(): Int {
        var clearance = resources.getDimensionPixelSize(R.dimen.nav_dock_clearance)
        if (binding.stats.allowShow) {
            clearance += binding.stats.height + resources.getDimensionPixelSize(R.dimen.stats_bar_gap)
        }
        return clearance
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.dock.select(dockItemOf(fragment))
        settingsListState = when {
            // 从设置页进二级页面：记下设置列表的滚动位置（替换是异步提交的，此时设置页还在）
            // （只有一级页有非空状态；分类页间不互相进入）
            fragment.opensFromSettings -> (currentFragment() as? SettingsFragment)?.listState()
            // 回到设置一级页：交给新的设置列表恢复（takeSettingsListState）
            fragment is SettingsFragment -> settingsListState
            else -> null
        }
    }

    // 从设置一级页进入二级页面（七个分类页和末尾四个入口）前，一级列表的滚动状态；
    // 返回一级页时恢复，免得每次从顶部滚到底
    private var settingsListState: Parcelable? = null

    /** 新的设置列表取走进入二级页面前保存的滚动状态，只取一次 */
    fun takeSettingsListState(): Parcelable? = settingsListState.also { settingsListState = null }

    /** 设置一级页上点分类入口（嵌套的 PreferenceScreen）：打开对应的二级页 */
    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat, pref: PreferenceScreen
    ): Boolean {
        displayFragment(SettingsFragment.section(pref.key))
        return true
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_holder)

    // 页面在 Dock 上对应的项：二级页面（分类页、日志、工具、关于、仪表板）都从设置页进入，选中「设置」
    @IdRes
    private fun dockItemOf(fragment: Fragment?): Int = when (fragment) {
        is ConfigurationFragment -> R.id.nav_configuration
        is GroupFragment -> R.id.nav_group
        is RouteFragment -> R.id.nav_route
        else -> R.id.nav_settings
    }

    // 当前页面自己的 id（与 displayFragmentWithId 的分发一致），未知页面为 0
    @IdRes
    private fun pageIdOf(fragment: Fragment?): Int = when (fragment) {
        is ConfigurationFragment -> R.id.nav_configuration
        is GroupFragment -> R.id.nav_group
        is RouteFragment -> R.id.nav_route
        // 只有一级页算「设置」；分类页为 0，所以在分类页上点 Dock 的「设置」会回到一级页
        is SettingsFragment -> if (fragment.sectionKey == null) R.id.nav_settings else 0
        is WebviewFragment -> R.id.nav_traffic
        is ToolsFragment -> R.id.nav_tools
        is LogcatFragment -> R.id.nav_logcat
        is AboutFragment -> R.id.nav_about
        else -> 0
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment.hub())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        ServiceRegistry.state = state
        // 停止时终值已随 postFinalTraffic 落库，缓存的实时流量不再需要
        if (state == BaseService.State.Stopped) ProfileManager.liveTraffic.clear()

        binding.fab.changeState(state, ServiceRegistry.state, animate)
        val statsAllowed = binding.stats.allowShow
        binding.stats.changeState(state, animate)
        if (binding.stats.allowShow != statsAllowed) ViewCompat.requestApplyInsets(binding.coordinator)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? WebviewFragment)
            ?.onCoreStateChanged(state)
    }

    // callers show() the returned Snackbar
    @SuppressLint("ShowToast")
    override fun snackbarInternal(text: CharSequence): Snackbar {
        // 显示在 Dock 之上（Dock 在所有页面常驻）；状态卡片展开时在卡片之上，不盖住它
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = if (binding.stats.isExpanded) binding.stats else binding.dock
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.fromOrdinal(service.state)
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old)
            ProfileManager.postUpdate(id)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (ServiceRegistry.state.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 焦点在 Dock 上时方向键只走系统的焦点移动，不转给页面：配置页会把焦点抢回节点列表，
        // 遥控器用户就没法在 Dock 里左右移动
        if (binding.dock.hasFocus()) return super.onKeyDown(keyCode, event)
        if (super.onKeyDown(keyCode, event)) return true
        val fragment = currentFragment() as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
