package io.nekohasekai.sagernet.fmt

import java.io.File
import java.io.IOException

// 外核启动前校验与启动就绪的纯逻辑（plan.md K0 做法 3、5）：校验报错怎样对回节点、校验与就绪等待的结论、
// 给用户的报错文案。跑进程、连端口的部分在 bg/ExternalCoreProbes.kt，由 BoxInstance 串起来。
// 校验入口、退出码与报错格式只对固定版本的内置 Xray（run -test）、mihomo（-t）核实过：外部插件 app
// 提供的版本与要装插件 app 的核心不校验，就绪也只等端口能连上

/**
 * 核心校验报错里的一条失败。tag（Xray 的出站 / 入站 tag、mihomo 的代理 / listener 名）或 position（mihomo
 * 按 0 起算的代理 / listener 序号，与组内跳实例的顺序相同）指向出错的跳实例，都为 null 表示对不回节点。
 * reason 是给用户看的原因：对得回时保留核心原文，只去掉定位用的前缀与 Go 包路径。
 */
class ExternalCheckError(val reason: String, val tag: String? = null, val position: Int? = null)

/** 一次校验的结论。 */
sealed class ExternalCheckResult {
    object Passed : ExternalCheckResult()

    /** 校验没能给出结论（超时、被信号杀掉、进程起不来）：照旧启动，由运行时的守护兜底，与没有校验时相同。 */
    class Inconclusive(val reason: String) : ExternalCheckResult()

    /** 配置被核心拒绝：不启动。error 对得回节点时是 ProfileBuildException，否则写明核心与它的原文。 */
    class Failed(val error: Exception) : ExternalCheckResult()
}

/** 校验没过的原因（核心原文）。对得回节点时作为 ProfileBuildException 的 cause，消息即「节点名: 原因」。 */
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

    /**
     * 校验进程跑完后的结论。exitCode 为 null 表示超时被杀。退出码 0 即通过；被信号杀掉不算配置的问题；
     * 其余非 0 都按配置被拒绝处理：两个核心的校验与启动走同一段加载，这里没过，启动时进程也会立刻退出。
     */
    fun result(group: ExternalCoreGroup, exitCode: Int?, output: String): ExternalCheckResult {
        if (exitCode == null) return ExternalCheckResult.Inconclusive("$coreName config check timed out")
        if (exitCode == 0) return ExternalCheckResult.Passed
        if (exitCode in SIGNAL_EXIT_CODES) {
            return ExternalCheckResult.Inconclusive("$coreName config check was killed (exit code $exitCode)")
        }
        val errors = findErrors(output)
        val first = errors.firstOrNull()
            ?: return ExternalCheckResult.Failed(ExternalCoreCheckException(unrecognized(exitCode, output)))
        val hop = first.tag?.let { tag -> group.hops.firstOrNull { it.inboundTag == tag || it.outboundTag == tag } }
            ?: first.position?.let { group.hops.getOrNull(it) }
        return ExternalCheckResult.Failed(
            if (hop != null) {
                ProfileBuildException(hop.bean.displayName(), ExternalCoreCheckException(first.reason))
            } else {
                ExternalCoreCheckException("$coreName config check failed: ${first.reason}")
            }
        )
    }

    // 退出码异常、输出里又找不到核心的报错行：带上退出码与输出的最后几行
    private fun unrecognized(exitCode: Int, output: String): String {
        val tail = output.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(UNRECOGNIZED_TAIL_LINES)
        if (tail.isEmpty()) return "$coreName config check failed with exit code $exitCode and no output"
        val text = tail.joinToString(" / ")
        val shown = if (text.length > UNRECOGNIZED_TAIL_CHARS) "…" + text.takeLast(UNRECOGNIZED_TAIL_CHARS) else text
        return "$coreName config check failed with exit code $exitCode: $shown"
    }

    companion object {
        // Process.waitFor() 对被信号杀掉的进程返回 128 + 信号值
        private val SIGNAL_EXIT_CODES = 129..159
        private const val UNRECOGNIZED_TAIL_LINES = 3
        private const val UNRECOGNIZED_TAIL_CHARS = 500
    }
}

// Xray 26.3.27 的 run -test：配置有错时退出码 23，标准输出里一行
// 「Failed to start: main: failed to load config files: [<文件>] > infra/conf: failed to build outbound config
// with tag out-2 > infra/conf: …」，各层用 " > " 连接、每层前面是 Go 包路径；tag 重复时是
// 「Failed to start: main: failed to create server > app/proxyman/outbound: existing tag found: out-1」
private const val XRAY_FAILED = "Failed to start: "
private val XRAY_TAG = Regex("""(?:\bwith tag|\bexisting tag found:) (\S+)$""")
private val GO_PACKAGE_PREFIX = Regex("""^(?:main|[\w.-]+(?:/[\w.-]+)+): """)

