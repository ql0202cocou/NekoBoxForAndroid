package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.database.SQLException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
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
        suspend fun onUpdated(profile: ProxyEntity)
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

    // 与 GroupManager.iterator 同一策略：单个 listener 失败只记日志——异常
    // 传回 UI 调用方会造成崩溃（此时节点/规则变更已落库），也不该中断其余
    // listener 的通知
    suspend fun iterator(what: suspend Listener.() -> Unit) {
        for (listener in listeners) {
            try {
                what(listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    // 同 iterator：单个 listener 失败不中断其余通知
    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        for (listener in ruleListeners) {
            try {
                what(listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
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

    suspend fun createProfile(groupId: Long, bean: AbstractBean, core: Int = 0): ProxyEntity =
        createProfiles(groupId, listOf(bean), core).single()

    // 批量导入：整批一个事务、只查一次 nextOrder，userOrder 依次递增；
    // 写完后逐个发 onAdd，与单个创建的通知相同
    suspend fun createProfiles(
        groupId: Long, beans: List<AbstractBean>, core: Int = 0,
    ): List<ProxyEntity> {
        // 默认值与实体构造不必占着事务。Kryo 序列化仍在事务内：它发生在
        // addProxy 插入时的 Room TypeConverter 里，putBean 只是赋值
        val profiles = beans.map { bean ->
            bean.applyDefaultValues()
            ProxyEntity(groupId = groupId).apply {
                id = 0
                this.core = core
                putBean(bean)
            }
        }
        SagerDatabase.instance.runInTransaction {
            var order = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
            for (profile in profiles) {
                profile.userOrder = order++
                profile.id = SagerDatabase.proxyDao.addProxy(profile)
            }
        }
        for (profile in profiles) iterator { onAdd(profile) }
        return profiles
    }

    // 返回 false：这一行已不在库里（例如编辑期间被订阅更新删掉），什么都没写
    suspend fun updateProfile(profile: ProxyEntity): Boolean {
        if (SagerDatabase.proxyDao.updateEditableFields(ProxyEditableFields(profile)) == 0) return false
        val current = SagerDatabase.proxyDao.getById(profile.id) ?: return true
        iterator { onUpdated(current) }
        return true
    }

    suspend fun moveProfile(profileId: Long, groupId: Long): ProxyEntity? {
        if (SagerDatabase.proxyDao.updateGroup(profileId, groupId) == 0) return null
        val current = SagerDatabase.proxyDao.getById(profileId) ?: return null
        iterator { onUpdated(current) }
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

    // 只写测试状态列，整批一个事务（同 updateTraffic(List)）。不发逐行更新：
    // 调用方扫完整组后自己整组刷新一次
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

    // 拖拽排好的 userOrder 落库：只写这一列，整批一个事务（同
    // GroupManager.updateUserOrders），逐行独立提交会产生 N 次 commit
    fun updateUserOrders(profiles: Collection<ProxyEntity>) {
        if (profiles.isEmpty()) return
        SagerDatabase.instance.runInTransaction {
            for (profile in profiles) {
                SagerDatabase.proxyDao.updateOrder(profile.id, profile.userOrder)
            }
        }
    }

    // 选中节点指向的行已不存在时清掉选择；在删除行之后调用，调用处不必各自
    // 匹配删除列表。不清的话下次 reload 时 getProfile(selectedProxy) 返回
    // null，VPN 静默停止
    fun clearSelectedProxyIfGone() {
        val selected = DataStore.selectedProxy
        if (selected > 0L && SagerDatabase.proxyDao.getById(selected) == null) {
            DataStore.selectedProxy = 0L
        }
    }

    // Removes the row; false when the profile no longer existed. Listeners are
    // notified by the callers, which order that against their own fixups —
    // 清选择也归调用处，整批删完调一次就够，不必每行查一次库
    private fun deleteProfileRow(profileId: Long): Boolean {
        return SagerDatabase.proxyDao.deleteById(profileId) != 0
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
        // DataStore 写的是 PublicDatabase，放在 SagerDatabase 事务外
        clearSelectedProxyIfGone()
        for (profile in removed) iterator { onRemoved(profile.groupId, profile.id) }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (!deleteProfileRow(profileId)) return
        clearSelectedProxyIfGone()
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

    suspend fun postUpdate(profileId: Long) {
        postUpdate(getProfile(profileId) ?: return)
    }

    suspend fun postUpdate(profile: ProxyEntity) {
        iterator { onUpdated(profile) }
    }

    // 主进程收到的最近一次实时流量。TrafficLooper 只推变化项，列表整组重读或
    // 新建页面时，没变化的行要从这里取值，否则会退回库里的旧值
    val liveTraffic = ConcurrentHashMap<Long, TrafficData>()

    suspend fun postUpdate(data: TrafficData) {
        liveTraffic[data.id] = data
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

    // 规则版的 updateUserOrders；与下面的开关写入一样不通知 RuleListener
    fun updateRuleOrders(rules: Collection<RuleEntity>) {
        if (rules.isEmpty()) return
        SagerDatabase.instance.runInTransaction {
            for (rule in rules) {
                SagerDatabase.rulesDao.updateOrder(rule.id, rule.userOrder)
            }
        }
    }

    fun updateRuleEnabled(ruleId: Long, enabled: Boolean) {
        SagerDatabase.rulesDao.updateEnabled(ruleId, enabled)
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
        if (!DataStore.rulesFirstCreate) defaultRulesMutex.withLock {
            // 等锁期间另一个调用可能已全部建完，锁内复查标记避免重复创建
            if (!DataStore.rulesFirstCreate) createDefaultRules()
        }
        return SagerDatabase.rulesDao.allRules()
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
