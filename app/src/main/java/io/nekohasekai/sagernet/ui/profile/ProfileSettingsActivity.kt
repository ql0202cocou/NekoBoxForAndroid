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
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
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
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
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

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(EditorCache.editingId) }

    // action_move 的可见性要查库得出；onCreate 末尾异步预取后由
    // invalidateOptionsMenu 应用，默认值与菜单 XML 一致（隐藏）
    private var canMoveProfile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        beginEditorSession(savedInstanceState)
        // Before super.onCreate(): a restored MyPreferenceFragmentCompat runs
        // createPreferences() from there, and the StandardV2Ray editor reads the
        // lazy proxyEntity in it — with the in-memory cache gone after process
        // death editingId would still be 0 and null would be cached for good.
        if (ownsEditorSession) {
            EditorCache.editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        }
        super.onCreate(savedInstanceState)
        if (finishIfEditorSessionLost()) return
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
        if (savedInstanceState == null || EditorCache.profileCacheStore.getString(Key.PROFILE_CORE) == null) {
            val editingId = EditorCache.editingId
            lifecycleScope.launch(Dispatchers.Default) {
                if (editingId == 0L) {
                    EditorCache.editingGroup = GroupManager.selectedGroupForImport()
                    createEntity().applyDefaultValues().init()
                    EditorCache.profileCacheStore.putString(
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
                    EditorCache.editingGroup = proxyEntity!!.groupId
                    (proxyEntity!!.requireBean() as T).init()
                    EditorCache.profileCacheStore.putString(
                        Key.PROFILE_CORE, proxyEntity!!.core.toString()
                    )
                }

                // The cache was empty (process death): re-claim the session so
                // later recreations still match this editor's token
                renewEditorSessionToken()

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

        // action_move 可见性条件要查库，等初始化写好 editingGroup 后异步预取
        // 再刷新菜单；节点已不存在或初始化失败时 editorReady 被取消，这里随之结束
        // （会话被接管时 onCreate 早已返回，走不到这里）
        lifecycleScope.launch(Dispatchers.Default) {
            awaitEditorReady()
            // proxyEntity 是 lazy，首次求值要查库；先在后台算好，
            // 菜单回调（创建快捷方式/移动分组）再读就是缓存命中
            if (EditorCache.editingId != 0L) proxyEntity
            canMoveProfile = EditorCache.editingId != 0L // 非新建
                    && SagerDatabase.groupDao.getById(EditorCache.editingGroup)?.type == GroupType.BASIC // 不在订阅组
                    && SagerDatabase.groupDao.allGroups()
                .filter { it.type == GroupType.BASIC }.size > 1 // 还有其他普通分组
            onMainDispatcher { invalidateOptionsMenu() }
        }

    }

    protected open fun validateEditor(): String? = null

    override suspend fun saveAndExit() {
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

        val editingId = EditorCache.editingId
        // entity-level field, not part of the bean; seeded in onCreate
        val profileCore = EditorCache.profileCacheStore.getString(Key.PROFILE_CORE)
            ?.toIntOrNull() ?: ProxyEntity.CORE_AUTO
        if (editingId == 0L) {
            val editingGroup = EditorCache.editingGroup
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
            val saved = ProfileManager.updateProfile(proxyEntity!!.apply {
                core = profileCore
                (requireBean() as T).serialize()
            })
            // proxyEntity 是打开编辑器时读的缓存：节点在编辑期间被删掉时更新 0 行，
            // 不提示的话修改就悄悄丢了
            if (!saved) onMainDispatcher {
                Toast.makeText(this@ProfileSettingsActivity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
            }
        }
        finish()

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

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // 重新挂上自定义 JSON 回调：重建后 ConfigEditActivity 的结果会送到这个新实例，
            // 而菜单处理里设的回调随旧实例一起没了
            profileActivity?.proxyEntity?.requireBean()?.let { bean ->
                callbackCustom = { bean.customConfigJson = it }
                callbackCustomOutbound = { bean.customOutboundJson = it }
            }
        }

        var callbackCustom: ((String) -> Unit)? = null
        var callbackCustomOutbound: ((String) -> Unit)? = null

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustom?.let { it(EditorCache.serverCustom) }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustomOutbound?.let { it(EditorCache.serverCustomOutbound) }
        }

        @SuppressLint("CheckResult")
        override fun onMenuItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_custom_outbound_json -> {
                profileActivity?.proxyEntity?.apply {
                    val bean = requireBean()
                    // seeding the editor is not an edit: keep dirty as it was
                    val dirty = EditorCache.dirty
                    EditorCache.serverCustomOutbound = bean.customOutboundJson
                    EditorCache.dirty = dirty
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
                profileActivity?.proxyEntity?.apply {
                    val bean = requireBean()
                    val dirty = EditorCache.dirty
                    EditorCache.serverCustom = bean.customConfigJson
                    EditorCache.dirty = dirty
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
                // proxyEntity 是打开编辑器时读的缓存，编辑期间节点可能已被订阅更新删掉：
                // 在后台重查一次，免得固定一个指向已删节点的快捷方式
                lifecycleScope.launch(Dispatchers.Default) {
                    val ent = activity.proxyEntity?.let { SagerDatabase.proxyDao.getById(it.id) }
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
                    // 编辑期间节点可能被订阅更新删掉；与 saveAndExit 的判空同理
                    val ent = activity.proxyEntity
                    if (ent == null) {
                        Toast.makeText(activity, R.string.deleted_profile, Toast.LENGTH_LONG).show()
                        return
                    }
                    lifecycleScope.launch(Dispatchers.Default) {
                        val groups = SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.BASIC && it.id != ent.groupId }
                        onMainDispatcher {
                            val view = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL

                                groups.forEach { group ->
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
                                                EditorCache.editingGroup = newGroupId // post switch animation
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
