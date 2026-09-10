package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    // null until the preference fragment is committed; a redelivered picker
    // result can land before that
    private var frontProxyPreference: OutboundPreference? = null
    private var landingProxyPreference: OutboundPreference? = null
    private var subscriptionLinkPreference: EditTextPreference? = null

    // A redelivered activity result (process death while the picker was
    // foreground) may run before the async re-init; init() must re-apply
    // these so the entity values do not overwrite the user's selection.
    @Volatile
    private var pendingFrontProxy: Long? = null

    @Volatile
    private var pendingLandingProxy: Long? = null

    @Volatile
    private var pendingSubscriptionLink: String? = null

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        DataStore.landingProxyTmp = if (landingProxy >= 0) 3 else 0
        DataStore.proxyServerNameserver = proxyServerNameserver

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector

        frontProxy = if (DataStore.frontProxyTmp == 3) DataStore.frontProxy else -1
        landingProxy = if (DataStore.landingProxyTmp == 3) DataStore.landingProxy else -1
        proxyServerNameserver = DataStore.proxyServerNameserver.trim()

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
            }
        }
    }

    fun needSave(): Boolean {
        if (!DataStore.dirty) return false
        return true
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference<OutboundPreference>(Key.GROUP_FRONT_PROXY)!!.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddFront.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                            .putExtra(ProfileSelectActivity.EXTRA_NO_CHAIN, true)
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference<OutboundPreference>(Key.GROUP_LANDING_PROXY)!!.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == "3") {
                    selectProfileForAddLanding.launch(
                        Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
                            .putExtra(ProfileSelectActivity.EXTRA_NO_CHAIN, true)
                    )
                    false
                } else {
                    true
                }
            }
        }

        subscriptionLinkPreference = findPreference(Key.SUBSCRIPTION_LINK)
        findPreference<Preference>(Key.SUBSCRIPTION_LINK_FILE)!!.setOnPreferenceClickListener {
            try {
                selectSubscriptionFile.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this@GroupSettingsActivity, R.string.file_manager_missing, Toast.LENGTH_SHORT
                ).show()
            }
            true
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        // 每行一个 DNS 地址
        findPreference<EditTextPreference>(Key.PROXY_SERVER_NAMESERVER)!!.apply {
            setOnBindEditTextListener { editText ->
                editText.setSingleLine(false)
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.minLines = 2
                editText.setSelection(editText.text.length)
            }
        }

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                // resolve on the main thread: the dialog is detached right after
                // this click, and requireActivity() on the Default dispatcher
                // would race it
                val activity = requireActivity() as GroupSettingsActivity
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
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    @SuppressLint("CommitTransaction")
    // Token proving this Activity still owns the shared profileCacheStore;
    // persisted so a restore can detect another editor taking it over.
    private var editorSession = 0L

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EDITOR_SESSION, editorSession)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Session ownership of the shared profileCacheStore (see
        // EditorSession.kt): a first creation resets the cache and claims it,
        // a restore verifies the claim.
        var sessionTakenOver = false
        if (savedInstanceState == null) {
            editorSession = claimEditorSession()
        } else {
            editorSession = savedInstanceState.getLong(STATE_EDITOR_SESSION, 0L)
            sessionTakenOver = checkEditorSession(editorSession) == EditorSessionState.TAKEN_OVER
        }
        super.onCreate(savedInstanceState)
        if (sessionTakenOver) {
            // Another top-level editor claimed the cache meanwhile; saving
            // from here would write into the wrong group.
            Toast.makeText(this, R.string.editor_session_lost, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        guardUnsavedChanges(::needSave) { UnsavedChangesDialogFragment().apply { key() } }

        // The edit state lives in the in-memory profileCacheStore and dies with
        // the process. On a process-death restore savedInstanceState != null but
        // the cache is empty; re-initialize from the intent extras, or the blank
        // editor would save a garbage group.
        if (savedInstanceState == null || DataStore.profileCacheStore.getString(Key.GROUP_TYPE) == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                // Re-apply a picker result redelivered before this init ran,
                // so the user's selection wins over the entity values.
                pendingFrontProxy?.let {
                    DataStore.frontProxy = it
                    DataStore.frontProxyTmp = 3
                }
                pendingLandingProxy?.let {
                    DataStore.landingProxy = it
                    DataStore.landingProxyTmp = 3
                }
                pendingSubscriptionLink?.let { DataStore.subscriptionLink = it }

                // The cache was empty (process death): re-claim the session so
                // later recreations still match this editor's token
                renewEditorSession(editorSession)

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                }
            }

        }

    }

    suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink)
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = "";
                // 链接或类型变了就按新订阅对待：不重置 lastUpdated 的话，
                // 新链接的首次自动更新会按旧链接的时间点被推迟
                entity.subscription?.lastUpdated = 0L
            }
            GroupManager.updateGroup(
                entity.apply { serialize() }, preserveSubscriptionRuntime = keepUserInfo
            )
        }

        finish()

    }

    // a getter, not lazy: the fragment is committed after an async DB read, and a
    // menu click before that would cache null for good
    val child get() = supportFragmentManager.findFragmentById(R.id.settings) as? MyPreferenceFragmentCompat

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
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

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
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
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            listView.padForSystemBars()

            activity?.apply {
                // Only clear dirty on first creation; resetting it after a
                // recreation (rotation) would silently drop unsaved edits.
                if (savedInstanceState == null) {
                    DataStore.dirty = false
                }
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
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

            else -> false
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

    private fun profilePicker(
        onSelected: (Long) -> Unit,
        preference: () -> OutboundPreference?,
    ) = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            // onSelected sets the pending value before the DataStore writes, so
            // the re-init either re-applies it (callback ran first) or loses to
            // these writes.
            onSelected(profile.id)
            onMainDispatcher {
                // The fragment may not be committed yet on a process-death
                // restore; it reads the DataStore values when created.
                preference()?.value = "3"
            }
        }
    }

    // Registration order fixes the request keys the framework uses to redeliver
    // results, so keep front before landing.
    val selectProfileForAddFront = profilePicker({
        pendingFrontProxy = it
        DataStore.frontProxy = it
        DataStore.frontProxyTmp = 3
    }, { frontProxyPreference })

    val selectProfileForAddLanding = profilePicker({
        pendingLandingProxy = it
        DataStore.landingProxy = it
        DataStore.landingProxyTmp = 3
    }, { landingProxyPreference })

    // A hand-typed content:// URI carries no grant; only a picked document can still
    // be read after process death (auto-updates run in :bg), hence the persistable grant.
    val selectSubscriptionFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // provider without persistable grants: the link still works for this process
            Logs.w(e)
        }
        val link = uri.toString()
        // pending first, like the profile pickers: a redelivered result may run
        // before the async re-init, which then re-applies it
        pendingSubscriptionLink = link
        DataStore.subscriptionLink = link
        // null before the fragment is committed on a process-death restore; it
        // reads the DataStore value when created
        subscriptionLinkPreference?.text = link
    }

}
