package io.nekohasekai.sagernet.fmt

import java.io.File
import java.io.IOException

// 外核启动前校验与启动就绪的纯逻辑：校验报错怎样对回节点、校验与就绪等待的结论、
// 给用户的报错文案。跑进程、连端口的部分在 bg/ExternalCoreProbes.kt，由 BoxInstance 串起来。
// 校验入口、退出码与报错格式只对固定版本的内置 Xray（run -test）核实过：外部插件 app
// 提供的版本与要装插件 app 的核心不校验，就绪也只等端口能连上

/**
 * 核心校验报错里的一条失败。tag（Xray 的出站 / 入站 tag）指向出错的跳实例，为 null 表示对不回节点。
 * reason 只包含可展示的原因类别。
 */
class ExternalCheckError(val reason: String, val tag: String? = null)

/** 一次校验的结论。 */
sealed class ExternalCheckResult {
    object Passed : ExternalCheckResult()

    /** 校验没能给出结论（超时、被信号杀掉、进程起不来）：照旧启动，由运行时的守护兜底，与没有校验时相同。 */
    class Inconclusive(val reason: String) : ExternalCheckResult()

    /** 配置被核心拒绝：不启动。error 对得回节点时是 ProfileBuildException，否则写明核心与安全的原因。 */
    class Failed(val error: Exception) : ExternalCheckResult()
}

/** 校验没过的原因（安全的类别）。对得回节点时作为 ProfileBuildException 的 cause，消息即「节点名: 原因」。 */
class ExternalCoreCheckException(message: String) : IllegalArgumentException(message)

/**
 * 一种核心的校验入口。command 像 ExternalCore 的启动命令一样经 writeCacheFile 落盘配置（调用方校验完即删），
 * findErrors 从进程的合并输出（标准输出与标准错误）里按出现顺序取出失败。
 */
class ExternalCoreCheck(
    /** 对不回节点时报错里写的核心名。 */
    val coreName: String,
    private val buildCommand: (
        pluginPath: String,
        config: String,
        writeCacheFile: (String, String, String) -> File,
    ) -> ExternalCoreLaunch,
    private val findErrors: (output: String) -> List<ExternalCheckError>,
) {

    fun command(
        pluginPath: String,
        config: String,
        writeCacheFile: (String, String, String) -> File,
    ): ExternalCoreLaunch = buildCommand(pluginPath, config, writeCacheFile)

    // 守护进程也可能输出同一种拒绝行；其它运行日志由调用方按配置值脱敏。
    fun logLine(redacted: String): String {
        val error = findErrors(redacted).firstOrNull() ?: return redacted
        return "$coreName: " + listOfNotNull(error.tag, error.reason).joinToString(": ")
    }

    /**
     * 校验进程跑完后的结论。exitCode 为 null 表示超时被杀。退出码 0 即通过；被信号杀掉不算配置的问题；
     * 其余非 0 都按配置被拒绝处理：校验与启动走同一段加载，这里没过，启动时进程也会立刻退出。
     */
    fun result(group: ExternalCoreGroup, exitCode: Int?, output: String): ExternalCheckResult {
        if (exitCode == null) return ExternalCheckResult.Inconclusive("$coreName config check timed out")
        if (exitCode == 0) return ExternalCheckResult.Passed
        if (exitCode in SIGNAL_EXIT_CODES) {
            return ExternalCheckResult.Inconclusive("$coreName config check was killed (exit code $exitCode)")
        }
        val errors = findErrors(output)
        val first = errors.firstOrNull()
            ?: return ExternalCheckResult.Failed(ExternalCoreCheckException("$coreName config check failed with exit code $exitCode"))
        val hop = first.tag?.let { tag -> group.hops.firstOrNull { it.inboundTag == tag || it.outboundTag == tag } }
        return ExternalCheckResult.Failed(
            if (hop != null) {
                ProfileBuildException(hop.bean.displayName(), ExternalCoreCheckException(first.reason))
            } else {
                ExternalCoreCheckException("$coreName config check failed: ${first.reason}")
            }
        )
    }

    companion object {
        // Process.waitFor() 对被信号杀掉的进程返回 128 + 信号值
        private val SIGNAL_EXIT_CODES = 129..159
    }
}

// Xray v26.9.30 的 run -test（v26.10.10、v26.10.17、v26.10.21、v26.10.31 实测格式相同）：配置有错时退出码 23，标准输出里一行
// 「Failed to start: main: failed to load config files: [<文件>] > infra/conf: failed to build outbound config
// with tag out-2 > infra/conf: …」，各层用 " > " 连接、每层前面是 Go 包路径；tag 重复时是
// 「Failed to start: main: failed to create server > app/proxyman/outbound: existing tag found: out-1」
private const val XRAY_FAILED = "Failed to start: "
private val XRAY_TAG = Regex("""(?:failed to build (?:outbound|inbound) config with tag|existing tag found:) ((?:in|out)-[0-9]{1,10})$""")

