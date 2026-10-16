package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util

// BUG-001 彻底方案（libcore 逐出站试建）的 Kotlin 一侧：内部核心跳的 sing-box 出站 / 端点在规划时交给 libcore 的
// TrialSingBoxOutbound 只解析与构造（不监听、不拨号），把 sing-box 加载期才做的格式类校验（证书 PEM、SSH 密钥、
// SS2022 密钥、WireGuard 密钥等）提前到规划。失败抛 SingBoxTrialException，与 UnsupportedSingBoxValueException
// 同为 IllegalArgumentException：经 withProfileName 带上节点名，选择器成员与路由规则目标由 planOrSkip 跳过，
// 选中的节点整次构建失败。SingBoxValueSets.kt 的名单与 TlsFields.kt 的 uTLS 预检先跑（能回显合格的取值），
// 试建是兜底的第二道；外核跳不试建（同 SingBoxValueSets 的边界）。
//
// 脱敏：sing-box 的原始报错可能带出证书、私钥、路径等原文，只留在 Go 里；跨 JNI 的只有封闭的类别标识，解析类
// 错误另附一个 snake_case 字段路径（SING_BOX_TRIAL_PATH，不合格就丢掉）。异常消息、日志里只有类别说明与路径，
// 没有任何字段值。

/** 试建的调用种类，与写进配置时放 endpoints 还是 outbounds 的判断相同（commitHop）。wire 是交给 libcore 的取值。 */
enum class SingBoxTrialKind(val wire: String) {
    OUTBOUND("outbound"),
    ENDPOINT("endpoint");

    companion object {
        fun of(option: SingBoxOption): SingBoxTrialKind =
            if (option is SingBoxOptions.Endpoint) ENDPOINT else OUTBOUND
    }
}

/**
 * 试建失败的类别：封闭集合，标识（枚举名）与 libcore 回传的类别一一对应。reason 是构建报错里的英文说明（与现有
 * 构建报错同为英文），为 null 的只说「sing-box 拒绝了出站选项」。libcore 回传不认识的标识时归 [OTHER]。
 * 跳过诊断（ProfileSkipped.reason）与构建失败都用这段英文异常文本（同 UnsupportedSingBoxValueException），不另设界面文案。
 * SingBoxTrialCategoryWireTest 读 libcore/outbound_trial.go 核对两边的标识一一对应。
 */
enum class SingBoxTrialCategory(val reason: String?) {
    DECODE("invalid option"),
    UNKNOWN_TYPE("unknown outbound type"),
    SS_KEY_BASE64("Shadowsocks 2022 key is not valid base64"),
    SS_KEY_LENGTH("Shadowsocks 2022 key length does not match the method"),
    PASSWORD_MISSING("password is empty"),
    SS_PLUGIN("invalid Shadowsocks plugin options"),
    TLS_CERTIFICATE("certificate is not a valid PEM certificate"),
    TLS_CERTIFICATE_FILE("certificate file cannot be read"),
    TLS_ECH_CONFIG("ECH config is not a valid ECH CONFIGS PEM block"),
    TLS_REALITY("invalid REALITY public key or short ID"),
    TLS_UTLS_FINGERPRINT("unknown uTLS fingerprint"),
    TLS_OPTIONS("invalid TLS options"),
    TLS_REQUIRED("this protocol requires TLS"),
    SSH_PRIVATE_KEY("SSH private key cannot be parsed"),
    SSH_PRIVATE_KEY_PASSPHRASE("SSH private key passphrase is missing or wrong"),
    SSH_HOST_KEY("SSH host key cannot be parsed"),
    WG_PRIVATE_KEY("WireGuard private key must be 32 bytes of base64"),
    WG_PEER_PUBLIC_KEY("WireGuard peer public key must be 32 bytes of base64"),
    WG_PRE_SHARED_KEY("WireGuard pre-shared key must be 32 bytes of base64"),
    WG_PEER("invalid WireGuard peer or address"),
    UUID("invalid UUID"),
    VLESS_FLOW("unsupported VLESS flow"),
    PACKET_ENCODING("unknown packet encoding"),
    TRANSPORT("invalid transport options"),
    MULTIPLEX("invalid multiplex options"),
    HYSTERIA("invalid Hysteria options"),
    VALUE_SET("unsupported enum value"),
    DIALER("invalid dial options"),
    OTHER(null);

    companion object {
        /** libcore 回传的类别标识；不认识的归 [OTHER]。 */
        fun of(wire: String): SingBoxTrialCategory = entries.firstOrNull { it.name == wire } ?: OTHER
    }
}

/** libcore 回传的「没有结论」（基础实例建不起来、构造 panic）：不拦节点，真实启动照旧是最后一道检查。 */
val SING_BOX_TRIAL_NO_VERDICT: Set<String> = setOf("UNAVAILABLE", "PANIC")

// 解析类错误附带的字段路径：sing-box 选项键都是 snake_case，数组下标写成 [N]；不合格的路径不进消息与日志。
// 长度上限与 libcore/outbound_trial.go 的相同（SingBoxTrialCategoryWireTest 核对）
internal val SING_BOX_TRIAL_PATH = Regex("""^[a-z0-9_]+(\.[a-z0-9_]+|\[[0-9]+])*$""")
internal const val SING_BOX_TRIAL_PATH_MAX = 64

