package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceRegistry
import io.nekohasekai.sagernet.bg.awaitSettled
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private companion object {
        // 停止要关 box、等插件进程退出并落最后一笔流量，正常几百毫秒；给慢设备留足余量
        const val SERVICE_STOP_TIMEOUT_MS = 15_000L

        const val STATE_EXPORT_SCOPE = "exportScope"
    }

    override fun name0() = app.getString(R.string.backup)

    // 导出时选中的范围（节点 / 规则 / 设置）。文件选择器在前台期间 Fragment 可能被重建甚至进程被回收，
    // 所以只记范围，由 onSaveInstanceState 存下；备份内容在选择器返回后才生成：内容含全部节点凭证明文，
    // 不放进 Fragment 字段，也不放进会被系统保存的 Bundle，取消选择时也就没有白生成的内容
    private var exportScope: BooleanArray? = null

    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            val scope = exportScope
            exportScope = null
            if (data != null) {
                // Fragment 重建后回调落在新实例上，视图可能还没建好：放在进程级的作用域里写，
                // writeToDocument 自己处理界面已不在的情况
                runOnDefaultDispatcher {
                    val backup = if (scope == null || scope.size != 3) "" else {
                        runCatching { doBackup(scope[0], scope[1], scope[2]) }
                            .onFailure { Logs.w(it) }
                            .getOrDefault("")
                    }
                    // 空内容由 writeToDocument 拒绝并提示，不会截断选中的文件
                    writeToDocument(data, backup)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportScope = savedInstanceState?.getBooleanArray(STATE_EXPORT_SCOPE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exportScope?.let { outState.putBooleanArray(STATE_EXPORT_SCOPE, it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)
        binding.root.padForSystemBars(bottomExtra = mainBottomClearance())
        // 滚动时通知工具页重新判断顶部栏是否抬升
        binding.root.setOnScrollChangeListener { _, _, _, _, _ ->
            (parentFragment as? ToolsFragment)?.updateAppBarLift()
        }
        binding.root.post { (parentFragment as? ToolsFragment)?.updateAppBarLift() }

        binding.resetSettings.setOnClickListener {
            requireContext().confirm(R.string.reset_settings_message) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
        }

        binding.actionExport.setOnClickListener {
            val profile = binding.backupConfigurations.isChecked
            val rule = binding.backupRules.isChecked
            val setting = binding.backupSettings.isChecked
            exportScope = booleanArrayOf(profile, rule, setting)
            startFilesForResult(exportSettings, backupFileName())
        }

        binding.actionShare.setOnClickListener {
            val profile = binding.backupConfigurations.isChecked
            val rule = binding.backupRules.isChecked
            val setting = binding.backupSettings.isChecked
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                val backup = doBackup(profile, rule, setting)
                // 直接写进分享目录（shareFile 只分享其中的文件），先清掉上次的残留
                app.deleteSharedBackups()
                val cacheFile = File(app.shareDir.apply { mkdirs() }, backupFileName())
                cacheFile.writeText(backup)
                onMainDispatcher {
                    requireContext().shareFile(cacheFile, "application/json")
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    private fun backupFileName() =
        "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"

    override fun onDestroyView() {
        super.onDestroyView()
        // 分享落在 cache 的备份明文含全部凭证，离开页面就清掉；
        // 接收方还没读完会让这次分享失败，代价可接受
        runOnDefaultDispatcher { app.deleteSharedBackups() }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    // 节点、分组、规则在同一个 Room 事务里读完：导出期间别处的写入（订阅更新、编辑、删除）不会让三张表互相对不上。
    // 设置在另一个数据库（PublicDatabase），跨库没有同一事务，也与上面三张表没有引用关系
    private class SagerSnapshot(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
    )

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val snapshot = if (profile || rule) {
            SagerDatabase.instance.runInTransaction(Callable {
                SagerSnapshot(
                    if (profile) SagerDatabase.proxyDao.getAll() else null,
                    if (profile) SagerDatabase.groupDao.allGroups() else null,
                    if (rule) SagerDatabase.rulesDao.allRules() else null,
                )
            })
        } else null
        val out = JSONObject().apply {
            put("version", 1)
            if (snapshot?.profiles != null && snapshot.groups != null) {
                put("profiles", JSONArray().apply {
                    snapshot.profiles.forEach { put(it.toBase64Str()) }
                })

                put("groups", JSONArray().apply {
                    snapshot.groups.forEach { put(it.toBase64Str()) }
                })
            }
            if (snapshot?.rules != null) {
                put("rules", JSONArray().apply {
                    snapshot.rules.forEach { put(it.toBase64Str()) }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    // the Clash API secret is per-install (InstallMarker), not a preference
                    PublicDatabase.kvPairDao.all().filter { it.key != Key.CLASH_API_SECRET }.forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = try {
            app.contentResolver.displayName(file)
        } catch (e: Exception) {
            Logs.w(e)
            return
        }

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            snackbar(getString(R.string.invalid_backup_file)).show()
        }

        val content = try {
            JSONObject((app.contentResolver.openInputStream(file) ?: return).use {
                it.readBytesLimited().toString(Charsets.UTF_8).checkJsonNesting()
            })
        } catch (e: Exception) {
            Logs.w("Backup parsing failed: ${e.javaClass.simpleName}")
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            // the user may have left while the file was read in the background
            if (!isAdded) return@onMainDispatcher
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                        runCatching {
                            val profile = import.backupConfigurations.isChecked
                            val rule = import.backupRules.isChecked
                            val setting = import.backupSettings.isChecked
                            // 先解码并校验（备份损坏、引用断链在这里就中止），此时服务还没动
                            val decoded = BackupRestore.decode(content, profile, rule, setting)
                            // 服务在跑时先停，等它确认停止（最终流量已落库）再写库；
                            // 否则旧服务收尾时按 id 写的 tx / rx 会盖掉恢复值，或写到复用同 id 的别的节点
                            if (!stopServiceAndWait()) throw ServiceStopTimeoutException()
                            val skipped = finishImport(content, decoded, profile, rule, setting)
                            if (skipped > 0) {
                                Logs.w("Backup import skipped $skipped invalid record(s)")
                                showSnackbar(app.getString(R.string.backup_import_skipped, skipped))
                            }
                            // 恢复绕过 GroupManager 事件，重启也不会重排持久化的
                            // WorkManager 任务：导入后按新的分组集合重排一次订阅调度，
                            // 排期失败只记日志，不会拦住下面的重启
                            SubscriptionUpdater.reconfigureUpdater()
                            triggerFullRestart(app)
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                if (isAdded) alert(importFailureMessage(it)).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private class ServiceStopTimeoutException : Exception()

    // 请求停止并等 :bg 报告已停止。:bg 的 stopRunner 在最终流量落库之后才报 Stopped（BaseService.killProcesses），
    // 所以等到的状态本身就保证了后面的写库不会再被旧服务的收尾覆盖。停止请求总是发：主进程没连上 :bg 时
    // 状态也是 Idle，服务却可能在跑；服务没在跑时这个广播没人接收，已在 Stopping 的再收一次也是空操作。
    // 超时返回 false，调用方放弃这次恢复
    private suspend fun stopServiceAndWait(): Boolean {
        SagerNet.stopService()
        return ServiceRegistry.stateFlow.awaitSettled(SERVICE_STOP_TIMEOUT_MS)
    }

    // 引用断链只显示类别与数量（BackupIntegrity），不带 id 与字段值
    private fun importFailureMessage(failure: Throwable): String {
        if (failure is ServiceStopTimeoutException) return getString(R.string.backup_import_service_timeout)
        if (failure !is BackupIntegrityException) return failure.readableMessage
        val lines = failure.breaks.map {
            val format = when (it.kind) {
                BackupIntegrity.Kind.PROFILE_GROUP -> R.string.backup_broken_profile_group
                BackupIntegrity.Kind.GROUP_FRONT_PROXY -> R.string.backup_broken_group_front
                BackupIntegrity.Kind.GROUP_LANDING_PROXY -> R.string.backup_broken_group_landing
                BackupIntegrity.Kind.CHAIN_MEMBER -> R.string.backup_broken_chain_member
                BackupIntegrity.Kind.RULE_OUTBOUND -> R.string.backup_broken_rule_outbound
            }
            getString(format, it.count)
        }
        return (listOf(getString(R.string.backup_broken_title)) + lines).joinToString("\n")
    }

    fun finishImport(
        content: JSONObject, decoded: BackupRestore.Decoded,
        profile: Boolean, rule: Boolean, setting: Boolean
    ): Int {
        val journal = RestoreJournal.default
        // 暂存、两次提交、清日志整段持日志的跨进程锁：:bg 启动时的重放（completePending）同样在这把锁里
        // 读日志、提交、清日志。不持锁时，重放能在日志刚暂存就读到它，与这里并行提交同一份备份，
        // 或在这里提交失败回滚之后把备份又写一遍，清日志也会互相抢先
        return journal.withLock {
            // Staged before the first commit: a process killed between the two database
            // commits replays this backup on the next start (RestoreJournal).
            journal.stage(content, profile, rule, setting)
            BackupRestore.commit(decoded, journal)
            decoded.skipped
        }
    }

}
