package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 跨进程端口保留表（K5）：主进程测速与 :bg 运行服务共用一张表。纯 JVM：时钟与挑端口的函数注入；
 * 「两个进程」在前几条用例里用两个实例共享同一个文件模拟，最后一条起一个真实的子 JVM 并发分配。
 */
class PortReservationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000_000L

    private fun picker(vararg ports: Int): () -> Int {
        val queue = ArrayDeque(ports.toList())
        return { queue.removeFirst() }
    }

    private fun table(file: File, pick: () -> Int, ttl: Long = PortReservations.TTL_MS) =
        PortReservations(file, now = { clock }, pickPort = pick, ttlMs = ttl)

    @Test
    fun `保留期内同一个端口不会发两次`() {
        val file = File(tmp.root, "ports")
        val t = table(file, picker(5000, 5000, 5000, 5001))
        assertEquals(5000, t.reserve())
        assertEquals("系统又给了 5000，跳过它", 5001, t.reserve())
    }

    @Test
    fun `两个进程共用一个文件时不会分到同一个端口`() {
        val file = File(tmp.root, "ports")
        // 各自的实例只共享文件，模拟主进程与 :bg
        val main = table(file, picker(6000, 6001))
        val bg = table(file, picker(6000, 6000, 6002))
        assertEquals(6000, main.reserve())
        assertEquals(6002, bg.reserve())
        assertEquals(6001, main.reserve())
    }

    @Test
    fun `过了保留时长的记录作废，端口可以再发`() {
        val file = File(tmp.root, "ports")
        val t = table(file, picker(7000, 7000), ttl = 60_000L)
        assertEquals(7000, t.reserve())
        clock += 60_001L
        assertEquals(7000, t.reserve())
        // 过期记录在写回时清掉，表里只剩新的一条
        assertEquals(listOf("7000 $clock"), file.readLines())
    }

    @Test
    fun `时间戳比当前时间还大的记录来自重启之前，按过期处理`() {
        val file = File(tmp.root, "ports")
        file.writeText("8000 ${clock + 5_000_000L}\n")
        val t = table(file, picker(8000))
        assertEquals(8000, t.reserve())
    }

    @Test
    fun `坏行与越界端口直接丢弃`() {
        val file = File(tmp.root, "ports")
        file.writeText("garbage\n9000\n70000 $clock\n9001 notatime\n9002 $clock\n")
        val t = table(file, picker(9002, 9003))
        assertEquals("9002 仍在保留期内", 9003, t.reserve())
        assertEquals(setOf("9002 $clock", "9003 $clock"), file.readLines().toSet())
    }

    @Test
    fun `一直撞在保留表上就报错，不无限重试，也不改动保留表`() {
        val file = File(tmp.root, "ports")
        val first = table(file, picker(10000))
        assertEquals(10000, first.reserve())
        val before = file.readText()
        val stuck = table(file, { 10000 })
        try {
            stuck.reserve()
            fail("应当抛 IOException")
        } catch (_: IOException) {
        }
        assertEquals(before, file.readText())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `真实的两个进程并发分配，端口互不重复`() {
        val file = File(tmp.root, "ports")
        val go = File(tmp.root, "go")
        val count = 150
        // 两边都只在 2 * count 个端口里随机挑，挑的时候停 1 毫秒拉长「读表—写表」之间的窗口：
        // 不经同一把跨进程锁协调的话几乎必然撞车（去掉锁时本用例失败）
        val base = 30000
        val range = 2 * count
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("java.class.path"),
            PortReservationsChild::class.java.name,
            file.path, go.path, count.toString(), base.toString(), range.toString(),
        ).redirectErrorStream(true).start()
        val reader = child.inputStream.bufferedReader()
        assertEquals("子进程没有就绪", "READY", reader.readLine())

        val mine = PortReservations(
            file, now = { System.currentTimeMillis() },
            pickPort = { Thread.sleep(1); base + Random.nextInt(range) },
        )
        // 两边同时开跑
        go.createNewFile()
        val parentPorts = (1..count).map { mine.reserve() }

        assertTrue("子进程超时", child.waitFor(60, TimeUnit.SECONDS))
        val output = reader.readLines()
        assertEquals("子进程退出码，输出：$output", 0, child.exitValue())
        val childPorts = output.map { it.trim().toInt() }

        assertEquals(count, childPorts.size)
        val all = parentPorts + childPorts
        assertEquals("两个进程发出的端口有重复", all.size, all.toSet().size)
        assertEquals(all.toSet(), file.readLines().map { it.substringBefore(' ').toInt() }.toSet())
    }
}

/** 子进程入口：报「READY」后等开始信号文件出现，再在共享文件上分配端口，每行打印一个 */
object PortReservationsChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, goPath, count, base, range) = args
        val table = PortReservations(
            File(path), now = { System.currentTimeMillis() },
            pickPort = { Thread.sleep(1); base.toInt() + Random.nextInt(range.toInt()) },
        )
        println("READY")
        System.out.flush()
        val go = File(goPath)
        while (!go.exists()) Thread.sleep(1)
        repeat(count.toInt()) { println(table.reserve()) }
    }
}
