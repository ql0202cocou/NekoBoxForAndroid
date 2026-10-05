package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.fmt.ExternalCoreLaunch
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.WrongPasswordReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

// 外核启动前校验、启动就绪与认证检查里碰进程、套接字的部分（plan.md K0 做法 3、5，K0b），由 BoxInstance 调用；
// 结论与报错文案在 fmt/ExternalCoreStartup.kt。只用 JDK 与协程：JVM 单测可以拿 /bin/sh 当校验进程、
// 本机 ServerSocket 当外核入站直接跑。三处等待都按 isCancelled（实例已关闭）与协程取消立即结束

/** 校验进程的结果：exitCode 为 null 表示超时被杀；output 是合并后的标准输出与标准错误，只留最后若干行。 */
class CheckProcessResult(val exitCode: Int?, val output: String)

private const val CHECK_POLL_MS = 20L
private const val CHECK_OUTPUT_LINES = 200
private const val CHECK_OUTPUT_LINE_CHARS = 8192
private const val CHECK_DRAIN_MS = 1000L
private const val CHECK_KILL_GRACE_MS = 1000L

/**
 * 跑一次校验进程直到退出、超时或被取消。超时返回 exitCode 为 null 的结果；isCancelled 为真或协程被取消时
 * 抛 CancellationException。没退出就返回或抛出时先 SIGTERM 并最多等 1 秒，
 * API 26 起仍没退出再强杀（与 GuardedProcessPool 相同），不留进程。进程起不来时 ProcessBuilder 的 IOException 原样抛出。
 */
suspend fun runCheckProcess(
    launch: ExternalCoreLaunch,
    workDir: File?,
    timeoutMs: Long,
    isCancelled: () -> Boolean,
): CheckProcessResult {
    val process = ProcessBuilder(launch.commands).directory(workDir).redirectErrorStream(true).apply {
        environment().putAll(launch.env)
    }.start()
    val name = File(launch.commands.first()).nameWithoutExtension
    val exit = CompletableDeferred<Int>()
    val lines = ArrayDeque<String>()
    // 输出读到 EOF 为止，只留最后若干行（报错在末尾）
    val reader = thread(name = "check-out-$name", isDaemon = true) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(lines) {
                    lines.addLast(line.take(CHECK_OUTPUT_LINE_CHARS))
                    if (lines.size > CHECK_OUTPUT_LINES) lines.removeFirst()
                }
            }
        } catch (_: IOException) {
        }
    }
    // waitFor 只能阻塞着等：放在线程里，协程这边按间隔轮询，以便及时响应取消与超时
    thread(name = "check-wait-$name", isDaemon = true) {
        exit.complete(process.waitFor())
    }
    try {
        runCatching { process.outputStream.close() }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val exitCode = withTimeoutOrNull(CHECK_POLL_MS) { exit.await() }
            if (exitCode != null) {
                // 进程已退出，管道写端随之关闭，读线程很快到 EOF；有继承了管道的子进程时也不无限等
                reader.join(CHECK_DRAIN_MS)
                return CheckProcessResult(exitCode, synchronized(lines) { lines.joinToString("\n") })
            }
            if (isCancelled()) throw CancellationException("external core check cancelled")
            if (System.nanoTime() - deadline >= 0) {
                return CheckProcessResult(null, synchronized(lines) { lines.joinToString("\n") })
            }
        }
    } finally {
        // 先 SIGTERM 等它退出（等待线程随之回收进程）；不退出且系统支持时再强杀
        if (!exit.isCompleted) withContext(NonCancellable) {
            process.destroy()
            if (withTimeoutOrNull(CHECK_KILL_GRACE_MS) { exit.await() } == null) {
                if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly()
            }
        }
        runCatching { process.inputStream.close() }
    }
}

/**
 * 一个要等的本机端口。strict 为真时连上后还要用 auth 完成 SOCKS5 用户名 / 密码握手（见 probeLocalPort），监听它的
 * 进程退出就立即结束等待；否则只等端口能连上，不发任何字节（auth 不用）。exitCode 返回监听它的进程最近一次退出的
 * 退出码，仍在运行为 null。
 */
