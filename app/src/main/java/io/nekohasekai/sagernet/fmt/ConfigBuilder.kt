package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.splitHostPort
import io.nekohasekai.sagernet.ktx.usableNameservers
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.plugin.PluginManager
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.InetAddress

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

// 预检生成外部核心配置用的占位端口与占位凭据，生成的配置随即丢弃；不为预检消耗随机数
private const val PRECHECK_PORT = 1080
private val PRECHECK_AUTH = LocalSocksAuth("u" + "0".repeat(16), "p" + "0".repeat(32))

// sing-box 1.14 去掉了旧的 DNS 服务器地址格式：按 sing-box 1.13 内部升级的同样方式换成带类型的服务器。
// parseNumeric 解析数字地址字面量（不是数字地址时返回 null），由构建的平台适配对象提供
private fun makeDnsServer(address: String, tag: String, parseNumeric: (String) -> InetAddress?): DNSServerOptions {
    fun DNSServerOptions.setAuthority(authority: String) {
        val invalid = "Invalid DNS server authority"
        require(authority.isNotBlank() && authority.none { it.isWhitespace() || it in "/?#@" }) { invalid }
        val (host, portText) = authority.splitHostPort()
            ?: throw IllegalArgumentException(invalid)
        // Brackets promise an IPv6 literal, and only a bare one may keep its colons.
        if (authority.startsWith("[")) {
            require(':' in host && parseNumeric(host) != null) { invalid }
        } else {
            require(':' !in host || parseNumeric(host) != null) { invalid }
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
    // 构建登记的每条链上走外核的节点与它们的运行时数据，外核运行计划只从这里得到（ExternalRunPlan.from）
    val externalChains: List<ExternalChainRecord>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    // "outbound[2]" / "endpoint[0]" -> 节点名，按最终配置里的位置记录；见 withBoxErrorProfileName
    val boxIndexNames: Map<String, String> = emptyMap(),
    // 节点出站 / 端点的 tag -> 节点名
    val boxTagNames: Map<String, String> = emptyMap(),
    // 构建中收集到的诊断，提示由调用方生成；见 configBuildNotices
    val diagnostics: List<ConfigBuildDiagnostic> = emptyList(),
    // 本次构建的本机 socks 凭据：sing-box 里接入站支持认证的外核的 socks 出站都带它，外核运行计划
    // （ExternalRunPlan.from）把同一个值交给这些外核的入站。没有这样的跳实例时为 null
    val localAuth: LocalSocksAuth? = null,
    // 构建时随 ConfigSettings 采集的外核设置：预检用的就是它，组装、启动外核也用它（运行、测速、导出）。
    // 完整配置节点不采集设置，为 null，它也没有外核跳实例
    val externalCoreSettings: ExternalCoreSettings? = null,
    // 测速时是否让 mihomo 自己测延迟（TestInstance 的 mihomoController）：构建按 mihomoDelayTestApplies 用它采集的
    // 主分组行与设置算出，只有测速构建用到。完整配置节点为 false
    val delayTestOnMihomo: Boolean = false,
) {
    // 组装与启动外核用的设置；只在有外核跳实例时调用（那时一定有）
    fun requireExternalCoreSettings(): ExternalCoreSettings =
        checkNotNull(externalCoreSettings) { "this config build captured no external core settings" }

    // 写日志前按值遮蔽本次构建的本机 socks 凭据（与格式、键名无关）；按键名的脱敏另外照常做
    fun redactLocalAuth(text: String): String = localAuth?.redact(text) ?: text
}

private val BOX_INDEX_ERROR = Regex("initialize ((?:outbound|endpoint)\\[\\d+])")
private val BOX_TAG_ERROR = Regex("(?:outbound|endpoint)/[^\\[\\s]+\\[")

// sing-box 报错里的出站 / 端点在界面上认不出来，换成节点名：创建阶段只报
// "initialize outbound[序号]"，启动阶段报 "start outbound/vless[g-23]" 这类内部 tag。
// 对不上的（direct / selector、自定义配置里的出站）以及 tag 本身就是节点名的原样返回
fun ConfigBuildResult.withBoxErrorProfileName(e: Exception): Exception {
    val message = e.message ?: return e
    val name = BOX_INDEX_ERROR.find(message)?.let { boxIndexNames[it.groupValues[1]] }
        ?: BOX_TAG_ERROR.find(message)?.let { match ->
            // 节点名可能含 "]"（如 "[IPLC] 香港"），不能用正则截取 tag，逐个比对已知 tag；
            // 取最长的，免得 "a" 抢走 "a]b"
            val rest = message.substring(match.range.last + 1)
            boxTagNames.entries.filter { rest.startsWith(it.key + "]") }.maxByOrNull { it.key.length }
                ?.takeIf { it.key != it.value }?.value
        }
        ?: return e
    return ProfileBuildException(name, e)
}

// 某个节点的数据让构建失败：消息前加上节点名，否则分组里节点一多，用户看不出该改哪个
class ProfileBuildException(val profileName: String, cause: Throwable) :
    IllegalArgumentException("$profileName: ${cause.readableMessage}", cause)

// 把 block 抛出的异常包成 ProfileBuildException。已带节点名的不重复包；
// 缺插件保持原样，BaseService / 测试按类型识别它来引导安装插件；采集范围之外的输入不是节点的错，也不包
inline fun <T> withProfileName(bean: AbstractBean, block: () -> T): T = try {
    block()
} catch (e: ProfileBuildException) {
    throw e
} catch (e: PluginManager.PluginNotFoundException) {
    throw e
} catch (e: ConfigInputScopeException) {
    throw e
} catch (e: Exception) {
    throw ProfileBuildException(bean.displayName(), e)
}

// 构建的外壳：从设置、数据库与包名采集输入（captureConfigInput，见 ConfigInputAndroid.kt），连同本次构建的平台适配
// 对象交给纯构建入口；插件状态不在这里采集，由构建经平台按需查询。完整配置节点照旧先直接返回、不采集；
// 之后才检查 forTest / forExport 不能同时为真（ConfigBuildMode.of），顺序与以前一致。
// diagnostics 是诊断收集器：构建抛异常时调用方仍能从中拿到已收集的部分，
// 成功时 ConfigBuildResult.diagnostics 是同样的内容。newLocalAuth 生成本次构建的本机 socks 凭据，
// 只在第一个需要它的跳实例处调用一次（见 ConfigBuildResult.localAuth）；测试可传固定的生成方式
fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false,
    diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
    newLocalAuth: () -> LocalSocksAuth = LocalSocksAuth::random,
): ConfigBuildResult {
    if (proxy.isFullConfig()) return fullConfigResult(proxy)
    val mode = ConfigBuildMode.of(forTest, forExport)
    return buildConfig(captureConfigInput(proxy, mode), diagnostics, newLocalAuth)
}

// 纯构建入口：只消费 input，不读设置、数据库、包名与插件状态，也不改调用方的对象（主节点从 input.main 新建）。
// diagnostics、newLocalAuth 同上
fun buildConfig(
    input: ConfigInput,
    diagnostics: MutableList<ConfigBuildDiagnostic> = ArrayList(),
    newLocalAuth: () -> LocalSocksAuth = LocalSocksAuth::random,
): ConfigBuildResult {
    val proxy = input.main.newEntity()
    if (proxy.isFullConfig()) return fullConfigResult(proxy)
    return ConfigBuild(input, proxy, diagnostics, newLocalAuth).build()
}

// 完整配置节点的配置原样运行，没有外核
private fun fullConfigResult(proxy: ProxyEntity) = ConfigBuildResult(
    (proxy.requireBean() as ConfigBean).config,
    listOf(),
    proxy.id, //
    mapOf(TAG_PROXY to listOf(proxy)), //
    mapOf(proxy.id to TAG_PROXY), //
    -1L
)

// 完整配置型自定义节点（ConfigBean.type == 0）：整份配置原样运行，只能单独使用
internal fun ProxyEntity.isFullConfig() =
    type == TYPE_CONFIG && (requireBean() as ConfigBean).type == 0

// 普通构建（运行模式）得到的 ConfigBuildResult.selectorGroupId：只取决于节点所在分组。完整配置节点同 buildConfig
// 开头的分支为 -1，其余与 ConfigBuild 同一个判断（selectorGroupOf），分组读一次数据库。
// canReloadSelector 用它判断能否原地切换，不必为此构建整份配置
fun selectorGroupIdOf(proxy: ProxyEntity): Long {
    if (proxy.isFullConfig()) return -1L
    return selectorGroupOf(SagerDatabase.groupDao.getById(proxy.groupId), ConfigBuildMode.RUN)?.id ?: -1L
}

// 一次配置构建。下面的状态由 build() 依次运行的各段共用；每段原是以前单个 buildConfig 函数里的一截，
// 函数体保持原样，只是捕获的局部变量变成了属性。填 sing-box 选项的段写成 MyOptions 的扩展，读起来与在原来的
// MyOptions().apply 里一样。输入只来自 input（设置、数据快照、包名 UID、平台能力），不读 DataStore、DAO、
// PackageCache、插件，也不改调用方的对象；proxy 是从 input.main 新建的主节点，整次构建只此一份。
// 不碰 Android 运行环境，有两处例外，在 Android 上的行为与以前相同，只是不在「JVM 上可执行」的保证之内：
// 损坏数据的报错消息（类型是链而 bean 为空的行，requireBean 的消息经 displayType 取界面字符串）；
// NekoBean 的反序列化（用 org.json，JSON 损坏时调 Logs）
private class ConfigBuild(
    input: ConfigInput,
    val proxy: ProxyEntity,
    val diagnostics: MutableList<ConfigBuildDiagnostic>,
    val newLocalAuth: () -> LocalSocksAuth,
) {

    val settings = input.settings
    val data = input.data
    val platform = input.platform
    val packageUids = input.packageUids
    val forTest = input.mode == ConfigBuildMode.TEST
    val forExport = input.mode == ConfigBuildMode.EXPORT

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = HashSet<String>()
    // a profile named like a built-in outbound tag would collide with it
    val reservedSelectorTags = setOf(TAG_PROXY, TAG_DIRECT, TAG_BYPASS, TAG_BLOCK)
    // 全局「允许不安全」：影响 sing-box 出站的 TLS 与选核（needExternal 等）
    val globalAllowInsecure = settings.globalAllowInsecure
    val group = data.group(proxy.groupId)

    // 警告只记日志
    private val warn: (String) -> Unit = { platform.warn(it) }

    // 一次构建的插件查询，按插件记住结果（见 PluginQueries）
    private val plugins = PluginQueries(platform)

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

    // 根节点要建的整条链（倒序），见 ChainPlan.kt 的 resolveChain
    private fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> = resolveChain(data, proxy.groupId, group, warn)

    val extraRules = if (forTest) listOf() else data.enabledRules()

    // 按快照记下的数据源顺序建规则目标的出站
    val extraProxies =
        if (forTest) mapOf() else data.ruleTargets(ruleTargetIds(extraRules, proxy.id)).associateBy { it.id }
    // 成员建成 selector 出站的分组；为 null 时建单条链（测速与导出总是这样）。判断见 selectorGroupOf
    val selectorGroup = selectorGroupOf(group, input.mode)
    val buildSelector = selectorGroup != null
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    // per-group nameserver: resolve this group's node server domains with it,
    // multiple addresses (one per line) are used in order with fallback.
    // forTest honors it too: a fake node domain may only resolve through it.
    val groupNameservers = group?.proxyServerNameserver.usableNameservers(platform::parseNumericAddress)
    val groupNsDomains = LinkedHashSet<String>()
    // Parse once up front: both the DNS servers/rules below and the outbound
    // domain_resolver bindings in buildChain must reference only successfully
    // parsed servers. An address makeDnsServer cannot parse must not take down
    // the whole config build — skip it and keep the remaining servers.
    // (scheme + authority only get logged: a DoH path can embed tokens)
    val groupDnsServers = groupNameservers.mapIndexedNotNull { index, address ->
        runCatching {
            makeDnsServer(address, "dns-group-$index", platform::parseNumericAddress).apply {
                // no detour either (see dns-direct): 1.14 dials directly
                // by default and detouring to the empty direct outbound
                // kills the box at start
                domain_resolver = "dns-local"
            }
        }.getOrElse {
            platform.warn(
                "Skip unsupported group nameserver at index $index: ${it.javaClass.simpleName}"
            )
            null
        }
    }
    // libcore-only neko-sequential transport chaining the group nameservers
    // in order with dns-direct as the last resort
    val groupSequentialTag = "dns-node-${proxy.groupId}"
    val isVPN = settings.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && settings.allowAccess) "0.0.0.0" else LOCALHOST
    // 每行一个 DNS 服务器，跳过空行和 # 注释
    fun dnsLines(text: String) = text.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val remoteDns = dnsLines(settings.remoteDns)
    val directDNS = dnsLines(settings.directDns)
    val enableDnsRouting = settings.enableDnsRouting
    val useFakeDns = settings.enableFakeDns && !forTest
    val needSniff = settings.trafficSniffing > 0
    val externalChains = ArrayList<ExternalChainRecord>()
    // 本次构建的本机 socks 凭据，第一个需要它的跳实例处生成，之后共用；见 ConfigBuildResult.localAuth
    var localAuth: LocalSocksAuth? = null
    // 每个节点出站 / 端点的 tag -> 节点名，build() 末尾按最终配置换算成 boxIndexNames
    val hopNames = HashMap<String, String>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else settings.ipv6Mode

    // IPv6 模式对应的 sing-box domain strategy；模式值越界时为 null
    val ipv6Strategy = when (ipv6Mode) {
        IPv6Mode.DISABLE -> "ipv4_only"
        IPv6Mode.ENABLE -> "prefer_ipv4"
        IPv6Mode.PREFER -> "prefer_ipv6"
        IPv6Mode.ONLY -> "ipv6_only"
        else -> null
    }

    // DNS 服务器没单独设置 strategy 时按 IPv6 模式取
    fun autoDnsDomainStrategy(s: String): String? = s.ifEmpty { null } ?: ipv6Strategy

    // sing-box 1.14 has no server-level strategy (legacy DNS format removed):
    // the final server's strategy becomes the DNS default (below), the rest
    // are carried by the rule actions routing to each server.
    val directStrategy = autoDnsDomainStrategy(settings.domainStrategy("dns-direct"))
    val remoteStrategy = autoDnsDomainStrategy(settings.domainStrategy("dns-remote"))
    val defaultServerDomainStrategy = settings.domainStrategy("server")

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
            if (!forTest && !forExport) _hack_custom_config = settings.globalCustomConfig
        }
        val configMap = options.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        return ConfigBuildResult(
            gson.toJson(configMap),
            externalChains,
            proxy.id,
            trafficMap,
            tagMap,
            selectorGroup?.id ?: -1L,
            boxIndexNames(configMap),
            hopNames,
            diagnostics.toList(),
            localAuth,
            settings.externalCore,
            mihomoDelayTestApplies(proxy, group, globalAllowInsecure),
        )
    }

    // 必须在自定义配置合并之后取位置：合并可能增删出站，sing-box 报的是最终配置里的序号
    private fun boxIndexNames(configMap: Map<String, Any?>): Map<String, String> {
        val names = HashMap<String, String>()
        for (kind in arrayOf("outbound", "endpoint")) {
            (configMap["${kind}s"] as? List<*>)?.forEachIndexed { index, item ->
                val tag = (item as? Map<*, *>)?.get("tag") as? String ?: return@forEachIndexed
                hopNames[tag]?.let { names["$kind[$index]"] = it }
            }
        }
        return names
    }

    private fun MyOptions.applyLogAndClashApi() {
        if (!forTest && settings.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = CLASH_API_LISTEN
                external_ui = "../files/yacd"
                // 没有 secret 时设备上任何应用都能经回环端口读连接列表、切换节点；导出的配置会被分享，
                // 不能带本机的 secret。secret 由外壳在运行模式开了 Clash API 时取得（为空时生成并写回）
                if (!forExport) secret = checkNotNull(settings.clashApiSecret) { "clash api secret was not captured" }
            }
        }

        log = LogOptions().apply {
            level = when (settings.logLevel) {
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
                stack = when (settings.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = settings.mtu
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
                listen_port = settings.mixedPort
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
                if (!forTest) strategy = defaultServerDomainStrategy
            }
        }
    }

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
        // 这条链上走外核的节点，按 buildHop 的顺序登记
        val externalHops = ArrayList<ExternalHopRecord>()
        var muxApplied = false
        lateinit var pastOutbound: SingBoxOption
        lateinit var pastInboundTag: String
        var pastEntity: ProxyEntity? = null
    }

    private fun MyOptions.buildChain(chainId: Long, entity: ProxyEntity): String {
        val profileList = entity.resolveChain()
        requireBuildableChain(entity, profileList, globalAllowInsecure)
        // dedup by id and keep insertion order: a HashSet<ProxyEntity>
        // collapses two fully identical entities (the collapsed node's
        // traffic never lands in the DB) and iterates in arbitrary order
        val chainTrafficList = (profileList + entity).distinctBy { it.id }

        val chain = ChainState(chainId, entity, profileList)

        profileList.forEachIndexed { index, proxyEntity -> buildHop(chain, index, proxyEntity) }
        // 每条链登记一条（没有外核节点的也是），序号即链在全部已建链里的序号
        externalChains.add(ExternalChainRecord(chain.externalHops.toList()))

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
        val currentOutbound = withProfileName(bean) {
            val (outbound, socks) = buildHopOutbound(chain, proxyEntity, bean, tagOut)
            val target = mapExternalHop(chain, index, proxyEntity, bean)
            if (socks != null) {
                chain.externalHops += ExternalHopRecord(
                    proxyEntity.id, bean, externalCore(bean), socks.localPort, target, socks.localAuth,
                )
            }
            outbound
        }

        hopNames[tagOut] = bean.displayName()
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
        val existing = if (needGlobal) globalOutbounds[proxyEntity.id] else null
        if (existing != null) tagOut = existing

        // chain rules
        if (index > 0) {
            // chain route/proxy rules
            // pastInboundTag is only assigned when the past profile got a
            // mapping inbound, which also requires canMapping() (NekoBean /
            // hy1 faketcp can't); those chain via detour like internal nodes
            val pastEntity = chain.pastEntity!!
            if (pastEntity.needExternal(globalAllowInsecure) && pastEntity.requireBean().canMapping()) {
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

        // now tagOut is determined; a hop built earlier is not emitted again
        if (existing != null) return null
        if (needGlobal) globalOutbounds[proxyEntity.id] = tagOut
        return tagOut
    }

    // sing-box 连外核的本机 socks 端点：端口与凭据（核心的入站不认证时为 null）
    private class ExternalSocks(val localPort: Int, val localAuth: LocalSocksAuth?)

    // 这一跳的 sing-box 出站；走外核时另带 sing-box 连外核用的本机端口与凭据，由 buildHop 登记
    private fun MyOptions.buildHopOutbound(
        chain: ChainState, proxyEntity: ProxyEntity, bean: AbstractBean, tagOut: String,
    ): Pair<SingBoxOption, ExternalSocks?> {
        requireBuildableHop(proxyEntity, bean, globalAllowInsecure)
        val currentOutbound: SingBoxOption
        var socks: ExternalSocks? = null
        if (proxyEntity.needExternal(globalAllowInsecure)) { // 外部核心出站
            val localPort = platform.newPort()
            // 外核的入站要求认证时带上本次构建的凭据；外核一侧由运行计划从登记里取同一个值
            val auth = if (externalCore(bean)?.inboundAuth == true) {
                localAuth ?: newLocalAuth().also { localAuth = it }
            } else null
            socks = ExternalSocks(localPort, auth)
            currentOutbound = Outbound_SocksOptions().apply {
                type = "socks"
                server = LOCALHOST
                server_port = localPort
                if (auth != null) {
                    username = auth.username
                    password = auth.password
                }
            }
        } else {
            // internal outbound
            currentOutbound = buildInternalOutbound(bean, globalAllowInsecure)

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
        return currentOutbound to socks
    }

    // 外核节点的拨号目标：外核的流量要经 sing-box 的映射入站出去，才能用已 protect 的 socket；能映射的外核节点
    // 分一个映射端口、加映射入站，返回 Mapped。不映射的（不能映射的节点、hysteria 1 免映射的最先拨号的一跳）
    // 以及内部核心节点返回 Direct（内部核心节点的不用）
    private fun MyOptions.mapExternalHop(
        chain: ChainState, index: Int, proxyEntity: ProxyEntity, bean: AbstractBean,
    ): ExternalDialTarget {
        if (bean.canMapping() && proxyEntity.needExternal(globalAllowInsecure)) {
            // 自带 protect 的插件不用映射
            val needExternal = !(index == chain.profileList.lastIndex && hysteriaSkipsMapping(bean, plugins))
            if (needExternal) {
                val mappingPort = platform.newPort()

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
                return ExternalDialTarget.Mapped(mappingPort)
            }
        }
        return ExternalDialTarget.Direct
    }

    // 选择器成员 / 路由规则目标构建前的只读预检：把 buildChain 会走的检查空跑一遍，
    // 结果全部丢弃。buildChain 抛错时已写入一半的出站、入站与端口，只能事先判断。
    // 不能写 MyOptions 与本类的构建状态，也不能分配端口（platform.newPort），所以不调 mapExternalHop
    private fun precheck(entity: ProxyEntity) {
        val profileList = entity.resolveChain()
        requireBuildableChain(entity, profileList, globalAllowInsecure)
        profileList.forEachIndexed { index, hop ->
            val bean = hop.requireBean()
            withProfileName(bean) {
                requireBuildableHop(hop, bean, globalAllowInsecure)
                if (!hop.needExternal(globalAllowInsecure)) {
                    buildInternalOutbound(bean, globalAllowInsecure)
                    return@withProfileName
                }
                if (bean.canMapping() && index == profileList.lastIndex) hysteriaSkipsMapping(bean, plugins)
                // NekoBean 没有 ExternalCore，与 BoxInstance.init 一样跳过
                val core = externalCore(bean) ?: return@withProfileName
                // 经运行时同一个组装入口，给只有这一个跳实例的计划试生成一次。生成的配置丢弃，端口与凭据只是占位；
                // 不分配映射端口，拨号目标一律是「不映射、拨服务器本身」（与以前相同，生成器的输出与报错只取决于
                // 节点本身）。hysteria 1 会写 CA 临时文件，用完删掉
                val tempFiles = ArrayList<File>()
                try {
                    val probe = ExternalHop(
                        0, 0, hop.id, bean, PRECHECK_PORT, ExternalDialTarget.Direct, core,
                        PRECHECK_AUTH.takeIf { core.inboundAuth },
                    )
                    ExternalRunPlan(listOf(probe)).assemble({ prefix, ext ->
                        platform.createTempFile(prefix, ext).also { tempFiles.add(it) }
                    }, null, settings.externalCore)
                } finally {
                    tempFiles.forEach { runCatching { it.delete() } }
                }
                // 导出不需要装插件
                if (!forExport) plugins.requireAvailable(core.pluginId)
            }
        }
    }

    // 选择器成员 / 路由规则目标预检失败时跳过并告警，而不是拖垮整份配置。
    // 用户选中的节点不预检、照常报错——那是用户明确要用的。查了采集范围之外的输入是采集的缺漏，不跳过，整次构建失败
    private fun skipBroken(entity: ProxyEntity): Boolean {
        if (entity.id == proxy.id) return false
        try {
            precheck(entity)
            return false
        } catch (e: ConfigInputScopeException) {
            throw e
        } catch (e: Exception) {
            platform.warn("profile ${entity.id} skipped", e)
            val name = entity.requireBean().displayName()
            // 出错的就是这个成员本身时，消息已以它的名字开头；链成员里出错的另有其名
            diagnostics += ConfigBuildDiagnostic.ProfileSkipped(
                entity.id, name, e.readableMessage, e is ProfileBuildException && e.profileName == name
            )
            return true
        }
    }

    // 已作为别的链里最先拨号的一跳建过全局 outbound（globalOutbounds 在
    // profileList.lastIndex 处填入）的节点：只在它单独成链（所在分组没有前置 /
    // 落地）时直接复用那个 g-<id>，重建会让 outbound/inbound tag 重复、sing-box
    // 拒绝整份配置。有前置 / 落地时裸的 g-<id> 不经过它们，要 buildChain：
    // linkHop 会复用已建的 g-<id>，并在外面接上前置 / 落地
    private fun MyOptions.reuseOrBuildChain(id: Long, entity: ProxyEntity): String =
        globalOutbounds[id]?.takeIf { entity.resolveChain().size == 1 } ?: buildChain(id, entity)

    private fun MyOptions.buildOutbounds() {
        // build outbounds
        val selectorGroup = selectorGroup
        if (selectorGroup != null) {
            val list = data.profilesByGroup(selectorGroup.id)
            list.forEach {
                // 完整配置型成员不是出站，塞进来会让整份配置被拒；选中它时
                // selectorGroupIdOf 为 -1，走重启、由 buildConfig 开头单独运行
                if (it.isFullConfig()) return@forEach
                if (skipBroken(it)) return@forEach
                // 每个成员都要记进 tagMap：profileTagMap 驱动选择器切换，缺了它
                // 连接中选这个成员会解析成空 tag、什么都不做。中间跳只有链内 tag，
                // 照常单独建一个全局 outbound，让它仍可选、可被路由规则引用
                tagMap[it.id] = reuseOrBuildChain(it.id, it)
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
            // 已作为选择器成员建过的先查 tagMap：重建会让前置多跳链的中间跳
            // 重名，sing-box 拒绝整份配置；tagMap 也会被改成不在选择器里的 tag，
            // 连接中选它不生效。中间跳没有可复用的全局 tag，单独建一个，不能丢掉
            // 这条路由规则
            // 预检失败的目标跳过：规则随之落入「出站不存在」告警
            if (tagMap[key] == null && skipBroken(p)) return@forEach
            tagMap[key] = tagMap[key] ?: reuseOrBuildChain(key, p)
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
        // UID 由外壳在读取事务之后解析好（PackageCache），这里只查表；没解析过的包名是采集的缺漏，整次构建失败
        val uidList = rule.packages.map {
            if (it !in packageUids) throw ConfigInputScopeException("package $it is not in the config input")
            packageUids[it]?.takeIf { uid -> uid >= 1000 }
        }.toHashSet().filterNotNull()
        // 填了应用却一个 uid 都解析不到（已卸载 / 无效包名）时整条跳过：下面只在
        // uidList 非空时写 user_id，带 domain / ip 等其他条件的规则（及其 DNS 规则）
        // 会扩大到所有应用。先于下面「需要 VPN」的诊断判断，一条规则最多一条诊断
        if (rule.packages.isNotEmpty() && uidList.isEmpty()) {
            platform.warn("rule ${rule.displayName()}: none of its apps are installed, skipped")
            diagnostics += ConfigBuildDiagnostic.RuleAppsNotInstalled(rule.id, rule.displayName())
            return
        }
        // 每条规则记一次，不按包名逐个记
        if (!isVPN && rule.packages.isNotEmpty()) {
            diagnostics += ConfigBuildDiagnostic.RuleNeedsVpn(rule.id, rule.displayName())
        }
        val ruleSets = mutableListOf<RuleSet>()

        val ruleObj = Rule_DefaultOptions().apply {
            if (uidList.isNotEmpty()) user_id = uidList
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
            // 含 ":" 的是端口范围，其余必须是单个端口；返回 (单个端口, 范围)
            fun parsePorts(text: String, what: String): Pair<List<Int>, List<String>> {
                val (ranges, singles) = text.listByLineOrComma().partition { it.contains(":") }
                return singles.map {
                    it.toIntOrNull() ?: error("invalid $what port \"$it\" in rule ${rule.displayName()}")
                } to ranges
            }
            if (rule.port.isNotBlank()) {
                parsePorts(rule.port, "dst").let { (p, r) -> port = p; port_range = r }
            }
            if (rule.sourcePort.isNotBlank()) {
                parsePorts(rule.sourcePort, "src").let { (p, r) -> source_port = p; source_port_range = r }
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
                diagnostics += ConfigBuildDiagnostic.RuleOutboundMissing(rule.id, rule.displayName(), rule.outbound)
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

        dns.servers.add(makeDnsServer(
            directDNS.firstOrNull() ?: throw Exception("No direct DNS, check your settings!"),
            "dns-direct", platform::parseNumericAddress
        ).apply {
            // sing-box 1.14：不设 detour 的 typed DNS 服务器用自己的 dialer
            // 直连，正是这里要的；显式 detour 到空的 direct 出站反而会让整个
            // box 启动失败（"detour to an empty direct outbound makes no sense"）
            domain_resolver = "dns-local"
        })

        // Always use direct DNS for urlTest
        if (!forTest) dns.servers.add(makeDnsServer(
            remoteDns.firstOrNull() ?: throw Exception("No remote DNS, check your settings!"),
            "dns-remote", platform::parseNumericAddress
        ).apply {
            // 远程 DNS 必须经隧道出去，同 1.14 之前走默认出站的行为：1.14 会让它
            // 直连（见 dns-direct），所以 detour 到代理出站。代理出站不会是空的
            // direct 出站，上面那条启动时检查不受影响
            detour = TAG_PROXY
            domain_resolver = "dns-direct"
        })

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
            if (settings.resolveDestination) route.rules.add(0, Rule_DefaultOptions().apply {
                action = "resolve"
                // 越界的 IPv6 模式按默认的 prefer_ipv4
                _hack_config_map["strategy"] = ipv6Strategy ?: "prefer_ipv4"
            })
            if (needSniff) route.rules.add(0, Rule_DefaultOptions().apply {
                action = "sniff"
            })
            if (settings.bypassLanInCore) {
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
        // 分组的节点解析 DNS：本组节点的服务器域名用它解析，多台按顺序尝试。
        // 用户被劫持的查询走下面的 DNS 规则（neko 规则 fallback）；出站解析则绑定
        // neko-sequential 传输（见 buildHopOutbound）。forTest 同样保留：节点域名
        // 解析不了测试就拨不出去。解析不了的地址在 groupDnsServers 里已跳过
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
                    strategy = autoDnsDomainStrategy(settings.domainStrategy(dnsServer.tag))
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
