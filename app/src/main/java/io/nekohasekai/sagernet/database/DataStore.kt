package io.nekohasekai.sagernet.database

import android.os.Binder
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.fmt.CLASH_API_LISTEN
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringToInt
import java.util.UUID

object DataStore {

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { GroupManager.currentGroupId() } // "ungrouped" group id = 1

    // main

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION)
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API)
    private var clashApiSecret by configurationStore.string(Key.CLASH_API_SECRET)

    // Generated once per install and shared by :bg (ConfigBuilder) and the main
    // process (WebviewFragment); bare hex so yacd's query-string parsing keeps it intact
    fun requireClashApiSecret(): String = clashApiSecret.ifBlank {
        // 首次「生成并写回」在 :bg 与 main 之间存在竞争；与 InstallMarker 共用
        // 恢复日志的跨进程锁，锁内复查，晚到的进程直接用已生成的值
        RestoreJournal.default.withLock {
            clashApiSecret.ifBlank {
                UUID.randomUUID().toString().replace("-", "").also { clashApiSecret = it }
            }
        }
    }

    // Per-install: a restored configuration.db must not carry another
    // installation's secret (InstallMarker); the next access regenerates it.
    fun resetClashApiSecret() {
        clashApiSecret = ""
    }
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 9000 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL)
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://dns.google/dns-query" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "https://223.5.5.5/dns-query" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER)
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)

    // 多用户 mixedPort 偏移：uid 高位即 user id，平台约定每个用户占 100000
    // 个 uid 段（UserHandle.getUserId 的内部实现，但该静态方法在 SDK 里
    // @hide 不可直接调，故用字面量除法）。替代依赖「hashCode==mHandle」
    // 这一未契约化实现的 getCallingUserHandle().hashCode()
    private val userIndex by lazy { Binder.getCallingUid() / 100000 }
    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
    }

    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY)
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") { 5 }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    // persistent: user-deleted default route rules must not be recreated on process restart
    var rulesFirstCreate by configurationStore.boolean(Key.RULES_FIRST_CREATE)

    // set once SagerNet.migrateLegacyAssets moved the external-storage assets dir
    var legacyAssetsMigrated by configurationStore.boolean(Key.LEGACY_ASSETS_MIGRATED)

    // trailing slash: sing-box's /ui redirect drops the ?secret= query
    var yacdURL by configurationStore.string("yacdURL") { "http://$CLASH_API_LISTEN/ui/" }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }
}
