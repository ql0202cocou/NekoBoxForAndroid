package io.nekohasekai.sagernet.ui

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RuleRepository
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference
import io.nekohasekai.sagernet.database.EditorCache

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : EditorActivity(resId) {

    // 选择结果重放得比异步初始化早（选择器在前台时进程死亡）时暂存在 ViewModel 里：
    // 初始化要把它补回缓存，免得实体里的值盖掉用户刚选的

    // 新建规则的初始内容；读应用名可能阻塞，调用方要用 checked 包起来，读完再写缓存
    fun newRule(packageName: String?) = RuleEntity().apply {
        if (!packageName.isNullOrBlank()) {
            packages = setOf(packageName)
            name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
        }
    }

    fun RuleEntity.init() {
        EditorCache.routeName = name
        EditorCache.serverConfig = config
        EditorCache.routeDomain = domains
        EditorCache.routeIP = ip
        EditorCache.routePort = port
        EditorCache.routeSourcePort = sourcePort
        EditorCache.routeNetwork = network
        EditorCache.routeSource = source
        EditorCache.routeProtocol = protocol
        EditorCache.routeOutboundRule = outbound
        EditorCache.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> 3
        }
        EditorCache.routePackages = packages.joinToString("\n")
    }

    fun RuleEntity.serialize() {
        name = EditorCache.routeName
        config = EditorCache.serverConfig
        domains = EditorCache.routeDomain
        ip = EditorCache.routeIP
        port = EditorCache.routePort
        sourcePort = EditorCache.routeSourcePort
        network = EditorCache.routeNetwork
        source = EditorCache.routeSource
        protocol = EditorCache.routeProtocol
        outbound = when (EditorCache.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> EditorCache.routeOutboundRule
        }
        packages = EditorCache.routePackages.split("\n").filter { it.isNotBlank() }.toSet()

        if (EditorCache.editingId == 0L) {
            enabled = true
        }
    }

    fun needSave(): Boolean {
        return EditorCache.dirty
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)
    }

    override fun onResume() {
        super.onResume()

        // 子编辑器写完缓存回来，刷新「自定义配置」一行的摘要；Fragment 还没提交时没有可刷新的
        child?.findPreference<EditConfigPreference>(Key.SERVER_CONFIG)?.notifyChanged()
    }

    val selectProfileForAdd = profilePicker({
        editor.setPending(PENDING_ROUTE_OUTBOUND, it)
        EditorCache.routeOutboundRule = it
        EditorCache.routeOutbound = 3
    }, { child?.findPreference<OutboundPreference>(Key.ROUTE_OUTBOUND) })

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        // The fragment may not be committed yet on a process-death restore;
        // AppListActivity already wrote EditorCache.routePackages, so skipping
        // the refresh loses nothing.
        child?.findPreference<AppListPreference>(Key.ROUTE_PACKAGES)?.postUpdate()
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        val outbound = findPreference<OutboundPreference>(Key.ROUTE_OUTBOUND)!!
        val apps = findPreference<AppListPreference>(Key.ROUTE_PACKAGES)!!

        outbound.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == "3") {
                selectProfileForAdd.launch(
                    Intent(
                        this@RouteSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
                false
            } else {
                true
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity, AppListActivity::class.java
                )
            )
            true
        }
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    RuleRepository.deleteRule(arg.ruleId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    override fun deleteConfirmationDialog() = DeleteConfirmationDialogFragment().apply {
        arg(ProfileIdArg(EditorCache.editingId))
        key()
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"

        private const val PENDING_ROUTE_OUTBOUND = "routeOutbound"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        super.onCreate(savedInstanceState)
        if (finishIfEditorSessionLost()) return
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        guardUnsavedChanges(::needSave) { UnsavedChangesDialogFragment().apply { key() } }

        // The edit state lives in the in-memory profileCacheStore and dies with
        // the process. On a process-death restore savedInstanceState != null but
        // the cache is empty; re-initialize from the intent extras, or the blank
        // editor would save a garbage rule.
        if (needsEditorInit(savedInstanceState, Key.ROUTE_OUTBOUND)) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            EditorCache.editingId = editingId
            launchEditorInit({ EditorPreferenceFragment() }) {
                if (editingId == 0L) {
                    checked { newRule(intent.getStringExtra(EXTRA_PACKAGE_NAME)) }.init()
                } else {
                    // 读库之后才写缓存：checked 在读完后复查会话
                    val ruleEntity = checked { RuleRepository.getRule(editingId) } ?: return@launchEditorInit false
                    ruleEntity.init()
                }

                // Re-apply a picker result redelivered before this init ran,
                // so the user's selection wins over the entity values.
                editor.pending<Long>(PENDING_ROUTE_OUTBOUND)?.let {
                    EditorCache.routeOutboundRule = it
                    EditorCache.routeOutbound = 3
                }
                true
            }
        }

    }

    override suspend fun save() {

        val editingId = EditorCache.editingId

        // Only block a brand-new empty route; an existing rule left unchanged
        // just exits without saving.
        if (editingId == 0L && !needSave()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        if (editingId == 0L) {
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                setResult(RESULT_OK, Intent())
            }

            RuleRepository.createRule(RuleEntity().apply { serialize() })
        } else if (needSave()) {
            val entity = RuleRepository.getRule(EditorCache.editingId)
            if (entity == null) {
                finish()
                return
            }
            RuleRepository.updateRule(entity.apply { serialize() })
        }
        finish()

    }

}
