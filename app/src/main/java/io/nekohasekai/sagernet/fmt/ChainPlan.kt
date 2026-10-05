package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.utils.Util
import java.io.File

// 配置构建里链的展开、逐跳检查与单跳规划（plan.md R2）。都是顶层函数，输入全部显式传入：数据快照、主节点所在分组、
// 设置里的全局「允许不安全」、警告回调与插件查询；不读也不写 ConfigBuild 的状态，JVM 单测可以直接调用。
// 数据快照每次查询都给新对象，所以同一节点在不同链里（或同一条链展开两次）是不同的对象

// 按用户填写的顺序展开链（含任意层嵌套），结果首项是第一跳；不是链的节点展开成它自己。
// membersOf 对非链节点返回 null；lookup 按 id 批量取成员，取不到的交给 onMissing 后跳过，
// 其余成员照常展开。只检查递归栈：链在自己的展开过程中再次出现才算循环引用，交给 onLoop
// 抛错（已损坏的数据，避免栈溢出）；同一节点或子链出现在不同位置照常重复展开
internal fun <T> expandChainInOrder(
    root: T,
    idOf: (T) -> Long,
    membersOf: (T) -> List<Long>?,
    lookup: (List<Long>) -> Map<Long, T>,
    onMissing: (chain: T, missingId: Long) -> Unit,
    onLoop: (chain: T) -> Nothing,
): MutableList<T> {
    val result = ArrayList<T>()
    val visiting = HashSet<Long>()
    fun expand(item: T) {
        val members = membersOf(item)
        if (members == null) {
            result.add(item)
            return
        }
        val id = idOf(item)
        if (!visiting.add(id)) onLoop(item)
        val found = lookup(members)
        for (memberId in members) {
            val member = found[memberId]
            if (member == null) {
                onMissing(item, memberId)
                continue
            }
            expand(member)
        }
        visiting.remove(id)
    }
    expand(root)
    return result
}

// 展开这个节点（是链时逐层展开成员，成员从快照取），返回的列表是倒序的（末尾是第一跳）：完整展开后只在这里反转一次。
// 缺失的成员告警后跳过；循环引用、损坏的行（requireBean）报错
internal fun ProxyEntity.resolveChainInternal(data: ConfigSnapshot, warn: (String) -> Unit): MutableList<ProxyEntity> =
    expandChainInOrder(
        this,
        idOf = { it.id },
        membersOf = { (it.requireBean() as? ChainBean)?.proxies },
        lookup = { ids -> data.profiles(ids).associateBy { it.id } },
        onMissing = { chain, missingId ->
            warn("chain profile ${chain.id} references missing profile $missingId, skipped")
        },
        onLoop = { chain -> error("chain loop detected: profile ${chain.id} (${chain.requireBean().name})") },
    ).asReversed()

// 一个根节点（主节点、选择器成员、路由规则目标）要建的整条链，倒序（末尾是最先拨号的一跳）：它自己展开后，按它所在
// 分组接上前置（最先拨号）与落地（出口）。分组看的是根节点自己的分组，链成员所在的分组不参与。
// mainGroupId / mainGroup 是主节点所在分组与构建开头从快照读到的那一行（库里没有时为 null）：选择器分组的成员同属
// 一组，直接复用这一行；其余分组查快照
internal fun ProxyEntity.resolveChain(
    data: ConfigSnapshot,
    mainGroupId: Long,
    mainGroup: ProxyGroup?,
    warn: (String) -> Unit,
): MutableList<ProxyEntity> {
    val thisGroup = if (groupId == mainGroupId) mainGroup else data.group(groupId)
    val frontProxy = thisGroup?.frontProxy?.let { data.profile(it) }
    val landingProxy = thisGroup?.landingProxy?.let { data.profile(it) }
    if (thisGroup != null) {
        if (thisGroup.frontProxy > 0 && frontProxy == null) {
            warn("group $groupId front proxy ${thisGroup.frontProxy} no longer exists, ignored")
        }
        if (thisGroup.landingProxy > 0 && landingProxy == null) {
            warn("group $groupId landing proxy ${thisGroup.landingProxy} no longer exists, ignored")
        }
    }
    // 列表是倒序的（末尾是第一跳）。前置 / 落地代理本身是链时展开成成员，
    // 否则原样加进来的 ChainBean 会在 buildInternalOutbound 报 "can't reach"。
    // 链的成员被删光时链行仍在，分组引用不会被 resetDanglingGroupProxies 清掉；
    // 静默丢掉会让流量绕过用户设的前置 / 落地，直接报错
    fun expand(hop: ProxyEntity, role: String) = hop.resolveChainInternal(data, warn).ifEmpty {
        error("group $groupId $role proxy ${hop.id} (${hop.displayName()}) has no valid member")
    }
    val list = resolveChainInternal(data, warn)
    if (frontProxy != null) {
        list.addAll(expand(frontProxy, "front"))
    }
    if (landingProxy != null) {
        list.addAll(0, expand(landingProxy, "landing"))
    }
    return list
}