fun xrayCheckErrors(output: String): List<ExternalCheckError> = output.lineSequence().mapNotNull { line ->
    val at = line.indexOf(XRAY_FAILED)
    if (at < 0) return@mapNotNull null
    val text = line.substring(at + XRAY_FAILED.length).trim()
    val layers = text.split(" > ")
    val tagAt = layers.indexOfFirst { XRAY_TAG.containsMatchIn(it) }
    // 找不到 tag 的（配置整体解析失败等）对不回节点，原文照给
    if (tagAt < 0) return@mapNotNull ExternalCheckError(text)
    val tag = XRAY_TAG.find(layers[tagAt])!!.groupValues[1]
    // 原因取 tag 那一层之后的各层（tag 重复时那一层本身就是原因），去掉每层开头的 Go 包路径；
    // 之前的各层只是定位（含临时配置文件路径）
    val reason = layers.drop(tagAt + 1).ifEmpty { listOf(layers[tagAt]) }
        .joinToString(" > ") { it.replaceFirst(GO_PACKAGE_PREFIX, "") }
    ExternalCheckError(reason, tag = tag)
}.toList()

// mihomo v1.19.31 的 -t：失败时退出码 1，先输出 logrus 格式的错误行再输出「… test failed」，都在标准输出。
// 代理 / listener 按 0 起算的序号报（level=error msg="proxy 1: unsupport proxy type: anytlsx"），
// 名字重复时报名字（msg="proxy out-0 is the duplicate name"）
private val LOGRUS_ERROR = Regex("""\blevel=(?:error|fatal) msg="((?:[^"\\]|\\.)*)"""")
private val MIHOMO_INDEXED = Regex("""^(proxy|listener) (\d+): (.*)$""")
private val MIHOMO_DUPLICATE = Regex("""^(?:proxy|listener) (\S+) is the duplicate name$""")

fun mihomoCheckErrors(output: String): List<ExternalCheckError> = output.lineSequence().mapNotNull { line ->
    val message = LOGRUS_ERROR.find(line)?.groupValues?.get(1)?.let(::unquoteLogrus) ?: return@mapNotNull null
    MIHOMO_INDEXED.matchEntire(message)?.let { match ->
        val (kind, index, rest) = match.destructured
        // listener 由应用生成，出错时把「listener」留在原因里，免得看成节点本身的问题
        val reason = if (kind == "listener") "listener: $rest" else rest
        return@mapNotNull ExternalCheckError(reason, position = index.toInt())
    }
    MIHOMO_DUPLICATE.matchEntire(message)?.let { match ->
        return@mapNotNull ExternalCheckError(message, tag = match.groupValues[1])
    }
    ExternalCheckError(message)
}.toList()

// logrus 按 Go 的 %q 转义引号里的内容；还原引号与反斜杠，其余转义原样保留
private fun unquoteLogrus(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && (text[i + 1] == '"' || text[i + 1] == '\\')) {
            out.append(text[i + 1])
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * 一个跳实例的就绪等待结果。strict 为真表示它跑在内置的 Xray / mihomo 上：要求完成 SOCKS5 握手，等不到就失败；
 * 否则（插件核心、外部插件 app 提供的核心）只等端口能连上，等不到只警告。exitCode 是等待期间监听它的进程
 * 退出时的退出码，lastError 是最后一次探测失败的原因。
 */
class HopReadiness(
    val hop: ExternalHop,
    val strict: Boolean,
    val ready: Boolean,
    val exitCode: Int?,
    val lastError: String?,
)

/** 就绪等待的结论：failure 非空就不启动 box；warnings 记日志后照常启动。 */
class ExternalReadyOutcome(val failure: Exception?, val warnings: List<String>)

fun externalReadyOutcome(results: List<HopReadiness>, timeoutMs: Long): ExternalReadyOutcome {
    val warnings = ArrayList<String>()
    for (result in results) {
        if (result.strict || result.ready) continue
        val hop = result.hop
        warnings += if (result.exitCode != null) {
            "${hop.pluginId} exited with code ${result.exitCode} before ${hop.bean.displayName()} " +
                "(${localInbound(hop)}) was ready; starting anyway"
        } else {
            "${hop.pluginId}: ${hop.bean.displayName()} (${localInbound(hop)}) not ready after $timeoutMs ms" +
                "${result.lastError?.let { " ($it)" }.orEmpty()}; starting anyway"
        }
    }
    val strictFailures = results.filter { it.strict && !it.ready }
    // 进程退出排在前面：它是原因，同组其余跳实例的未就绪只是结果
    strictFailures.firstOrNull { it.exitCode != null }?.let { exited ->
        val core = exited.hop.core.check?.coreName ?: exited.hop.pluginId
        val message = "$core exited with code ${exited.exitCode} before its local inbounds were ready; " +
            "see the log for details"
        return ExternalReadyOutcome(IOException(message), warnings)
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
            IOException(
                "local inbounds of ${names.size} profiles not ready after $timeoutMs ms: $shown" +
                    if (more > 0) " and $more more" else ""
            )
        }
    }
    return ExternalReadyOutcome(failure, warnings)
}

private const val READY_FAILURE_NAMES = 5

private fun localInbound(hop: ExternalHop) = "$LOCALHOST:${hop.localPort}"
