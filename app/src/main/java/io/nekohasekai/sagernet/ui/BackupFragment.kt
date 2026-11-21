package io.nekohasekai.sagernet.ui

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.JsonWriter
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
import io.nekohasekai.sagernet.widget.hostMainScrollable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
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
                    if (scope == null || scope.size != 3) {
                        showSnackbar(R.string.action_export_err)
                    } else {
                        exportBackup(scope[0], scope[1], scope[2]) { writeToDocument(data, it) }
                    }
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
        // 顶部栏的抬升由工具页的控制器按当前页判断，这里只让本页的滚动视图参与
        hostMainScrollable(binding.root, lift = (parentFragment as? ToolsFragment)?.appBarLift)

        binding.resetSettings.setOnClickListener {
            requireContext().confirm(R.string.reset_settings_message) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
        }

        val exportOptions = listOf(binding.backupConfigurations, binding.backupRules, binding.backupSettings)
        fun updateExportButtons() {
            val selected = exportOptions.any { it.isChecked }
            binding.actionExport.isEnabled = selected
            binding.actionShare.isEnabled = selected
        }
        exportOptions.forEach { it.setOnCheckedChangeListener { _, _ -> updateExportButtons() } }
        updateExportButtons()

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
                exportBackup(profile, rule, setting) { backup ->
                    app.deleteSharedBackups()
                    val cacheFile = File(app.shareDir.apply { mkdirs() }, backupFileName())
                    try {
                        cacheFile.writeText(backup)
                        onMainDispatcher { requireContext().shareFile(cacheFile, "application/json") }
                    } catch (e: Exception) {
                        cacheFile.delete()
                        throw e
                    }
                }
            }
        }

        binding.retryRestore.setOnClickListener {
            runImport {
                if (!stopServiceAndWait()) throw ServiceStopTimeoutException()
                if (BackupRestore.replayPending(retry = true) == RestoreJournal.Result.PENDING) {
                    throw ReplayPendingException()
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
        // 进程级：页面销毁之后才执行的清理，不碰 View
        runOnDefaultDispatcher { app.deleteSharedBackups() }
    }

    internal class BackupTooLargeException : Exception()

    private suspend fun exportBackup(profile: Boolean, rule: Boolean, setting: Boolean, write: suspend (String) -> Unit) {
        try {
            write(doBackup(profile, rule, setting))
        } catch (e: CancellationException) {
            throw e
        } catch (_: BackupTooLargeException) {
            showSnackbar(app.getString(R.string.backup_too_large, MAX_IMPORT_BYTES / (1024 * 1024)))
        } catch (e: Exception) {
            Logs.w("Backup export failed: ${e.javaClass.simpleName}")
            showSnackbar(R.string.action_export_err)
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        require(profile || rule || setting) { "No backup sections selected" }
        val snapshot = BackupRepository.readSnapshot(profile, rule)
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.setIndent("  ")
            json.beginObject().name("version").value(1L)
            fun section(name: String, records: List<Parcelable>) {
                json.name(name).beginArray()
                for (record in records) {
                    val parcel = Parcel.obtain()
                    try {
                        record.writeToParcel(parcel, 0)
                        // 键名和无填充 URL-safe Base64 都是 ASCII，字符数就是 UTF-8 字节数。
                        // 先检查本条编码长度，避免为已经超限的数据构造大字符串。
                        val encodedSize = (parcel.dataSize() * 4L + 2) / 3
                        if (output.buffer.length + encodedSize > MAX_IMPORT_BYTES) throw BackupTooLargeException()
                        json.value(Util.b64EncodeUrlSafe(parcel.marshall()))
                    } finally {
                        parcel.recycle()
                    }
                }
                json.endArray()
            }
            snapshot?.profiles?.let { profiles ->
                // Room 快照独占这些实体；旧测速错误可能含无法再按当前配置遮蔽的凭据。
                profiles.forEach {
                    it.error = null
                    if (it.status > 1) {
                        it.status = 0
                        it.ping = 0
                    }
                }
                section("profiles", profiles)
            }
            snapshot?.groups?.let { section("groups", it) }
            snapshot?.rules?.let { section("rules", it) }
            if (setting) {
                section("settings", PublicDatabase.kvPairDao.all().filter {
                    it.key != Key.CLASH_API_SECRET && it.key != Key.SETTINGS_RESTORE_GENERATION
                })
            }
            json.endObject()
        }
        // 最后一条记录后的引号、缩进和结束符也计入导入上限。
        if (output.buffer.length > MAX_IMPORT_BYTES) throw BackupTooLargeException()
        return output.toString()
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
        if (version != 1 || listOf("profiles", "rules", "settings").none(content::has)) {
            invalid()
            return
        }

        onMainDispatcher {
            // the user may have left while the file was read in the background
            if (!isAdded) return@onMainDispatcher
            val import = LayoutImportBinding.inflate(layoutInflater)
            val options = listOf(
                import.backupConfigurations to "profiles", import.backupRules to "rules", import.backupSettings to "settings"
            )
            for ((box, section) in options) {
                box.isVisible = content.has(section)
                box.isChecked = box.isVisible
            }
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    // 三个勾选项在点击时（主线程）读完再往后传：后面的进程级协程不碰 CheckBox
                    val profile = import.backupConfigurations.isChecked
                    val rule = import.backupRules.isChecked
                    val setting = import.backupSettings.isChecked
                    if (!profile && !rule && !setting) return@setPositiveButton
                    runImport {
                        // 先校验，文件无效时不打断正在运行的服务。
                        val decoded = BackupRestore.decode(content, profile, rule, setting)
                        if (!stopServiceAndWait()) throw ServiceStopTimeoutException()
                        val skipped = finishImport(content, decoded)
                        if (skipped > 0) {
                            Logs.w("Backup import skipped $skipped invalid record(s)")
                            showSnackbar(app.getString(R.string.backup_import_skipped, skipped))
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun updateConfirm() {
                confirm.isEnabled = options.any { (box, _) -> box.isVisible && box.isChecked }
            }
            options.forEach { (box, _) -> box.setOnCheckedChangeListener { _, _ -> updateConfirm() } }
            updateConfirm()
        }
    }

    // 恢复一旦开始就跨过页面重建执行；只有反馈与进度框依附于原窗口。
    private fun runImport(restore: suspend () -> Unit) {
        val host = requireActivity()
        val binding = LayoutProgressBinding.inflate(layoutInflater)
        binding.content.text = getString(R.string.backup_importing)
        val dialog = AlertDialog.Builder(host).setView(binding.root).setCancelable(false).show()
        runOnDefaultDispatcher {
            runCatching {
                restore()
                SubscriptionUpdater.reconfigureUpdater()
                triggerFullRestart(app)
            }.onFailure { failure ->
                Logs.w("Backup import failed: ${failure.javaClass.simpleName}")
                onLiveView {
                    updateRetryVisibility()
                    alert(importFailureMessage(failure)).tryToShow()
                }
            }
            host.onLiveActivity { dialog.dismiss() }
        }
    }

    private fun updateRetryVisibility() {
        LayoutBackupBinding.bind(requireView()).retryRestore.isVisible = RestoreJournal.default.isPending()
    }

    override fun onResume() {
        super.onResume()
        updateRetryVisibility()
    }

    private class ReplayPendingException : Exception()
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
        if (failure is RestoreJournal.IncompleteReplacementException) return getString(R.string.restore_missing_sections)
        if (failure is ReplayPendingException) return getString(R.string.restore_replay_failed)
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

    fun finishImport(content: JSONObject, decoded: BackupRestore.Decoded): Int {
        val journal = RestoreJournal.default
        // 暂存、两次提交、清日志整段持日志的跨进程锁：:bg 启动时的重放（completePending）同样在这把锁里
        // 读日志、提交、清日志。不持锁时，重放能在日志刚暂存就读到它，与这里并行提交同一份备份，
        // 或在这里提交失败回滚之后把备份又写一遍，清日志也会互相抢先
        return journal.withLock {
            // 第一次提交之前先暂存：两次数据库提交之间进程被杀时，下次启动会重放这份备份（RestoreJournal）
            val wasPending = journal.stage(content, decoded.profiles != null, decoded.rules != null, decoded.settings != null)
            // 已有中断恢复时，补偿只能退回导入前的混合状态，仍需保留恢复来源。
            if (wasPending) {
                BackupRestore.commit(decoded, null)
                journal.clear()
            } else {
                BackupRestore.commit(decoded, journal)
            }
            decoded.skipped
        }
    }

}