// 整条链的检查。profileList 是 entity 展开后的整条链（resolveChain 的结果）
internal fun requireBuildableChain(entity: ProxyEntity, profileList: List<ProxyEntity>, globalAllowInsecure: Boolean) {
    // 成员全部悬空的链展开后什么都没有：配置里会缺少路由规则引用的出站，sing-box 只会报含糊的
    // "outbound not found"。与循环引用一样在这里明确报错
    if (profileList.isEmpty()) {
        error("chain profile ${entity.id} (${entity.requireBean().displayName()}) has no valid member")
    }
    // 同一个外部核心节点在链里出现两次（链编辑器允许重复加入，或分组前置同时
    // 是组内某条链的首跳）：映射入站 tag（<链 tag>-mapping-<节点 id>）重名，
    // sing-box 只会报 duplicate inbound tag。这里给出明确错误
    profileList.filter { it.needExternal(globalAllowInsecure) }.groupBy { it.id }.values
        .firstOrNull { it.size > 1 }?.let {
            error("profile ${it[0].id} (${it[0].requireBean().displayName()}) runs on an external core and appears twice in chain ${entity.id}")
        }
}

// 单跳的检查，与节点在链里的位置无关
internal fun requireBuildableHop(proxyEntity: ProxyEntity, bean: AbstractBean, globalAllowInsecure: Boolean) {
    // 用户要求固定证书却静默放行，比没有这个功能更危险：内部与外部核心都在这里拒绝
    if (proxyEntity.certificatePinUnsupported(globalAllowInsecure)) {
        error("this core cannot pin certificates; clear the fingerprint or use a core that supports it")
    }
    // mldsa65Verify 同理：sing-box 上会被悄悄丢掉
    proxyEntity.mldsa65VerifyUnsupported(globalAllowInsecure)?.let { error(it) }
    // 完整配置型自定义节点作为链成员、前置 / 落地或路由目标时，给出明确
    // 错误，而不是让 sing-box 以 "unknown outbound type" 拒绝整份配置
    if (!proxyEntity.needExternal(globalAllowInsecure) && proxyEntity.isFullConfig()) {
        error("a full-config profile can only run on its own")
    }
    // 自定义出站 JSON 要到 build() 末尾序列化时才解析，在这里先解析一次，
    // 出错时才能带上节点名、预检才能跳过这个成员
    if (!bean.customOutboundJson.isNullOrBlank()) Util.mergeJSON(HashMap<String, Any?>(), bean.customOutboundJson)
}

// 内部核心出站。ConfigBean（type 1）的 JSON 同理提前解析
internal fun buildInternalOutbound(bean: AbstractBean, globalAllowInsecure: Boolean): SingBoxOption =
    buildSingBoxOutbound(bean, globalAllowInsecure).also { if (it is CustomSingBoxOption) it.getBasicMap() }

/**
 * 一次构建的插件查询：ConfigPlatform 的两种查询（外部插件 app、插件可用性）按插件分别记住结果，查不到的也记下。
 * 同一次构建里每个插件每种查询只做一次（在 Android 上每次都是一轮 IPC）；次数与次序由 GoldenJvmPluginStateTest 核对。
 */
internal class PluginQueries(private val platform: ConfigPlatform) {
    private val authorities = HashMap<String, String?>()
    private val errors = HashMap<String, Exception?>()

    /** 外部插件 app 的 provider authority，没有外部插件 app 时为 null。 */
    fun externalAuthority(pluginId: String): String? {
        if (pluginId !in authorities) authorities[pluginId] = platform.pluginExternalAuthority(pluginId)
        return authorities[pluginId]
    }

    /** 插件不可用时抛出平台给的原因（同一个插件每次抛同一个异常对象）。 */
    fun requireAvailable(pluginId: String) {
        if (pluginId !in errors) errors[pluginId] = platform.pluginError(pluginId)
        errors[pluginId]?.let { throw it }
    }
}

// 链上最先拨号的一跳是否免映射：只有 hysteria 走 Matsuri exe 免映射（没装外部插件 app 时用内置的也算）；
// 装的是别的外部插件 app 时报不受支持。其余协议为否，不查插件
internal fun hysteriaSkipsMapping(bean: AbstractBean, plugins: PluginQueries): Boolean {
    if (bean !is HysteriaBean) return false
    val pluginId = externalCore(bean)!!.pluginId
    val authority = plugins.externalAuthority(pluginId)
    if (authority == null || authority.startsWith(Plugins.AUTHORITIES_PREFIX_NEKO_EXE)) return true
    throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
}