// 核心可在任一层带出用户内容，只提取生成的 tag 和已知原因。未知格式保留拒绝结论。
fun xrayCheckErrors(output: String): List<ExternalCheckError> = output.lineSequence().mapNotNull { line ->
    val at = line.indexOf(XRAY_FAILED)
    if (at < 0) return@mapNotNull null
    val layers = line.substring(at + XRAY_FAILED.length).split(" > ")
    val tag = layers.firstNotNullOfOrNull { XRAY_TAG.find(it)?.groupValues?.get(1) }
    val reason = layers.firstNotNullOfOrNull { layer ->
        when {
            layer.startsWith("common/uuid: invalid UUID:") -> "invalid UUID: ***"
            layer.startsWith("encoding/hex: invalid byte:") -> "invalid byte: ***"
            layer.startsWith("infra/conf: invalid \"shortId\":") -> "invalid \"shortId\": ***"
            layer.startsWith("infra/conf: too long \"shortId\":") -> "too long \"shortId\": ***"
            layer.startsWith("infra/conf: invalid \"password\":") -> "invalid REALITY password"
            layer.startsWith("infra/conf: VLESS users: \"flow\" doesn't support") -> "unsupported VLESS flow"
            layer.contains("existing tag found:") && XRAY_TAG.containsMatchIn(layer) -> "duplicate tag"
            layer == "unexpected EOF" -> "unexpected EOF"
            else -> null
        }
    } ?: "invalid core configuration"
    ExternalCheckError(reason, tag)
}.toList()

/**
 * 一个跳实例的就绪等待结果。strict 为真表示它跑在内置的 Xray 上：要求用本机 socks 凭据完成 SOCKS5 握手，
 * 等不到就失败；否则（插件核心、外部插件 app 提供的核心）只等端口能连上，等不到只警告。exitCode 非空只表示监听
 * 它的进程已退出且当时没有在跑（守护放弃了它，或到期时仍未重启），值是退出码；退出后被守护重启、等待期间一直
 * 在跑的进程为 null，lastError 是最后一次探测失败的原因。
 */
class HopReadiness(
    val hop: ExternalHop,
    val strict: Boolean,
    val ready: Boolean,
    val exitCode: Int?,
    val lastError: String?,
    val abandoned: Boolean = false,
)

/** 就绪等待的结论：failure 非空就不启动 box；warnings 记日志后照常启动。 */
class ExternalReadyOutcome(val failure: Exception?, val warnings: List<String>)

fun externalReadyOutcome(results: List<HopReadiness>, timeoutMs: Long): ExternalReadyOutcome {
    val strictFailures = results.filter { it.strict && !it.ready }
    // 进程退出排在前面：它是原因，同组其余跳实例的未就绪只是结果
    strictFailures.firstOrNull { it.exitCode != null }?.let { exited ->
        val core = coreName(exited.hop)
        val message = "$core exited with code ${exited.exitCode} before its local inbounds were ready; " +
            "see the log for details"
        return ExternalReadyOutcome(IOException(message), emptyList())
    }
    // 守护提前放弃时其余端口只有当时的快照，不能给它们报超时。
    strictFailures.firstOrNull { it.abandoned }?.let {
        return ExternalReadyOutcome(IOException("${coreName(it.hop)} stopped before its local inbounds were ready"), emptyList())
    }
    val failure = when (strictFailures.size) {
        0 -> null
        1 -> strictFailures[0].let { result ->
            ProfileBuildException(
                result.hop.bean.displayName(),
                IOException(
                    "local inbound ${localInbound(result.hop)} not ready after $timeoutMs ms" +
                        result.lastError?.let { " ($it)" }.orEmpty()
                ),
            )
        }

        else -> {
            val names = strictFailures.map { it.hop.bean.displayName() }
            val shown = names.take(READY_FAILURE_NAMES).joinToString(", ")
            val more = names.size - READY_FAILURE_NAMES
            val counts = strictFailures.groupingBy { readinessReason(it.lastError) }.eachCount()
            val reasons = counts.entries.joinToString(", ") { (reason, count) -> "$reason: $count" }
            IOException(
                "local inbounds of ${names.size} profiles not ready after $timeoutMs ms: $shown" +
                    (if (more > 0) " and $more more" else "") + " ($reasons)"
            )
        }
    }
    if (failure != null) return ExternalReadyOutcome(failure, emptyList())
    val warnings = ArrayList<String>()
    for (result in results) {
        if (result.strict || result.ready) continue
        val hop = result.hop
        warnings += if (result.exitCode != null) {
            "${hop.pluginId} exited with code ${result.exitCode} before ${hop.bean.displayName()} " +
                "(${localInbound(hop)}) was ready; starting anyway"
        } else if (result.abandoned) {
            "${hop.pluginId} stopped before ${hop.bean.displayName()} (${localInbound(hop)}) was ready; starting anyway"
        } else {
            "${hop.pluginId}: ${hop.bean.displayName()} (${localInbound(hop)}) not ready after $timeoutMs ms" +
                "${result.lastError?.let { " ($it)" }.orEmpty()}; starting anyway"
        }
    }
    return ExternalReadyOutcome(null, warnings)
}

