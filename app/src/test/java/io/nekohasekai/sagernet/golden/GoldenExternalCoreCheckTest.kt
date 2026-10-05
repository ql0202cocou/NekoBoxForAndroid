package io.nekohasekai.sagernet.golden

import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.IdentityHashMap

// 骨架自检：基线读取完整，断言入口真的能发现差异，临时文件路径按动态值处理
class GoldenExternalCoreCheckTest {

    private val pluginIds = listOf(
        "xray-plugin", "mihomo-plugin", "trojan-go-plugin", "naive-plugin", "mieru-plugin", "hysteria-plugin",
    )

    private fun assertContains(text: String, vararg parts: String) {
        parts.forEach { assertTrue("消息里没有「$it」：\n$text", it in text) }
    }

    private fun failureOf(pluginId: String, generator: GoldenExternalGenerator): String =
        assertThrows(AssertionError::class.java) {
            GoldenExternalCoreCheck.assertMatchesBaseline(pluginId, generator)
        }.message.orEmpty()

    @Test
    fun `六个外核的用例都能读出且覆盖基线里全部外核配置`() {
        val baseline = GoldenBaseline.assumeAvailable()
        val beans = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var total = 0
        var hops = 0
        for (pluginId in pluginIds) {
            val cases = baseline.externalCases(pluginId)
            for (mode in GoldenBoxMode.entries) {
                assertTrue("$pluginId $mode 没有用例", cases.any { it.mode == mode })
            }
            cases.forEach { assertEquals(it.toString(), pluginId, it.pluginId) }
            cases.forEach { assertEquals(it.toString(), pluginId, it.group.pluginId) }
            cases.forEach { case -> case.plan.hops.forEach { beans += it.bean } }
            total += cases.size
            hops += cases.sumOf { it.plan.hops.size }
        }
        // 每个用例重建自己的计划，bean 互不共享
        assertEquals(hops, beans.size)
        val files = baseline.scenarioIds.sumOf { id ->
            GoldenBoxMode.entries.sumOf { mode ->
                baseline.root.resolve("scenarios/$id/${mode.dir}").listFiles().orEmpty().count { it.name.startsWith("ext-") }
            }
        }
        assertEquals("用例数应等于基线里 run / test 的外核配置文件数", files, total)
    }

    @Test
    fun `mihomo 用例带上了运行期字段、测速控制端口与设置`() {
        val cases = GoldenBaseline.assumeAvailable().externalCases("mihomo-plugin")
        assertTrue(cases.all { it.format == GoldenFormat.YAML })
        val hops = cases.flatMap { it.group.hops }
        assertTrue(hops.all { it.bean is AnyTLSBean })
        assertTrue(hops.all { it.finalAddress.isNotEmpty() && it.finalPort > 0 })
        assertTrue(hops.all { it.bean.finalAddress == it.finalAddress && it.bean.finalPort == it.finalPort })
        assertTrue(cases.filter { it.mode == GoldenBoxMode.RUN }.all { it.controller == null })
        assertTrue(cases.any { it.mode == GoldenBoxMode.TEST && it.controller != null })
        assertTrue("应当覆盖不同的日志级别", cases.map { it.settings.logLevel }.distinct().size > 1)
    }

    @Test
    fun `输出被改动时失败，消息带场景 id、模式与差异，并只列前几个`() {
        val cases = GoldenBaseline.assumeAvailable().externalCases("mihomo-plugin")
        val message = failureOf("mihomo-plugin") { case, cacheFile ->
            GoldenExternalCoreCheck.PRODUCTION.generate(case, cacheFile).replace("mode: rule", "mode: global")
        }
        val first = cases.first()
        assertContains(
            message,
            "${cases.size} 个用例中 ${cases.size} 个与基线不一致",
            "只列前",
            "[$first]",
            "${first.file} $.mode：预期 \"rule\"，实际 \"global\"",
        )
        // 排在后面的用例不展开
        assertTrue(message, "[${cases.last()}]" !in message)
    }

    @Test
    fun `只有一个用例不同时只报这一个`() {
        val cases = GoldenBaseline.assumeAvailable().externalCases("mihomo-plugin")
        val target = cases.last()
        val message = failureOf("mihomo-plugin") { case, cacheFile ->
            val text = GoldenExternalCoreCheck.PRODUCTION.generate(case, cacheFile)
            if (case.scenarioId == target.scenarioId && case.mode == target.mode && case.file == target.file) {
                text.replace("udp: true", "udp: false")
            } else {
                text
            }
        }
        assertContains(message, "${cases.size} 个用例中 1 个与基线不一致", "[$target]", ".udp：预期 true，实际 false")
    }

    @Test
    fun `生成抛异常时失败并带上异常`() {
        GoldenBaseline.assumeAvailable()
        val message = failureOf("mihomo-plugin") { _, _ -> throw IllegalStateException("故意失败") }
        assertContains(message, "生成时抛出 java.lang.IllegalStateException：故意失败")
    }

    @Test
    fun `没有用例时失败而不是通过`() {
        GoldenBaseline.assumeAvailable()
        val message = failureOf("no-such-plugin") { _, _ -> error("不应被调用") }
        assertContains(message, "no-such-plugin：基线里读到 0 个用例")
    }

    // hysteria 1 的 CA 文件路径每次不同：基线一侧按 tempFiles、本次一侧按实际分到的路径换成占位符。
    // 生成器在 JVM 上还跑不了，这里直接拿基线原文代替生成结果
    @Test
    fun `临时文件路径按动态值比较`() {
        val cases = GoldenBaseline.assumeAvailable().externalCases("hysteria-plugin")
        val withCa = cases.filter { it.tempFiles.isNotEmpty() }
        assertTrue("基线里应有带 CA 文件的 hysteria 用例", withCa.isNotEmpty())

        val relocated = GoldenExternalGenerator { case, cacheFile ->
            case.tempFiles.fold(case.expected) { text, path ->
                val file = cacheFile("hysteria", "ca")
                assertTrue(file.isFile && file.absolutePath != path)
                text.replace(path, file.absolutePath)
            }
        }
        assertNull(GoldenExternalCoreCheck.report("hysteria-plugin", cases, relocated))

        // 不经 cacheFile、原样写出采集时的路径，就与基线对不上
        val message = GoldenExternalCoreCheck.report("hysteria-plugin", withCa) { case, _ -> case.expected }
        assertNotNull(message)
        assertContains(message!!, "${withCa.size} 个用例中 ${withCa.size} 个与基线不一致", "[${withCa.first()}]", "{PATH#1}")
    }
}
