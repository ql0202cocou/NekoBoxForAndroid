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

    // 路由编辑器的状态在内存里的 profileCacheStore，随进程一起消失。进程死亡
    // 恢复时路由编辑器是异步重新初始化的，这里 routePackages 可能还是空的；
    // 从空值开始，下次编辑就会把应用列表写坏。直接退出，让用户从编辑器重新进入。
    // （按 String 读：routeOutbound 经 stringToInt 存储，行类型是 TYPE_STRING，
    // getInt() 永远读不到）
    override fun onViewsCreated(savedInstanceState: Bundle?) = savedInstanceState == null ||
        EditorCache.profileCacheStore.getString(Key.ROUTE_OUTBOUND) != null
}