/** 一次试建的失败：类别与字段路径。路径不合格（形态不对或超过 64 字符）时为 null。 */
class SingBoxTrialFailure(val category: SingBoxTrialCategory, path: String?) {
    val path: String? = path?.takeIf { it.length <= SING_BOX_TRIAL_PATH_MAX && SING_BOX_TRIAL_PATH.matches(it) }

    override fun equals(other: Any?) = other is SingBoxTrialFailure && other.category == category && other.path == path
    override fun hashCode() = category.hashCode() * 31 + path.hashCode()
    override fun toString() = "SingBoxTrialFailure($category, $path)"
}

/** 试建失败：消息只有类别说明与字段路径，不含任何字段值。 */
class SingBoxTrialException(val category: SingBoxTrialCategory, val path: String?) :
    IllegalArgumentException(singBoxTrialMessage(category, path)) {
    constructor(failure: SingBoxTrialFailure) : this(failure.category, failure.path)
}

private fun singBoxTrialMessage(category: SingBoxTrialCategory, path: String?): String {
    val reason = category.reason ?: return "sing-box rejected the outbound options"
    val at = if (category == SingBoxTrialCategory.DECODE && path != null) " at $path" else ""
    return "sing-box rejected the outbound options: $reason$at"
}

/**
 * 读 libcore TrialSingBoxOutbound 的返回值：空串是通过；「类别」或「类别\t字段路径」是失败；没有结论
 * （[SING_BOX_TRIAL_NO_VERDICT]）当作通过，每种只告警一次。平台实现每次构建新建一个，所以是「每次构建一次」。
 */
class SingBoxTrialResultReader(private val warn: (String) -> Unit) {
    private val warned = HashSet<String>()

    fun read(result: String): SingBoxTrialFailure? {
        if (result.isEmpty()) return null
        val wire = result.substringBefore('\t')
        if (wire in SING_BOX_TRIAL_NO_VERDICT) {
            // wire 已核对在名单里，可以写进日志
            if (warned.add(wire)) warn("sing-box outbound trial gave no verdict: $wire")
            return null
        }
        return SingBoxTrialFailure(SingBoxTrialCategory.of(wire), result.substringAfter('\t', "").ifEmpty { null })
    }
}

/**
 * 一次构建的试建：同一次构建按（种类，JSON）记住平台的结论（同 [PluginQueries]），分组的前置 / 落地出现在每个
 * 成员的链里、复用的最后一跳会重复规划，记住后同一份 JSON 一次构建只过一次 JNI。
 */
internal class SingBoxTrials(private val platform: ConfigPlatform) {
    private val results = HashMap<Pair<SingBoxTrialKind, String>, SingBoxTrialFailure?>()

    /** 试建不通过时抛 [SingBoxTrialException]；通过或没有结论时什么都不做。 */
    fun require(kind: SingBoxTrialKind, json: String) {
        val key = kind to json
        if (key !in results) results[key] = platform.trialSingBoxOutbound(kind, json)
        results[key]?.let { throw SingBoxTrialException(it) }
    }
}

// 试建 JSON 里的占位 tag 与 detour：构造只要求 tag 非空；detour 的取值到第一次拨号才解析，有无才影响构造
const val SING_BOX_TRIAL_TAG = "neko-trial"
const val SING_BOX_TRIAL_DETOUR = "neko-trial-next"

/**
 * 试建用的 JSON：用同一个序列化与同样的合并次序（基本字段 → commitHop 写的 _hack_config_map → 自定义出站 JSON），
 * 与 commitHop 写入、序列化进配置的那份只差三处：tag 是占位；有 detour 时取值是占位；不写构建自己绑的
 * domain_resolver（它引用本次构建的分组 DNS；自定义出站 JSON 里写的照样合并，由 libcore 改指占位服务器）。
 * 主节点的 customConfigJson 与全局自定义配置属于整份配置，不反映在这里。
 *
 * outbound 与 multiplex 是 [planHop] 内部核心分支的结果（[HopCore.Internal]），调用时 outbound 的 _hack_* 都还空着；
 * 不改 outbound。firstDialing 就是 planHop 的同名参数：提交时 linkHop 在下一跳处给上一跳接 detour，只有上一跳有
 * 映射入站时改用路由规则，而内部核心跳从不建映射入站，所以内部核心跳在且仅在不是最先拨号的一跳时带 detour。
 */
internal fun singBoxTrialJson(
    outbound: SingBoxOption,
    multiplex: Map<String, Any?>?,
    bean: AbstractBean,
    firstDialing: Boolean,
): String {
    check(outbound._hack_config_map.isNullOrEmpty() && outbound._hack_custom_config == null) {
        "sing-box trial input must be built from the planned outbound before commit"
    }
    val map = outbound.asMap()
    val hack = LinkedHashMap<String, Any?>()
    if (multiplex != null) hack["multiplex"] = multiplex
    if (udpOverTcp(bean)) hack["udp_over_tcp"] = udpOverTcpOption(bean)
    hack["tag"] = SING_BOX_TRIAL_TAG
    if (!firstDialing) hack["detour"] = SING_BOX_TRIAL_DETOUR
    Util.mergeMap(map, hack)
    // requireBuildableHop 已解析过一次自定义出站 JSON，这里不会新抛
    Util.mergeJSON(map, bean.customOutboundJson.orEmpty())
    return gson.toJson(map)
}
