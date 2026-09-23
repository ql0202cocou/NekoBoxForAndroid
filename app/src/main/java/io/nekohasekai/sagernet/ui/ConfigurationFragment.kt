package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.preference.PreferenceDataStore
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.lookupViaNameserver
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.writeToDocument
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.settingActivityOf
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.EditorCache

// runGroupTest 在主线程置位、在后台 dispatcher 清除；CAS 防止连点两下起两个
// 测试。放在进程级而不是 fragment 字段：重建后的 fragment 不能再起第二个
private val runningTest = AtomicBoolean(false)

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0,
    val noChain: Boolean = false
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): ProfileListFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as ProfileListFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            // 滑动时每帧都会回调；只在分组变化时写，免得每帧一次主线程写库和监听回调
            val id = adapter.groupList.getOrNull(position)?.id ?: return
            if (DataStore.selectedGroup != id) DataStore.selectedGroup = id
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        // Side navigation bar / cutout in landscape: the AppBar padding above does not cover
        // this sibling strip. Top/bottom stay untouched.
        tabLayout.padForSystemBars(bottom = false)

        // onViewCreated can run again (rotation): unregister the previous
        // adapter before replacing it
        if (::adapter.isInitialized) {
            ProfileRepository.removeListener(adapter)
            GroupRepository.removeListener(adapter)
        }
        adapter = GroupPagerAdapter(this)
        ProfileRepository.addListener(adapter)
        GroupRepository.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        // Same re-registration guard as the adapter listeners above:
        // onViewCreated runs again after the detach/attach in onCreate,
        // while onDestroy only unregisters once.
        EditorCache.profileCacheStore.unregisterChangeListener(this)
        EditorCache.profileCacheStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup: an editor took the user to the edited group
            if (key == Key.PROFILE_GROUP) selectGroupTab(EditorCache.editingGroup)
        }
    }

    // Switch the pager to a group tab. import() calls this directly: the
    // cache is editor-owned, so changing tabs must not write PROFILE_GROUP
    // into it.
    private fun selectGroupTab(targetId: Long) {
        if (targetId > 0 && targetId != DataStore.selectedGroup) {
            DataStore.selectedGroup = targetId
            val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
            if (targetIndex >= 0) {
                groupPager.setCurrentItem(targetIndex, false)
            } else {
                adapter.reload()
            }
        }
    }

    override fun onDestroy() {
        EditorCache.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupRepository.removeListener(adapter)
            ProfileRepository.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            // appScope: the import outlives this fragment, so go through the app
            // context and report only while still attached
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        app.contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    // SAF providers may grant the uri but fail to open it
                    val inputStream = app.contentResolver.openInputStream(file)
                        ?: throw FileNotFoundException(file.toString())
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        // use(): a throwing parseRaw used to leak the fd
                        ZipInputStream(inputStream).use { zip ->
                            var remaining = MAX_IMPORT_BYTES
                            var entries = 0
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                require(++entries <= 4096) { "Too many ZIP entries" }
                                val bytes = zip.readBytesLimited(remaining)
                                remaining -= bytes.size
                                if (entry.isDirectory) continue
                                val fileText = bytes.toString(Charsets.UTF_8)
                                RawUpdater.parseRaw(fileText, entry.name)
                                    ?.let { pl -> proxies.addAll(pl) }
                                zip.closeEntry()
                            }
                        }
                    } else {
                        val fileText = inputStream.use {
                            it.readBytesLimited().toString(Charsets.UTF_8)
                        }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        if (isAdded) snackbar(R.string.no_proxies_found_in_file).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (activity as? MainActivity)?.importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded) snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = GroupManager.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileRepository.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            // Same tab switch the PROFILE_GROUP cache listener performs; the
            // view (and its adapter) may be gone for an appScope caller
            if (::adapter.isInitialized) selectGroupTab(targetId)
            // appScope caller: the fragment may be gone by now
            if (isAdded) snackbar(
                app.resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)
            ).show()
        }

    }

    // 「新建节点」菜单项对应的节点类型；VLESS 与 VMess 共用编辑器，靠 "vless" extra 区分
    private val newProfileTypes = mapOf(
        R.id.action_new_socks to ProxyEntity.TYPE_SOCKS,
        R.id.action_new_http to ProxyEntity.TYPE_HTTP,
        R.id.action_new_ss to ProxyEntity.TYPE_SS,
        R.id.action_new_vmess to ProxyEntity.TYPE_VMESS,
        R.id.action_new_vless to ProxyEntity.TYPE_VMESS,
        R.id.action_new_trojan to ProxyEntity.TYPE_TROJAN,
        R.id.action_new_trojan_go to ProxyEntity.TYPE_TROJAN_GO,
        R.id.action_new_mieru to ProxyEntity.TYPE_MIERU,
        R.id.action_new_naive to ProxyEntity.TYPE_NAIVE,
        R.id.action_new_hysteria to ProxyEntity.TYPE_HYSTERIA,
        R.id.action_new_tuic to ProxyEntity.TYPE_TUIC,
        R.id.action_new_ssh to ProxyEntity.TYPE_SSH,
        R.id.action_new_wg to ProxyEntity.TYPE_WG,
        R.id.action_new_shadowtls to ProxyEntity.TYPE_SHADOWTLS,
        R.id.action_new_anytls to ProxyEntity.TYPE_ANYTLS,
        R.id.action_new_config to ProxyEntity.TYPE_CONFIG,
        R.id.action_new_chain to ProxyEntity.TYPE_CHAIN,
    )

    override fun onMenuItemClick(item: MenuItem): Boolean {
        newProfileTypes[item.itemId]?.let { type ->
            startActivity(Intent(requireActivity(), settingActivityOf(type)).apply {
                if (item.itemId == R.id.action_new_vless) putExtra("vless", true)
            })
            return true
        }
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher {
                            if (isAdded) snackbar(R.string.no_proxies_found_in_clipboard).show()
                        } else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (activity as? MainActivity)?.importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        Logs.w(e)

                        onMainDispatcher {
                            if (isAdded) snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_update_subscription -> {
                val group = GroupManager.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val toClear = ProfileRepository.getProfilesByGroup(GroupManager.currentGroupId())
                        .filter { it.tx != 0L || it.rx != 0L }
                    if (toClear.isNotEmpty()) {
                        ProfileRepository.clearTraffic(toClear)
                        // :bg keeps its own counters; without this the running
                        // looper persists the pre-clear totals right back
                        SagerNet.clearTrafficStatistics(toClear.map { it.id }.toLongArray())
                        // the bare column write posts nothing, so refresh the list here
                        for (profile in toClear) ProfileRepository.postUpdate(profile)
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val toClear = ProfileRepository.getProfilesByGroup(GroupManager.currentGroupId())
                        .filter { it.status != 0 }
                    ProfileRepository.clearTestResults(toClear)
                    // the bare column write posts nothing, so refresh the list here
                    for (profile in toClear) ProfileRepository.postUpdate(profile)
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileRepository.getProfilesByGroup(GroupManager.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        ProfileRepository.deleteProfiles(toClear)
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = ProfileRepository.getProfilesByGroup(GroupManager.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.delete_confirm_prompt) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        ProfileRepository.deleteProfiles(toClear)
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest()
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }
        }
        return true
    }

    fun pingTest() {
        // 同组节点常共用一个域名，而下面的解析不带缓存、失败还要等满超时，
        // 所以按本轮测试缓存结果（失败时缓存域名本身，后面照常报解析失败）
        val resolvedAddresses = ConcurrentHashMap<String, String>()
        runGroupTest({ it.requireBean().canTCPing() }) { group, profile ->
            val domain = profile.requireBean().serverAddress
            val address = if (domain.isIpAddress()) domain else {
                resolvedAddresses.getOrPut(domain) {
                    // 组里配了节点解析 DNS（如 DoH）时优先使用，伪造域名也能解析
                    val results = lookupViaNameserver(
                        group.proxyServerNameserver, domain
                    ) ?: try {
                        SagerNet.underlyingNetwork?.getAllByName(domain)?.toList()
                            ?: emptyList()
                    } catch (ignored: UnknownHostException) {
                        emptyList()
                    }
                    results.firstOrNull()?.hostAddress ?: domain
                }
            }
            if (!isActive) return@runGroupTest false
            // 在后台线程运行，fragment 可能已分离，getString() 会抛异常，所以用 app
            if (!address.isIpAddress()) {
                profile.status = 2
                profile.error = app.getString(R.string.connection_test_domain_not_found)
                return@runGroupTest true
            }
            try {
                val socket =
                    SagerNet.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
                try {
                    socket.soTimeout = 3000
                    socket.bind(InetSocketAddress(0))
                    val start = SystemClock.elapsedRealtime()
                    socket.connect(
                        InetSocketAddress(address, profile.requireBean().serverPort), 3000
                    )
                    if (!isActive) return@runGroupTest false
                    profile.status = 1
                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                } finally {
                    // OkHttp 5 的 okhttp3.internal.closeQuietly 不是公开 API
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                if (!isActive) return@runGroupTest false
                val message = e.readableMessage
                profile.status = 2
                when {
                    !message.contains("failed:") -> profile.error =
                        app.getString(R.string.connection_test_timeout)

                    message.contains("ECONNREFUSED") -> profile.error =
                        app.getString(R.string.connection_test_refused)

                    message.contains("ENETUNREACH") -> profile.error =
                        app.getString(R.string.connection_test_unreachable)

                    else -> {
                        profile.status = 3
                        profile.error = message
                    }
                }
            }
            true
        }
    }

    fun urlTest() {
        runGroupTest({ true }) { _, profile ->
            try {
                // 注意：这里不在 bg 进程
                val result =
                    TestInstance(profile, DataStore.connectionTestURL, 5000).doTest()
                profile.status = 1
                profile.ping = result
            } catch (e: PluginManager.PluginNotFoundException) {
                profile.status = 2
                profile.error = e.readableMessage
            } catch (e: Exception) {
                profile.status = 3
                profile.error = e.readableMessage
            }
            true
        }
    }

    // 两种测试共用的框架：按并发数起 worker 逐个测当前组里 filter 通过的节点，
    // 结束或取消时把状态写回数据库。testOne 填好 profile 的测试结果，返回 false
    // 表示已被取消、不要上报这一条
    @OptIn(DelicateCoroutinesApi::class)
    private fun runGroupTest(
        filter: (ProxyEntity) -> Boolean,
        testOne: suspend CoroutineScope.(ProxyGroup, ProxyEntity) -> Boolean,
    ) {
        if (!runningTest.compareAndSet(false, true)) return
        val test = TestDialog(this)
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = GroupManager.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = ProfileRepository.getProfilesByGroup(group.id).filter(filter)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0
                        if (!testOne(group, profile)) break
                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            // appScope 上的测试任务结束时 activity 可能已销毁，
            // 这时 dismiss() 会抛 "not attached to window manager"
            runCatching { dialog.dismiss() }
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                // 只写状态：快照是测试开始时读的，整行更新会把 :bg 之后
                // 写入的 tx/rx 回滚
                test.results.forEach {
                    try {
                        ProfileRepository.updateStatus(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupRepository.postReload(GroupManager.currentGroupId())
                runningTest.set(false)
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    // Content of a config export awaiting the picked document; kept in a
    // field (not the editor-owned profileCacheStore) and still lost with the
    // process, which writeToDocument refuses to write instead of truncating
    // the picked file.
    internal var pendingExportConfig = ""

    internal val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                runOnDefaultDispatcher { writeToDocument(data, pendingExportConfig) }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

}
