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
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.group.isEditableSubscriptionLink
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onLiveActivity
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.bindValidatedPreference
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

    // 选择结果重放得比异步初始化早（选择器在前台时进程死亡）时暂存在 ViewModel 里：
    // 初始化要把它们补回缓存，免得实体里的值盖掉用户刚选的。键见下面的常量
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
        // 用户改了 nameserver 就不再算订阅写入的，订阅撤下该键时不会被清空
        val nameserver = EditorCache.proxyServerNameserver.trim()
        val nameserverEdited = nameserver != proxyServerNameserver
        proxyServerNameserver = nameserver

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = EditorCache.subscriptionLink.trim()
                forceResolve = EditorCache.subscriptionForceResolve
                deduplication = EditorCache.subscriptionDeduplication
                updateWhenConnectedOnly = EditorCache.subscriptionUpdateWhenConnectedOnly
                customUserAgent = EditorCache.subscriptionUserAgent
                autoUpdate = EditorCache.subscriptionAutoUpdate
                autoUpdateDelay = EditorCache.subscriptionAutoUpdateDelay
                if (nameserverEdited) nameserverFromSubscription = false
            }
        }
    }

    fun needSave() = EditorCache.dirty

    // 订阅分组的链接按编辑器的同一条规则检查（见 subscriptionLinkError；空链接可以先建分组再填）
    private fun subscriptionLinkAcceptable(): Boolean =
        EditorCache.groupType != GroupType.SUBSCRIPTION || isEditableSubscriptionLink(EditorCache.subscriptionLink.trim())

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
            findPreference<SwitchPreferenceCompat>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
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
        subscriptionAutoUpdateDelay.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        // 拒绝时给出原因：之前静默不保存，看起来像没点到确定
        subscriptionAutoUpdateDelay.bindValidatedPreference(R.string.auto_update_delay_error) {
            (it.toIntOrNull() ?: 0) >= 15
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
                // 进程级：删除落库，页面随即关闭也要删完，不碰界面
                runOnDefaultDispatcher {
                    GroupRepository.deleteGroup(arg.groupId)
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

        private const val PENDING_FRONT_PROXY = "frontProxy"
        private const val PENDING_LANDING_PROXY = "landingProxy"
        private const val PENDING_SUBSCRIPTION_LINK = "subscriptionLink"
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
        if (needsEditorInit(savedInstanceState, Key.GROUP_TYPE)) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            EditorCache.editingId = editingId
            launchEditorInit({ EditorPreferenceFragment() }) {
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    // 读库之后才写缓存：checked 在读完后复查会话
                    val entity = checked { GroupRepository.getGroup(editingId) } ?: return@launchEditorInit false
                    entity.init()
                }

                // Re-apply a picker result redelivered before this init ran,
                // so the user's selection wins over the entity values.
                editor.pending<Long>(PENDING_FRONT_PROXY)?.let {
                    EditorCache.frontProxy = it
                    EditorCache.frontProxyTmp = 3
                }
                editor.pending<Long>(PENDING_LANDING_PROXY)?.let {
                    EditorCache.landingProxy = it
                    EditorCache.landingProxyTmp = 3
                }
                editor.pending<String>(PENDING_SUBSCRIPTION_LINK)?.let { EditorCache.subscriptionLink = it }
                true
            }
        }

    }

    override suspend fun save() {

        val editingId = EditorCache.editingId
        // 真要写库时才校验链接：没改动就退出的旧分组不被历史遗留的值拦住
        if ((editingId == 0L || needSave()) && !subscriptionLinkAcceptable()) {
            onLiveActivity {
                Toast.makeText(
                    this@GroupSettingsActivity, R.string.invalid_subscription_link_input, Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (editingId == 0L) {
            GroupRepository.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = GroupRepository.getGroup(EditorCache.editingId)
            if (entity == null) {
                onLiveActivity { finish() }
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    EditorCache.groupType == GroupType.SUBSCRIPTION &&
                    // 旧版本保存时不去首尾空白：库里的链接也按去空白后比，只改名之类的保存不当作换了订阅
                    entity.subscription?.link?.trim() == EditorCache.subscriptionLink.trim())
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = "";
                // 链接或类型变了就按新订阅对待：不重置 lastUpdated 的话，
                // 新链接的首次自动更新会按旧链接的时间点被推迟
                entity.subscription?.lastUpdated = 0L
                // 流量与到期信息属于旧订阅，GroupFragment 按这三项显示，一并清零
                entity.subscription?.bytesUsed = 0L
                entity.subscription?.bytesRemaining = 0L
                entity.subscription?.expiryDate = 0L
            }
            GroupRepository.updateGroup(
                entity.apply { serialize() }, preserveSubscriptionRuntime = keepUserInfo
            )
        }

        onLiveActivity { finish() }

    }

    // Registration order fixes the request keys the framework uses to redeliver
    // results, so keep front before landing.
    val selectProfileForAddFront = profilePicker({
        editor.setPending(PENDING_FRONT_PROXY, it)
        EditorCache.frontProxy = it
        EditorCache.frontProxyTmp = 3
    }, { frontProxyPreference })

    val selectProfileForAddLanding = profilePicker({
        editor.setPending(PENDING_LANDING_PROXY, it)
        EditorCache.landingProxy = it
        EditorCache.landingProxyTmp = 3
    }, { landingProxyPreference })

    // A hand-typed content:// URI carries no grant; only a picked document can still
    // be read after process death (auto-updates run in :bg), hence the persistable grant.
    val selectSubscriptionFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // 迟到的回调：页面已销毁或缓存已被别的编辑器接管时，不能再写缓存
        if (uri == null || !holdsEditorSession()) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // provider without persistable grants: the link still works for this process
            Logs.w(e)
        }
        val link = uri.toString()
        // pending first, like the profile pickers: a redelivered result may run
        // before the async re-init, which then re-applies it
        editor.setPending(PENDING_SUBSCRIPTION_LINK, link)
        EditorCache.subscriptionLink = link
        // 进程死亡恢复时 fragment 提交前为 null；它创建时会读 EditorCache 里的值
        subscriptionLinkPreference?.text = link
    }

}
