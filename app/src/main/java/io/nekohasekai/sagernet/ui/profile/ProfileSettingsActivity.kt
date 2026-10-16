package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.fmt.coreSelectable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.CoreDecision
import io.nekohasekai.sagernet.fmt.decideCore
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.EditorActivity
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import io.nekohasekai.sagernet.database.EditorCache

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : EditorActivity(resId) {

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileRepository.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    override fun deleteConfirmationDialog() = DeleteConfirmationDialogFragment().apply {
        arg(ProfileIdArg(EditorCache.editingId, EditorCache.editingGroup))
        key()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    private val editorReady = CompletableDeferred<Unit>()

    protected suspend fun awaitEditorReady() = editorReady.await()

    // 正在编辑的那一行，新建节点时为 null。onCreate 里在后台载入一次，主线程不查库：载入完成前这里是 null，
    // 要用它的地方先 awaitProxyEntity()
    @Volatile
    var proxyEntity: ProxyEntity? = null
        private set
    private val proxyEntityLoad = CompletableDeferred<ProxyEntity?>()

    suspend fun awaitProxyEntity(): ProxyEntity? = proxyEntityLoad.await()

    // 不挂在页面生命周期上：保存跑在 appScope 里等这个结果，页面在载入途中被销毁也不能让它一直等。
    // 读库抛出的异常交给每个等待方（与原先 lazy 求值时抛给读取方相同）
    private fun startProxyEntityLoad(editingId: Long) {
        if (editingId == 0L) {
            proxyEntityLoad.complete(null)
            return
        }
        runOnDefaultDispatcher {
            try {
                val entity = ProfileRepository.getProfile(editingId)
                proxyEntity = entity
                proxyEntityLoad.complete(entity)
            } catch (e: Throwable) {
                proxyEntityLoad.completeExceptionally(e)
            }
        }
    }

    // action_move 的可见性要查库得出；onCreate 末尾异步预取后由
    // invalidateOptionsMenu 应用，默认值与菜单 XML 一致（隐藏）
    private var canMoveProfile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        // 在 super.onCreate() 之前写：恢复的 MyPreferenceFragmentCompat 从那里执行 createPreferences()，
        // 进程死亡后内存里的缓存已清空，不先写的话 editingId 仍是 0
        if (ownsEditorSession) {
            EditorCache.editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        }
        super.onCreate(savedInstanceState)
        if (finishIfEditorSessionLost()) {
            proxyEntityLoad.complete(null)
            return
        }
        startProxyEntityLoad(EditorCache.editingId)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        guardUnsavedChanges({ EditorCache.dirty }) { UnsavedChangesDialogFragment().apply { key() } }

        // The edit state lives in the in-memory profileCacheStore and dies with
        // the process. On a process-death restore savedInstanceState != null but
        // the cache is empty; re-initialize from the intent extras, or the blank
        // editor would save a garbage profile.
        if (needsEditorInit(savedInstanceState, Key.PROFILE_CORE)) {
            val editingId = EditorCache.editingId
            lifecycleScope.launch(Dispatchers.Default) {
                if (!holdsEditorSession()) return@launch
                if (editingId == 0L) {
                    EditorCache.editingGroup = GroupRepository.selectedGroupForImport()
                    createEntity().applyDefaultValues().init()
                    EditorCache.profileCacheStore.putString(
                        Key.PROFILE_CORE, ProxyEntity.CORE_AUTO.toString()
                    )
                } else {
                    val entity = awaitProxyEntity()
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        editorReady.cancel()
                        return@launch
                    }
                    EditorCache.editingGroup = entity.groupId
                    (entity.requireBean() as T).init()
                    EditorCache.profileCacheStore.putString(
                        Key.PROFILE_CORE, entity.core.toString()
                    )
                }

                // 提交前再校验会话归属，并重新认领缓存（进程死亡后缓存被初始化重写）
                if (commitEditorFragment(MyPreferenceFragmentCompat(), now = true)) {
                    editorReady.complete(Unit)
                }
            }.invokeOnCompletion {
                if (!editorReady.isCompleted) editorReady.cancel()
            }
        } else {
            editorReady.complete(Unit)
        }

        // action_move 可见性条件要查库，等初始化写好 editingGroup 后异步预取
        // 再刷新菜单；节点已不存在或初始化失败时 editorReady 被取消，这里随之结束
        // （会话被接管时 onCreate 早已返回，走不到这里）
        lifecycleScope.launch(Dispatchers.Default) {
            awaitEditorReady()
            canMoveProfile = EditorCache.editingId != 0L // 非新建
                    && GroupRepository.getGroup(EditorCache.editingGroup)?.type == GroupType.BASIC // 不在订阅组
                    && GroupRepository.getAllGroups()
                .filter { it.type == GroupType.BASIC }.size > 1 // 还有其他普通分组
            onMainDispatcher { invalidateOptionsMenu() }
        }

    }

    protected open fun validateEditor(): String? = null

    // 实体级字段，不在 bean 里；onCreate 时写入
    protected fun editorCore() = EditorCache.profileCacheStore.getString(Key.PROFILE_CORE)
        ?.toIntOrNull() ?: ProxyEntity.CORE_AUTO

    // 用编辑器当前内容拼一个临时 bean（serialize 逐字段读缓存库），给选核判定用，不落库
    protected fun editorBean(): T = createEntity().apply { serialize() }.applyDefaultValues()

    // 按能力表判定这个 bean 配上 core 的结果（D14）；全局「允许不安全」取 DataStore 当前值
    protected fun decideEditorCore(bean: T, core: Int): CoreDecision =
        decideCore(ProxyEntity().putBean(bean).type, core, bean, DataStore.globalAllowInsecure)

    override suspend fun save() {
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
                !isServerAddress(EditorCache.serverAddress)
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

        // 保存时按能力表拦截（D14）：手动选的核心有冲突、或自动选核找不到能完整承载的核心，就列出冲突字段，
        // 不保存。用编辑器当前内容拼临时 bean 判断，放在整数校验之后、主线程之外（serialize 逐字段读库）
        val probe = editorBean()
        // 能选核的协议才用核心选项的值；不能选的（HTTP 与其它协议）一律保存为自动，
        // 不把打开时读到的旧值（例如别的协议留下的手动值）原样写回
        val profileCore = if (coreSelectable(ProxyEntity().putBean(probe).type, probe)) editorCore() else ProxyEntity.CORE_AUTO
        val decision = decideEditorCore(probe, profileCore)
        if (decision is CoreDecision.Rejected) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@ProfileSettingsActivity).setTitle(R.string.error_title)
                    .setMessage(coreRejectionMessage(decision, probe))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            return
        }
        val editingId = EditorCache.editingId
        if (editingId == 0L) {
            val editingGroup = EditorCache.editingGroup
            ProfileRepository.createProfile(
                editingGroup, createEntity().apply { serialize() }, profileCore
            )
        } else {
            val entity = awaitProxyEntity()
            if (entity == null) {
                finish()
                return
            }
            if (entity.id == DataStore.selectedProxy) {
                SagerNet.stopService()
            }
            val saved = ProfileRepository.updateProfile(entity.apply {
                core = profileCore
                (requireBean() as T).serialize()
            })
            // proxyEntity 是打开编辑器时读的缓存：节点在编辑期间被删掉时更新 0 行，
            // 不提示的话修改就悄悄丢了
            if (!saved) onMainDispatcher {
                Toast.makeText(this@ProfileSettingsActivity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
            }
        }
        onSaved()
        finish()

    }

    /** 保存写完库、关闭页面之前调用一次；放弃修改、校验不过、会话已失效都不会走到这里。 */
    protected open fun onSaved() {
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        // action_move 可见性取 onCreate 预取的结果，勿在主线程查库
        menu.findItem(R.id.action_move)?.isVisible = canMoveProfile
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && EditorCache.editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        // shared menu item; they need an existing entity, hide them for a new
        // profile (the click body would silently no-op on a null proxyEntity).
        // EditorCache.editingId is set synchronously in onCreate, so this is
        // already stable when the menu is created.
        val hasEntity = EditorCache.editingId != 0L
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = hasEntity
        menu.findItem(R.id.action_custom_config_json)?.isVisible = hasEntity
        return true
    }

    class MyPreferenceFragmentCompat : EditorPreferenceFragment() {

        // 基类 activity 的具体类型，同样在 createPreferences 抛异常时为 null
        private val profileActivity get() = activity as ProfileSettingsActivity<*>?

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            super.onCreatePreferences(savedInstanceState, rootKey)
            // input-time counterpart of the save check in saveAndExit(); null for
            // editors without a server field (chain, custom config)
            findPreference<EditTextPreference>(Key.SERVER_ADDRESS)
                ?.bindValidatedPreference(R.string.server_address_error, ::isServerAddress)
            // 各协议编辑器共用的端口校验；hysteria 用 serverPorts，链式与自定义配置没有端口，均为 null
            findPreference<EditTextPreference>(Key.SERVER_PORT)?.bindPortPreference()
        }

        // ConfigEditActivity 改完的 JSON 写回正在编辑的那一行的 bean，保存时随它一起写库。重建（旋转、进程死亡）后
        // 结果会送到新实例，这时节点可能还在后台载入，等载入完成再写；新建节点没有这两项菜单，结果直接丢弃
        private fun applyEditedJson(json: String, apply: (AbstractBean, String) -> Unit) {
            val activity = profileActivity ?: return
            lifecycleScope.launch {
                activity.awaitProxyEntity()?.requireBean()?.let { apply(it, json) }
            }
        }

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            applyEditedJson(EditorCache.serverCustom) { bean, json -> bean.customConfigJson = json }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            applyEditedJson(EditorCache.serverCustomOutbound) { bean, json -> bean.customOutboundJson = json }
        }

        // 用正在编辑的那一行的 bean 打开 JSON 编辑页；节点还在后台载入时等它（菜单回调在主线程，不能查库）
        private fun launchJsonEditor(
            key: String,
            seed: (AbstractBean) -> Unit,
            launcher: ActivityResultLauncher<Intent>,
        ) {
            val activity = profileActivity ?: return
            lifecycleScope.launch {
                val bean = activity.awaitProxyEntity()?.requireBean() ?: return@launch
                // seeding the editor is not an edit: keep dirty as it was
                val dirty = EditorCache.dirty
                seed(bean)
                EditorCache.dirty = dirty
                launcher.launch(Intent(requireContext(), ConfigEditActivity::class.java).apply {
                    putExtra("key", key)
                })
            }
        }

        @SuppressLint("CheckResult")
        override fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_custom_outbound_json -> {
                launchJsonEditor(Key.SERVER_CUSTOM_OUTBOUND, {
                    EditorCache.serverCustomOutbound = it.customOutboundJson
                }, resultCallbackCustomOutbound)
                true
            }

            R.id.action_custom_config_json -> {
                launchJsonEditor(Key.SERVER_CUSTOM, {
                    EditorCache.serverCustom = it.customConfigJson
                }, resultCallbackCustom)
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                // proxyEntity 是打开编辑器时读的缓存，编辑期间节点可能已被订阅更新删掉：
                // 在后台重查一次，免得固定一个指向已删节点的快捷方式
                lifecycleScope.launch(Dispatchers.Default) {
                    val ent = activity.awaitProxyEntity()?.let { ProfileRepository.getProfile(it.id) }
                    onMainDispatcher {
                        if (ent == null) {
                            Toast.makeText(activity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
                            return@onMainDispatcher
                        }
                        val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                            .setShortLabel(ent.displayName())
                            .setLongLabel(ent.displayName())
                            .setIcon(
                                IconCompat.createWithResource(
                                    activity, R.drawable.ic_qu_shadowsocks_launcher
                                )
                            ).setIntent(Intent(
                                activity, QuickToggleShortcut::class.java
                            ).apply {
                                action = Intent.ACTION_MAIN
                                putExtra("profile", ent.id)
                            }).build()
                        ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
                    }
                }
                // requestPinShortcut 在不支持的 launcher 上返回 false，菜单事件仍算已处理
                true
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>

                fun showMoveDialog() {
                    lifecycleScope.launch(Dispatchers.Default) {
                        // 编辑期间节点可能被订阅更新删掉；与 saveAndExit 的判空同理。节点在后台载入，还没载完时等它
                        val ent = activity.awaitProxyEntity()
                        if (ent == null) {
                            onMainDispatcher {
                                Toast.makeText(activity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                        val groups = GroupRepository.getAllGroups()
                            .filter { it.type == GroupType.BASIC && it.id != ent.groupId }
                        onMainDispatcher {
                            val view = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL

                                groups.forEach { group ->
                                    LayoutGroupItemBinding.inflate(layoutInflater, this, true).apply {
                                        edit.isVisible = false
                                        options.isVisible = false
                                        groupName.text = group.displayName()
                                        // 更新入口在卡片里是图标；移动对话框改为点整张卡片
                                        groupUpdate.isVisible = false
                                        // 对话框里没有进度与状态文字，去掉它们的占位，卡片只留名称一行
                                        subscriptionUpdateProgress.isGone = true
                                        groupStatusRow.isGone = true
                                        root.setOnClickListener {
                                            runOnDefaultDispatcher {
                                                val oldGroupId = ent.groupId
                                                val newGroupId = group.id
                                                val moved = ProfileRepository.moveProfile(ent.id, newGroupId)
                                                    ?: return@runOnDefaultDispatcher
                                                activity.proxyEntity?.groupId = moved.groupId
                                                GroupRepository.postUpdate(oldGroupId) // reload
                                                GroupRepository.postUpdate(newGroupId)
                                                // 移动期间缓存可能已被别的编辑器接管：节点照样移动，只是不再往缓存里写
                                                if (activity.holdsEditorSession()) {
                                                    EditorCache.editingGroup = newGroupId // post switch animation
                                                }
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
                            MaterialAlertDialogBuilder(activity).setTitle(R.string.move)
                                .setView(scrollView).show()
                        }
                    }
                }

                if (EditorCache.dirty) {
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

            else -> super.onMenuItemSelected(item)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                // 固定 8 个圆点：圆点数若等于密码长度，旁观者能直接数出长度
                "\u2022".repeat(8)
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
        val value = EditorCache.profileCacheStore.getString(key)
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

// Every pin-capable protocol editor shares one rule: blank means no pinning, and
// mihomo's only consumer of the value hex-decodes it into 32 bytes.
fun EditTextPreference.bindCertificateFingerprintPreference(): EditTextPreference =
    bindValidatedPreference(R.string.certificate_fingerprint_error) {
        it.isBlank() || isCertificateFingerprint(it)
    }

// Save-time half of bindCertificateFingerprintPreference(), for values that were
// imported or cached before the check existed. Null when the pin is acceptable.
fun Context.certificateFingerprintError(value: String): String? =
    if (value.isBlank() || isCertificateFingerprint(value)) null
    else getString(R.string.certificate_fingerprint_error)
