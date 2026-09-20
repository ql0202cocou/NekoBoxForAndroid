package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.parseNumericAddress
import io.nekohasekai.sagernet.ktx.splitHostPort
import io.nekohasekai.sagernet.ktx.usableNameservers
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

// The Clash API endpoint this build serves. The dashboard hands the API secret to this
// exact host:port and to nothing else, so both sides have to read the same constant.
const val CLASH_API_LISTEN = "$LOCALHOST:9090"

// Shape of the tags buildConfig generates itself (g-<entryId>, c-<chainId>-…);
// a user-chosen profile name matching it would collide with one.
private val GENERATED_TAG_SHAPE = Regex("g-\\d+|c-\\d+.*")

// sing-box 1.14 removed the legacy DNS server address format; map it to typed
// servers the same way sing-box 1.13's internal upgrade did.
private fun makeDnsServer(address: String, tag: String): DNSServerOptions {
    fun DNSServerOptions.setAuthority(authority: String) {
        val invalid = "Invalid DNS server authority"
        require(authority.isNotBlank() && authority.none { it.isWhitespace() || it in "/?#@" }) { invalid }
        val (host, portText) = authority.splitHostPort()
            ?: throw IllegalArgumentException(invalid)
        // Brackets promise an IPv6 literal, and only a bare one may keep its colons.
        if (authority.startsWith("[")) {
            require(':' in host && host.parseNumericAddress() != null) { invalid }
        } else {
            require(':' !in host || host.parseNumericAddress() != null) { invalid }
        }
        require(host.isNotBlank() && '[' !in host && ']' !in host) { invalid }
        server = host
        if (portText != null) {
            server_port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException(invalid)
        }
    }

    return DNSServerOptions().apply {
        this.tag = tag
        if (!address.contains("://")) {
            when (address) {
                "local" -> type = "local"
                else -> {
                    type = "udp"
                    setAuthority(address)
                }
            }
            return@apply
        }
        val rest = address.substringAfter("://")
        when (val scheme = address.substringBefore("://")) {
            "tcp", "udp", "tls", "quic" -> {
                type = scheme
                setAuthority(rest)
            }

            "https", "h3" -> {
                type = scheme
                setAuthority(rest.substringBefore("/"))
                if (rest.contains("/")) {
                    path = "/" + rest.substringAfter("/")
                }
            }

            // dhcp is deliberately absent: libcore registers no dhcp transport (no
            // with_dhcp tag, see box_include.go), so mapping it would only turn a
            // clear build error into an opaque box start failure
            else -> throw Exception("unsupported DNS server address: $address")
        }
    }
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    return ConfigBuild(proxy, forTest, forExport).build()
}

