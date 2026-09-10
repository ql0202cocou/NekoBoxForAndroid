package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.database.renewEditorSession
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.parcelize.Parcelize

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId), OnPreferenceDataStoreChangeListener {

    /**
     * Whether the preference list is the bottom-most scrollable view of the screen. Chain
     * settings put a separate node list below it, which then owns the bottom inset instead.
     */
    open val preferenceListReachesBottom = true

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                // resolve on the main thread: the dialog is detached right after
                // this click, and requireActivity() on the Default dispatcher
                // would race it
                val activity = requireActivity() as ProfileSettingsActivity<*>
                runOnDefaultDispatcher {
                    activity.saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    private val editorReady = CompletableDeferred<Unit>()

    protected suspend fun awaitEditorReady() = editorReady.await()

    // Token proving this Activity still owns the shared profileCacheStore;
    // persisted so a restore can detect another editor taking it over.
    private var editorSession = 0L

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EDITOR_SESSION, editorSession)
    }

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(DataStore.editingId) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Session ownership of the shared profileCacheStore (see
        // EditorSession.kt): a first creation resets the cache and claims it,
        // a restore verifies the claim. The reset must run before editingId
        // or any other key is written.
        var sessionTakenOver = false
        if (savedInstanceState == null) {
            editorSession = claimEditorSession()
        } else {
            editorSession = savedInstanceState.getLong(STATE_EDITOR_SESSION, 0L)
            sessionTakenOver = checkEditorSession(editorSession) == EditorSessionState.TAKEN_OVER
        }
        // Before super.onCreate(): a restored MyPreferenceFragmentCompat runs
        // createPreferences() from there, and the StandardV2Ray editor reads the
        // lazy proxyEntity in it — with the in-memory cache gone after process
        // death editingId would still be 0 and null would be cached for good.
        if (!sessionTakenOver) {
            DataStore.editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        }
        super.onCreate(savedInstanceState)
        if (sessionTakenOver) {
            // Another top-level editor claimed the cache meanwhile; saving
            // from here would write into the wrong profile.
            Toast.makeText(this, R.string.editor_session_lost, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        guardUnsavedChanges({ DataStore.dirty }) { UnsavedChangesDialogFragment().apply { key() } }

        // The edit state lives in the in-memory profileCacheStore and dies with
        // the process. On a process-death restore savedInstanceState != null but
        // the cache is empty; re-initialize from the intent extras, or the blank
        // editor would save a garbage profile.
        if (savedInstanceState == null || DataStore.profileCacheStore.getString(Key.PROFILE_CORE) == null) {
            val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            DataStore.editingId = editingId
            lifecycleScope.launch(Dispatchers.Default) {
                if (editingId == 0L) {
                    DataStore.editingGroup = DataStore.selectedGroupForImport()
                    createEntity().applyDefaultValues().init()
                    DataStore.profileCacheStore.putString(
                        Key.PROFILE_CORE, ProxyEntity.CORE_AUTO.toString()
                    )
                } else {
                    if (proxyEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        editorReady.cancel()
                        return@launch
                    }
                    DataStore.editingGroup = proxyEntity!!.groupId
                    (proxyEntity!!.requireBean() as T).init()
                    DataStore.profileCacheStore.putString(
                        Key.PROFILE_CORE, proxyEntity!!.core.toString()
                    )
                }

                // The cache was empty (process death): re-claim the session so
                // later recreations still match this editor's token
                renewEditorSession(editorSession)

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commitNow()
                    editorReady.complete(Unit)
                }
            }.invokeOnCompletion {
                if (!editorReady.isCompleted) editorReady.cancel()
            }
        } else {
            editorReady.complete(Unit)
        }

    }

    protected open fun validateEditor(): String? = null

    open suspend fun saveAndExit() {
        awaitEditorReady()
        val canSave = onMainDispatcher {
            val screen = child?.preferenceScreen ?: return@onMainDispatcher false
            validateEditor()?.let { message ->
                Toast.makeText(this@ProfileSettingsActivity, message, Toast.LENGTH_LONG).show()
                return@onMainDispatcher false
            }
            // every protocol editor with a server field shares this check
            val address = screen.findPreference<EditTextPreference>(Key.SERVER_ADDRESS)
            if (address != null && address.isVisible && address.isEnabled &&
                !isServerAddress(DataStore.serverAddress)
            ) {
                Toast.makeText(
                    this@ProfileSettingsActivity, R.string.server_address_error, Toast.LENGTH_LONG
                ).show()
                return@onMainDispatcher false
            }
            val invalid = screen.findInvalidIntegerPreference()
            if (invalid != null) {
                Toast.makeText(
                    this@ProfileSettingsActivity,
                    "${invalid.title}: ${getString(R.string.integer_range_error, invalid.extras.getInt(INTEGER_MIN), invalid.extras.getInt(INTEGER_MAX))}",
                    Toast.LENGTH_LONG
                ).show()
            }
            invalid == null
        }
        if (!canSave) return

        val editingId = DataStore.editingId
        // entity-level field, not part of the bean; seeded in onCreate
        val profileCore = DataStore.profileCacheStore.getString(Key.PROFILE_CORE)
            ?.toIntOrNull() ?: ProxyEntity.CORE_AUTO
        if (editingId == 0L) {
            val editingGroup = DataStore.editingGroup
            ProfileManager.createProfile(
                editingGroup, createEntity().apply { serialize() }, profileCore
            )
        } else {
            if (proxyEntity == null) {
                finish()
                return
            }
            if (proxyEntity!!.id == DataStore.selectedProxy) {
                SagerNet.stopService()
            }
            ProfileManager.updateProfile(proxyEntity!!.apply {
                core = profileCore
                (requireBean() as T).serialize()
            })
        }
        finish()

    }

    // a getter, not lazy: the fragment is committed after an async DB read, and a
    // menu click before that would cache null (or, after process death, the
    // restored fragment that init() then replaces) for good
    val child get() = supportFragmentManager.findFragmentById(R.id.settings) as? MyPreferenceFragmentCompat

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_move)?.apply {
            if (DataStore.editingId != 0L // not new profile
                && SagerDatabase.groupDao.getById(DataStore.editingGroup)?.type == GroupType.BASIC // not in subscription group
                && SagerDatabase.groupDao.allGroups()
                    .filter { it.type == GroupType.BASIC }.size > 1 // have other basic group
            ) isVisible = true
        }
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && DataStore.editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        // shared menu item; they need an existing entity, hide them for a new
        // profile (the click body would silently no-op on a null proxyEntity).
        // DataStore.editingId is set synchronously in onCreate, so this is
        // already stable when the menu is created.
        val hasEntity = DataStore.editingId != 0L
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = hasEntity
        menu.findItem(R.id.action_custom_config_json)?.isVisible = hasEntity
        return true
    }

    // the fragment may not be committed yet when the menu is clicked
    override fun onOptionsItemSelected(item: MenuItem) = child?.onMenuItemSelected(item) == true

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
            // input-time counterpart of the save check in saveAndExit(); null for
            // editors without a server field (chain, custom config)
            findPreference<EditTextPreference>(Key.SERVER_ADDRESS)
                ?.bindValidatedPreference(R.string.server_address_error, ::isServerAddress)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            listView.padForSystemBars(bottom = activity?.preferenceListReachesBottom ?: true)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                // Only clear dirty on first creation; resetting it after a
                // recreation (rotation) would silently drop unsaved edits.
                if (savedInstanceState == null) {
                    DataStore.dirty = false
                }
                DataStore.profileCacheStore.registerChangeListener(this)
                // Re-attach the custom JSON callbacks: after a recreation the
                // result of ConfigEditActivity arrives at this new instance
                // while the callbacks set by the menu handler died with the
                // old one.
                proxyEntity?.requireBean()?.let { bean ->
                    callbackCustom = { bean.customConfigJson = it }
                    callbackCustomOutbound = { bean.customOutboundJson = it }
                }
            }
        }

        var callbackCustom: ((String) -> Unit)? = null
        var callbackCustomOutbound: ((String) -> Unit)? = null

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustom?.let { it(DataStore.serverCustom) }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustomOutbound?.let { it(DataStore.serverCustomOutbound) }
        }

        @SuppressLint("CheckResult")
        fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(
                            ProfileIdArg(
                                DataStore.editingId, DataStore.editingGroup
                            )
                        )
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            R.id.action_custom_outbound_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    // seeding the editor is not an edit: keep dirty as it was
                    val dirty = DataStore.dirty
                    DataStore.serverCustomOutbound = bean.customOutboundJson
                    DataStore.dirty = dirty
                    callbackCustomOutbound = { bean.customOutboundJson = it }
                    resultCallbackCustomOutbound.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
                        })
                }
                true
            }

            R.id.action_custom_config_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    val dirty = DataStore.dirty
                    DataStore.serverCustom = bean.customConfigJson
                    DataStore.dirty = dirty
                    callbackCustom = { bean.customConfigJson = it }
                    resultCallbackCustom.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM)
                        })
                }
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            activity, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        context, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>

                fun showMoveDialog() {
                    val view = LinearLayout(context).apply {
                        val ent = activity.proxyEntity!!
                        orientation = LinearLayout.VERTICAL

                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.BASIC && it.id != ent.groupId }
                            .forEach { group ->
                                LayoutGroupItemBinding.inflate(layoutInflater, this, true).apply {
                                    edit.isVisible = false
                                    options.isVisible = false
                                    groupName.text = group.displayName()
                                    groupUpdate.text = getString(R.string.move)
                                    groupUpdate.setOnClickListener {
                                        runOnDefaultDispatcher {
                                            val oldGroupId = ent.groupId
                                            val newGroupId = group.id
                                            val moved = ProfileManager.moveProfile(ent.id, newGroupId)
                                                ?: return@runOnDefaultDispatcher
                                            activity.proxyEntity?.groupId = moved.groupId
                                            GroupManager.postUpdate(oldGroupId) // reload
                                            GroupManager.postUpdate(newGroupId)
                                            DataStore.editingGroup = newGroupId // post switch animation
                                            runOnMainDispatcher {
                                                activity.finish()
                                            }
                                        }
                                    }
                                }
                            }
                    }
                    val scrollView = ScrollView(context).apply {
                        addView(view)
                    }
                    MaterialAlertDialogBuilder(activity).setView(scrollView).show()
                }

                if (DataStore.dirty) {
                    // Moving finishes the editor without applying the edits;
                    // confirm like the back guard instead of silently dropping them.
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.unsaved_changes_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher { activity.saveAndExit() }
                        }
                        .setNegativeButton(R.string.no) { _, _ ->
                            showMoveDialog() // discard the edits and move
                        }
                        .setNeutralButton(android.R.string.cancel, null)
                        .show()
                } else {
                    showMoveDialog()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}