class LocalPortTarget(val port: Int, val strict: Boolean, val auth: LocalSocksAuth?, val exitCode: () -> Int?) {
    init {
        require(!strict || auth != null) { "local port $port needs credentials for the SOCKS5 handshake" }
    }
}

/** 等待结束时一个端口的状态：ready 就绪；exitCode 非空表示等待期间进程退出；lastError 是最后一次探测失败的原因。 */
class LocalPortStatus(val ready: Boolean, val exitCode: Int?, val lastError: String?)

private const val READY_POLL_MS = 20L
private const val PROBE_TIMEOUT_MS = 200

/**
 * 逐个端口确认就绪（mihomo 的 listener 并发启动，最后一个能连上不代表前面的都好了）。直到每个端口都就绪或其
 * 进程已退出、某个 strict 端口的进程退出、或超时为止；不解析日志，也不访问远端。返回与 targets 一一对应。
 * 判定就绪时再确认一次 strict 端口的进程仍在：端口被同 uid 的别的进程同时监听（SO_REUSEPORT）时，探测可能先被
 * 它应答，而本实例的进程随后因故退出。isCancelled 为真或协程被取消时抛 CancellationException。
 */
suspend fun awaitLocalPorts(
    targets: List<LocalPortTarget>,
    timeoutMs: Long,
    isCancelled: () -> Boolean,
    probe: (port: Int, auth: LocalSocksAuth?) -> String? = ::probeLocalPort,
): List<LocalPortStatus> {
    val ready = BooleanArray(targets.size)
    val exitCodes = arrayOfNulls<Int>(targets.size)
    val errors = arrayOfNulls<String>(targets.size)
    fun statuses() = targets.indices.map { LocalPortStatus(ready[it], exitCodes[it], errors[it]) }
    fun cancelled() = CancellationException("closed while waiting for external cores")

    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (true) {
        var pending = false
        for ((i, target) in targets.withIndex()) {
            if (ready[i] || exitCodes[i] != null) continue
            if (isCancelled()) throw cancelled()
            val exitCode = target.exitCode()
            if (exitCode != null) {
                exitCodes[i] = exitCode
                if (target.strict) return statuses()
                continue
            }
            // 已过时限的不再探测：一个不应答的端口要等满探测超时，端口多时会把时限拖长
            if (System.nanoTime() - deadline >= 0) {
                pending = true
                continue
            }
            val error = probe(target.port, if (target.strict) target.auth else null)
            if (error == null) ready[i] = true else {
                errors[i] = error
                pending = true
            }
        }
        if (isCancelled()) throw cancelled()
        if (!pending || System.nanoTime() - deadline >= 0) break
        delay(READY_POLL_MS)
    }
    for ((i, target) in targets.withIndex()) {
        if (!ready[i] || !target.strict) continue
        target.exitCode()?.let {
            ready[i] = false
            exitCodes[i] = it
        }
    }
    return statuses()
}

private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

// 选中用户名 / 密码方法（05 02）且认证通过（01 00）
private val SOCKS5_AUTH_ACCEPTED = byteArrayOf(5, 2, 1, 0)

/**
 * 探测一次本机端口：就绪返回 null，否则返回原因（只有应答的字节与套接字的错误，不含凭据）。auth 为 null 时只等
 * 端口能连上，不发字节；否则一次写入 [socks5PasswordHandshake]，读满 4 字节，正好是 05 02 01 00 才算就绪，
 * 别的应答、读超时、连接被关闭都不算。
 */
fun probeLocalPort(port: Int, auth: LocalSocksAuth?): String? = try {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
        if (auth == null) return null
        socket.soTimeout = PROBE_TIMEOUT_MS
        socket.getOutputStream().apply {
            write(socks5PasswordHandshake(auth))
            flush()
        }
        val reply = ByteArray(SOCKS5_AUTH_ACCEPTED.size)
        val input = socket.getInputStream()
        var read = 0
        var ended: String? = null
        while (read < reply.size) {
            val n = try {
                input.read(reply, read, reply.size - read)
            } catch (_: SocketTimeoutException) {
                ended = "timed out"
                break
            }
            if (n < 0) {
                ended = "connection closed"
                break
            }
            read += n
        }
        if (ended == null && reply.contentEquals(SOCKS5_AUTH_ACCEPTED)) return null
        unexpectedReply(reply, read, ended)
    }
} catch (e: IOException) {
    e.message ?: e.javaClass.simpleName
}

