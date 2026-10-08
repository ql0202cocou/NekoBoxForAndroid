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
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
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
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.safeDrawingTypes
import io.nekohasekai.sagernet.widget.systemBarMargins
import moe.matsuri.nb4a.utils.Util
import io.nekohasekai.sagernet.bg.ServiceRegistry

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        binding.dock.onItemSelected = { id ->
            // 已在该页时不重建页面；二级页面上点「设置」仍回到设置页
            if (pageIdOf(currentFragment()) != id) displayFragmentWithId(id)
        }

        // Edge-to-edge: the gesture pill overlaps the bottom of the stats bar. Pad its
        // inner layout so the text stays above the pill while the bar's background band
        // extends under it. The FAB is anchored to the bar's top edge, which only moves
        // up as the bar grows.
        binding.statsContent.padForSystemBars()
        // Dock 悬浮在导航栏上方，横屏时也避开侧边的导航栏与刘海。插入区在外层 coordinator 上
        // 统一取：它先于所有子视图收到，API 30 以下某个页面（仪表板的 WebView 容器）把插入区
        // 清零后，排在后面的兄弟视图就只能收到清零后的值
        val dockMargins = binding.dock.systemBarMargins()
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            dockMargins(insets.getInsets(safeDrawingTypes))
            insets
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            // 重建时 FragmentManager 已自行恢复了当前页面，不经过 displayFragment()，
            // 这里补上按页面决定的 FAB 与 StatsBar 可见性，都不播动画，免得第一帧先显示再收起。
            // FAB 和 allowShow 立即处理：FAB 还没布局，hide() / show() 直接改可见性。
            // StatsBar 的收起距离在它的 Behavior 布局完成后才确定，早于此收起会让它
            // 原地隐身，FAB 回到配置页时就停在 StatsBar 展开时的位置，所以等整个
            // coordinator 布局完再无动画收起，仍早于第一次绘制
            val fragment = currentFragment()
            // Dock 的选中项同样按恢复出来的页面设置（二级页面选中「设置」）
            binding.dock.select(dockItemOf(fragment))
            val visible = bottomControlsVisible(fragment)
            if (visible != null) {
                binding.stats.allowShow = visible
                if (visible) binding.fab.show() else {
                    binding.fab.hide()
                    binding.coordinator.doOnLayout { binding.stats.performHide(false) }
                }
            }
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

    // 配置页总是显示 FAB 并允许 StatsBar 出现；其它页面除非开了「底栏」，否则两者都隐藏，
    // StatsBar 也不再随连接状态弹出（changeState 只在 allowShow 时 performShow）。
    // 返回 null 表示保持现状（其它页面且开了「底栏」）
    private fun bottomControlsVisible(fragment: Fragment?): Boolean? = when {
        fragment is ConfigurationFragment -> true
        !DataStore.showBottomBar -> false
        else -> null
    }

    private fun updateBottomControls(fragment: Fragment?) {
        when (bottomControlsVisible(fragment)) {
            true -> {
                binding.stats.allowShow = true
                binding.fab.show()
            }

            false -> {
                binding.stats.allowShow = false
                binding.stats.performHide()
                binding.fab.hide()
            }

            null -> {}
        }
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        updateBottomControls(fragment)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.dock.select(dockItemOf(fragment))
        settingsListState = when {
            // 从设置页进二级页面：记下设置列表的滚动位置（替换是异步提交的，此时设置页还在）
            fragment.opensFromSettings -> (currentFragment() as? SettingsFragment)?.let {
                (supportFragmentManager.findFragmentById(R.id.settings) as? SettingsPreferenceFragment)
                    ?.listState()
            }
            // 回到设置页：交给新的设置列表恢复（takeSettingsListState）
            fragment is SettingsFragment -> settingsListState
            else -> null
        }
    }

    // 设置页末尾的四个入口进入二级页面前，设置列表的滚动状态；返回设置页时恢复，免得每次从顶部滚到底
    private var settingsListState: Parcelable? = null

    /** 新的设置列表取走进入二级页面前保存的滚动状态，只取一次 */
    fun takeSettingsListState(): Parcelable? = settingsListState.also { settingsListState = null }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_holder)

    // 页面在 Dock 上对应的项：二级页面都从设置页进入，选中「设置」
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
        is SettingsFragment -> R.id.nav_settings
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
            R.id.nav_settings -> displayFragment(SettingsFragment())
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
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? WebviewFragment)
            ?.onCoreStateChanged(state)
    }

    // callers show() the returned Snackbar
    @SuppressLint("ShowToast")
    override fun snackbarInternal(text: CharSequence): Snackbar {
        // 显示在 Dock 之上（Dock 在所有页面常驻）
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            anchorView = binding.dock
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