// One config build. The state below is shared by the sections build() runs
// in order; each section was a stretch of the former single buildConfig
// function and keeps its body, only the captured locals became properties.
// Sections that fill the sing-box options are extensions on MyOptions so
// their bodies read the same as inside the original MyOptions().apply.
private class ConfigBuild(
    val proxy: ProxyEntity, val forTest: Boolean, val forExport: Boolean,
) {

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    // a profile named like a built-in outbound tag would collide with it
    val reservedSelectorTags = setOf(TAG_PROXY, TAG_DIRECT, TAG_BYPASS, TAG_BLOCK)
    // profile ids whose outbounds are already built, including chain members
    // pulled in by resolveChain(): rebuilding them duplicates their tags and
    // sing-box rejects the whole config
    val builtProfiles = HashSet<Long>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(visiting: MutableSet<Long> = mutableSetOf()): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            // Guard against chain loops in already-corrupted data: track the
            // recursion stack and fail loudly instead of overflowing it.
            if (!visiting.add(id)) {
                error("chain loop detected: profile $id (${bean.name})")
            }
            try {
                val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
                val beansMap = beans.associateBy { it.id }
                val beanList = ArrayList<ProxyEntity>()
                for (proxyId in bean.proxies) {
                    val item = beansMap[proxyId]
                    if (item == null) {
                        // A partially missing chain keeps building with the
                        // remaining members (legacy semantics), but the
                        // dangling id should not vanish silently
                        Logs.w("chain profile $id references missing profile $proxyId, skipped")
                        continue
                    }
                    beanList.addAll(item.resolveChainInternal(visiting))
                }
                return beanList.asReversed()
            } finally {
                visiting.remove(id)
            }
        }
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        // a profile named like an auto-generated tag (g-<entryId>,
        // c-<chainId>-…) would collide with it; break the pattern up front —
        // appending "-N" alone keeps the "c-<digits>" prefix forever
        val base = if (name_.matches(GENERATED_TAG_SHAPE)) "p-$name_" else name_
        var name = base
        var count = 0
        while (selectorNames.contains(name) || name in reservedSelectorTags) {
            count++
            name = "$base-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        if (thisGroup != null) {
            if (thisGroup.frontProxy > 0 && frontProxy == null) {
                Logs.w("group $groupId front proxy ${thisGroup.frontProxy} no longer exists, ignored")
            }
            if (thisGroup.landingProxy > 0 && landingProxy == null) {
                Logs.w("group $groupId landing proxy ${thisGroup.landingProxy} no longer exists, ignored")
            }
        }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    // the group whose members become selector outbounds; null builds a plain
    // chain (tests and exports always do)
    val selectorGroup = group?.takeIf { !forTest && it.isSelector && !forExport }
    val buildSelector = selectorGroup != null
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    // per-group nameserver: resolve this group's node server domains with it,
    // multiple addresses (one per line) are used in order with fallback.
    // forTest honors it too: a fake node domain may only resolve through it.
    val groupNameservers = group?.proxyServerNameserver.usableNameservers()
    val groupNsDomains = LinkedHashSet<String>()
    // Parse once up front: both the DNS servers/rules below and the outbound
    // domain_resolver bindings in buildChain must reference only successfully
    // parsed servers. An address makeDnsServer cannot parse must not take down
    // the whole config build — skip it and keep the remaining servers.
    // (scheme + authority only get logged: a DoH path can embed tokens)
    val groupDnsServers = groupNameservers.mapIndexedNotNull { index, address ->
        runCatching {
            makeDnsServer(address, "dns-group-$index").apply {
                // no detour either (see dns-direct): 1.14 dials directly
                // by default and detouring to the empty direct outbound
                // kills the box at start
                domain_resolver = "dns-local"
            }
        }.getOrElse {
            Logs.w(
                "Skip unsupported group nameserver at index $index: ${it.javaClass.simpleName}"
            )
            null
        }
    }
    // libcore-only neko-sequential transport chaining the group nameservers
    // in order with dns-direct as the last resort
    val groupSequentialTag = "dns-node-${proxy.groupId}"
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    fun autoDnsDomainStrategy(s: String): String? {
        if (s.isNotEmpty()) {
            return s
        }
        return when (ipv6Mode) {
            IPv6Mode.DISABLE -> "ipv4_only"
            IPv6Mode.ENABLE -> "prefer_ipv4"
            IPv6Mode.PREFER -> "prefer_ipv6"
            IPv6Mode.ONLY -> "ipv6_only"
            else -> null
        }
    }

    // sing-box 1.14 has no server-level strategy (legacy DNS format removed):
    // the final server's strategy becomes the DNS default (below), the rest
    // are carried by the rule actions routing to each server.
    val directStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
    val remoteStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"))
    val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

    fun build(): ConfigBuildResult {
        val options = MyOptions().apply {
            applyLogAndClashApi()
            dns = DNSOptions().apply {
                servers = mutableListOf()
                rules = mutableListOf()
            }
            buildInbounds()
            initRoute()
            buildOutbounds()
            applyUserRules()
            buildDns()
            applyBuiltinRules()
            applyGroupNameserver()
            // 与 applyLogAndClashApi 排除 secret 同理：导出配置会被分享，
            // 不能混入本机全局自定义配置
            if (!forTest && !forExport) _hack_custom_config = DataStore.globalCustomConfig
        }
        val configMap = options.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        return ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            selectorGroup?.id ?: -1L
        )
    }

    private fun MyOptions.applyLogAndClashApi() {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = CLASH_API_LISTEN
                external_ui = "../files/yacd"
                // without a secret every app on the device can read the connection
                // list and switch nodes through the loopback port; an exported config
                // is shared, so it must not carry this install's secret
                if (!forExport) secret = DataStore.requireClashApiSecret()
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }
    }

    private fun MyOptions.buildInbounds() {
        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }
    }

    private fun MyOptions.initRoute() {
        outbounds = mutableListOf()
        endpoints = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            // Safety net for every dialer without an explicit resolver
            // (cross-group chain members, endpoints, custom JSON outbounds),
            // replacing the deprecated resolve-through-DNS-rules path
            // sing-box may remove. Exports skip it on purpose: vanilla
            // sing-box 1.14 still walks the kept DNS rules for outbounds
            // without a resolver, which preserves group nameserver fallback.
            if (!forExport) default_domain_resolver = DomainResolveOptions().apply {
                server = "dns-direct"
                if (!forTest) strategy = SingBoxOptionsUtil.domainStrategy("server")
            }
        }
    }

    // returns outbound tag
    // The state one hop of buildChain hands to the next.
    //
    // 隐含不变量：linkHop 在 index > 0 分支解引用 chain.pastEntity!! /
    // pastOutbound / pastInboundTag，其安全依赖「buildHop 因 linkHop 返回 null
    // 而提前返回（needGlobal 命中已构建的全局 outbound）只可能发生在
    // index == profileList.lastIndex 那一跳」——lastIndex 之后没有下一跳，
    // 这些字段不会再被读取；中间跳若提前返回，下一跳读到的是上一跳的状态
    // 或直接未初始化。修改 needGlobal 的置真条件时必须保住这条不变量。
    private class ChainState(val chainId: Long, val entity: ProxyEntity, val profileList: List<ProxyEntity>) {
        val chainTag = "c-$chainId"
        // chainTagOut: v2ray outbound tag for this chain
        var chainTagOut = ""
        val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
        var muxApplied = false
        lateinit var pastOutbound: SingBoxOption
        lateinit var pastInboundTag: String
        var pastEntity: ProxyEntity? = null
    }

    private fun MyOptions.buildChain(chainId: Long, entity: ProxyEntity): String {
        val profileList = entity.resolveChain()
        // A chain whose members all dangle resolves to nothing: the config would
        // lack the outbound rules reference and sing-box would fail with a cryptic
        // "outbound not found". Fail loudly here instead, like the loop guard.
        if (profileList.isEmpty()) {
            error("chain profile ${entity.id} (${entity.requireBean().displayName()}) has no valid member")
        }
        builtProfiles.add(entity.id)
        profileList.forEach { builtProfiles.add(it.id) }
        // dedup by id and keep insertion order: a HashSet<ProxyEntity>
        // collapses two fully identical entities (the collapsed node's
        // traffic never lands in the DB) and iterates in arbitrary order
        val chainTrafficList = (profileList + entity).distinctBy { it.id }

        val chain = ChainState(chainId, entity, profileList)
        externalIndexMap.add(IndexEntity(chain.externalChainMap))

        profileList.forEachIndexed { index, proxyEntity -> buildHop(chain, index, proxyEntity) }

        trafficMap[chain.chainTagOut] = chainTrafficList
        return chain.chainTagOut
    }

    private fun MyOptions.buildHop(chain: ChainState, index: Int, proxyEntity: ProxyEntity) {
        val bean = proxyEntity.requireBean()

        // only this group's own nodes: resolveChain() also pulls in the group's front/
        // landing proxy and any cross-group chain member, whose domains this group's
        // (often private, split-horizon) nameserver has no business being asked about
        if (groupNameservers.isNotEmpty() &&
            proxyEntity.groupId == proxy.groupId &&
            bean.serverAddress.isNotBlank() && !bean.serverAddress.isIpAddress()
        ) {
            groupNsDomains += bean.serverAddress
        }

        val tagOut = linkHop(chain, index, proxyEntity) ?: return
        val currentOutbound = buildHopOutbound(chain, proxyEntity, bean, tagOut)
        mapExternalHop(chain, index, proxyEntity, bean)

        // wireguard is an endpoint since sing-box 1.13; its tag still resolves as an outbound
        if (currentOutbound is SingBoxOptions.Endpoint) {
            endpoints.add(currentOutbound)
        } else {
            outbounds.add(currentOutbound)
        }
        chain.pastOutbound = currentOutbound
        chain.pastEntity = proxyEntity
    }

    // Settles the hop's outbound tag and wires the previous hop to it; null
    // when the hop is a global outbound built earlier, so there is nothing to build.
    private fun MyOptions.linkHop(chain: ChainState, index: Int, proxyEntity: ProxyEntity): String? {
        val profileList = chain.profileList
        val chainTag = chain.chainTag
        // tagOut: v2ray outbound tag for a profile
        // profile2 (in) (global)   tag g-(id)
        // profile1                 tag (chainTag)-(id)
        // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
        var tagOut = "$chainTag-${proxyEntity.id}"

        // 同一节点在链中出现两次时，保留基础名的两跳会生成同名 outbound，
        // sing-box 直接拒绝整个配置；给第二次起的出现追加序号。首尾跳随后
        // 会被改名（proxy / selector 显示名 / g-(id)），不受此影响
        if (profileList.subList(0, index).any { it.id == proxyEntity.id }) {
            tagOut += "-$index"
        }

        // needGlobal: can only contain one?
        var needGlobal = false

        // first profile set as global
        if (index == profileList.lastIndex) {
            needGlobal = true
            tagOut = "g-" + proxyEntity.id
            bypassDNSBeans += proxyEntity.requireBean()
        }

        // last profile set as "proxy"
        if (chain.chainId == 0L && index == 0) {
            tagOut = TAG_PROXY
        }

        // selector human readable name: use the profile being built,
        // not profileList[0] (which is the group's landing proxy when set)
        if (buildSelector && index == 0) {
            tagOut = selectorName(chain.entity.requireBean().displayName())
        }

        // an entry hop built earlier keeps its existing global tag ("proxy"
        // for the main profile, the display name for a selector member):
        // resolve it BEFORE the chain rule below, or the previous hop detours
        // to a g-<id> that is never emitted and sing-box refuses to start
        // with "dependency[g-N] not found"
        if (needGlobal) globalOutbounds[proxyEntity.id]?.let { tagOut = it }

        // chain rules
        if (index > 0) {
            // chain route/proxy rules
            // pastInboundTag is only assigned when the past profile got a
            // mapping inbound, which also requires canMapping() (NekoBean /
            // hy1 faketcp can't); those chain via detour like internal nodes
            val pastEntity = chain.pastEntity!!
            if (pastEntity.needExternal() && pastEntity.requireBean().canMapping()) {
                route.rules.add(Rule_DefaultOptions().apply {
                    inbound = listOf(chain.pastInboundTag)
                    outbound = tagOut
                })
            } else {
                // wireguard is an endpoint since sing-box 1.13, but the
                // endpoint options embed DialerOptions, so detour works
                // the same as for outbounds (the builder never sets
                // listen_port, which sing-box would reject with detour)
                chain.pastOutbound._hack_config_map["detour"] = tagOut
            }
        } else {
            // index == 0 means last profile in chain / not chain
            chain.chainTagOut = tagOut
        }

        // now tagOut is determined
        if (needGlobal) {
            globalOutbounds[proxyEntity.id]?.let {
                if (index == 0) chain.chainTagOut = it // single, duplicate chain
                return null
            }
            globalOutbounds[proxyEntity.id] = tagOut
        }
        return tagOut
    }

    private fun MyOptions.buildHopOutbound(
        chain: ChainState, proxyEntity: ProxyEntity, bean: AbstractBean, tagOut: String,
    ): SingBoxOption {
        val currentOutbound: SingBoxOption
        if (proxyEntity.needExternal()) { // externel outbound
            val localPort = mkPort()
            chain.externalChainMap[localPort] = proxyEntity
            currentOutbound = Outbound_SocksOptions().apply {
                type = "socks"
                server = LOCALHOST
                server_port = localPort
            }
        } else {
            // internal outbound

            currentOutbound = buildSingBoxOutbound(bean)

            // internal mux
            if (!chain.muxApplied) {
                val muxObj = proxyEntity.singMux()
                if (muxObj != null && muxObj.enabled) {
                    chain.muxApplied = true
                    currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                }
            }
        }

        // internal & external
        currentOutbound.apply {
            // udp over tcp
            if (udpOverTcp(bean)) {
                _hack_config_map["udp_over_tcp"] = true
            }

            // domain resolution: this group's own domain nodes resolve
            // through the group's ordered nameserver chain (the
            // neko-sequential transport); every other dialer falls
            // back to route.default_domain_resolver. Exports skip the
            // binding: neko-sequential is libcore-only, and vanilla
            // sing-box 1.14 still resolves outbounds through the kept
            // dns-group-N rules. User custom JSON merges after this
            // and wins if it carries its own domain_resolver.
            chain.pastEntity?.requireBean()?.apply {
                // don't loopback
                if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$serverAddress")
                }
            }
            if (!forExport && groupDnsServers.isNotEmpty() &&
                proxyEntity.groupId == proxy.groupId &&
                bean.serverAddress.isNotBlank() && !bean.serverAddress.isIpAddress()
            ) {
                _hack_config_map["domain_resolver"] = DomainResolveOptions().apply {
                    server = groupSequentialTag
                    if (!forTest) strategy = defaultServerDomainStrategy
                }
            }

            _hack_config_map["tag"] = tagOut

            _hack_custom_config = bean.customOutboundJson
        }
        return currentOutbound
    }

    private fun MyOptions.mapExternalHop(
        chain: ChainState, index: Int, proxyEntity: ProxyEntity, bean: AbstractBean,
    ) {
        // External proxy need a dokodemo-door inbound to forward the traffic
        // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
        bean.finalAddress = bean.serverAddress
        bean.finalPort = bean.serverPort
        if (bean.canMapping() && proxyEntity.needExternal()) {
            // With ss protect, don't use mapping
            var needExternal = true
            if (index == chain.profileList.lastIndex) {
                val pluginId = externalPluginId(bean)
                if (Plugins.isUsingMatsuriExe(pluginId)) {
                    needExternal = false
                } else if (Plugins.getPluginExternal(pluginId) != null) {
                    throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                }
            }
            if (needExternal) {
                val mappingPort = mkPort()
                bean.finalAddress = LOCALHOST
                bean.finalPort = mappingPort

                inbounds.add(Inbound_DirectOptions().apply {
                    type = "direct"
                    listen = LOCALHOST
                    listen_port = mappingPort
                    tag = "${chain.chainTag}-mapping-${proxyEntity.id}"

                    override_address = bean.serverAddress
                    override_port = effectiveServerPort(bean)

                    chain.pastInboundTag = tag

                    // no chain rule and not outbound, so need to set to direct
                    if (index == chain.profileList.lastIndex) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(tag)
                            outbound = TAG_DIRECT
                        })
                    }
                })
            }
        }
    }

    private fun MyOptions.buildOutbounds() {
        // build outbounds
        val selectorGroup = selectorGroup
        if (selectorGroup != null) {
            val list = SagerDatabase.proxyDao.getByGroup(selectorGroup.id)
            list.forEach {
                // already built inside another member's chain: rebuilding
                // would duplicate its outbound/inbound tags (same guard as
                // the extraProxies loop below)
                if (builtProfiles.contains(it.id)) {
                    // ...but it still needs a tagMap entry: profileTagMap drives
                    // selector switching, and without one, selecting this member
                    // while connected resolves to a blank tag and does nothing.
                    // Only the first hop of another member's chain has a global
                    // outbound of its own (globalOutbounds is filled at
                    // profileList.lastIndex, which resolveChain reverses to the
                    // hop dialed first).
                    val globalTag = globalOutbounds[it.id]
                    if (globalTag != null) {
                        tagMap[it.id] = globalTag
                        return@forEach
                    }
                    // A middle hop only has a chain-scoped tag. Build a
                    // standalone global outbound so the group member remains
                    // selectable and can be targeted by route rules.
                }
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                // a chain whose exit node is also a group member maps to that
                // member's tag via the globalOutbounds dedup — list it once
                outbounds = tagMap.values.distinct()
            })
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            // already built as a selector member or inside another chain:
            // rebuilding would duplicate its outbound/inbound tags
            if (builtProfiles.contains(key)) {
                val globalTag = globalOutbounds[key]
                if (globalTag != null) {
                    tagMap[key] = globalTag
                    return@forEach
                }
                // Middle chain hops have no reusable global tag. Give route
                // rules a standalone outbound instead of dropping the rule.
            }
            tagMap[key] = buildChain(key, p)
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })
    }

    private fun MyOptions.applyUserRules() {
        for (rule in extraRules) applyUserRule(rule)

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }
    }

    private fun MyOptions.applyUserRule(rule: RuleEntity) {
        if (rule.packages.isNotEmpty()) {
            PackageCache.awaitLoadSync()
        }
        if (!isVPN && rule.packages.isNotEmpty()) {
            // once per rule, not per package; buildConfig runs on a Looper-less
            // background thread in the :bg process, so post the Toast to main
            runOnMainDispatcher {
                Toast.makeText(
                    SagerNet.application,
                    SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val uidList = rule.packages.map {
            PackageCache[it]?.takeIf { uid -> uid >= 1000 }
        }.toHashSet().filterNotNull()
        val ruleSets = mutableListOf<RuleSet>()

        val ruleObj = Rule_DefaultOptions().apply {
            if (uidList.isNotEmpty()) {
                PackageCache.awaitLoadSync()
                user_id = uidList
            }
            var domainList: List<String>? = null
            if (rule.domains.isNotBlank()) {
                domainList = rule.domains.listByLineOrComma()
                makeSingBoxRule(domainList, false)
            }
            if (rule.ip.isNotBlank()) {
                makeSingBoxRule(rule.ip.listByLineOrComma(), true)
            }

            if (rule_set != null) generateRuleSet(rule_set, ruleSets)

            // A malformed range already fails box start ("bad port range");
            // a malformed single port must not be dropped silently, which
            // would widen the rule to every port.
            if (rule.port.isNotBlank()) {
                port = mutableListOf<Int>()
                port_range = mutableListOf<String>()
                rule.port.listByLineOrComma().map {
                    if (it.contains(":")) {
                        port_range.add(it)
                    } else {
                        port.add(it.toIntOrNull() ?: error("invalid dst port \"$it\" in rule ${rule.displayName()}"))
                    }
                }
            }
            if (rule.sourcePort.isNotBlank()) {
                source_port = mutableListOf<Int>()
                source_port_range = mutableListOf<String>()
                rule.sourcePort.listByLineOrComma().map {
                    if (it.contains(":")) {
                        source_port_range.add(it)
                    } else {
                        source_port.add(it.toIntOrNull() ?: error("invalid src port \"$it\" in rule ${rule.displayName()}"))
                    }
                }
            }
            if (rule.network.isNotBlank()) {
                network = listOf(rule.network)
            }
            if (rule.source.isNotBlank()) {
                source_ip_cidr = rule.source.listByLineOrComma()
            }
            if (rule.protocol.isNotBlank()) {
                protocol = rule.protocol.listByLineOrComma()
            }

            fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                return DNSRule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) user_id = uidList
                    domainList?.let { makeSingBoxRule(it) }
                }
            }

            when (rule.outbound) {
                -1L -> {
                    userDNSRuleList += makeDnsRuleObj().apply {
                        server = "dns-direct"
                        strategy = directStrategy
                    }
                }

                0L -> {
                    if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                        server = "dns-fake"
                        strategy = "ipv4_only"
                        inbound = listOf("tun-in")
                    }
                    userDNSRuleList += makeDnsRuleObj().apply {
                        server = "dns-remote"
                        strategy = remoteStrategy
                    }
                }

                -2L -> {
                    userDNSRuleList += makeDnsRuleObj().apply {
                        action = "predefined"
                        rcode = "NOERROR"
                    }
                }
            }

            outbound = when (val outId = rule.outbound) {
                0L -> TAG_PROXY
                -1L -> TAG_BYPASS
                -2L -> TAG_BLOCK
                else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
            }

            _hack_custom_config = rule.config
        }

        if (!ruleObj.checkEmpty()) {
            if (ruleObj.outbound.isNullOrBlank()) {
                runOnMainDispatcher {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // block 改用新的写法
                if (ruleObj.outbound == TAG_BLOCK) {
                    ruleObj.outbound = null
                    ruleObj.action = "reject"
                }
                route.rules.add(ruleObj)
                route.rule_set.addAll(ruleSets)
            }
        }
    }

    private fun MyOptions.buildDns() {
        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(makeDnsServer(
                it ?: throw Exception("No direct DNS, check your settings!"), "dns-direct"
            ).apply {
                // sing-box 1.14: a typed DNS server with no detour dials directly
                // with its own dialer, which is the intent here — an explicit
                // detour to the empty direct outbound fails the whole box start
                // ("detour to an empty direct outbound makes no sense")
                domain_resolver = "dns-local"
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(makeDnsServer(
                it ?: throw Exception("No remote DNS, check your settings!"), "dns-remote"
            ).apply {
                // remote DNS must leave through the tunnel like pre-1.14's
                // default-outbound behavior: 1.14 would dial it directly
                // (see dns-direct) — detour to the proxy outbound instead,
                // which is never an empty direct outbound, so the start-time
                // check above does not apply
                detour = TAG_PROXY
                domain_resolver = "dns-direct"
            })
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"
        // the final server's strategy becomes the DNS default (see directStrategy)
        dns.strategy = if (forTest) directStrategy else remoteStrategy

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }
    }

    private fun MyOptions.applyBuiltinRules() {
        if (forTest) {
            dns.rules = mutableListOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            // legacy inbound fields were removed in sing-box 1.13:
            // sniff / domain_strategy migrate to rule actions at the top
            genDomainStrategy(DataStore.resolveDestination).takeIf { it.isNotEmpty() }?.let {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "resolve"
                    _hack_config_map["strategy"] = it
                })
            }
            if (needSniff) route.rules.add(0, Rule_DefaultOptions().apply {
                action = "sniff"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // Source and destination conditions in one default rule are ANDed.
            // Separate rules reject multicast/reserved traffic in either direction.
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            route.rules.add(Rule_DefaultOptions().apply {
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    strategy = "ipv4_only"
                    disable_cache = true
                })
            }
            // avoid loopback: with route.default_domain_resolver in place,
            // dialer resolution no longer walks DNS rules, so this rule is
            // dead in-app. Exports keep it: vanilla sing-box 1.14 still
            // resolves outbounds through DNS rules.
            if (forExport) dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
                strategy = directStrategy
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                    strategy = directStrategy
                })
            }
        }
    }

    private fun MyOptions.applyGroupNameserver() {
        // per-group nameserver: this group's node server domains resolve via
        // it, multiple servers are tried in order. User-hijacked queries keep
        // using the DNS rules below (neko rule fallback); outbound resolution
        // binds to the neko-sequential transport instead (see buildChain).
        // Kept for forTest too: node domains must resolve or the test cannot
        // dial. Unparseable addresses were already skipped in groupDnsServers.
        if (groupDnsServers.isNotEmpty() && groupNsDomains.isNotEmpty()) {
            dns.servers.addAll(groupDnsServers)
            if (!forExport) dns.servers.add(DNSServerOptions().apply {
                type = "neko-sequential"
                tag = groupSequentialTag
                servers = groupDnsServers.map { it.tag } + "dns-direct"
            })
            val groupRules = groupDnsServers.map { dnsServer ->
                DNSRule_DefaultOptions().apply {
                    domain = groupNsDomains.toList()
                    server = dnsServer.tag
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(dnsServer.tag))
                    // fallback 是 neko 补丁专有字段（原版 sing-box 对 DNS 规则
                    // 禁未知字段，解析即硬错误），导出配置不能携带
                    if (!forExport) fallback = true
                }
            }
            // top priority DNS rules, in server order
            dns.rules.addAll(0, groupRules)
        }
    }
}
