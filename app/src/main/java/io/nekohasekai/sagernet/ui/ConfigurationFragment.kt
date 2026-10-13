package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.preference.PreferenceDataStore
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.displayType
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirm
import io.nekohasekai.sagernet.ktx.displayName
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showSnackbar
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.writeToDocument
import io.nekohasekai.sagernet.ui.profile.settingActivityOf
import moe.matsuri.nb4a.Protocols
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.EditorCache

class ConfigurationFragment : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    companion object {
        private const val ARG_SELECT = "select"
        private const val ARG_SELECTED_ITEM = "selectedItem"
        private const val ARG_TITLE_RES = "titleRes"

        // 选择模式（ProfileSelectActivity / SwitchActivity）。参数必须走 arguments：
        // 进程被杀后系统只用无参构造恢复 Fragment 并带回 arguments，构造参数会丢，
        // 恢复出来的就成了无标题的普通节点页
        fun newSelectInstance(selectedItem: ProxyEntity?, @StringRes titleRes: Int) =
            ConfigurationFragment().apply {
                arguments = bundleOf(
                    ARG_SELECT to true,
                    ARG_SELECTED_ITEM to selectedItem,
                    ARG_TITLE_RES to titleRes,
                )
            }
    }

    val select get() = arguments?.getBoolean(ARG_SELECT) ?: false
    val selectedItem: ProxyEntity? by lazy {
        arguments?.let { BundleCompat.getParcelable(it, ARG_SELECTED_ITEM, ProxyEntity::class.java) }
    }
    private val titleRes get() = arguments?.getInt(ARG_TITLE_RES) ?: 0

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private lateinit var appBar: AppBarLayout

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): ProfileListFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as ProfileListFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    /**
     * 顶部栏（含 Tab 条）是否抬升：当前页的列表能向上滚就抬升。
     * 主界面没有 CoordinatorLayout（嵌套滚动要一路传到外层给 Dock 与状态卡片），
     * AppBarLayout 的抬升不会自己触发，由这里手动驱动。
     * 按 groupPager.currentItem 取当前页：选择模式下 DataStore.selectedGroup 不更新，
     * getCurrentGroupFragment 不适用
     */
    fun updateAppBarLift() {
        if (!::appBar.isInitialized || !::groupPager.isInitialized || !::adapter.isInitialized) return
        val id = adapter.groupList.getOrNull(groupPager.currentItem)?.id
        val page = id?.let { childFragmentManager.findFragmentByTag("f$it") as? ProfileListFragment }
        val list = page?.listViewOrNull
        appBar.setLifted(list?.canScrollVertically(-1) == true)
    }

    private val appBarLiftCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = updateAppBarLift()
    }

    // 节点卡片的错位入场只在冷启动（本页不是经转场进入）和用户切分组时播；经 Dock 淡变回到本页时
    // 不播，免得两种动画叠在一起。ViewPager2 建好后的第一次 onPageSelected 是初始页，不算用户切换
    private var pageSelections = 0
    private val enterAnimationCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            pageSelections++
        }
    }

    /** 当前选中的分组页能否播错位入场：用户切过分组，或本页不是经转场进入的 */
    fun allowsEnterAnimation(): Boolean = pageSelections > 1 || enterTransition == null

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
        if (query.isEmpty()) {
            // 清空作用到每个分组页：滑到别的页时搜索框失焦收起才发出这次清空，
            // selectedGroup 此时已是新页，只清当前页的话原页留着搜索词，之后每次
            // 整组重读都按它过滤
            for (page in childFragmentManager.fragments) {
                (page as? ProfileListFragment)?.adapter?.filter("")
            }
        } else {
            getCurrentGroupFragment()?.adapter?.filter(query)
        }
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

            // 搜索框展开时返回键先收起它，而不是直接退出；收起后回调停用，
            // 返回交还给 MainActivity 的页面级处理
            val closeSearchOnBack = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = cancelSearch(searchView)
            }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closeSearchOnBack)
            searchView.setOnSearchClickListener { closeSearchOnBack.isEnabled = true }
            searchView.setOnCloseListener {
                closeSearchOnBack.isEnabled = false
                false
            }
            searchViewBackCallback = closeSearchOnBack
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        appBar = view.findViewById(R.id.appbar)

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
        // 普通与选择模式都要跟着换页更新抬升；先注销再注册，onViewCreated 会再次执行
        groupPager.unregisterOnPageChangeCallback(appBarLiftCallback)
        groupPager.registerOnPageChangeCallback(appBarLiftCallback)
        groupPager.unregisterOnPageChangeCallback(enterAnimationCallback)
        pageSelections = 0
        groupPager.registerOnPageChangeCallback(enterAnimationCallback)

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
            // ViewPager 预载间隙里 fragment 的视图可能还没建出来：adapter 在
            // configurationListView 之后赋值，它为空时下面的 scrollTo 同样会崩
            val listAdapter = fragment?.adapter

            if (fragment != null && listAdapter != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex = listAdapter.configurationIdList.indexOf(selectedProxy)
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

    // 文件 / 剪贴板导入的解析在 ConfigurationImporter 里
    private val importer = ConfigurationImporter(this)

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            // appScope: the import outlives this fragment, so go through the app
            // context and report only while still attached
            if (file != null) importer.importFile(file)
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = GroupManager.selectedGroupForImport()
        ProfileRepository.createProfiles(targetId, proxies)
        onMainDispatcher {
            // Same tab switch the PROFILE_GROUP cache listener performs; the
            // view (and its adapter) may be gone for an appScope caller
            if (::adapter.isInitialized) selectGroupTab(targetId)
        }
        // appScope caller: the fragment may be gone by now
        showSnackbar(app.resources.getQuantityString(R.plurals.added, proxies.size, proxies.size))

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
                } else importer.importClipboard(text)
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_update_subscription -> {
                // currentGroup 有读库兜底，不能留在主线程
                runOnDefaultDispatcher {
                    val group = GroupManager.currentGroup()
                    if (group.type != GroupType.SUBSCRIPTION) {
                        Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                        showSnackbar(R.string.group_not_subscription)
                    } else {
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
                    val toClear = ProfileRepository.getProfilesByGroup(GroupManager.currentGroupId())
                        .filter { it.status != 0 && it.status != 1 }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            // 读库期间 fragment 可能已销毁，requireContext 会在 appScope 上抛异常崩溃
                            if (!isAdded) return@onMainDispatcher
                            requireContext().confirm(R.string.delete_confirm_prompt) {
                                runOnDefaultDispatcher {
                                    ProfileRepository.deleteProfiles(toClear)
                                }
                            }
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
                            if (!isAdded) return@onMainDispatcher
                            // 最多列出 20 个，再多的用省略号代替
                            val names = toClear.take(21).mapIndexed { index, proxyEntity ->
                                if (index < 20) proxyEntity.displayName() else "......"
                            }
                            requireContext().confirm(
                                getString(R.string.delete_confirm_prompt) + "\n" + names.joinToString("\n")
                            ) {
                                runOnDefaultDispatcher {
                                    ProfileRepository.deleteProfiles(toClear)
                                }
                            }
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

    // TCP ping / URL 测试的调度在 GroupTestRunner 里
    private val groupTest = GroupTestRunner(this)

    fun pingTest() = groupTest.pingTest()

    fun urlTest() = groupTest.urlTest()


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

    private var searchViewBackCallback: OnBackPressedCallback? = null

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
        searchViewBackCallback?.isEnabled = false
    }

}
