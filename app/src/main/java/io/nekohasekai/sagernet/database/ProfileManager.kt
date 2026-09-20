package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.database.SQLException
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

// A database that cannot be opened is an I/O failure for the caller; any
// other SQL failure is logged and reads as "no such row".
internal inline fun <T> guardedRead(fallback: T, read: () -> T): T = try {
    read()
} catch (ex: SQLiteCantOpenDatabaseException) {
    throw IOException(ex)
} catch (ex: SQLException) {
    Logs.w(ex)
    fallback
}

object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    // copy-on-write: iteration walks a snapshot, so a listener may add or
    // remove listeners while being notified
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val ruleListeners = CopyOnWriteArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        for (listener in listeners) what(listener)
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        for (listener in ruleListeners) what(listener)
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun addListener(listener: RuleListener) {
        ruleListeners.add(listener)
    }

    fun removeListener(listener: RuleListener) {
        ruleListeners.remove(listener)
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean, core: Int = 0): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            this.core = core
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        if (SagerDatabase.proxyDao.updateEditableFields(ProxyEditableFields(profile)) == 0) return
        val current = SagerDatabase.proxyDao.getById(profile.id) ?: return
        iterator { onUpdated(current, false) }
    }

    suspend fun moveProfile(profileId: Long, groupId: Long): ProxyEntity? {
        if (SagerDatabase.proxyDao.updateGroup(profileId, groupId) == 0) return null
        val current = SagerDatabase.proxyDao.getById(profileId) ?: return null
        iterator { onUpdated(current, false) }
        return current
    }

    // Snapshot-safe partial writes: the entity may have been read at VPN/test
    // start, so only the columns this caller owns go back to the DB.

    suspend fun updateTraffic(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateTraffic(profile.id, profile.tx, profile.rx)
    }

    // One transaction for a whole sweep: the DB runs in TRUNCATE journal mode, so every
    // single-row update is otherwise its own commit.
    suspend fun updateTraffic(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        SagerDatabase.instance.runInTransaction {
            for (profile in profiles) {
                SagerDatabase.proxyDao.updateTraffic(profile.id, profile.tx, profile.rx)
            }
        }
    }

    suspend fun updateStatus(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateStatus(profile.id, profile.status, profile.ping, profile.error)
        iterator { onUpdated(profile, false) }
    }

    // Batch counterpart, one transaction like updateTraffic(List). It posts no
    // update: sweeps over a whole group refresh the list once themselves.
    suspend fun updateStatus(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        SagerDatabase.instance.runInTransaction {
            for (profile in profiles) {
                SagerDatabase.proxyDao.updateStatus(
                    profile.id, profile.status, profile.ping, profile.error
                )
            }
        }
    }

    // Removes the row and drops a selection pointing at it; false when the
    // profile no longer existed. Listeners are notified by the callers, which
    // order that against their own fixups.
    private fun deleteProfileRow(profileId: Long): Boolean {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return false
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        return true
    }

    // Bulk-delete path: listeners fire per profile, but the expensive fixups
    // (dangling front/landing proxies, rearrange) run once for the whole batch
    // instead of per profile. 整批删除与 fixups 包在一个事务里：逐行自动提交
    // 在进程中途死亡时留下删了一半的批次，且 fixups 不会执行，其他分组的
    // front/landingProxy 悬挂残留。监听器通知留在事务提交后
    suspend fun deleteProfiles(profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        val removed = ArrayList<ProxyEntity>(profiles.size)
        SagerDatabase.instance.runInTransaction {
            for (profile in profiles) {
                if (deleteProfileRow(profile.id)) removed.add(profile)
            }
            GroupManager.resetDanglingGroupProxies()
            val groupId = profiles.first().groupId
            if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
                GroupManager.rearrange(groupId)
            }
        }
        for (profile in removed) iterator { onRemoved(profile.groupId, profile.id) }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (!deleteProfileRow(profileId)) return
        // the profile may be referenced as a group's frontProxy/landingProxy
        GroupManager.resetDanglingGroupProxies()
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return guardedRead(null) { SagerDatabase.proxyDao.getById(profileId) }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return guardedRead(listOf()) { SagerDatabase.proxyDao.getEntities(profileIds) }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun clearRules() {
        SagerDatabase.rulesDao.reset()
        ruleIterator { onCleared() }
    }

    // 「检查标记-创建默认规则」不是原子的：rulesFirstCreate 为 false 时两个并发
    // 调用会各自创建一整套默认规则，用进程内 Mutex 串行化创建段
    private val defaultRulesMutex = Mutex()

    suspend fun getRules(): List<RuleEntity> {
        if (DataStore.rulesFirstCreate) return SagerDatabase.rulesDao.allRules()
        return defaultRulesMutex.withLock {
            // 等锁期间另一个调用可能已全部建完，锁内复查标记避免重复创建
            if (!DataStore.rulesFirstCreate) createDefaultRules()
            SagerDatabase.rulesDao.allRules()
        }
    }

    // 只允许在 defaultRulesMutex 内调用
    private suspend fun createDefaultRules() {
        // 等锁期间前一个调用也可能建到一半失败（标记未置），去重判据用锁内的
        // 最新快照，否则把别人已建的规则再建一遍
        val rules = SagerDatabase.rulesDao.allRules()
        // A previous attempt may have crashed midway through creation;
        // skip the default rules it already created instead of duplicating them.
        suspend fun createDefaultRule(rule: RuleEntity, post: Boolean = true) {
            val exists = rules.any {
                it.port == rule.port && it.network == rule.network &&
                        it.domains == rule.domains && it.ip == rule.ip &&
                        it.outbound == rule.outbound
            }
            if (!exists) createRule(rule, post)
        }
        createDefaultRule(
            RuleEntity(
                name = app.getString(R.string.route_opt_block_quic),
                port = "443",
                network = "udp",
                outbound = -2
            )
        )
        createDefaultRule(
            RuleEntity(
                name = app.getString(R.string.route_opt_block_ads),
                domains = "geosite:category-ads-all",
                outbound = -2
            )
        )
        val fuckedCountry = mutableListOf("cn:中国")
        if (Locale.getDefault().country != Locale.CHINA.country) {
            // 非中文用户
            fuckedCountry += "ir:Iran"
            fuckedCountry += "ru:Russia"
        }
        for (c in fuckedCountry) {
            val country = c.substringBefore(":")
            val displayCountry = c.substringAfter(":")
            //
            if (country == "cn") createDefaultRule(
                RuleEntity(
                    name = app.getString(R.string.route_play_store, displayCountry),
                    domains = "googleapis.cn",
                ), false
            )
            createDefaultRule(
                RuleEntity(
                    name = app.getString(R.string.route_bypass_domain, displayCountry),
                    domains = "geosite:$country",
                    outbound = -1
                ), false
            )
            createDefaultRule(
                RuleEntity(
                    name = app.getString(R.string.route_bypass_ip, displayCountry),
                    ip = "geoip:$country",
                    outbound = -1
                ), false
            )
        }
        // mark only after all default rules were created successfully
        DataStore.rulesFirstCreate = true
    }

}
