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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
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
import io.nekohasekai.sagernet.ui.InlineValidation
import io.nekohasekai.sagernet.ui.InlineValidations
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
                // 进程级：删除落库，页面随即关闭也要删完，不碰界面
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

    // 等初始化把实体写进缓存；节点已不存在或会话失效时抛 CancellationException，等它的协程随之结束
    protected suspend fun awaitEditorReady() = editor.awaitInit()

    // 正在编辑的那一行，新建节点时为 null。在 ViewModel 里后台载入一次（旋转不重读），主线程不查库：
    // 载入完成前这里是 null，要用它的地方先 awaitProxyEntity()
    val proxyEntity: ProxyEntity? get() = editor.proxyEntity

    suspend fun awaitProxyEntity(): ProxyEntity? = editor.awaitProxyEntity()

    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        // 在 super.onCreate() 之前写：恢复的 MyPreferenceFragmentCompat 从那里执行 createPreferences()，
        // 进程死亡后内存里的缓存已清空，不先写的话 editingId 仍是 0
        if (ownsEditorSession) {
            EditorCache.editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        }
        super.onCreate(savedInstanceState)
        if (finishIfEditorSessionLost()) {
            editor.loadProxyEntity(0L)
            return
        }
        editor.loadProxyEntity(EditorCache.editingId)
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
            // 读库与写缓存在 ViewModel 里只跑一次；节点不存在（load 返回 false）、会话失效、页面销毁
            // 都不会提交 Fragment，等初始化的协程随之结束
            launchEditorInit({ MyPreferenceFragmentCompat() }, now = true) {
                if (editingId == 0L) {
                    EditorCache.editingGroup = checked { GroupRepository.selectedGroupForImport() }
                    createEntity().applyDefaultValues().init()
                    EditorCache.profileCacheStore.putString(
                        Key.PROFILE_CORE, ProxyEntity.CORE_AUTO.toString()
                    )
                } else {
                    val entity = checked { awaitProxyEntity() } ?: return@launchEditorInit false
                    EditorCache.editingGroup = entity.groupId
                    (entity.requireBean() as T).init()
                    EditorCache.profileCacheStore.putString(
                        Key.PROFILE_CORE, entity.core.toString()
                    )
                }
                true
            }
        } else {
            editor.markInitDone()
        }

        // action_move 可见性条件要查库，等初始化写好 editingGroup 后在 ViewModel 里异步预取一次（旋转不重算），
        // 算好后由这里订阅并刷新菜单（会话被接管时 onCreate 早已返回，走不到这里）
        editor.prefetchCanMoveProfile()
        lifecycleScope.launch {
            editor.canMoveProfile.drop(1).collect { invalidateOptionsMenu() }
        }

    }

    protected open fun validateEditor(): String? = null

    // 偏好屏幕里有服务器地址项（链式、自定义配置没有）；Fragment 创建时写入，页面销毁后的保存用它决定是否校验地址
    var hasServerAddressField = false

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
            // 页面已销毁（保存途中旋转、被系统回收）后没有偏好屏幕可查，也不能再弹提示：保存是进程级的，
            // 要写完才对得起用户点的「保存」，所以只跳过依赖屏幕的检查（输入时已逐项拦过），其余缓存检查照做。
            // Fragment 还没提交（页面活着、child 为空）则没有可保存的内容，直接不保存
            val gone = isDestroyed
            val screen = if (gone) null else child?.preferenceScreen
            if (screen == null && !gone) return@onMainDispatcher false
            validateEditor()?.let { message ->
                if (!gone) Toast.makeText(this@ProfileSettingsActivity, message, Toast.LENGTH_LONG).show()
                return@onMainDispatcher false
            }
            // 页面已销毁时看不到地址项是否可见：有地址项的编辑器只要缓存里的地址不合法就不存（保守，等同原来的不保存），
            // 免得新建节点空地址被悄悄写进库；整数项输入时已逐项拦过，库里旧值原样写回不会更糟
            if (screen == null) return@onMainDispatcher !hasServerAddressField || isServerAddress(EditorCache.serverAddress)
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
            onLiveActivity {
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
                onLiveActivity { finish() }
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
            if (!saved) onLiveActivity {
                Toast.makeText(this@ProfileSettingsActivity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
            }
        }
        onSaved()
        onLiveActivity { finish() }

    }

    /** 保存写完库、关闭页面之前调用一次；放弃修改、校验不过、会话已失效都不会走到这里。 */
    protected open fun onSaved() {
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        // action_move 可见性取 ViewModel 预取的结果，勿在主线程查库
        menu.findItem(R.id.action_move)?.isVisible = editor.canMoveProfile.value
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
            val address = findPreference<EditTextPreference>(Key.SERVER_ADDRESS)
            address?.bindValidatedPreference(R.string.server_address_error, ::isServerAddress)
            // 页面销毁后的保存看不到偏好屏幕，靠这个标记决定要不要校验地址（见 save）
            profileActivity?.hasServerAddressField = address != null
            // 各协议编辑器共用的端口校验；hysteria 用 serverPorts，链式与自定义配置没有端口，均为 null
            findPreference<EditTextPreference>(Key.SERVER_PORT)?.bindPortPreference()
        }

        // ConfigEditActivity 改完的 JSON 写回正在编辑的那一行的 bean，保存时随它一起写库。重建（旋转、进程死亡）后
        // 结果会送到新实例，这时节点可能还在后台载入，等载入完成再写；新建节点没有这两项菜单，结果直接丢弃。
        // 缓存里的 JSON 要等会话复查通过后再读：等待期间缓存可能已被别的编辑器接管，那时读到的是别人的内容
        private fun applyEditedJson(readJson: () -> String, apply: (AbstractBean, String) -> Unit) {
            val activity = profileActivity ?: return
            activity.launchInSession(lifecycleScope, Dispatchers.Main.immediate) {
                val bean = checked { activity.awaitProxyEntity() }?.requireBean() ?: return@launchInSession
                apply(bean, readJson())
            }
        }

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            applyEditedJson({ EditorCache.serverCustom }) { bean, json -> bean.customConfigJson = json }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            applyEditedJson({ EditorCache.serverCustomOutbound }) { bean, json -> bean.customOutboundJson = json }
        }

        // 用正在编辑的那一行的 bean 打开 JSON 编辑页；节点还在后台载入时等它（菜单回调在主线程，不能查库）。
        // 等待之后写缓存前复查会话，被接管就不再往别人的缓存里写种子内容
        private fun launchJsonEditor(
            key: String,
            seed: (AbstractBean) -> Unit,
            launcher: ActivityResultLauncher<Intent>,
        ) {
            val activity = profileActivity ?: return
            activity.launchInSession(lifecycleScope, Dispatchers.Main.immediate) {
                val bean = checked { activity.awaitProxyEntity() }?.requireBean() ?: return@launchInSession
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
                                            // 进程级：移动节点落库，页面随即关闭也要移完；关闭页面走 onLiveActivity
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
                                                activity.onLiveActivity { activity.finish() }
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
                            // 进程级：移动前先保存，要写完；界面收尾在 saveAndExit 里走 onLiveActivity
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

// 输入时与「确定」时共用同一条规则：对话框里按它实时校验（InlineValidation），change 监听器照旧兜底拒绝
fun EditTextPreference.bindIntegerPreference(
    min: Int = 0, max: Int = Int.MAX_VALUE, allowEmpty: Boolean = false,
): EditTextPreference {
    extras.putInt(INTEGER_MIN, min)
    extras.putInt(INTEGER_MAX, max)
    extras.putBoolean(INTEGER_ALLOW_EMPTY, allowEmpty)
    setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    InlineValidations.register(this, InlineValidation(
        message = { it.getString(R.string.integer_range_error, min, max) },
        isValid = { io.nekohasekai.sagernet.database.preference.isIntegerInRange(it, min, max, allowEmpty) },
    ))
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

// 输入时在对话框里实时校验并禁用「确定」（InlineValidation），change 监听器在「确定」时再拒绝一次并弹 Toast 兜底；
// 与 validateEditor() 配对：导入或之前缓存的、没经过输入校验的值在保存时拦下。
fun EditTextPreference.bindValidatedPreference(
    @StringRes message: Int, valid: (String) -> Boolean,
): EditTextPreference {
    InlineValidations.register(this, InlineValidation(
        message = { it.getString(message) },
        isValid = valid,
    ))
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
