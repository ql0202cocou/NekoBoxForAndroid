package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.padForSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
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
                app.cacheDir.mkdirs()
                // each share leaves a file holding every credential behind: keep only this one
                app.cacheDir.listFiles { f -> f.name.startsWith("nekobox_backup_") }
                    ?.forEach { it.delete() }
                val cacheFile = File(
                    app.cacheDir,
                    "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                )
                cacheFile.writeText(backup)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
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

    // Inverse of toBase64Str(): null when the record cannot be decoded, so a
    // single corrupt entry is skipped instead of failing the whole import.
    private inline fun <T> unmarshal(b64: String, create: (Parcel) -> T): T? = runCatching {
        val data = Util.b64Decode(b64)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            create(parcel)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

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
                    PublicDatabase.kvPairDao.all().forEach {
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
            app.contentResolver.query(file, null, null, null, null)
                ?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
                }
                ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')
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
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
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
        var skippedRecords = 0

        fun <T> decodeArray(key: String, decode: (String) -> T?): List<T> {
            val result = mutableListOf<T>()
            val array = content.getJSONArray(key)
            for (i in 0 until array.length()) {
                val encoded = array.get(i)
                require(encoded is String) { "$key[$i] is not an encoded record" }
                val item = decode(encoded)
                if (item == null) {
                    skippedRecords++
                } else {
                    result.add(item)
                }
            }
            require(array.length() == 0 || result.isNotEmpty()) {
                "$key contains no valid records"
            }
            return result
        }

        // Decode and validate every selected section before touching either
        // database. A malformed later section must not erase earlier data.
        val profiles = if (profile && content.has("profiles")) {
            decodeArray("profiles") { encoded ->
                unmarshal(encoded) {
                    ProxyEntity.CREATOR.createFromParcel(it).also { entity ->
                        // Unknown types leave every bean null (putByteArray has no
                        // else); the configuration list would crash in requireBean()
                        // when binding such a record.
                        entity.requireBean()
                    }
                }
            }
        } else null
        val groups = if (profiles != null) {
            decodeArray("groups") { encoded ->
                unmarshal(encoded, ProxyGroup.CREATOR::createFromParcel)
            }
        } else null
        val rules = if (rule && content.has("rules")) {
            decodeArray("rules") { encoded ->
                unmarshal(encoded, ParcelizeBridge::createRule)
            }
        } else null
        val settings = if (setting && content.has("settings")) {
            decodeArray("settings") { encoded ->
                unmarshal(encoded, KeyValuePair.CREATOR::createFromParcel)
            }
        } else null

        val oldProfiles = profiles?.let { SagerDatabase.proxyDao.getAll() }
        val oldGroups = groups?.let { SagerDatabase.groupDao.allGroups() }
        val oldRules = rules?.let { SagerDatabase.rulesDao.allRules() }

        fun replaceSagerData(
            newProfiles: List<ProxyEntity>?,
            newGroups: List<ProxyGroup>?,
            newRules: List<RuleEntity>?,
        ) {
            SagerDatabase.instance.runInTransaction {
                if (newProfiles != null && newGroups != null) {
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.proxyDao.insert(newProfiles)
                    SagerDatabase.groupDao.reset()
                    SagerDatabase.groupDao.insert(newGroups)
                }
                if (newRules != null) {
                    SagerDatabase.rulesDao.reset()
                    SagerDatabase.rulesDao.insert(newRules)
                }
            }
        }

        var sagerCommitted = false
        try {
            if (profiles != null || rules != null) {
                replaceSagerData(profiles, groups, rules)
                sagerCommitted = true
            }
            if (settings != null) {
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(settings)
                    // The imported PROFILE_GROUP may reference a group that does not
                    // exist here (e.g. a settings-only import); currentGroupId() trusts
                    // any positive value and the configuration page would stay blank.
                    if (DataStore.selectedGroup > 0L &&
                        SagerDatabase.groupDao.getById(DataStore.selectedGroup) == null
                    ) {
                        DataStore.selectedGroup =
                            SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
                    }
                }
            }
        } catch (failure: Throwable) {
            // Room makes each database transaction atomic. Compensate the
            // already-committed other database if the second commit fails.
            if (sagerCommitted) {
                try {
                    replaceSagerData(oldProfiles, oldGroups, oldRules)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
        return skippedRecords
    }

}
