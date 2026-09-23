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
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
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

    // A redelivered activity result (process death while the picker was
    // foreground) may run before the async re-init; init() must re-apply
    // this so the entity values do not overwrite the user's selection.
    @Volatile
    private var pendingRouteOutbound: Long? = null

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
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

    private lateinit var editConfigPreference: EditConfigPreference

    fun needSave(): Boolean {
        return EditorCache.dirty
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.route_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

    val selectProfileForAdd = profilePicker({
        pendingRouteOutbound = it
        EditorCache.routeOutboundRule = it
        EditorCache.routeOutbound = 3
    }, { if (::outbound.isInitialized) outbound else null })

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { (_, _) ->
        // The fragment may not be committed yet on a process-death restore;
        // AppListActivity already wrote EditorCache.routePackages, so skipping
        // the refresh loses nothing.
        if (::apps.isInitialized) {
            apps.postUpdate()
        }
    }

    lateinit var outbound: OutboundPreference
    lateinit var apps: AppListPreference

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!

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
                    ProfileManager.deleteRule(arg.ruleId)
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
        if (savedInstanceState == null || EditorCache.profileCacheStore.getString(Key.ROUTE_OUTBOUND) == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            EditorCache.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                // Re-apply a picker result redelivered before this init ran,
                // so the user's selection wins over the entity values.
                pendingRouteOutbound?.let {
                    EditorCache.routeOutboundRule = it
                    EditorCache.routeOutbound = 3
                }

                // The cache was empty (process death): re-claim the session so
                // later recreations still match this editor's token
                renewEditorSessionToken()

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, EditorPreferenceFragment())
                        .commit()
                }
            }


        }

    }

    override suspend fun saveAndExit() {

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

            ProfileManager.createRule(RuleEntity().apply { serialize() })
        } else if (needSave()) {
            val entity = SagerDatabase.rulesDao.getById(EditorCache.editingId)
            if (entity == null) {
                finish()
                return
            }
            ProfileManager.updateRule(entity.apply { serialize() })
        }
        finish()

    }

}
