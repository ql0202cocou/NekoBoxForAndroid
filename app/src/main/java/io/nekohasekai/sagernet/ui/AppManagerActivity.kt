package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.TextUtils
import androidx.core.util.set
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirm
import moe.matsuri.nb4a.utils.NGUtil

// 设置里的分应用代理，勾选结果写回 DataStore.individual
class AppManagerActivity : AppSelectActivity() {

    private lateinit var binding: LayoutAppsBinding

    override fun inflateViews() = LayoutAppsBinding.inflate(layoutInflater).run {
        binding = this
        Views(root, toolbar, list, search, showSystemApps, appPlaceholder)
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
            // loadApps() may still be running; with an empty list the
            // filter below would wipe DataStore.individual
            if (!appsLoaded) return@confirm
            try {
                val needProxyAppsList = getAutoProxyApps("")
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

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }
}
