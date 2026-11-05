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
        val ready = File(tmp.root, "ready")
        val output = File(tmp.root, "child-output")
        val count = 150
        // 两边都从同一个端口开始循环覆盖，既制造碰撞，又保证每次最多挑 2 * count 次就能找到空位。
        // 随机抽样在只剩一个空位时可能耗尽重试额度，那是合法拒绝，不能当成跨进程锁失效。
        // 每次挑选停 1 毫秒拉长「读表—写表」窗口；时间固定，慢机器也不受保留记录到期影响。
        val base = 30000
        val range = 2 * count
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("java.class.path"),
            PortReservationsChild::class.java.name,
            file.path, go.path, ready.path, count.toString(), base.toString(), range.toString(),
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("子进程没有就绪，输出：${output.readText()}", ready.exists())

            val mine = table(file, cyclingPortPicker(base, range))
            // 两边同时开跑
            go.createNewFile()
            val parentPorts = (1..count).map { mine.reserve() }

            assertTrue("子进程超时，输出：${output.readText()}", child.waitFor(60, TimeUnit.SECONDS))
            val lines = output.readLines()
            assertEquals("子进程退出码，输出：$lines", 0, child.exitValue())
            val childPorts = lines.map { it.trim().toInt() }

            assertEquals(count, childPorts.size)
            val all = parentPorts + childPorts
            assertEquals("两个进程发出的端口有重复", all.size, all.toSet().size)
            assertEquals((base until base + range).toSet(), all.toSet())
            assertEquals(all.toSet(), file.readLines().map { it.substringBefore(' ').toInt() }.toSet())
        } finally {
            // 父进程断言失败也要结束子进程，避免留在等待开始信号或继续写临时目录。
            child.destroyForcibly()
            assertTrue("子进程未能退出", child.waitFor(5, TimeUnit.SECONDS))
        }
    }
}

/** 子进程入口：写就绪文件后等开始信号，再在共享文件上分配端口，每行打印一个。 */
object PortReservationsChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = args[0]
        val goPath = args[1]
        val readyPath = args[2]
        val count = args[3].toInt()
        val base = args[4].toInt()
        val range = args[5].toInt()
        val table = PortReservations(
            File(path), now = { 1_000_000L },
            pickPort = cyclingPortPicker(base, range),
        )
        File(readyPath).createNewFile()
        val go = File(goPath)
        while (!go.exists()) Thread.sleep(1)
        repeat(count) { println(table.reserve()) }
    }
}

private fun cyclingPortPicker(base: Int, range: Int): () -> Int {
    var next = 0
    return {
        Thread.sleep(1)
        base + next.also { next = (next + 1) % range }
    }
}
