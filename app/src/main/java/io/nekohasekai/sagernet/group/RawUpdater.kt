package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.isCertificateFingerprint
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import androidx.core.net.toUri

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
        val subscriptionText: String
        var remoteGroupName: String? = null
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.use { it.readBytesLimited().toString(Charsets.UTF_8) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))

            subscriptionText = contentText
            proxies = parseRaw(contentText)
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
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
            val responseText = Util.getStringBox(response.contentString)
            subscriptionText = responseText
            proxies = parseRaw(responseText)
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
        // clash/YAML 订阅撤下该键时同步清空残留值；base64/分享链接订阅本就
        // 没有此键（parseProxyServerNameserver 对非 YAML 也返回 null），不能误清
        val subscriptionNameserver = parseProxyServerNameserver(subscriptionText)
        val clearSubscriptionNameserver = subscriptionNameserver == null &&
                subscriptionText.contains("proxies:")
        if (subscriptionNameserver != null) {
            proxyGroup.proxyServerNameserver = subscriptionNameserver
        } else if (clearSubscriptionNameserver) {
            proxyGroup.proxyServerNameserver = ""
        }

        // nameMap 以 displayName 为键，先保证唯一；forceResolve 把无名节点的
        // 地址改写成 IP 后 displayName 可能再次撞名，resolve 后还要再跑一遍
        fun uniquifyNames(list: List<AbstractBean>): List<AbstractBean> {
            val proxiesMap = LinkedHashMap<String, AbstractBean>()
            for (proxy in list) {
                var index = 0
                var name = proxy.displayName()
                while (proxiesMap.containsKey(name)) {
                    index++
                    // suffix only: a global replace also ate a " (1)" that is part
                    // of the node's own name ("HK (1) Premium")
                    name = name.removeSuffix(" (${index - 1})")
                    name = "$name ($index)"
                    proxy.name = name
                }
                proxiesMap[proxy.displayName()] = proxy
            }
            return proxiesMap.values.toList()
        }
        proxies = uniquifyNames(proxies)

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
            proxies = uniquifyNames(proxies)
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

                        Logs.d("Updated profile: $name")
                    }

                    reordered -> {
                        // 纯重排也是变化，否则 changed==0 时误报“没有差异”
                        changed++
                        toReorder.add(entity)

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
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
                Logs.d("Inserted profile: $name")
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
        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {

        require(text.length <= MAX_IMPORT_BYTES) { "Import exceeds size limit" }
        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("proxies:")) {

            // clash & meta

            try {

                // SafeConstructor: never instantiate arbitrary classes from a
                // remote subscription (CVE-2022-1471). loadAs(Map) is unsupported
                // under it, but load() yields the same LinkedHashMap structure.
                // A valid YAML whose root is not a map (plain cast would throw a
                // ClassCastException out of the YAMLException catch below) falls
                // back to the base64 / share-link parsing like any non-clash body.
                val yaml = clashYaml().load(text) as? Map<*, *> ?: throw YAMLException("Root node is not a map")

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                // List<*> without the Map type argument: a generic List<Map> cast is
                // erased, so the compiler checkcasts each element AFTER the loop's
                // runCatching guard and one "- ss://..." string entry would kill the
                // whole update with a ClassCastException
                for (proxyEntry in (yaml["proxies"] as? List<*> ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                ))) {

                    // Skip a single broken node instead of failing the whole update
                    runCatching {
                        val proxy = proxyEntry as? Map<String, Any?>
                            ?: error("proxy entry is not a map")
                        when (proxy["type"] as String) {
                            "socks5" -> {
                                proxies.add(SOCKSBean().apply {
                                    serverAddress = proxy["server"] as String
                                    serverPort = proxy["port"].toString().toInt()
                                    username = proxy["username"]?.toString()
                                    password = proxy["password"]?.toString()
                                    name = proxy["name"]?.toString()
                                })
                            }

                            "http" -> {
                                proxies.add(HttpBean().apply {
                                    serverAddress = proxy["server"] as String
                                    serverPort = proxy["port"].toString().toInt()
                                    username = proxy["username"]?.toString()
                                    password = proxy["password"]?.toString()
                                    setTLS(proxy["tls"].clashBoolean())
                                    sni = proxy["sni"]?.toString()
                                    name = proxy["name"]?.toString()
                                    allowInsecure = proxy["skip-cert-verify"].clashBoolean()
                                })
                            }

                            "ss" -> {
                                val ssPlugin = mutableListOf<String>()
                                val ssPluginName = proxy["plugin"]?.toString().blankAsNull()
                                if (ssPluginName != null) {
                                    // plugin-opts is optional in clash; without it the
                                    // plugin still applies with its default options
                                    val opts = proxy["plugin-opts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                                    when (ssPluginName) {
                                        "obfs" -> {
                                            ssPlugin.apply {
                                                add("obfs-local")
                                                add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                                add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                            }
                                        }

                                        "v2ray-plugin" -> {
                                            ssPlugin.apply {
                                                add("v2ray-plugin")
                                                add("mode=" + (opts["mode"]?.toString() ?: ""))
                                                if (opts["tls"].clashBoolean()) add("tls")
                                                add("host=" + (opts["host"]?.toString() ?: ""))
                                                add("path=" + (opts["path"]?.toString() ?: ""))
                                                if (opts["mux"].clashBoolean()) add("mux=8")
                                            }
                                        }

                                        // shadow-tls/restls are configured through their own
                                        // *-opts keys and have no sip003 plugin here; importing
                                        // the node without them yields something that looks fine
                                        // and can never connect, so drop it loudly instead
                                        else -> error("unsupported shadowsocks plugin: $ssPluginName")
                                    }
                                }
                                proxies.add(ShadowsocksBean().apply {
                                    serverAddress = proxy["server"] as String
                                    serverPort = proxy["port"].toString().toInt()
                                    password = proxy["password"]?.toString()
                                    method = clashCipher(proxy["cipher"] as String)
                                    plugin = ssPlugin.joinToString(";")
                                    sUoT = proxy["udp-over-tcp"].clashBoolean()
                                    name = proxy["name"]?.toString()
                                })
                            }

                            "vmess", "vless", "trojan" -> {
                                val bean = when (proxy["type"] as String) {
                                    "vmess" -> VMessBean()
                                    "vless" -> VMessBean().apply {
                                        alterId = -1 // make it VLESS
                                        packetEncoding = 2 // clash meta default XUDP
                                    }

                                    "trojan" -> TrojanBean().apply {
                                        security = "tls"
                                    }

                                    else -> error("impossible")
                                }

                                // error() instead of continue: continuing the outer
                                // loop from an inline lambda is experimental; the
                                // runCatching wrapper skips this node either way.
                                bean.serverAddress = proxy["server"]?.toString()
                                    ?: error("missing server")
                                bean.serverPort = proxy["port"]?.toString()?.toIntOrNull()
                                    ?: error("missing port")

                                for (opt in proxy) {
                                    when (opt.key) {
                                        "name" -> bean.name = opt.value?.toString()
                                        "password" -> if (bean is TrojanBean) bean.password =
                                            opt.value?.toString()

                                        "uuid" -> if (bean is VMessBean) bean.uuid =
                                            opt.value?.toString()

                                        "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                            opt.value?.toString()?.toIntOrNull()

                                        "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                            (opt.value as? String)

                                        "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                            (opt.value as? String)?.let {
                                                if (it.contains(StandardV2RayBean.FLOW_VISION)) {
                                                    bean.encryption = StandardV2RayBean.FLOW_VISION
                                                }
                                            }
                                        }

                                        "packet-encoding" -> if (bean is VMessBean) {
                                            bean.packetEncoding = when ((opt.value as? String)) {
                                                "packetaddr" -> 1
                                                "xudp" -> 2
                                                else -> 0
                                            }
                                        }

                                        "tls" -> if (bean is VMessBean) {
                                            bean.security =
                                                if (opt.value.clashBoolean()) "tls" else ""
                                        }

                                        "servername", "sni" -> bean.sni = opt.value?.toString()

                                        "alpn" -> bean.alpn =
                                            (opt.value as? List<Any>)?.joinToString("\n")

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            opt.value.clashBoolean()

                                        "fingerprint" -> bean.certificateFingerprint =
                                            parseCertificateFingerprint(opt.value)

                                        "client-fingerprint" -> bean.utlsFingerprint =
                                            opt.value as String

                                        "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (realityOpt in it) {
                                                bean.security = "tls"

                                                when (realityOpt.key) {
                                                    "public-key" -> bean.realityPubKey =
                                                        realityOpt.value?.toString()

                                                    "short-id" -> bean.realityShortId =
                                                        realityOpt.value?.toString()
                                                }
                                            }
                                        }

                                        "network" -> {
                                            when (opt.value) {
                                                "h2", "http" -> bean.type = "http"
                                                "ws", "grpc" -> bean.type = opt.value as String
                                            }
                                        }

                                        "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (wsOpt in it) {
                                                when (wsOpt.key) {
                                                    "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value?.toString()
                                                            }
                                                        }
                                                    }

                                                    "path" -> {
                                                        bean.path = wsOpt.value?.toString()
                                                    }

                                                    "max-early-data" -> {
                                                        bean.wsMaxEarlyData =
                                                            wsOpt.value?.toString()?.toIntOrNull()
                                                    }

                                                    "early-data-header-name" -> {
                                                        bean.earlyDataHeaderName =
                                                            wsOpt.value?.toString()
                                                    }

                                                    "v2ray-http-upgrade" -> {
                                                        if (wsOpt.value.clashBoolean()) {
                                                            bean.type = "httpupgrade"
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (h2Opt in it) {
                                                when (h2Opt.key) {
                                                    "host" -> bean.host =
                                                        (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                    "path" -> bean.path = h2Opt.value?.toString()
                                                }
                                            }
                                        }

                                        "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (httpOpt in it) {
                                                when (httpOpt.key) {
                                                    // a list mihomo picks from at random;
                                                    // the builders carry a single path
                                                    "path" -> bean.path =
                                                        (httpOpt.value as? List<*>)?.firstOrNull()
                                                            ?.toString()

                                                    "headers" -> {
                                                        // Map<*, *>: a typed value cast is erased and the
                                                        // destructuring checkcasts it, so a scalar
                                                        // "Host: example.com" would drop the whole node
                                                        (httpOpt.value as? Map<*, *>)?.forEach { (key, value) ->
                                                            when (key.toString().lowercase()) {
                                                                "host" -> bean.host = when (value) {
                                                                    is List<*> -> value.mapNotNull { it?.toString() }
                                                                        .joinToString("\n")

                                                                    else -> value?.toString()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (grpcOpt in it) {
                                                when (grpcOpt.key) {
                                                    "grpc-service-name" -> bean.path =
                                                        grpcOpt.value?.toString()
                                                }
                                            }
                                        }

                                        "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (smuxOpt in it) {
                                                when (smuxOpt.key) {
                                                    "enabled" -> bean.enableMux =
                                                        smuxOpt.value.clashBoolean()

                                                    "max-streams" -> bean.muxConcurrency =
                                                        smuxOpt.value.toString().toInt()

                                                    "padding" -> bean.muxPadding =
                                                        smuxOpt.value.clashBoolean()

                                                    // same numbering as ProxyEntity.singMux
                                                    "protocol" -> bean.muxType =
                                                        when (smuxOpt.value?.toString()) {
                                                            "smux" -> 1
                                                            "yamux" -> 2
                                                            else -> 0 // h2mux, mihomo's default
                                                        }
                                                }
                                            }
                                        }

                                        "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            for (echOpt in it) {
                                                when (echOpt.key) {
                                                    "enable" -> bean.enableECH =
                                                        echOpt.value.clashBoolean()

                                                    "config" -> bean.echConfig =
                                                        echOpt.value?.toString() ?: ""
                                                }
                                            }
                                        }
                                    }
                                }
                                proxies.add(bean)
                            }

                            "anytls" -> {
                                val bean = AnyTLSBean()
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()
                                        "server" -> bean.serverAddress = opt.value as String
                                        "port" -> bean.serverPort = opt.value.toString().toInt()
                                        "password" -> bean.password = opt.value.toString()
                                        "client-fingerprint" -> bean.utlsFingerprint =
                                            opt.value as String

                                        "sni" -> bean.sni = opt.value.toString()
                                        "skip-cert-verify" -> bean.allowInsecure =
                                            opt.value.clashBoolean()

                                        // mihomo's "certificate"/"private-key" are the mTLS
                                        // CLIENT cert (ca.GetTLSConfig -> GetClientCertificate),
                                        // not a custom CA — mapping them to bean.certificates
                                        // would become a server-cert pin that can never match.
                                        "certificate", "private-key" -> Logs.w(
                                            "clash anytls mTLS client cert is unsupported, dropped"
                                        )

                                        "fingerprint" -> bean.certificateFingerprint =
                                            parseCertificateFingerprint(opt.value)

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n")
                                        }

                                        "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                            val enable = it["enable"]
                                            bean.enableECH = enable.clashBoolean()
                                            // mihomo turns ECH on from the config
                                            // alone when "enable" is absent
                                            if (enable == null || bean.enableECH) {
                                                bean.echConfig =
                                                    it["config"]?.toString() ?: ""
                                            }
                                        }
                                    }
                                }
                                proxies.add(bean)
                            }

                            "hysteria" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 1
                                var hopPorts = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()
                                        "server" -> bean.serverAddress = opt.value as String
                                        "port" -> bean.serverPorts = opt.value.toString()
                                        "ports" -> hopPorts = opt.value.toString()

                                        "obfs" -> bean.obfuscation = opt.value.toString()

                                        "auth-str" -> {
                                            bean.authPayloadType = HysteriaBean.TYPE_STRING
                                            bean.authPayload = opt.value.toString()
                                        }

                                        "auth" -> {
                                            bean.authPayloadType = HysteriaBean.TYPE_BASE64
                                            bean.authPayload = opt.value.toString()
                                        }

                                        "protocol" -> {
                                            when (opt.value.toString()) {
                                                "faketcp" -> bean.protocol =
                                                    HysteriaBean.PROTOCOL_FAKETCP

                                                "wechat-video" -> bean.protocol =
                                                    HysteriaBean.PROTOCOL_WECHAT_VIDEO
                                            }
                                        }

                                        // clash "ca" is a local file path, only
                                        // "ca-str" carries an inline PEM
                                        "ca-str" -> bean.caText = opt.value.toString()

                                        "sni" -> bean.sni = opt.value.toString()

                                        "fingerprint" -> bean.certificateFingerprint =
                                            parseCertificateFingerprint(opt.value)

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            opt.value.clashBoolean()

                                        "up" -> bean.uploadMbps =
                                            opt.value.toString().substringBefore(" ").toIntOrNull()
                                                ?: 100

                                        "down" -> bean.downloadMbps =
                                            opt.value.toString().substringBefore(" ").toIntOrNull()
                                                ?: 100

                                        "recv-window-conn" -> bean.streamReceiveWindow =
                                            opt.value.toString().toIntOrNull() ?: 0

                                        "recv-window" -> bean.connectionReceiveWindow =
                                            opt.value.toString().toIntOrNull() ?: 0

                                        "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                            opt.value.clashBoolean() || opt.value.toString() == "1"

                                        "hop-interval" -> bean.hopInterval =
                                            opt.value.toString().toIntOrNull() ?: bean.hopInterval

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                        }
                                    }
                                }
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "hysteria2" -> {
                                val bean = HysteriaBean()
                                bean.protocolVersion = 2
                                var hopPorts = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()
                                        "server" -> bean.serverAddress = opt.value as String
                                        "port" -> bean.serverPorts = opt.value.toString()
                                        "ports" -> hopPorts = opt.value.toString()

                                        "obfs-password" -> bean.obfuscation = opt.value.toString()

                                        "password" -> bean.authPayload = opt.value.toString()

                                        "sni" -> bean.sni = opt.value.toString()

                                        "fingerprint" -> bean.certificateFingerprint =
                                            parseCertificateFingerprint(opt.value)

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            opt.value.clashBoolean()

                                        "up" -> bean.uploadMbps =
                                            opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                        "down" -> bean.downloadMbps =
                                            opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                        "ca-str" -> bean.caText = opt.value.toString()

                                        "hop-interval" -> bean.hopInterval =
                                            opt.value.toString().toIntOrNull() ?: bean.hopInterval

                                        // no "alpn" here: hysteria2 mandates h3 and the
                                        // sing-box builder hardcodes it
                                    }
                                }
                                if (hopPorts.isNotBlank()) {
                                    bean.serverPorts = hopPorts
                                }
                                proxies.add(bean)
                            }

                            "tuic" -> {
                                val bean = TuicBean()
                                var ip = ""
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()
                                        "server" -> bean.serverAddress = opt.value.toString()
                                        "ip" -> ip = opt.value.toString()
                                        "port" -> bean.serverPort = opt.value.toString().toInt()

                                        // mihomo treats a "token" node as TUIC v4, which the
                                        // core dropped; importing it would only fail at connect
                                        // time, so skip it like an unsupported ss plugin
                                        "token" -> {
                                            Logs.w("Skipping TUIC v4 (token) node: v4 is not supported")
                                            error("unsupported TUIC v4 (token) node")
                                        }

                                        "uuid" -> bean.uuid = opt.value.toString()

                                        "password" -> bean.token = opt.value.toString()

                                        "skip-cert-verify" -> bean.allowInsecure =
                                            opt.value.clashBoolean()

                                        "disable-sni" -> bean.disableSNI =
                                            opt.value.clashBoolean()

                                        "reduce-rtt" -> bean.reduceRTT =
                                            opt.value.clashBoolean()

                                        "sni" -> bean.sni = opt.value.toString()

                                        "ca-str" -> bean.caText = opt.value.toString()

                                        "fingerprint" -> bean.certificateFingerprint =
                                            parseCertificateFingerprint(opt.value)

                                        "fast-open" -> bean.fastConnect =
                                            opt.value.clashBoolean()

                                        "alpn" -> {
                                            val alpn = (opt.value as? (List<String>))
                                            bean.alpn = alpn?.joinToString("\n")
                                        }

                                        "congestion-controller" -> bean.congestionController =
                                            opt.value.toString()

                                        "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                        // mihomo milliseconds, sing-box seconds (rounded)
                                        "heartbeat-interval" -> bean.heartbeatInterval =
                                            opt.value.toString().toLongOrNull()
                                                ?.let { ((it + 500) / 1000).toInt().coerceAtLeast(0) }
                                                ?: bean.heartbeatInterval

                                    }
                                }
                                if (ip.isNotBlank()) {
                                    val domain = bean.serverAddress
                                    bean.serverAddress = ip
                                    if (bean.sni.isNullOrBlank() && !domain.isNullOrBlank() && !domain.isIpAddress()) {
                                        bean.sni = domain
                                    }
                                }
                                proxies.add(bean)
                            }

                            "wireguard" -> {
                                val bean = WireGuardBean().applyDefaultValues()
                                val localAddresses = mutableListOf<String>()
                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key.replace("_", "-")) {
                                        "name" -> bean.name = opt.value.toString()
                                        "server" -> bean.serverAddress = opt.value.toString()
                                        "port" -> bean.serverPort = opt.value.toString().toInt()
                                        "ip", "ipv6" -> {
                                            val address = opt.value.toString()
                                            if (address.isNotBlank()) localAddresses.add(address)
                                        }

                                        "private-key" -> bean.privateKey = opt.value.toString()
                                        "public-key" -> bean.peerPublicKey = opt.value.toString()
                                        "pre-shared-key" -> bean.peerPreSharedKey =
                                            opt.value.toString()

                                        "mtu" -> bean.mtu =
                                            opt.value.toString().toIntOrNull() ?: bean.mtu

                                        // mihomo writes the three reserved bytes as a
                                        // list; the bean keeps genReservedList's comma form
                                        "reserved" -> bean.reserved =
                                            (opt.value as? List<*>)?.joinToString(",") {
                                                it?.toString() ?: ""
                                            } ?: opt.value.toString()

                                        "persistent-keepalive" -> bean.peerKeepalive =
                                            opt.value.toString().toIntOrNull() ?: 0

                                        // stored for export round-trips only; the builder
                                        // keeps the default route allowed_ips
                                        "allowed-ips" -> bean.peerAllowedIps =
                                            (opt.value as? List<*>)?.mapNotNull { it?.toString() }
                                                ?.joinToString(",") ?: opt.value.toString()

                                        "remote-dns-resolve", "amnezia-wg-option" -> Logs.w(
                                            "clash wireguard ${opt.key.replace("_", "-")} is unsupported, dropped"
                                        )
                                    }
                                }
                                bean.localAddress = localAddresses.joinToString("\n")
                                proxies.add(bean)
                            }
                        }
                    }.onFailure { Logs.w("Subscription entry rejected: ${it.javaClass.simpleName}") }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI; h2-opts/http-opts hosts arrive newline-joined and the
                        // SNI can only carry one of them
                        val host = it.host.substringBefore("\n")
                        if (it.isTLS() && it.sni.isNullOrBlank() && host.isNotBlank() && !host.isIpAddress()) {
                            it.sni = host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                return proxies.takeIf { it.isNotEmpty() } ?: error("Not found")
            } catch (e: YAMLException) {
                Logs.w("Subscription parsing failed: ${e.javaClass.simpleName}")
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                proxies.addAll(parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
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
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
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
    // 同样回退 nameserver —— mihomo 语义里前者的上游就是后者
    fun parseProxyServerNameserver(text: String): String? {
        if (!text.contains("proxies:")) return null
        return try {
            val yaml = clashYaml().load(text) as Map<*, *>
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

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    // mihomo "fingerprint" is the SHA-256 hash of a served certificate; a value
    // in any other shape would only fail inside the core, so it is dropped here
    private fun parseCertificateFingerprint(value: Any?): String {
        val text = value?.toString() ?: ""
        if (text.isNotEmpty() && !isCertificateFingerprint(text)) {
            Logs.w("clash fingerprint is not a SHA-256 digest, dropped")
            return ""
        }
        return text
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        bean.privateKey = iface["PrivateKey"].orEmpty()
        if (bean.privateKey.isBlank()) {
            // an empty key only surfaced as an opaque error at start time
            Logs.w("WireGuard profile skipped: missing PrivateKey in 'Interface' selection")
            error("Missing PrivateKey in 'Interface' selection")
        }
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: bean.mtu
        // The app has no per-profile DNS model; keep dropping the field but say so
        if (!iface.getAll("DNS").isNullOrEmpty()) {
            Logs.w("WireGuard 'Interface' DNS field dropped")
        }
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        for (peer in peers) {
            val endpoint = peer["Endpoint"]
            if (endpoint.isNullOrBlank()) {
                continue
            }

            val peerBean = bean.clone()
            // A bare IPv6 endpoint (no brackets, several colons) cannot be told
            // apart from host:port, and an endpoint without a port is unusable
            val (host, portText) = endpoint.splitHostPort() ?: continue
            if (':' in host && !endpoint.startsWith("[")) {
                Logs.w("WireGuard peer skipped: bare IPv6 endpoint without brackets")
                continue
            }
            peerBean.serverAddress = host
            peerBean.serverPort = portText?.toIntOrNull() ?: continue
            peerBean.peerPublicKey = peer["PublicKey"] ?: continue
            peerBean.peerPreSharedKey = peer["PresharedKey"]
            peerBean.peerKeepalive = peer["PersistentKeepalive"]?.toIntOrNull() ?: 0
            peerBean.peerAllowedIps = peer["AllowedIPs"].orEmpty()
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any, depth: Int = 0): List<AbstractBean> {
        require(depth <= 32) { "JSON subscription nesting exceeds limit" }
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                // SIP008 online config: {"version": 1, "servers": [{server, server_port,
                // password, method, plugin, plugin_opts, remarks}, ...]}
                json.optJSONArray("servers") != null -> {
                    return json.getJSONArray("servers")
                        .filterIsInstance<JSONObject>()
                        .filter { it.has("server") }
                        .mapNotNull { entry -> runCatching { entry.parseShadowsocks() }.getOrNull() }
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") -> {
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else if (json is JSONArray) {
            // Scalars and malformed nodes must not discard valid siblings.
            json.forEach { _, entry ->
                if (entry is JSONObject || entry is JSONArray) {
                    runCatching { parseJSON(entry, depth + 1) }
                        .getOrNull()?.let { proxies.addAll(it) }
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