/** 外核跳经 sing-box 的映射入站拨号时，入站转去的服务器地址与端口（hysteria 取 serverPorts 的第一个）。 */
internal class HopMapping(val address: String, val port: Int)

/** 一跳由谁拨号，以及构建要写进配置的、只取决于节点本身的部分。 */
internal sealed interface HopCore {
    /**
     * 内部核心：outbound 是 sing-box 的出站 / 端点（tag、解析等由构建再填）；multiplex 是这一跳要带的多路复用选项，
     * 一条链里只有第一个开了多路复用的内部核心跳才有（见 [planHop] 的 muxApplied）。
     */
    class Internal(val outbound: SingBoxOption, val multiplex: Map<String, Any?>?) : HopCore

    /**
     * 外核（needExternal）：sing-box 经本机 socks 出站连它。core 为 null 的节点（NekoBean）没有外核条目，运行计划跳过它。
     * mapping 不为 null 时外核经 sing-box 的映射入站拨号；为 null 时不映射，外核自己拨服务器（节点不能映射，或链上
     * 最先拨号的 hysteria 免映射）。
     */
    class External(val core: ExternalCore?, val mapping: HopMapping?) : HopCore {
        /** 本机 socks 入站要求本次构建的凭据（核心声明的，见 [ExternalCore.inboundAuth]）。 */
        val needsLocalAuth: Boolean get() = core?.inboundAuth == true
    }
}

/** 一跳的规划：会失败的计算都已做完；端口、凭据与 tag 留给构建。entity / bean 是构建用的那一份。 */
internal class HopPlan(
    val entity: ProxyEntity,
    val bean: AbstractBean,
    val core: HopCore,
    /** sing-box 出站带 udp_over_tcp。 */
    val udpOverTcp: Boolean,
)

/**
 * 规划一跳：先做单跳检查（[requireBuildableHop]），再按核心分两支——内部核心建好 sing-box 出站并按 muxApplied
 * 定多路复用；外核定是否经映射。firstDialing 表示这一跳是链上最先拨号的一跳（倒序列表的末项）：只有它可能免映射，
 * 免映射的判断要查外部插件 app（经 plugins，按插件记住结果）。muxApplied 表示链上在它之前的内部核心跳已带了多路复用。
 *
 * 不分配端口、不生成凭据、不定 tag，不写任何构建状态（插件查询的记忆表除外）；出错时抛出的异常不带节点名，调用方
 * 经 withProfileName 包上。会抛出的检查依次是：证书固定、mldsa65Verify、完整配置节点、自定义出站 JSON（以上见
 * [requireBuildableHop]），然后内部核心建出站（[buildInternalOutbound] 的各种拒绝），或外核最先拨号的 hysteria
 * 装的不是 Matsuri exe 的插件。
 */
internal fun planHop(
    entity: ProxyEntity,
    bean: AbstractBean,
    firstDialing: Boolean,
    muxApplied: Boolean,
    globalAllowInsecure: Boolean,
    plugins: PluginQueries,
): HopPlan {
    requireBuildableHop(entity, bean, globalAllowInsecure)
    val core = if (entity.needExternal(globalAllowInsecure)) {
        // 外核的流量要经 sing-box 的映射入站出去，才能用已 protect 的 socket；自带 protect 的插件不用映射
        val mapped = bean.canMapping() && !(firstDialing && hysteriaSkipsMapping(bean, plugins))
        HopCore.External(externalCore(bean), if (mapped) HopMapping(bean.serverAddress, effectiveServerPort(bean)) else null)
    } else {
        val outbound = buildInternalOutbound(bean, globalAllowInsecure)
        // 链上已有内部核心跳带了多路复用时不再取这一跳的选项
        val mux = if (muxApplied) null else entity.singMux()
        HopCore.Internal(outbound, mux?.takeIf { it.enabled }?.asMap())
    }
    return HopPlan(entity, bean, core, udpOverTcp(bean))
}

// 两段式构建（plan.md R2）：每条链先规划（planChain，下面），通过之后才提交（ConfigBuild.commitChain：先按跳的顺序要齐
// 端口与凭据，再定 tag、接线，写出站 / 入站 / 路由规则，登记外核跳数据与 trafficMap）。会失败的计算都在规划里，
// 规划失败的链不留下任何输出；选择器成员与路由规则目标规划失败时整条跳过，提交用的就是通过的那份规划

