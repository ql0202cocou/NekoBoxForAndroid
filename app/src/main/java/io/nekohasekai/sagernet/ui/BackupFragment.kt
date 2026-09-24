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
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                // Process death with the picker foreground loses content
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    writeToDocument(data, content)
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)
        binding.root.padForSystemBars()

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
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                val backup = doBackup(profile, rule, setting)
                onMainDispatcher {
                    content = backup
                    startFilesForResult(
                        exportSettings,
                        "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            val profile = binding.backupConfigurations.isChecked
            val rule = binding.backupRules.isChecked
            val setting = binding.backupSettings.isChecked
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                val backup = doBackup(profile, rule, setting)
                // 直接写进分享目录，shareFile 在主线程上就不必再挪文件；目录里的旧备份
                // 由 shareFile 清掉，这里只清旧版本留在 cacheDir 根目录的
                app.cacheDir.listFiles { f -> f.name.startsWith("nekobox_backup_") }
                    ?.forEach { it.delete() }
                val cacheFile = File(
                    app.shareDir.apply { mkdirs() },
                    "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                )
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

    override fun onDestroyView() {
        super.onDestroyView()
        // 分享落在 cache 的备份明文含全部凭证，离开页面就清掉；
        // 接收方还没读完会让这次分享失败，代价可接受
        runOnDefaultDispatcher {
            app.cacheDir.listFiles { f -> f.name.startsWith("nekobox_backup_") }
                ?.forEach { it.delete() }
            // 备份写在 share/ 子目录（见 cache_paths.xml），根目录可能还有旧版本留下的
            app.shareDir.listFiles { f -> f.name.startsWith("nekobox_backup_") }
                ?.forEach { it.delete() }
        }
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

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
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
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                        runCatching {
                            val skipped = finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            if (skipped > 0) {
                                Logs.w("Backup import skipped $skipped invalid record(s)")
                                onMainDispatcher {
                                    if (!isAdded) return@onMainDispatcher
                                    snackbar(
                                        getString(R.string.backup_import_skipped, skipped)
                                    ).show()
                                }
                            }
                            // 恢复绕过 GroupManager 事件，重启也不会重排持久化的
                            // WorkManager 任务：导入后按新的分组集合重排一次订阅调度。
                            // 两个库都已提交，排期失败只记日志，不能拦住下面的重启
                            runCatching { SubscriptionUpdater.reconfigureUpdater() }
                                .onFailure { Logs.w(it) }
                            triggerFullRestart(app)
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                if (isAdded) alert(it.readableMessage).tryToShow()
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

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ): Int {
        val decoded = BackupRestore.decode(content, profile, rule, setting)
        // Staged before the first commit: a process killed between the two database
        // commits replays this backup on the next start (RestoreJournal).
        val journal = RestoreJournal.default
        journal.stage(content, profile, rule, setting)
        BackupRestore.commit(decoded, journal)
        return decoded.skipped
    }

}
