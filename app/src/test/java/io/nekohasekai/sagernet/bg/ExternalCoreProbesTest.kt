package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.fmt.ExternalCoreLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// 启动前校验进程与启动就绪等待（plan.md K0 做法 3、5）：/bin/sh 当校验进程，本机 ServerSocket 当外核入站
class ExternalCoreProbesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = ArrayList<ServerSocket>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    // 一个本机监听：每个连接读到 request 那么多字节后回 reply（为 null 时什么都不回，连接挂着直到对端关闭）
    private fun listener(request: Int = 3, reply: ByteArray? = byteArrayOf(5, 0)): Int {
        val server = ServerSocket(0, 50, loopback)
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        runCatching {
                            val input = it.getInputStream()
                            repeat(request) { input.read() }
                            if (reply != null) it.getOutputStream().apply { write(reply); flush() }
                            input.read()
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    // 一个此刻没有人监听的端口
    private fun closedPort(): Int = ServerSocket(0, 50, loopback).use { it.localPort }

    private fun alive(): () -> Int? = { null }

    // ---- 就绪等待

    @Test
    fun `握手成功即就绪`() = runBlocking(Dispatchers.IO) {
        val port = listener()
        assertNull(probeLocalPort(port, socks5 = true))
        val started = System.nanoTime()
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, alive())), 5000, { false }).single()
        assertTrue(status.ready)
        assertNull(status.exitCode)
        assertTrue("就绪后立刻返回", System.nanoTime() - started < 1_000_000_000)
    }

    @Test
    fun `始终连不上时等到超时，带最后一次的原因`() = runBlocking(Dispatchers.IO) {
        val port = closedPort()
        val started = System.nanoTime()
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, alive())), 300, { false }).single()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(status.ready)
        assertTrue(status.lastError, status.lastError!!.isNotBlank())
        assertTrue("等到时限：$elapsedMs ms", elapsedMs >= 300)
        assertTrue("不超过时限太多：$elapsedMs ms", elapsedMs < 1500)
    }

    @Test
    fun `握手回了别的内容不算就绪，只连端口的不握手`() = runBlocking(Dispatchers.IO) {
        val rejecting = listener(reply = byteArrayOf(5, 0xff.toByte()))
        assertEquals("unexpected SOCKS5 reply 05 ff", probeLocalPort(rejecting, socks5 = true))
        // 接受连接却从不应答：握手读超时，只连端口时算就绪
        val silent = listener(reply = null)
        assertTrue(probeLocalPort(silent, socks5 = true)!!.isNotBlank())
        assertNull(probeLocalPort(silent, socks5 = false))

        val statuses = awaitLocalPorts(
            listOf(
                LocalPortTarget(rejecting, true, alive()),
                LocalPortTarget(silent, true, alive()),
                LocalPortTarget(silent, false, alive()),
            ),
            600, { false },
        )
        assertEquals(listOf(false, false, true), statuses.map { it.ready })
        assertEquals("unexpected SOCKS5 reply 05 ff", statuses[0].lastError)
    }

    @Test
    fun `部分端口就绪：逐个确认，返回各自的状态`() = runBlocking(Dispatchers.IO) {
        val first = listener()
        val missing = closedPort()
        val last = listener()
        val statuses = awaitLocalPorts(
            listOf(first, missing, last).map { LocalPortTarget(it, true, alive()) },
            300, { false },
        )
        assertEquals(listOf(true, false, true), statuses.map { it.ready })
    }

    @Test
    fun `端口晚一点才监听也能等到`() = runBlocking(Dispatchers.IO) {
        val port = closedPort()
        val late = async(Dispatchers.IO) {
            delay(150)
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(loopback, port))
            servers += server
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { s ->
                        repeat(3) { s.getInputStream().read() }
                        s.getOutputStream().write(byteArrayOf(5, 0))
                    }
                }
            }
        }
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, alive())), 5000, { false }).single()
        late.await()
        assertTrue(status.lastError, status.ready)
    }

    @Test
    fun `等待中实例被关闭立即停止`() = runBlocking(Dispatchers.IO) {
        val port = closedPort()
        val closed = AtomicBoolean(false)
        val started = System.nanoTime()
        thread(isDaemon = true) {
            Thread.sleep(100)
            closed.set(true)
        }
        try {
            awaitLocalPorts(listOf(LocalPortTarget(port, true, alive())), 10_000, closed::get)
            fail("应当抛 CancellationException")
        } catch (_: CancellationException) {
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("关闭后很快停止：$elapsedMs ms", elapsedMs < 1000)
    }

    @Test
    fun `等待中协程被取消立即停止`() = runBlocking(Dispatchers.IO) {
        val port = closedPort()
        val started = System.nanoTime()
        try {
            withTimeout(100) { awaitLocalPorts(listOf(LocalPortTarget(port, true, alive())), 10_000, { false }) }
            fail("应当被取消")
        } catch (_: CancellationException) {
        }
        assertTrue(System.nanoTime() - started < 1_000_000_000)
    }

    @Test
    fun `内置核心的进程退出立即结束，插件核心的退出只停止等它`() = runBlocking(Dispatchers.IO) {
        val missing = closedPort()
        val ready = listener()
        val started = System.nanoTime()
        val strict = awaitLocalPorts(
            listOf(LocalPortTarget(missing, true) { 255 }, LocalPortTarget(closedPort(), true, alive())),
            10_000, { false },
        )
        assertTrue("不等到超时", System.nanoTime() - started < 1_000_000_000)
        assertEquals(255, strict[0].exitCode)
        assertFalse(strict[0].ready)

        val lenient = awaitLocalPorts(
            listOf(LocalPortTarget(missing, false) { 1 }, LocalPortTarget(ready, false, alive())),
            10_000, { false },
        )
        assertEquals(1, lenient[0].exitCode)
        assertTrue(lenient[1].ready)
    }

    @Test
    fun `判定就绪时确认内置核心的进程仍在`() = runBlocking(Dispatchers.IO) {
        // 探测成功（可能是同 uid 的别的进程应答的），但本实例的进程随后退出
        val calls = AtomicInteger()
        val status = awaitLocalPorts(
            listOf(LocalPortTarget(1, true) { if (calls.incrementAndGet() > 1) 255 else null }),
            5000, { false },
        ) { _, _ -> null }.single()
        assertFalse(status.ready)
        assertEquals(255, status.exitCode)
    }

    // ---- 校验进程

    private fun sh(script: String) = ExternalCoreLaunch(listOf("/bin/sh", "-c", script))

    @Test
    fun `校验进程的退出码与合并后的输出`() = runBlocking(Dispatchers.IO) {
        val result = runCheckProcess(sh("echo out; echo err >&2; echo \"\$CHECK_ENV\"; exit 23").let {
            ExternalCoreLaunch(it.commands, mapOf("CHECK_ENV" to "env-ok"))
        }, tmp.root, 5000, { false })
        assertEquals(23, result.exitCode)
        assertEquals(listOf("out", "err", "env-ok"), result.output.lines())
        assertEquals(0, runCheckProcess(sh("true"), null, 5000, { false }).exitCode)
    }

    @Test
    fun `校验进程的输出只留末尾`() = runBlocking(Dispatchers.IO) {
        val script = "i=0; while [ \$i -lt 500 ]; do echo line-\$i; i=\$((i+1)); done"
        val result = runCheckProcess(sh(script), null, 5000, { false })
        val lines = result.output.lines()
        assertEquals(200, lines.size)
        assertEquals("line-499", lines.last())
    }

    // 校验进程把自己的 pid 写进文件后一直睡：返回或抛出之后它必须已经不在了
    private fun sleeper(pidFile: File) = sh("echo \$\$ > ${pidFile.absolutePath}; exec sleep 30")

    private fun processAlive(pid: String): Boolean =
        ProcessBuilder("/bin/sh", "-c", "kill -0 $pid 2>/dev/null").start().waitFor() == 0

    private fun awaitPid(pidFile: File): String {
        repeat(100) {
            pidFile.takeIf { it.length() > 0 }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(20)
        }
        error("pid file not written")
    }

    @Test
    fun `校验进程超时被杀`() = runBlocking(Dispatchers.IO) {
        val pidFile = tmp.newFile()
        val started = System.nanoTime()
        val result = runCheckProcess(sleeper(pidFile), null, 300, { false })
        assertNull(result.exitCode)
        assertTrue(System.nanoTime() - started < 3_000_000_000)
        assertFalse("超时后进程已被杀", processAlive(awaitPid(pidFile)))
    }

    @Test
    fun `实例被关闭时校验进程立即被杀`() = runBlocking(Dispatchers.IO) {
        val pidFile = tmp.newFile()
        val closed = AtomicBoolean(false)
        thread(isDaemon = true) {
            Thread.sleep(200)
            closed.set(true)
        }
        val started = System.nanoTime()
        try {
            runCheckProcess(sleeper(pidFile), null, 10_000, closed::get)
            fail("应当抛 CancellationException")
        } catch (_: CancellationException) {
        }
        assertTrue(System.nanoTime() - started < 3_000_000_000)
        assertFalse("取消后进程已被杀", processAlive(awaitPid(pidFile)))
    }

    @Test
    fun `协程被取消时校验进程立即被杀`() = runBlocking(Dispatchers.IO) {
        val pidFile = tmp.newFile()
        try {
            withTimeout(300) { runCheckProcess(sleeper(pidFile), null, 10_000, { false }) }
            fail("应当被取消")
        } catch (_: CancellationException) {
        }
        assertFalse("取消后进程已被杀", processAlive(awaitPid(pidFile)))
    }
}