/** 规划一条链用的输入：都取自本次构建，规划只读它们（插件查询的记忆表除外）。 */
internal class ChainPlanContext(
    val data: ConfigSnapshot,
    /** 主节点所在分组的 id 与构建开头从快照读到的那一行（库里没有时为 null），见 [resolveChain]。 */
    val mainGroupId: Long,
    val mainGroup: ProxyGroup?,
    /** 设置里的全局「允许不安全」：影响选核、sing-box 出站的 TLS 与单跳检查。 */
    val globalAllowInsecure: Boolean,
    val plugins: PluginQueries,
    /** 展开链时的警告（缺失的成员、失效的前置 / 落地），只记日志。 */
    val warn: (String) -> Unit,
)

/** 一条链的规划：entity 是根节点；hops 与 [resolveChain] 一样是倒序（首项是出口，末项是最先拨号的一跳），不为空。 */
internal class ChainPlan(val entity: ProxyEntity, val hops: List<HopPlan>)

/**
 * 规划一条链：只展开一次链（[resolveChain]），做整链检查（[requireBuildableChain]），再按跳的顺序（从出口起）逐跳
 * 规划（[planHop]）。对构建状态是纯的：不分配端口、不生成凭据、不定 tag，不写任何输出（插件查询的记忆表除外）。
 *
 * 检查类报错的次序与以前相同：展开链（含前置 / 落地）、整链检查，然后逐跳，每跳先 planHop、再 checkHop；逐跳的
 * 报错经 withProfileName 包上该跳的节点名。分端口与生成凭据不在此列：它们在提交里，整条链规划通过之后才做，所以
 * 一条链里既有检查不过的跳、分端口或生成凭据又会失败时，现在报的是检查。checkHop 是选择器成员与路由规则目标
 * 另做的检查（试生成外核配置、确认插件可用），在每跳规划之后、下一跳之前调用；主节点不传。
 *
 * 最后一跳（最先拨号）已作为全局出站建过时，提交会复用它、不再写它，这里照样规划与检查。选择器成员与路由规则目标
 * 以前的预检也检查它；主节点的链复用的那一跳与第一次建它时出自快照里同一条记录，检查只取决于节点内容、设置与记住的
 * 插件查询结果，第一次已经通过，结果相同。
 */
internal fun planChain(entity: ProxyEntity, context: ChainPlanContext, checkHop: (HopPlan) -> Unit = {}): ChainPlan {
    val globalAllowInsecure = context.globalAllowInsecure
    val profileList = entity.resolveChain(context.data, context.mainGroupId, context.mainGroup, context.warn)
    requireBuildableChain(entity, profileList, globalAllowInsecure)
    // 链上已有内部核心跳带了多路复用（见 planHop）
    var muxApplied = false
    val hops = profileList.mapIndexed { index, hopEntity ->
        val bean = hopEntity.requireBean()
        withProfileName(bean) {
            val hop = planHop(
                hopEntity, bean, index == profileList.lastIndex, muxApplied, globalAllowInsecure, context.plugins,
            )
            if ((hop.core as? HopCore.Internal)?.multiplex != null) muxApplied = true
            checkHop(hop)
            hop
        }
    }
    return ChainPlan(entity, hops)
}

// 试生成外核配置用的占位端口与占位凭据，生成的配置随即丢弃；不为它消耗随机数
private const val PRECHECK_PORT = 1080
private val PRECHECK_AUTH = LocalSocksAuth("u" + "0".repeat(16), "p" + "0".repeat(32))

/**
 * 试生成一个外核跳的配置（选择器成员 / 路由规则目标的规划检查之一）：经运行时同一个组装入口，给只有这一个跳实例的
 * 计划生成一次，生成的配置丢弃。端口与凭据只是占位，拨号目标一律是「不映射、拨服务器本身」（报错只取决于节点与
 * 外核设置，与这些占位无关），不分配端口、不生成凭据。hysteria 1 会写 CA 临时文件（createTempFile），用完删掉。
 */
internal fun probeExternalHop(
    hop: HopPlan,
    core: ExternalCore,
    settings: ExternalCoreSettings,
    createTempFile: (String, String) -> File,
) {
    val tempFiles = ArrayList<File>()
    try {
        val probe = ExternalHop(
            0, 0, hop.entity.id, hop.bean, PRECHECK_PORT, ExternalDialTarget.Direct, core,
            PRECHECK_AUTH.takeIf { core.inboundAuth },
        )
        ExternalRunPlan(listOf(probe)).assemble({ prefix, ext ->
            createTempFile(prefix, ext).also { tempFiles.add(it) }
        }, null, settings)
    } finally {
        tempFiles.forEach { runCatching { it.delete() } }
    }
}
