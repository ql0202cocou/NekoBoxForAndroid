package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.putBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import org.json.JSONTokener
import org.yaml.snakeyaml.error.YAMLException

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        var clashRoot: Map<*, *>? = null
        var remoteGroupName: String? = null
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.use { it.readBytesLimited().toString(Charsets.UTF_8) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))

            proxies = parseRaw(contentText) { clashRoot = it }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))

            // 本地文件没有 Subscription-Userinfo 响应头：订阅从 http(s) 改成
            // content:// 后显式清掉残留，下面的解析块会把流量字段一并归零
            subscription.subscriptionUserinfo = ""
        } else {

            val response = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) {
                    allowInsecure()
                }
                setURL(subscription.link)
                setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
            }.execute()
            try {
                val responseText = Util.getStringBox(response.contentString)
                proxies = parseRaw(responseText) { clashRoot = it }
                    ?: error(app.getString(R.string.no_proxies_found))

                subscription.subscriptionUserinfo =
                    Util.getStringBox(response.getHeader("Subscription-Userinfo"))

                // 修改默认名字
                if (proxyGroup.name?.startsWith("Subscription #") == true) {
                    var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                    if (remoteName.isNotBlank()) {
                        remoteName = Util.decodeFilename(remoteName)
                        if (remoteName.isNotBlank()) {
                            proxyGroup.name = remoteName
                            remoteGroupName = remoteName
                        }
                    }
                }
            } finally {
                response.close()
            }
        }

        // SIP008 / Open Online Config traffic fields shown by GroupFragment,
        // parsed from the Subscription-Userinfo header (blank when absent):
        // upload=…; download=…; total=…; expire=…
        run {
            val userinfo = subscription.subscriptionUserinfo ?: ""
            fun value(name: String): Long =
                "$name=([0-9]+)".toRegex().find(userinfo)?.groupValues?.get(1)?.toLongOrNull()
                    ?: 0L
            val used = value("upload") + value("download")
            subscription.bytesUsed = used
            subscription.bytesRemaining = value("total") - used
            subscription.expiryDate = value("expire")
        }

        // 订阅下发的节点解析 DNS，自动写入分组设置（在 forceResolve 之前生效）。
        // clash/YAML 订阅撤下该键时同步清空残留值；分享链接等非 YAML 订阅本就
        // 没有此键，不能误清。按是否真的载入了 YAML 根节点判断，而不是看原文：
        // 整段 base64 编码的 YAML 原文里没有 "proxies:"，按原文判断永远清不掉
        val subscriptionNameserver = parseProxyServerNameserver(clashRoot)
        val clearSubscriptionNameserver = subscriptionNameserver == null && clashRoot != null
        if (subscriptionNameserver != null) {
            proxyGroup.proxyServerNameserver = subscriptionNameserver
        } else if (clearSubscriptionNameserver) {
            proxyGroup.proxyServerNameserver = ""
        }

        // nameMap 以 displayName 为键，先保证唯一；forceResolve 把无名节点的
        // 地址改写成 IP 后 displayName 可能再次撞名，resolve 后还要再跑一遍。
        // 原地改名：首个同名节点名字不变，后续追加 " (1)"/" (2)" 后缀
        fun uniquifyNames(list: List<AbstractBean>) {
            // 输入是不可信的订阅内容，同名节点逐个扫表查重是 O(n²)，几十万个
            // 同名节点会卡死更新任务（期间一直持有跨进程文件锁），这里用哈希表
            // 做到 O(n)。只对改名后的新名字查重：节点本名里带 " (1)" 这类串时
            // （"HK (1) Premium"）不会被当成序号剥掉
            val taken = HashSet<String>()
            val next = HashMap<String, Int>() // 基础名 -> 下一个要试的序号
            for (proxy in list) {
                val base = proxy.displayName()
                if (taken.add(base)) continue
                var index = next[base] ?: 1
                var name = "$base ($index)"
                while (!taken.add(name)) name = "$base (${++index})"
                next[base] = index + 1
                proxy.name = name
            }
        }
        uniquifyNames(proxies)

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val droppedCounts = HashMap<Protocols.Deduplication, Int>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    // 只报告真正被删的节点；序号是该节点被删掉的第几份副本
                    val index = (droppedCounts[proxy] ?: 0) + 1
                    droppedCounts[proxy] = index
                    duplicate.add(_proxy.displayName() + " ($index)")
                }
            }
            proxies = uniqueProxies.toList().map { it.bean }
        }

        // 解析在去重/改名之后、nameMap 构建之前执行：解析到同一 IP 的节点
        // 不该被去重误删，resolve 改写 displayName 后的名字才是 nameMap 的键
        if (subscription.forceResolve) {
            forceResolve(proxies, proxyGroup.id, proxyGroup.proxyServerNameserver)
            // resolve 把无名节点的 displayName 改写成了 IP:port，撞名的要重新
            // 编号，否则下面 associateBy 会静默丢节点
            uniquifyNames(proxies)
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = LinkedHashMap<String, ProxyEntity>()
        for (entity in exists) {
            val name = entity.displayName()
            // toMap() would silently drop same-name duplicates; delete them so
            // the group ends up with exactly one entity per name
            if (nameMap.contains(name) && !toReplace.containsKey(name)) {
                toReplace[name] = entity
            } else {
                toDelete.add(entity)
            }
        }

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        // 分组删除的检查移入下面的事务里，与写入保持原子
        val toInsert = ArrayList<ProxyEntity>()
        val toUpdate = ArrayList<ProxyEntity>()
        // reorder-only rows: applied as a userOrder column update below, so
        // status/ping/error/tx/rx written during the download are not rolled back
        val toReorder = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                // 更新订阅，保留自定义覆写设置
                bean.customOutboundJson = existsBean.customOutboundJson
                bean.customConfigJson = existsBean.customConfigJson
                // Apply the subscription order even when the content also changed,
                // otherwise a reordered+edited node stays at its old position.
                val reordered = entity.userOrder != userOrder
                entity.userOrder = userOrder
                when {
                    existsBean != bean -> {
                        changed++
                        // putBean 之前取旧名：改名节点在报告里显示 旧名 => 新名
                        val oldName = entity.displayName()
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[oldName] = name

                        Logs.d("Updated profile #$userOrder")
                    }

                    reordered -> {
                        // 纯重排也是变化，否则 changed==0 时误报“没有差异”
                        changed++
                        toReorder.add(entity)

                        Logs.d("Reordered profile #$userOrder")
                    }

                    else -> {
                        Logs.d("Ignored profile #$userOrder")
                    }
                }
            } else {
                changed++
                toInsert.add(ProxyEntity(
                    groupId = proxyGroup.id, userOrder = userOrder
                ).apply {
                    putBean(bean)
                })
                added.add(name)
                Logs.d("Inserted profile #$userOrder")
            }
            userOrder++
        }

        // 写入整体放在一个事务里：进程在写入中途死亡不会留下新旧两套节点并存。
        // 通知 UI 的回调（finishUpdate / onUpdateSuccess）必须留在事务外
        SagerDatabase.instance.runInTransaction {
            // 下载期间分组可能已被删除，继续写入会留下指向不存在分组的孤儿节点
            if (SagerDatabase.groupDao.getById(proxyGroup.id) == null) {
                error("Group ${proxyGroup.id} was deleted during the update")
            }

            SagerDatabase.proxyDao.insert(toInsert)

            // toUpdate holds snapshots read before the download/resolve; a
            // full-row write would roll back status/ping/error/tx/rx updates
            // (URL tests, traffic persist) that landed meanwhile. Re-read each
            // row inside the transaction and merge only updater-owned fields
            // (userOrder and the bean content).
            var updatedCount = 0
            for (entity in toUpdate) {
                val current = SagerDatabase.proxyDao.getById(entity.id) ?: continue
                current.userOrder = entity.userOrder
                val currentBean = current.requireBean()
                val updatedBean = entity.requireBean().apply {
                    // Overrides may have been edited during DNS resolution.
                    // Preserve them from the transaction's fresh row too.
                    customOutboundJson = currentBean.customOutboundJson
                    customConfigJson = currentBean.customConfigJson
                }
                current.putBean(updatedBean)
                updatedCount += SagerDatabase.proxyDao.updateProxy(current)
            }
            Logs.d("Updated profiles: $updatedCount")

            for (entity in toReorder) {
                SagerDatabase.proxyDao.updateOrder(entity.id, entity.userOrder)
            }

            SagerDatabase.proxyDao.deleteProxy(toDelete).also {
                Logs.d("Deleted profiles: $it")
            }
            // 被删节点可能是其他分组的 frontProxy/landingProxy，清理悬挂引用
            if (toDelete.isNotEmpty()) GroupManager.resetDanglingGroupProxies()

            val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

            if (existCount != proxies.size) {
                Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
            }

            subscription.lastUpdated = System.currentTimeMillis() / 1000
            // 更新期间用户可能改过分组设置：重新读取当前行，只合并本流程负责写的
            // 字段（远端分组名 / 订阅下发的节点解析 DNS / lastUpdated /
            // subscriptionUserinfo 及流量字段），分组已被删除时（上面的检查之后）跳过写回
            SagerDatabase.groupDao.getById(proxyGroup.id)?.also { current ->
                if (remoteGroupName != null) current.name = remoteGroupName
                if (subscriptionNameserver != null) {
                    current.proxyServerNameserver = subscriptionNameserver
                } else if (clearSubscriptionNameserver) {
                    current.proxyServerNameserver = ""
                }
                // 传入的 subscription 是更新开始时的旧快照，整体回写会覆盖用户
                // 期间改的 link/deduplication 等；以新鲜行的 bean 为基础合并
                current.subscription?.apply {
                    lastUpdated = subscription.lastUpdated
                    subscriptionUserinfo = subscription.subscriptionUserinfo
                    bytesUsed = subscription.bytesUsed
                    bytesRemaining = subscription.bytesRemaining
                    expiryDate = subscription.expiryDate
                }
                SagerDatabase.groupDao.updateGroup(current)
            }
        }
        // DataStore 写的是 PublicDatabase（独立库文件），不加入上面的
        // SagerDatabase 事务；挪到事务成功后执行，避免回滚留下「选择已清、
        // 节点未删」的中间态
        ProfileManager.clearSelectedProxyIfGone()
        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    // onClashYaml：clash YAML 根节点载入成功时回调（无论节点解析成败），
    // doUpdate 借它读 dns 段，免得把整份订阅再解析一遍。
    // base64Depth 是内部递归深度：base64 整段编码的订阅解开后重跑一次本函数，
    // 只此一层，防无限递归
    suspend fun parseRaw(
        text: String,
        fileName: String = "",
        base64Depth: Int = 0,
        onClashYaml: (Map<*, *>) -> Unit = {},
    ): List<AbstractBean>? {

        require(text.length <= MAX_IMPORT_BYTES) { "Import exceeds size limit" }

        if (text.contains("proxies:")) {
            // clash & meta
            try {
                return parseClash(loadClashYaml(text).also(onClashYaml))
            } catch (e: YAMLException) {
                Logs.w("Subscription parsing failed: ${e.javaClass.simpleName}")
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                return parseWireGuard(text).also { beans ->
                    if (fileName.isBlank()) return@also
                    // one profile per [Peer], so the file name identifies the
                    // tunnel rather than the profile; numbering keeps the peers
                    // of a multi-peer config apart in the list
                    val tunnel = fileName.removeSuffix(".conf")
                    beans.forEachIndexed { index, bean ->
                        bean.name = if (beans.size == 1) tunnel else "$tunnel (${index + 1})"
                    }
                }
            } catch (e: Exception) {
                Logs.w("Subscription parsing failed: ${e.javaClass.simpleName}")
            }
        }

        try {
            val json = JSONTokener(text.checkJsonNesting()).nextValue()
            // An unrecognized JSON object (an API error body served with 200, an
            // unsupported schema) must fall through like every other path, not
            // come back as "0 nodes": doUpdate would delete the whole group.
            parseJSON(json).takeIf { it.isNotEmpty() }?.let { return it }
        } catch (ignored: Exception) {
        }

        try {
            val decoded = text.decodeBase64UrlSafe()
            // 整段 base64 编码的订阅（Clash YAML 常被整体编码）：解码文本重跑
            // 一次原始解析（含 Clash 判定），限一层
            if (base64Depth == 0) {
                parseRaw(decoded, fileName, base64Depth = 1, onClashYaml)?.let { return it }
            }
            return parseProxies(decoded).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            Logs.w("Subscription parsing failed: ${e.javaClass.simpleName}")
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    // mihomo/clash 订阅里 dns.proxy-server-nameserver（或顶层同名字段）的地址列表，
    // 每行一个；缺省时按 mihomo 语义回退 dns.nameserver（节点域名用它解析）。
    // mihomo 特有的 system 值对 sing-box 无意义，直接丢弃；回环/未指定地址
    // （如指向 mihomo 自身 dns.listen 的 127.0.0.1:7874）出了原核心就是死地址，同样丢弃；
    // #h3 之类 mihomo 私有后缀也一并剥掉，否则会漏进 sing-box 的 DoH path。
    // 候选键按优先级逐个尝试：proxy-server-nameserver 存在但过滤后为空时
    // 同样回退 nameserver —— mihomo 语义里前者的上游就是后者。
    // 入参是 parseRaw 载入的 clash YAML 根节点，没有（非 clash 订阅或 YAML 无效）时返回 null
    fun parseProxyServerNameserver(yaml: Map<*, *>?): String? {
        if (yaml == null) return null
        return try {
            val dns = yaml["dns"] as? Map<*, *>
            listOfNotNull(
                dns?.get("proxy-server-nameserver"),
                yaml["proxy-server-nameserver"],
                dns?.get("nameserver")
            ).firstNotNullOfOrNull { candidate ->
                val entries = when (candidate) {
                    is List<*> -> candidate.mapNotNull { it?.toString() }
                    is String -> listOf(candidate)
                    else -> emptyList()
                }
                entries.joinToString("\n").usableNameservers()
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Logs.w("Subscription DNS parsing failed: ${e.javaClass.simpleName}")
            null
        }
    }
}
