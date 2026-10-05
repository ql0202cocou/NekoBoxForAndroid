package io.nekohasekai.sagernet.bg

import android.os.Build
import io.nekohasekai.sagernet.fmt.ExternalCoreLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

// 外核启动前校验与启动就绪里碰进程、套接字的部分（plan.md K0 做法 3、5），由 BoxInstance 调用；
// 结论与报错文案在 fmt/ExternalCoreStartup.kt。只用 JDK 与协程：JVM 单测可以拿 /bin/sh 当校验进程、
// 本机 ServerSocket 当外核入站直接跑。两处等待都按 isCancelled（实例已关闭）与协程取消立即结束

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
 * 一个要等的本机端口。strict 为真时连上后还要完成 SOCKS5 无认证握手（发 05 01 00、收 05 00），监听它的进程
 * 退出就立即结束等待；否则只等端口能连上。exitCode 返回监听它的进程最近一次退出的退出码，仍在运行为 null。
 */
class LocalPortTarget(val port: Int, val strict: Boolean, val exitCode: () -> Int?)

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
    probe: (port: Int, socks5: Boolean) -> String? = ::probeLocalPort,
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
            val error = probe(target.port, target.strict)
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

/** 探测一次本机端口：就绪返回 null，否则返回原因。socks5 为真时还要完成 SOCKS5 无认证握手。 */
fun probeLocalPort(port: Int, socks5: Boolean): String? = try {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
        if (!socks5) return null
        socket.soTimeout = PROBE_TIMEOUT_MS
        socket.getOutputStream().apply {
            write(byteArrayOf(5, 1, 0))
            flush()
        }
        val reply = ByteArray(2)
        DataInputStream(socket.getInputStream()).readFully(reply)
        if (reply[0] == 5.toByte() && reply[1] == 0.toByte()) null
        else "unexpected SOCKS5 reply %02x %02x".format(reply[0], reply[1])
    }
} catch (e: IOException) {
    e.message ?: e.javaClass.simpleName
}
