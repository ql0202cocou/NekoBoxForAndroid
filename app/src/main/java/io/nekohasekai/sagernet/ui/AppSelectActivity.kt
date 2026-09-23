package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.appcompat.widget.Toolbar
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAppPlaceholderBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.exportToClipboard
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

// AppListActivity（路由规则的应用列表）与 AppManagerActivity（分应用代理）共用的
// 应用勾选页。子类只提供布局、勾选结果的存放位置、剪贴板首行的含义和各自的额外控件
abstract class AppSelectActivity : ThemedActivity() {
    companion object {
        private const val SWITCH = "switch"
        private const val SYS_APPS = "sys_apps"
    }

    protected val cachedApps
        get(): MutableMap<String, PackageInfo> {
            // register() runs asynchronously at app start; a cold restore
            // straight into this activity can get here before it finished
            PackageCache.awaitLoadSync()
            return PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
        }

    // 两份布局里共有的控件
    protected class Views(
        val root: View,
        val toolbar: Toolbar,
        val list: RecyclerView,
        val search: EditText,
        val showSystemApps: CompoundButton,
        val placeholder: LayoutAppPlaceholderBinding,
    )

    protected abstract fun inflateViews(): Views

    // 勾选结果，每行一个包名
    protected abstract var selectedPackages: String

    @get:StringRes
    protected abstract val titleRes: Int

    @get:MenuRes
    protected abstract val menuRes: Int

    protected abstract val defaultShowSystemApps: Boolean

    // 剪贴板导出格式的首行
    protected abstract val clipboardHeader: String

    // 剪贴板导入时首行的处理；没有换行时整段都算首行
    protected open fun onImportClipboardHeader(header: String) = Unit

    // setContentView 之后、载入勾选状态之前调用；返回 false 则直接 finish
    protected open fun onViewsCreated(savedInstanceState: Bundle?): Boolean = true

    private class ProxiedApp(
        private val pm: PackageManager, private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: CharSequence = appInfo.loadLabel(pm)    // cached for sorting
        val icon: Drawable get() = appInfo.loadIcon(pm)
        val uid get() = appInfo.uid
        val sys get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            binding.desc.text = "${app.packageName} (${app.uid})"
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            if (isProxiedApp(item)) proxiedUids.delete(item.uid) else proxiedUids[item.uid] = true
            selectedPackages = apps.filter { isProxiedApp(it) }
                .joinToString("\n") { it.packageName }
            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            PackageCache.reload()
            apps = cachedApps.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { ProxiedApp(packageManager, it, packageName) }
            }.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var views: Views

    // proxiedUids is a SparseBooleanArray read by the adapter on the main
    // thread; it is not thread-safe, so mutate it on the main thread only.
    protected val proxiedUids = SparseBooleanArray()
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var sysApps = false

    // loadApps() 还没跑完时列表为空，此时按列表写回会清空勾选结果
    protected val appsLoaded get() = apps.isNotEmpty()

    private fun initProxiedUids(str: String = selectedPackages) {
        proxiedUids.clear()
        val apps = cachedApps
        for (line in str.lineSequence()) {
            val app = (apps[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    // proxiedUids 整体改过之后：写回勾选结果，已勾选的排到前面并刷新列表
    @UiThread
    protected fun applySelection() {
        selectedPackages = apps.filter { isProxiedApp(it) }.joinToString("\n") { it.packageName }
        apps = apps.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))
        appsAdapter.filter.filter(views.search.text?.toString() ?: "")
    }

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            loading.crossFadeFrom(views.list)
            withContext(Dispatchers.IO) { appsAdapter.reload() }
            appsAdapter.filter.filter(views.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                views.list.visibility = View.GONE
                views.placeholder.root.crossFadeFrom(loading)
            } else {
                views.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        views = inflateViews()
        setContentView(views.root)
        if (!onViewsCreated(savedInstanceState)) {
            finish()
            return
        }

        views.placeholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(views.toolbar)
        supportActionBar?.apply {
            setTitle(titleRes)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        initProxiedUids()
        views.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        views.list.itemAnimator = DefaultItemAnimator()
        views.list.adapter = appsAdapter

        views.list.padForSystemBars()

        views.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        // the switch restores its own checked state on a config change; keep
        // the backing field in sync with it
        sysApps = savedInstanceState?.getBoolean(SYS_APPS) ?: defaultShowSystemApps
        views.showSystemApps.isChecked = sysApps
        views.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            appsAdapter.filter.filter(views.search.text?.toString() ?: "")
        }

        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(SYS_APPS, sysApps)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(menuRes, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                // 按反选前的快照判断：共用 uid 的多个应用只翻转一次
                val proxiedUidsOld = proxiedUids.clone()
                for (app in apps) {
                    if (proxiedUidsOld.contains(app.uid)) {
                        proxiedUids.delete(app.uid)
                    } else {
                        proxiedUids[app.uid] = true
                    }
                }
                applySelection()
                return true
            }

            R.id.action_clear_selections -> {
                proxiedUids.clear()
                applySelection()
            }

            R.id.action_export_clipboard -> {
                Snackbar.make(
                    views.list,
                    exportToClipboard("$clipboardHeader\n$selectedPackages"),
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString = SagerNet.getClipboardText()
                if (proxiedAppString.isNotEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (header, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        onImportClipboardHeader(header)
                        selectedPackages = apps
                        Snackbar.make(
                            views.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids(apps)
                        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, SWITCH)
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(views.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        val toolbar = views.toolbar
        if (toolbar.isOverflowMenuShowing) toolbar.hideOverflowMenu() else toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        loader?.cancel()
        super.onDestroy()
    }
}