fun EditTextPreference.bindPortPreference(): EditTextPreference {
    bindIntegerPreference(1, 65535)
    setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
    return this
}

private const val INTEGER_MIN = "editor.integer.min"
private const val INTEGER_MAX = "editor.integer.max"
private const val INTEGER_ALLOW_EMPTY = "editor.integer.allowEmpty"

private fun Preference.findInvalidIntegerPreference(): EditTextPreference? {
    // Categories report isEnabled=false even when their children are enabled.
    val disabled = if (this is PreferenceCategory) shouldDisableDependents() else !isEnabled
    if (!isVisible || disabled) return null
    if (this is EditTextPreference && extras.containsKey(INTEGER_MIN)) {
        val value = DataStore.profileCacheStore.getString(key)
        if (!io.nekohasekai.sagernet.database.preference.isIntegerInRange(
                value, extras.getInt(INTEGER_MIN), extras.getInt(INTEGER_MAX),
                extras.getBoolean(INTEGER_ALLOW_EMPTY)
            )) return this
    }
    if (this is PreferenceGroup) {
        for (index in 0 until preferenceCount) {
            getPreference(index).findInvalidIntegerPreference()?.let { return it }
        }
    }
    return null
}

fun EditTextPreference.bindIntegerPreference(
    min: Int = 0, max: Int = Int.MAX_VALUE, allowEmpty: Boolean = false,
): EditTextPreference {
    extras.putInt(INTEGER_MIN, min)
    extras.putInt(INTEGER_MAX, max)
    extras.putBoolean(INTEGER_ALLOW_EMPTY, allowEmpty)
    setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    setOnPreferenceChangeListener { _, value ->
        val valid = io.nekohasekai.sagernet.database.preference.isIntegerInRange(value, min, max, allowEmpty)
        if (!valid) {
            Toast.makeText(context, context.getString(R.string.integer_range_error, min, max), Toast.LENGTH_LONG).show()
        }
        valid
    }
    return this
}

fun EditTextPreference.bindPasswordPreference(): EditTextPreference {
    summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
    return this
}

// Rejects an edit that fails `valid` with a toast; pair it with validateEditor() so
// values that were imported or cached before the check existed are caught on save.
fun EditTextPreference.bindValidatedPreference(
    @StringRes message: Int, valid: (String) -> Boolean,
): EditTextPreference {
    setOnPreferenceChangeListener { _, value ->
        val ok = value is String && valid(value)
        if (!ok) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        ok
    }
    return this
}
