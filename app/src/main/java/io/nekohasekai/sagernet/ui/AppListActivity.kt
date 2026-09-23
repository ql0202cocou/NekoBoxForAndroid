package io.nekohasekai.sagernet.ui

import android.os.Bundle
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorCache
import io.nekohasekai.sagernet.databinding.LayoutAppListBinding

// 路由规则编辑器里的应用列表，勾选结果写回 EditorCache.routePackages
class AppListActivity : AppSelectActivity() {

    override fun inflateViews() = LayoutAppListBinding.inflate(layoutInflater).run {
        Views(root, toolbar, list, search, showSystemApps, appPlaceholder)
    }

    override var selectedPackages by EditorCache::routePackages
    override val titleRes = R.string.select_apps
    override val menuRes = R.menu.app_list_menu
    override val defaultShowSystemApps = false
    override val clipboardHeader = "false"

    // The route editor's state lives in the in-memory profileCacheStore
    // and dies with the process. On a process-death restore the route
    // editor re-initializes asynchronously, so routePackages may still
    // be blank here; starting from it would corrupt the app list on the
    // next edit. Bail out and let the user re-enter from the editor.
    // (read as String: routeOutbound is persisted via stringToInt, so the
    // row is TYPE_STRING and getInt() would never see it)
    override fun onViewsCreated(savedInstanceState: Bundle?) = savedInstanceState == null ||
        EditorCache.profileCacheStore.getString(Key.ROUTE_OUTBOUND) != null
}
