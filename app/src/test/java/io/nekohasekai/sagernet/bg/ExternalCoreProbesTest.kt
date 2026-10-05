package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.fmt.ExternalCoreLaunch
import io.nekohasekai.sagernet.fmt.LocalSocksAuth
import io.nekohasekai.sagernet.fmt.WrongPasswordReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// 启动前校验进程与启动就绪等待（plan.md K0 做法 3、5，K0b 带认证的握手）：/bin/sh 当校验进程，本机 ServerSocket 当外核入站
class ExternalCoreProbesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = ArrayList<ServerSocket>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private val auth = LocalSocksAuth("u0123456789abcdef", "p0123456789abcdef0123456789abcdef")

    // 客户端应发出的字节：05 01 02，接 RFC 1929 认证包 01 <ULEN> <USER> <PLEN> <PASS>（不经生产代码拼）
    private val expectedHandshake = byteArrayOf(5, 1, 2, 1, 17) + auth.username.toByteArray() +
        byteArrayOf(33) + auth.password.toByteArray()

    private val accepted = byteArrayOf(5, 2, 1, 0)

    // 一个本机监听：每个连接读满 request 个字节（或读到对端关闭）后回 reply（为 null 时什么都不回）；
    // closeAfterReply 为真时回完就断开，否则挂着直到对端关闭。每个连接收到的全部字节在连接结束时记进 received
    private fun listener(
        request: Int = expectedHandshake.size,
        reply: ByteArray? = accepted,
        closeAfterReply: Boolean = false,
        received: MutableList<ByteArray>? = null,
    ): Int {
        val server = ServerSocket(0, 50, loopback)
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    val buffer = ByteArrayOutputStream()
                    socket.use {
                        try {
                            val input = it.getInputStream()
                            while (buffer.size() < request) {
                                val b = input.read()
                                if (b < 0) break
                                buffer.write(b)
                            }
                            if (reply != null) it.getOutputStream().apply { write(reply); flush() }
                            if (!closeAfterReply) {
                                while (true) {
                                    val b = input.read()
                                    if (b < 0) break
                                    buffer.write(b)
                                }
                            }
                        } catch (_: IOException) {
                        } finally {
                            received?.add(buffer.toByteArray())
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

    private fun awaitReceived(received: List<ByteArray>): ByteArray {
        repeat(100) {
            received.firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("server recorded no connection")
    }

    private fun assertNoCredentials(text: String?) {
        assertTrue("应当给出原因", !text.isNullOrBlank())
        assertFalse(text, auth.username in text!! || auth.password in text)
        assertFalse(text, wrongPassword in text)
    }

    // 认证检查应发出的字节：用户名不变，密码改掉最后一个字符（等长、不同；不经生产代码拼）
    private val wrongPassword = "p0123456789abcdef0123456789abcdex"
    private val expectedWrongHandshake = byteArrayOf(5, 1, 2, 1, 17) + auth.username.toByteArray() +
        byteArrayOf(33) + wrongPassword.toByteArray()

    // ---- 就绪探测

    @Test
    fun `带认证的探测一次写出 05 01 02 与认证包，应答 05 02 01 00 才就绪`() {
        assertArrayEquals(expectedHandshake, socks5PasswordHandshake(auth))
        val received = CopyOnWriteArrayList<ByteArray>()
        val port = listener(received = received)
        assertNull(probeLocalPort(port, auth))
        // 探测连接上收到的全部字节正好是握手，没有别的请求
        assertArrayEquals(expectedHandshake, awaitReceived(received))
    }

    @Test
    fun `其余应答、只回一半、直接断开都不算就绪，原因里没有凭据`() {
        fun probe(reply: ByteArray?, closeAfterReply: Boolean = true, request: Int = expectedHandshake.size) =
            probeLocalPort(listener(request = request, reply = reply, closeAfterReply = closeAfterReply), auth)

        // Xray：不接受用户名 / 密码方法，或认证失败
        probe(byteArrayOf(5, 0xff.toByte())).let {
            assertEquals("unexpected SOCKS5 reply 05 ff (connection closed)", it)
            assertNoCredentials(it)
        }
        // mihomo：认证失败
        assertEquals("unexpected SOCKS5 reply 05 02 01 01", probe(byteArrayOf(5, 2, 1, 1)))
        assertEquals("unexpected SOCKS5 reply 05 02 01 ff", probe(byteArrayOf(5, 2, 1, 0xff.toByte())))
        // 选了无认证方法：服务端不要求认证
        assertEquals("unexpected SOCKS5 reply 05 00 (connection closed)", probe(byteArrayOf(5, 0)))
        // 只回一半：随即断开，或挂着不再应答
        assertEquals("unexpected SOCKS5 reply 05 02 (connection closed)", probe(byteArrayOf(5, 2)))
        assertEquals("unexpected SOCKS5 reply 05 02 (timed out)", probe(byteArrayOf(5, 2), closeAfterReply = false))
        // 什么都不回：直接断开，或挂着
        assertNoCredentials(probe(null, request = 0))
        assertEquals("no SOCKS5 reply (timed out)", probe(null, closeAfterReply = false))
        // 端口上没有人监听
        assertNoCredentials(probeLocalPort(closedPort(), auth))
    }

    @Test
    fun `只等端口的探测连上即就绪，不发任何字节`() {
        val received = CopyOnWriteArrayList<ByteArray>()
        // 服务端什么都不回、一直挂着：只连端口时照样就绪
        val port = listener(request = 0, reply = null, received = received)
        assertNull(probeLocalPort(port, null))
        assertEquals(0, awaitReceived(received).size)
        assertNoCredentials(probeLocalPort(closedPort(), null))
    }

    @Test
    fun `内置核心的目标必须带凭据，等待时只对它们握手`() = runBlocking(Dispatchers.IO) {
        assertThrows(IllegalArgumentException::class.java) { LocalPortTarget(1, true, null, alive()) }
        val seen = CopyOnWriteArrayList<Pair<Int, LocalSocksAuth?>>()
        awaitLocalPorts(
            listOf(LocalPortTarget(1, true, auth, alive()), LocalPortTarget(2, false, auth, alive()), LocalPortTarget(3, false, null, alive())),
            1000, { false },
        ) { port, given -> seen += port to given; null }
        assertEquals(listOf(1 to auth, 2 to null, 3 to null), seen.toList())
    }

    // ---- 就绪等待

    @Test
    fun `握手成功即就绪`() = runBlocking(Dispatchers.IO) {
        val port = listener()
        val started = System.nanoTime()
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, auth, alive())), 5000, { false }).single()
        assertTrue(status.ready)
        assertNull(status.exitCode)
        assertTrue("就绪后立刻返回", System.nanoTime() - started < 1_000_000_000)
    }

    @Test
    fun `始终连不上时等到超时，带最后一次的原因`() = runBlocking(Dispatchers.IO) {
        val port = closedPort()
        val started = System.nanoTime()
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, auth, alive())), 300, { false }).single()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(status.ready)
        assertTrue(status.lastError, status.lastError!!.isNotBlank())
        assertTrue("等到时限：$elapsedMs ms", elapsedMs >= 300)
        assertTrue("不超过时限太多：$elapsedMs ms", elapsedMs < 1500)
    }

    @Test
    fun `握手被拒或不应答不算就绪，只连端口的照常就绪`() = runBlocking(Dispatchers.IO) {
        val rejecting = listener(reply = byteArrayOf(5, 0xff.toByte()), closeAfterReply = true)
        // 接受连接却从不应答：握手读超时，只连端口时算就绪
        val silent = listener(reply = null)
        val statuses = awaitLocalPorts(
            listOf(
                LocalPortTarget(rejecting, true, auth, alive()),
                LocalPortTarget(silent, true, auth, alive()),
                LocalPortTarget(silent, false, auth, alive()),
            ),
            600, { false },
        )
        assertEquals(listOf(false, false, true), statuses.map { it.ready })
        assertEquals("unexpected SOCKS5 reply 05 ff (connection closed)", statuses[0].lastError)
        assertNoCredentials(statuses[1].lastError)
    }

    @Test
    fun `部分端口就绪：逐个确认，返回各自的状态`() = runBlocking(Dispatchers.IO) {
        val first = listener()
        val missing = closedPort()
        val last = listener()
        val statuses = awaitLocalPorts(
            listOf(first, missing, last).map { LocalPortTarget(it, true, auth, alive()) },
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
                        repeat(expectedHandshake.size) { s.getInputStream().read() }
                        s.getOutputStream().write(accepted)
                    }
                }
            }
        }
        val status = awaitLocalPorts(listOf(LocalPortTarget(port, true, auth, alive())), 5000, { false }).single()
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
            awaitLocalPorts(listOf(LocalPortTarget(port, true, auth, alive())), 10_000, closed::get)
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
            withTimeout(100) { awaitLocalPorts(listOf(LocalPortTarget(port, true, auth, alive())), 10_000, { false }) }
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
            listOf(LocalPortTarget(missing, true, auth) { 255 }, LocalPortTarget(closedPort(), true, auth, alive())),
            10_000, { false },
        )
        assertTrue("不等到超时", System.nanoTime() - started < 1_000_000_000)
        assertEquals(255, strict[0].exitCode)
        assertFalse(strict[0].ready)

        val lenient = awaitLocalPorts(
            listOf(LocalPortTarget(missing, false, null) { 1 }, LocalPortTarget(ready, false, null, alive())),
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
            listOf(LocalPortTarget(1, true, auth) { if (calls.incrementAndGet() > 1) 255 else null }),
            5000, { false },
        ) { _, _ -> null }.single()
        assertFalse(status.ready)
        assertEquals(255, status.exitCode)
    }

    // ---- 认证检查：正确的用户名与错误的密码握手一次

    private fun wrongPasswordProbe(port: Int, timeoutMs: Long = 1000, isCancelled: () -> Boolean = { false }) =
        runBlocking(Dispatchers.IO) { probeWrongPassword(port, auth, timeoutMs, isCancelled) }

    private fun wrongPasswordProbe(reply: ByteArray?, closeAfterReply: Boolean = true, request: Int = expectedWrongHandshake.size) =
        wrongPasswordProbe(listener(request = request, reply = reply, closeAfterReply = closeAfterReply), timeoutMs = 300)

    @Test
    fun `认证检查一次写出 05 01 02 与错误密码的认证包，应答 05 02 01 00 判为被接受`() {
        assertEquals(expectedHandshake.size, expectedWrongHandshake.size)
        assertFalse(expectedHandshake.contentEquals(expectedWrongHandshake))
        val received = CopyOnWriteArrayList<ByteArray>()
        val port = listener(request = expectedWrongHandshake.size, received = received)
        assertSame(WrongPasswordReply.Accepted, wrongPasswordProbe(port))
        // 连接上收到的全部字节正好是握手，没有别的请求
        assertArrayEquals(expectedWrongHandshake, awaitReceived(received))
    }

    @Test
    fun `认证检查：05 02 后跟非 00 的状态是明确拒绝`() {
        // mihomo、Xray 拒绝错误密码时的应答
        for (reply in listOf(byteArrayOf(5, 2, 1, 1), byteArrayOf(5, 2, 1, 0xff.toByte()))) {
            val result = wrongPasswordProbe(reply)
            assertTrue("$result", result is WrongPasswordReply.Rejected)
            assertNoCredentials((result as WrongPasswordReply.Rejected).reply)
        }
        assertEquals("05 02 01 01", (wrongPasswordProbe(byteArrayOf(5, 2, 1, 1)) as WrongPasswordReply.Rejected).reply)
        // 拒绝后不断开也一样
        assertTrue(wrongPasswordProbe(byteArrayOf(5, 2, 1, 1), closeAfterReply = false) is WrongPasswordReply.Rejected)
    }

    @Test
    fun `认证检查：其余应答、断开、超时、连不上都不算被接受，归为其它`() {
        fun unclear(result: WrongPasswordReply): String {
            assertTrue("$result", result is WrongPasswordReply.Unclear)
            return (result as WrongPasswordReply.Unclear).reason.also(::assertNoCredentials)
        }
        // 不接受用户名 / 密码方法
        assertEquals("unexpected SOCKS5 reply 05 ff (connection closed)", unclear(wrongPasswordProbe(byteArrayOf(5, 0xff.toByte()))))
        // 只回一半：随即断开，或挂着不再应答
        assertEquals("unexpected SOCKS5 reply 05 02 (connection closed)", unclear(wrongPasswordProbe(byteArrayOf(5, 2))))
        assertEquals("unexpected SOCKS5 reply 05 02 (timed out)", unclear(wrongPasswordProbe(byteArrayOf(5, 2), closeAfterReply = false)))
        // 状态为 00 但不是 05 02 01 00
        assertEquals("unexpected SOCKS5 reply 05 02 02 00", unclear(wrongPasswordProbe(byteArrayOf(5, 2, 2, 0))))
        // 什么都不回：直接断开，或挂着
        unclear(wrongPasswordProbe(null, request = 0))
        assertEquals("no SOCKS5 reply (timed out)", unclear(wrongPasswordProbe(null, closeAfterReply = false)))
        // 端口上没有人监听
        unclear(wrongPasswordProbe(closedPort()))
    }

    @Test
    fun `认证检查不应答时等到时限`() {
        val port = listener(request = expectedWrongHandshake.size, reply = null)
        val started = System.nanoTime()
        assertTrue(wrongPasswordProbe(port, timeoutMs = 400) is WrongPasswordReply.Unclear)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("等到时限：$elapsedMs ms", elapsedMs >= 400)
        assertTrue("不超过时限太多：$elapsedMs ms", elapsedMs < 1500)
    }

    @Test
    fun `认证检查中实例被关闭立即停止`() {
        val port = listener(request = expectedWrongHandshake.size, reply = null)
        val closed = AtomicBoolean(false)
        thread(isDaemon = true) {
            Thread.sleep(100)
            closed.set(true)
        }
        val started = System.nanoTime()
        try {
            wrongPasswordProbe(port, timeoutMs = 10_000, isCancelled = closed::get)
            fail("应当抛 CancellationException")
        } catch (_: CancellationException) {
        }
        // 已关闭时不再连接
        assertThrows(CancellationException::class.java) { wrongPasswordProbe(port, isCancelled = { true }) }
        assertTrue(System.nanoTime() - started < 1_000_000_000)
    }

    @Test
    fun `认证检查中协程被取消立即停止`() = runBlocking(Dispatchers.IO) {
        val port = listener(request = expectedWrongHandshake.size, reply = null)
        val started = System.nanoTime()
        try {
            withTimeout(100) { probeWrongPassword(port, auth, 10_000) { false } }
            fail("应当被取消")
        } catch (_: CancellationException) {
        }
        assertTrue(System.nanoTime() - started < 1_000_000_000)
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
