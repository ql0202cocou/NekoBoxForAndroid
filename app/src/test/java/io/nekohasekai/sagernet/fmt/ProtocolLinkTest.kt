package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.ProtocolInventory.fieldsOf
import io.nekohasekai.sagernet.ktx.Logs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// QUALITY-004：有专属分享链接的协议，字段齐全的 bean 导出链接再解析回来，字段逐个相等。
// 变体、有意不进链接的字段与原因都在 ProtocolLinkCases.kt。链接里的 base64（SS 的 userinfo、VMess 的 v2rayN JSON、
// 通用链接 sn://）走 android.util.Base64，JVM 上跑不了：SS 与 VMess 把 base64 之外的部分拆成 internal 函数来测，
// 通用链接的信封见 ProtocolContractTest
class ProtocolLinkTest {

    // 解析失败的回退链会写日志，JVM 里没有 libcore
    @Before
    fun silenceLogs() {
        Logs.enabled = false
    }

    @After
    fun restoreLogs() {
        Logs.enabled = true
    }

    private fun check(failures: List<String>) {
        assertTrue("${failures.size} 处问题：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `每个变体导出再解析，除清单里的字段外逐字段相等`() {
        val failures = ArrayList<String>()
        for (spec in ProtocolLinkCases.SPECS) {
            for (case in spec.cases) {
                val where = "${spec.protocol}/${case.id}"
                try {
                    val original = case.bean()
                    val link = spec.export(original)
                    val parsed = spec.parse(link)
                    failures += ProtocolInventory.diff(where, original, parsed, ignore = spec.neverInLink.keys)
                } catch (e: Throwable) {
                    failures += "$where：抛出 $e"
                }
            }
        }
        check(failures)
    }

    @Test
    fun `链接以约定的前缀开头`() {
        val failures = ArrayList<String>()
        for (spec in ProtocolLinkCases.SPECS) {
            // VMess 导出的是 v2rayN 的 JSON（base64 之前），没有前缀可查
            if (spec.protocol == "VMess") continue
            for (case in spec.cases) {
                val link = spec.export(case.bean())
                if (!link.startsWith(spec.prefix)) failures += "${spec.protocol}/${case.id}：链接 ${link.take(24)}… 不以 ${spec.prefix} 开头"
            }
        }
        check(failures)
    }

    @Test
    fun `不在清单里的每个字段至少在一个变体里是非默认值`() {
        val failures = ArrayList<String>()
        for (spec in ProtocolLinkCases.SPECS) {
            val defaults = ProtocolInventory.defaults(spec.beanClass)
            val covered = HashSet<String>()
            for (case in spec.cases) {
                val snapshot = ProtocolInventory.snapshot(case.bean())
                for ((name, value) in snapshot) if (value != defaults[name]) covered += name
            }
            for (field in fieldsOf(spec.beanClass)) {
                if (field.name in spec.neverInLink || field.name in spec.fixedByFormat || field.name in covered) continue
                failures += "${spec.protocol}.${field.name}：没有任何变体把它设成非默认值，漏导出这个字段测不出来。" +
                    "补一个变体，或写进 neverInLink 并说明原因"
            }
            for (name in spec.neverInLink.keys) {
                if (fieldsOf(spec.beanClass).none { it.name == name }) failures += "${spec.protocol}：neverInLink 里的 $name 不是 ${spec.beanClass.simpleName} 的字段"
            }
        }
        check(failures)
    }

    @Test
    fun `清单里的字段确实没被链接带走`() {
        // 清单变陈旧（字段后来进了链接）时要删掉，否则这个字段的导出 / 解析不再被比较
        val failures = ArrayList<String>()
        for (spec in ProtocolLinkCases.SPECS) {
            for (case in spec.cases) {
                val original = case.bean()
                ProtocolInventory.fillNonDefault(original, spec.neverInLink.keys)
                val parsed = try {
                    spec.parse(spec.export(original))
                } catch (e: Throwable) {
                    failures += "${spec.protocol}/${case.id}（清单字段填成非默认值后）：抛出 $e"
                    continue
                }
                val before = ProtocolInventory.snapshot(original)
                val after = ProtocolInventory.snapshot(parsed)
                for (name in spec.neverInLink.keys) {
                    if (before[name] == after[name]) failures += "${spec.protocol}/${case.id}.$name：写在 neverInLink 里，但导出再解析后值保持不变，该从清单删掉"
                }
            }
        }
        check(failures)
    }

    @Test
    fun `协议专属规格覆盖的类型都有清单里的 bean 类`() {
        for (spec in ProtocolLinkCases.SPECS) {
            val proto = ProtocolInventory.proto(spec.type)
            assertEquals(spec.protocol, proto.beanClass, spec.beanClass)
            assertTrue("${spec.protocol} 没有变体", spec.cases.isNotEmpty())
            for (case in spec.cases) assertEquals("${spec.protocol}/${case.id}", spec.beanClass, case.bean().javaClass)
        }
    }

    @Test
    fun `Shadowsocks 导出的 userinfo 是 method 冒号 password 的 URL-safe base64`() {
        val spec = ProtocolLinkCases.SPECS.single { it.protocol == "Shadowsocks" }
        for (case in spec.cases) {
            val bean = case.bean() as io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
            val link = spec.export(bean)
            val decoded = String(java.util.Base64.getUrlDecoder().decode(link.substringAfter("ss://").substringBefore("@")))
            assertEquals("${bean.method}:${bean.password}", decoded)
            // 端口后面不带 "/"（toUri 里去掉的那一段）
            assertTrue(link, link.contains("@${bean.serverAddress}:${bean.serverPort}") && !link.contains("${bean.serverPort}/"))
        }
    }
}
