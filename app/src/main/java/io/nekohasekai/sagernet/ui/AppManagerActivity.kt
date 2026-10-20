package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.TextUtils
import androidx.core.util.set
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.utils.NGUtil

// 设置里的分应用代理，勾选结果写回 DataStore.individual
class AppManagerActivity : AppSelectActivity() {

    private val binding by lazy(LazyThreadSafetyMode.NONE) { LayoutAppsBinding.inflate(layoutInflater) }

    override fun inflateViews() = binding.run {
        Views(root, toolbar, list, search, showSystemApps, appPlaceholder, loading, appEmpty.root)
    }

    override var selectedPackages by DataStore::individual
    override val titleRes = R.string.proxied_apps
    override val menuRes = R.menu.per_app_proxy_menu
    override val defaultShowSystemApps = true
    override val clipboardHeader get() = DataStore.bypass.toString()

    override fun onImportClipboardHeader(header: String) {
        binding.bypassGroup.check(if (header.toBoolean()) R.id.appProxyModeBypass else R.id.appProxyModeOn)
    }

    override fun onViewsCreated(savedInstanceState: Bundle?): Boolean {
        if (!DataStore.proxyApps) {
            DataStore.proxyApps = true
        }

        binding.bypassGroup.check(if (DataStore.bypass) R.id.appProxyModeBypass else R.id.appProxyModeOn)
        binding.bypassGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.appProxyModeDisable -> {
                    DataStore.proxyApps = false
                    finish()
                }

                R.id.appProxyModeOn -> DataStore.bypass = false
                R.id.appProxyModeBypass -> DataStore.bypass = true
            }
        }
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }
        return true
    }

    private fun selectProxyApp() {
        confirm(R.string.auto_select_proxy_apps_message) {
            // loadApps() 可能还没跑完：列表为空时下面的筛选会把
            // DataStore.individual 清空
            if (!appsLoaded) return@confirm
            // 内置包名清单在 APK assets 里，放到 IO 上读；proxiedUids 只在主线程改
            lifecycleScope.launch {
                val needProxyAppsList = withContext(Dispatchers.IO) { getAutoProxyApps("") }
                if (needProxyAppsList == null) {
                    // 清单读不出来时不动现有勾选，否则代理模式只剩 uid 1000，绕过模式全选
                    Snackbar.make(binding.root, R.string.auto_select_proxy_apps_failed, Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                try {
                    val bypass = DataStore.bypass
                    proxiedUids.clear()
                    for (app in cachedApps) {
                        val needProxy =
                            needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                                ?: 0) == 1000
                        if (needProxy) {
                            if (!bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        } else {
                            if (bypass) {
                                app.value.applicationInfo?.apply {
                                    proxiedUids[uid] = true
                                }
                            }
                        }
                    }
                    applySelection()
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
        }
    }

    // 读取失败或清单为空返回 null，由调用方中止自动选择
    private fun getAutoProxyApps(content: String): List<String>? {
        return try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (TextUtils.isEmpty(proxyApps)) null else proxyApps.split("\n")
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }
}
