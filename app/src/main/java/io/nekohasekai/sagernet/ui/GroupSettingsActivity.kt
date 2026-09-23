package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
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
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : EditorActivity(resId) {

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
        EditorCache.groupName = name ?: ""
        EditorCache.groupType = type
        EditorCache.groupOrder = order
        EditorCache.groupIsSelector = isSelector

        EditorCache.frontProxy = frontProxy
        EditorCache.landingProxy = landingProxy
        EditorCache.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        EditorCache.landingProxyTmp = if (landingProxy >= 0) 3 else 0
        EditorCache.proxyServerNameserver = proxyServerNameserver

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        EditorCache.subscriptionLink = subscription.link
        EditorCache.subscriptionForceResolve = subscription.forceResolve
        EditorCache.subscriptionDeduplication = subscription.deduplication
        EditorCache.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        EditorCache.subscriptionUserAgent = subscription.customUserAgent
        EditorCache.subscriptionAutoUpdate = subscription.autoUpdate
        EditorCache.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
    }

    fun ProxyGroup.serialize() {
        name = EditorCache.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = EditorCache.groupType
        order = EditorCache.groupOrder
        isSelector = EditorCache.groupIsSelector

        frontProxy = if (EditorCache.frontProxyTmp == 3) EditorCache.frontProxy else -1
        landingProxy = if (EditorCache.landingProxyTmp == 3) EditorCache.landingProxy else -1
        proxyServerNameserver = EditorCache.proxyServerNameserver.trim()

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = EditorCache.subscriptionLink
                forceResolve = EditorCache.subscriptionForceResolve
                deduplication = EditorCache.subscriptionDeduplication
                updateWhenConnectedOnly = EditorCache.subscriptionUpdateWhenConnectedOnly
                customUserAgent = EditorCache.subscriptionUserAgent
                autoUpdate = EditorCache.subscriptionAutoUpdate
                autoUpdateDelay = EditorCache.subscriptionAutoUpdateDelay
            }
        }
    }

    fun needSave() = EditorCache.dirty

    override fun PreferenceFragmentCompat.createPreferences(
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

        fun updateGroupType(groupType: Int = EditorCache.groupType) {
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

    override fun deleteConfirmationDialog() = DeleteConfirmationDialogFragment().apply {
        arg(GroupIdArg(EditorCache.editingId))
        key()
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        super.onCreate(savedInstanceState)
        if (finishIfEditorSessionLost()) return
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
        if (savedInstanceState == null || EditorCache.profileCacheStore.getString(Key.GROUP_TYPE) == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            EditorCache.editingId = editingId
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
                    EditorCache.frontProxy = it
                    EditorCache.frontProxyTmp = 3
                }
                pendingLandingProxy?.let {
                    EditorCache.landingProxy = it
                    EditorCache.landingProxyTmp = 3
                }
                pendingSubscriptionLink?.let { EditorCache.subscriptionLink = it }

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
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(EditorCache.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    EditorCache.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == EditorCache.subscriptionLink)
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

    // Registration order fixes the request keys the framework uses to redeliver
    // results, so keep front before landing.
    val selectProfileForAddFront = profilePicker({
        pendingFrontProxy = it
        EditorCache.frontProxy = it
        EditorCache.frontProxyTmp = 3
    }, { frontProxyPreference })

    val selectProfileForAddLanding = profilePicker({
        pendingLandingProxy = it
        EditorCache.landingProxy = it
        EditorCache.landingProxyTmp = 3
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
        EditorCache.subscriptionLink = link
        // null before the fragment is committed on a process-death restore; it
        // reads the DataStore value when created
        subscriptionLinkPreference?.text = link
    }

}