// ExternalCoreProbes 写进 lastError 的进程状态，readinessReason 据此归类：探测期间重启过、
// 就绪后退出过（到期时又在跑，说明已重启）、被守护放弃
internal const val PROBE_PROCESS_RESTARTED = "process restarted during the probe"
internal const val PROBE_PROCESS_EXITED = "process exited after the probe"
internal const val PROBE_PROCESS_STOPPED = "process was stopped"

private fun readinessReason(error: String?): String = when {
    error == null -> "not probed"
    error.contains("refused", ignoreCase = true) || error.contains("ECONNREFUSED") -> "connection refused"
    error.contains("timed out", ignoreCase = true) -> "timed out"
    error.contains("SOCKS5 reply") -> "SOCKS5 handshake failed"
    error == PROBE_PROCESS_RESTARTED || error == PROBE_PROCESS_EXITED -> "process restarted"
    error == PROBE_PROCESS_STOPPED -> "process stopped"
    else -> "connection error"
}

private const val READY_FAILURE_NAMES = 5

private fun localInbound(hop: ExternalHop) = "$LOCALHOST:${hop.localPort}"

// 启动时确认入站认证生效（K0b）：内置 Xray 的入站全部就绪之后、启动 box 之前，对每个这样的进程抽一个
// 入站，用正确的用户名与错误的密码握手一次。认证生效时立即拒绝；错误的密码被接受，说明入站实际上
// 不认证，不启动。只看握手的应答，不解析日志

/** 用错误的密码握手一次的结果。原因与应答里只有应答的字节与套接字的错误，不含凭据。 */
sealed class WrongPasswordReply {
    /** 应答正好是 05 02 01 00：错误的密码被接受，入站的认证没有生效。 */
    object Accepted : WrongPasswordReply()

    /** 选中用户名 / 密码方法后明确拒绝（05 02 后跟非 00 的状态）：认证生效。reply 是应答的十六进制。 */
    class Rejected(val reply: String) : WrongPasswordReply()

    /** 其余情况（别的应答、对端断开、读超时、连接错误）：没有被接受，但也不是预期的拒绝。 */
    class Unclear(val reason: String) : WrongPasswordReply()
}

/** 认证没有生效、拒绝启动时抛出。消息只写核心，不带节点名与凭据。 */
class ExternalCoreAuthException(message: String) : IllegalStateException(message)

/**
 * 要做认证检查的跳实例：strict 的进程（内置的 Xray）各取它的第一个跳实例，其余进程不查。
 * 同一进程的入站出自同一段配置生成，抽一个就能发现整个进程的认证没有生效；顺序同 processes。
 */
fun localAuthCheckHops(
    processes: List<ExternalCoreProcess>,
    strict: (ExternalCoreProcess) -> Boolean,
): List<ExternalHop> = processes.filter(strict).map { it.group.hops.first() }

/**
 * 认证检查的结论：有错误密码被接受就失败（不启动 box）；Unclear 的记一条警告后照常启动；Rejected 即通过。
 * 结论沿用 [ExternalReadyOutcome]。
 */
fun externalAuthOutcome(results: List<Pair<ExternalHop, WrongPasswordReply>>): ExternalReadyOutcome {
    val warnings = results.mapNotNull { (hop, reply) ->
        (reply as? WrongPasswordReply.Unclear)?.let {
            "${coreName(hop)}: local inbound ${localInbound(hop)} gave no clear rejection of a wrong password " +
                "(${it.reason}); starting anyway"
        }
    }
    val accepted = results.filter { it.second is WrongPasswordReply.Accepted }.map { coreName(it.first) }.distinct()
    val failure = if (accepted.isEmpty()) null else ExternalCoreAuthException(
        "${accepted.joinToString(", ")}: local inbound accepted a wrong password, so local SOCKS authentication " +
            "is not in effect; refusing to start"
    )
    return ExternalReadyOutcome(failure, warnings)
}

private fun coreName(hop: ExternalHop) = hop.core.check?.coreName ?: hop.pluginId