// 没读到预期应答时的原因：读到的字节（十六进制）加提前结束的原因
private fun unexpectedReply(reply: ByteArray, read: Int, ended: String?): String {
    val got = if (read == 0) "no SOCKS5 reply" else "unexpected SOCKS5 reply " + reply.take(read).toHex()
    return got + ended?.let { " ($it)" }.orEmpty()
}

private fun List<Byte>.toHex() = joinToString(" ") { "%02x".format(it) }

// 认证检查按小段读，每段之间看一次关闭与取消
private const val AUTH_CHECK_READ_SLICE_MS = 50

/**
 * 认证检查的一次握手（结论在 fmt/ExternalCoreStartup.kt 的 externalAuthOutcome）：用 auth 的用户名与
 * [LocalSocksAuth.withWrongPassword] 的错误密码，一次写入与就绪探测同构的 [socks5PasswordHandshake]，
 * 读满 4 字节、读到对端关闭或超过 timeoutMs 为止。正好是 05 02 01 00 为 Accepted；读满且是 05 02 后跟非 00 的
 * 状态为 Rejected；其余（别的应答、断开、超时、连接错误）为 Unclear。isCancelled 为真或协程被取消时抛
 * CancellationException。
 */
suspend fun probeWrongPassword(
    port: Int,
    auth: LocalSocksAuth,
    timeoutMs: Long,
    isCancelled: () -> Boolean,
): WrongPasswordReply {
    val context = currentCoroutineContext()
    fun checkCancelled() {
        if (isCancelled()) throw CancellationException("closed while checking local inbound authentication")
        context.ensureActive()
    }
    checkCancelled()
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = AUTH_CHECK_READ_SLICE_MS
            socket.getOutputStream().apply {
                write(socks5PasswordHandshake(auth.withWrongPassword()))
                flush()
            }
            val reply = ByteArray(SOCKS5_AUTH_ACCEPTED.size)
            val input = socket.getInputStream()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var read = 0
            var ended: String? = null
            while (read < reply.size) {
                checkCancelled()
                val n = try {
                    input.read(reply, read, reply.size - read)
                } catch (_: SocketTimeoutException) {
                    if (System.nanoTime() - deadline >= 0) {
                        ended = "timed out"
                        break
                    }
                    continue
                }
                if (n < 0) {
                    ended = "connection closed"
                    break
                }
                read += n
            }
            when {
                ended != null -> WrongPasswordReply.Unclear(unexpectedReply(reply, read, ended))
                reply.contentEquals(SOCKS5_AUTH_ACCEPTED) -> WrongPasswordReply.Accepted
                reply[0] == SOCKS5_AUTH_ACCEPTED[0] && reply[1] == SOCKS5_AUTH_ACCEPTED[1] && reply[3] != 0.toByte() ->
                    WrongPasswordReply.Rejected(reply.toList().toHex())

                else -> WrongPasswordReply.Unclear(unexpectedReply(reply, read, null))
            }
        }
    } catch (e: IOException) {
        WrongPasswordReply.Unclear(e.message ?: e.javaClass.simpleName)
    }
}

/**
 * 只提供用户名 / 密码一种方法的问候 05 01 02，紧接 RFC 1929 认证包 01 <ULEN> <USER> <PLEN> <PASS>。
 * 两个内置核心都按这个顺序应答 05 02、01 00，所以一次写完再读。
 */
fun socks5PasswordHandshake(auth: LocalSocksAuth): ByteArray {
    val user = auth.username.toByteArray(Charsets.UTF_8)
    val pass = auth.password.toByteArray(Charsets.UTF_8)
    return byteArrayOf(5, 1, 2, 1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass
}
