package io.nekohasekai.sagernet.database

import android.os.Parcel
import android.os.Process
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File

// Decode-then-commit half of a backup import, shared by BackupFragment and the
// startup replay driven by RestoreJournal.
object BackupRestore {

    class Decoded(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
        val settings: List<KeyValuePair>?,
        val skipped: Int,
    )

    // Settings that describe this installation rather than the user's preferences:
    // the imported value is dropped and the local one kept.
    private val installationKeys = listOf(Key.CLASH_API_SECRET, Key.LEGACY_ASSETS_MIGRATED)

    // rulesFirstCreate belongs with the rules table, so it only travels together with
    // the rules; profileCurrent is state of a service that is stopped for the import.
    fun mergeSettings(
        imported: List<KeyValuePair>, local: List<KeyValuePair>, rulesImported: Boolean,
    ): List<KeyValuePair> {
        val retained = installationKeys +
            if (rulesImported) emptyList() else listOf(Key.RULES_FIRST_CREATE)
        val dropped = retained + Key.PROFILE_CURRENT
        return imported.filterNot { it.key in dropped } + local.filter { it.key in retained }
    }

    // Inverse of BackupFragment.toBase64Str(): null when the record cannot be
    // decoded, so a single corrupt entry is skipped instead of failing the import.
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

    // Decode and validate every selected section before touching either database:
    // a malformed later section must not erase earlier data.
    fun decode(content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean): Decoded {
        var skipped = 0

        fun <T> decodeArray(key: String, decode: (String) -> T?): List<T> {
            val result = mutableListOf<T>()
            val array = content.getJSONArray(key)
            for (i in 0 until array.length()) {
                val encoded = array.get(i)
                val item = if (encoded is String) decode(encoded) else null
                if (item == null) {
                    skipped++
                } else {
                    result.add(item)
                }
            }
            require(array.length() == 0 || result.isNotEmpty()) {
                "$key contains no valid records"
            }
            return result
        }

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
                unmarshal(encoded) {
                    KeyValuePair.CREATOR.createFromParcel(it).also { pair -> pair.validate() }
                }
            }
        } else null
        return Decoded(profiles, groups, rules, settings, skipped)
    }

    // Debug-only fault injection for the window between the two commits:
    // `adb shell run-as <package> touch files/debug.restore-crash`.
    private val crashBetweenCommits
        get() = BuildConfig.DEBUG && File(app.filesDir, "debug.restore-crash").exists()

    // Two databases, two transactions. The journal is cleared once both committed, or
    // once the in-process compensation restored the first database; it stays only when
    // that compensation failed too, so the next start replays the backup.
    fun commit(decoded: Decoded, journal: RestoreJournal?) {
        val profiles = decoded.profiles
        val groups = decoded.groups
        val rules = decoded.rules
        val settings = decoded.settings

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
                if (crashBetweenCommits) Process.killProcess(Process.myPid())
            }
            if (settings != null) {
                PublicDatabase.instance.runInTransaction {
                    val dao = PublicDatabase.kvPairDao
                    val merged = mergeSettings(settings, dao.all(), rules != null)
                    dao.reset()
                    dao.insert(merged)
                    // Imported selections may reference rows that do not exist here (e.g.
                    // a settings-only import): currentGroupId() trusts any positive value
                    // and the configuration page would stay blank; the service would try
                    // to start a missing profile.
                    if (DataStore.selectedGroup > 0L &&
                        SagerDatabase.groupDao.getById(DataStore.selectedGroup) == null
                    ) {
                        DataStore.selectedGroup =
                            SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
                    }
                    if (DataStore.selectedProxy > 0L &&
                        SagerDatabase.proxyDao.getById(DataStore.selectedProxy) == null
                    ) {
                        DataStore.selectedProxy = 0L
                    }
                }
            }
            journal?.clear()
        } catch (failure: Throwable) {
            // Room makes each database transaction atomic. Compensate the
            // already-committed other database if the second commit fails.
            if (sagerCommitted) {
                try {
                    replaceSagerData(oldProfiles, oldGroups, oldRules)
                    journal?.clear()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            } else {
                journal?.clear()
            }
            throw failure
        }
    }
}
