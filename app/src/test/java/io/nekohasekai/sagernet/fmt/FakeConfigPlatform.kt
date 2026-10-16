package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.net.InetAddress

/**
 * JVM 上的平台能力：端口从 firstPort 起递增；地址解析交给 parse；插件状态按两张表回答（表里没有的插件
 * 视为已安装、没有外部插件 app）；临时文件建在 tempDir（null 时不许建）；sing-box 试建交给 trials，默认一律通过。
 * 每次构建新建一个，查询与警告都记下来，测试据此核对构建查了什么、告了什么警。
 */
class FakeConfigPlatform(
    private val parse: (String) -> InetAddress? = { null },
    private val pluginErrors: Map<String, Exception> = emptyMap(),
    private val externalAuthorities: Map<String, String> = emptyMap(),
    private val tempDir: File? = null,
    firstPort: Int = 50001,
    private val trials: (SingBoxTrialKind, String) -> SingBoxTrialFailure? = { _, _ -> null },
) : ConfigPlatform {

    private var nextPort = firstPort

    /** 分出去的端口，按分配顺序。 */
    val ports = ArrayList<Int>()

    /** 每次插件查询：pluginError(<id>) / pluginExternalAuthority(<id>)，按查询顺序。 */
    val pluginQueries = ArrayList<String>()

    /** 建过的临时文件，按创建顺序。 */
    val tempFiles = ArrayList<File>()

    /** 警告：消息，带异常时接上「: 类名: 消息」。 */
    val warnings = ArrayList<String>()

    /** 每次 sing-box 试建的种类与 JSON，按查询顺序。 */
    val trialQueries = ArrayList<Pair<SingBoxTrialKind, String>>()

    override fun newPort(): Int = nextPort++.also { ports += it }

    override fun parseNumericAddress(text: String): InetAddress? = parse(text)

    override fun createTempFile(prefix: String, ext: String): File {
        val dir = checkNotNull(tempDir) { "this test does not expect temp files" }
        return File.createTempFile(prefix + "_", ".$ext", dir).also { tempFiles += it }
    }

    override fun pluginExternalAuthority(pluginId: String): String? {
        pluginQueries += "pluginExternalAuthority($pluginId)"
        return externalAuthorities[pluginId]
    }

    override fun pluginError(pluginId: String): Exception? {
        pluginQueries += "pluginError($pluginId)"
        return pluginErrors[pluginId]
    }

    override fun warn(message: String, error: Throwable?) {
        warnings += if (error == null) message else "$message: ${error.javaClass.name}: ${error.message}"
    }

    override fun trialSingBoxOutbound(kind: SingBoxTrialKind, json: String): SingBoxTrialFailure? {
        trialQueries += kind to json
        return trials(kind, json)
    }

    companion object {
        /**
         * 按表回答试建：表的键是（种类，JSON），JSON 按结构比较（键的次序、空白不计），表里没有的一律通过。
         * 让某个出站试建失败时，先用 singBoxTrialJson 得到它的试建 JSON 再放进表里。
         */
        fun trialTable(
            failures: Map<Pair<SingBoxTrialKind, String>, SingBoxTrialFailure>,
        ): (SingBoxTrialKind, String) -> SingBoxTrialFailure? {
            val table: Map<Pair<SingBoxTrialKind, JsonElement>, SingBoxTrialFailure> =
                failures.mapKeys { (key, _) -> key.first to JsonParser.parseString(key.second) }
            return { kind, json -> table[kind to JsonParser.parseString(json)] }
        }
    }
}

/** 测试用的设置：默认值同 DataStore 没存过任何键、三个 domain strategy 键存的是 auto 时。 */
fun testConfigSettings(
    serviceMode: String = "vpn",
    remoteDns: String = "https://dns.google/dns-query",
    directDns: String = "https://223.5.5.5/dns-query",
    ipv6Mode: Int = 0,
    enableClashAPI: Boolean = false,
    clashApiSecret: String? = null,
    globalAllowInsecure: Boolean = false,
) = ConfigSettings(
    serviceMode = serviceMode, allowAccess = false, bypassLanInCore = false,
    remoteDns = remoteDns, directDns = directDns, enableDnsRouting = true, enableFakeDns = true,
    trafficSniffing = 1, resolveDestination = false, ipv6Mode = ipv6Mode, logLevel = 0, mixedPort = 2080,
    mtu = 9000, tunImplementation = 0, globalCustomConfig = "", enableClashAPI = enableClashAPI,
    clashApiSecret = clashApiSecret, globalAllowInsecure = globalAllowInsecure,
    domainStrategyRemote = "", domainStrategyDirect = "", domainStrategyServer = "prefer_ipv4",
)
