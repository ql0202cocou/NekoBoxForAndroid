package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// 启动前校验的报错解析、结论与就绪等待的报错文案（plan.md K0 做法 3、5）。报错样例取自模拟器上内置的
// Xray v26.3.27（run -test）与 mihomo v1.19.31（-t）对坏配置的真实输出
class ExternalCoreStartupTest {

    private fun vless(name: String) = VMessBean().apply {
        this.name = name
        serverAddress = "$name.example.com"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000001"
        alterId = -1
        initializeDefaultValues()
    }

    private fun anytls(name: String) = AnyTLSBean().apply {
        this.name = name
        serverAddress = "$name.example.com"
        serverPort = 8443
        password = "fake-password"
        initializeDefaultValues()
    }

    private val auth = LocalSocksAuth("u0123456789abcdef", "p0123456789abcdef0123456789abcdef")

    // 计划里的跳实例按顺序编号，本机端口从 21000 起；入站支持认证的核心带凭据
    private fun plan(vararg beans: AbstractBean) = ExternalRunPlan(beans.mapIndexed { i, bean ->
        ExternalHop(
            i, 0, i.toLong() + 1, bean, 21000 + i, ExternalDialTarget.Mapped(30000 + i),
            localAuth = auth.takeIf { externalCore(bean)!!.inboundAuth },
        )
    })

    private val xrayGroup = plan(vless("x0"), vless("x1"), vless("x2")).groups.single()
    private val mihomoGroup = plan(anytls("m0"), anytls("m1"), anytls("m2")).groups.single()
    private val xray get() = xrayGroup.core.check!!
    private val mihomo get() = mihomoGroup.core.check!!

    // ---- Xray 报错解析

    private val xrayBanner = """
        Xray 26.3.27 (Xray, Penetrates Everything.) d2758a0 (go1.26.1 android/arm64)
        A unified platform for anti-censorship.
        2026/10/04 19:12:23.324565 [Info] infra/conf/serial: Reading config: &{Name:/data/user/0/moe.nb4a.debug/cache/tmpcfg/xray_1.json Format:json}
    """.trimIndent()

    private val xrayRealityKey = xrayBanner + "\n" +
        "Failed to start: main: failed to load config files: [/data/user/0/moe.nb4a.debug/cache/tmpcfg/xray_1.json] " +
        "> infra/conf: failed to build outbound config with tag out-2 > infra/conf: failed to build stream settings " +
        "for outbound detour > infra/conf: Failed to build REALITY config. > infra/conf: invalid \"password\": not-a-valid-key"

    // 「Configuration OK.」之前也可能夹着弃用警告：解析不看行的位置
    private val xrayFlow = xrayBanner + "\n" +
        "2026/10/04 19:12:23.183821 [Warning] common/errors: The feature VMess (with no Forward Secrecy, etc.) is " +
        "deprecated, not recommended for using and might be removed. Please migrate to VLESS Encryption as soon as possible.\n" +
        "Failed to start: main: failed to load config files: [xray_1.json] > infra/conf: failed to build outbound config " +
        "with tag out-1 > infra/conf: failed to build outbound handler for protocol vless > infra/conf: VLESS users: " +
        "\"flow\" doesn't support \"xtls-rprx-bogus\" in this version"

    private val xrayDuplicateOutbound =
        "Failed to start: main: failed to create server > app/proxyman/outbound: existing tag found: out-1"

    private val xrayDuplicateInbound =
        "Failed to start: main: failed to create server > app/proxyman/inbound: existing tag found: in-0"

    private val xraySyntax = xrayBanner + "\n" +
        "Failed to start: main: failed to load config files: [xray_1.json] > infra/conf/serial: failed to decode " +
        "config: &{Name:xray_1.json Format:json} > infra/conf/serial: failed to read config file > unexpected EOF"

    @Test
    fun `Xray 按出站 tag 定位，原因去掉定位前缀与 Go 包路径`() {
        val errors = xrayCheckErrors(xrayRealityKey)
        assertEquals(1, errors.size)
        assertEquals("out-2", errors[0].tag)
        assertEquals(
            "failed to build stream settings for outbound detour > Failed to build REALITY config. > " +
                "invalid \"password\": not-a-valid-key",
            errors[0].reason,
        )
        assertEquals(
            "failed to build outbound handler for protocol vless > VLESS users: \"flow\" doesn't support " +
                "\"xtls-rprx-bogus\" in this version",
            xrayCheckErrors(xrayFlow).single().reason,
        )
    }

    @Test
    fun `Xray 的 tag 重复按那一层定位，原因就是那一层`() {
        xrayCheckErrors(xrayDuplicateOutbound).single().let {
            assertEquals("out-1", it.tag)
            assertEquals("existing tag found: out-1", it.reason)
        }
        assertEquals("in-0", xrayCheckErrors(xrayDuplicateInbound).single().tag)
    }

    @Test
    fun `Xray 找不到 tag 的报错原文照给`() {
        val error = xrayCheckErrors(xraySyntax).single()
        assertNull(error.tag)
        assertNull(error.position)
        assertTrue(error.reason, error.reason.startsWith("main: failed to load config files: [xray_1.json] > "))
        assertTrue(error.reason.endsWith("> unexpected EOF"))
    }

    @Test
    fun `Xray 通过或没有报错行时没有失败`() {
        assertTrue(xrayCheckErrors("$xrayBanner\nConfiguration OK.").isEmpty())
        assertTrue(xrayCheckErrors("").isEmpty())
    }

    // ---- mihomo 报错解析

    private fun mihomoOutput(vararg messages: String) = buildString {
        appendLine("time=\"2026-10-05T03:12:23.727378664+08:00\" level=info msg=\"Start initial configuration in progress\"")
        for (message in messages) appendLine("time=\"2026-10-05T03:12:23.727465748+08:00\" level=error msg=\"$message\"")
        append("configuration file /data/user/0/moe.nb4a.debug/cache/tmpcfg/mihomo_1.yaml test failed")
    }

    @Test
    fun `mihomo 按 0 起算的代理序号定位，原因去掉序号前缀`() {
        mihomoCheckErrors(mihomoOutput("proxy 1: unsupport proxy type: anytlsx")).single().let {
            assertEquals(1, it.position)
            assertNull(it.tag)
            assertEquals("unsupport proxy type: anytlsx", it.reason)
        }
        assertEquals(
            "'' has unset fields: password",
            mihomoCheckErrors(mihomoOutput("proxy 2: '' has unset fields: password")).single().reason,
        )
        assertEquals(
            "base64 decode ech config string failed: illegal base64 data at input byte 0",
            mihomoCheckErrors(
                mihomoOutput("proxy 1: base64 decode ech config string failed: illegal base64 data at input byte 0")
            ).single().reason,
        )
    }

    @Test
    fun `mihomo 消息里转义的引号还原`() {
        val error = mihomoCheckErrors(
            mihomoOutput("proxy 1: cannot parse 'port' as int: strconv.ParseInt: parsing \\\"abc\\\": invalid syntax")
        ).single()
        assertEquals("cannot parse 'port' as int: strconv.ParseInt: parsing \"abc\": invalid syntax", error.reason)
    }

    @Test
    fun `mihomo 的 listener 序号同样定位，原因里留着 listener`() {
        mihomoCheckErrors(mihomoOutput("listener 1: unsupport proxy type: sockz")).single().let {
            assertEquals(1, it.position)
            assertEquals("listener: unsupport proxy type: sockz", it.reason)
        }
    }

    @Test
    fun `mihomo 名字重复时按名字定位`() {
        mihomoCheckErrors(mihomoOutput("proxy out-0 is the duplicate name")).single().let {
            assertEquals("out-0", it.tag)
            assertEquals("proxy out-0 is the duplicate name", it.reason)
        }
        assertEquals("in-2", mihomoCheckErrors(mihomoOutput("listener in-2 is the duplicate name")).single().tag)
    }

    @Test
    fun `mihomo 对不回节点的报错原文照给`() {
        val unbound = "rules[0] [MATCH,node-missing] error: proxy [node-missing] not found"
        mihomoCheckErrors(mihomoOutput(unbound)).single().let {
            assertNull(it.tag)
            assertNull(it.position)
            assertEquals(unbound, it.reason)
        }
        assertEquals(
            "yaml: line 23: did not find expected key",
            mihomoCheckErrors(mihomoOutput("yaml: line 23: did not find expected key")).single().reason,
        )
    }

    // ---- 结论与报错文案

    @Test
    fun `退出码 0 通过，超时与被信号杀掉没有结论`() {
        assertSame(ExternalCheckResult.Passed, xray.result(xrayGroup, 0, "$xrayBanner\nConfiguration OK."))
        (xray.result(xrayGroup, null, "") as ExternalCheckResult.Inconclusive).let {
            assertEquals("Xray config check timed out", it.reason)
        }
        (mihomo.result(mihomoGroup, 137, "") as ExternalCheckResult.Inconclusive).let {
            assertEquals("mihomo config check was killed (exit code 137)", it.reason)
        }
    }

    private fun failure(result: ExternalCheckResult): Exception = (result as ExternalCheckResult.Failed).error

    @Test
    fun `对得回的报成「节点名 - 核心原因」`() {
        val xrayError = failure(xray.result(xrayGroup, 23, xrayRealityKey))
        assertTrue(xrayError is ProfileBuildException)
        assertEquals("x2", (xrayError as ProfileBuildException).profileName)
        assertEquals(
            "x2: failed to build stream settings for outbound detour > Failed to build REALITY config. > " +
                "invalid \"password\": not-a-valid-key",
            xrayError.message,
        )
        assertTrue(xrayError.cause is ExternalCoreCheckException)

        val mihomoError = failure(mihomo.result(mihomoGroup, 1, mihomoOutput("proxy 1: unsupport proxy type: anytlsx")))
        assertEquals("m1: unsupport proxy type: anytlsx", mihomoError.message)
        assertEquals(
            "m0: proxy out-0 is the duplicate name",
            failure(mihomo.result(mihomoGroup, 1, mihomoOutput("proxy out-0 is the duplicate name"))).message,
        )
    }

    @Test
    fun `组里没有的 tag 与越界的序号对不回节点，写明核心与原文`() {
        // 标识只在本组里找：out-5 不在这一组
        val xrayError = failure(
            xray.result(
                xrayGroup, 23,
                "Failed to start: main: failed to create server > app/proxyman/outbound: existing tag found: out-5",
            )
        )
        assertTrue(xrayError is ExternalCoreCheckException)
        assertEquals("Xray config check failed: existing tag found: out-5", xrayError.message)
        assertEquals(
            "mihomo config check failed: unsupport proxy type: anytlsx",
            failure(mihomo.result(mihomoGroup, 1, mihomoOutput("proxy 3: unsupport proxy type: anytlsx"))).message,
        )
        assertEquals(
            "Xray config check failed: main: failed to load config files: [xray_1.json] > infra/conf/serial: " +
                "failed to decode config: &{Name:xray_1.json Format:json} > infra/conf/serial: failed to read config " +
                "file > unexpected EOF",
            failure(xray.result(xrayGroup, 23, xraySyntax)).message,
        )
        val unbound = "rules[0] [MATCH,node-missing] error: proxy [node-missing] not found"
        assertEquals(
            "mihomo config check failed: $unbound",
            failure(mihomo.result(mihomoGroup, 1, mihomoOutput(unbound))).message,
        )
    }

    @Test
    fun `输出为空或认不出时带上退出码与最后几行`() {
        assertEquals(
            "Xray config check failed with exit code 23 and no output",
            failure(xray.result(xrayGroup, 23, "")).message,
        )
        assertEquals(
            "mihomo config check failed with exit code 1 and no output",
            failure(mihomo.result(mihomoGroup, 1, " \n\n")).message,
        )
        // 退出码异常（Go 的 panic 是 2）：不是约定的退出码，同样按没过处理
        val panic = """
            panic: runtime error: invalid memory address or nil pointer dereference
            [signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc=0x0]

            goroutine 1 [running]:
            main.main()
        """.trimIndent()
        assertEquals(
            "Xray config check failed with exit code 2: [signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc=0x0] " +
                "/ goroutine 1 [running]: / main.main()",
            failure(xray.result(xrayGroup, 2, panic)).message,
        )
        // 没有 level=error 行：取最后几行原文
        val noErrorLine = failure(mihomo.result(mihomoGroup, 1, mihomoOutput())).message!!
        assertTrue(noErrorLine, noErrorLine.startsWith("mihomo config check failed with exit code 1: time="))
        assertTrue(noErrorLine.endsWith(" / configuration file /data/user/0/moe.nb4a.debug/cache/tmpcfg/mihomo_1.yaml test failed"))
        // 很长的输出只留末尾
        val long = failure(xray.result(xrayGroup, 23, "x".repeat(2000))).message!!
        assertTrue(long, long.startsWith("Xray config check failed with exit code 23: …"))
        assertTrue(long.length < 600)
    }

    // ---- 就绪等待的报错文案

    private fun readiness(
        group: ExternalCoreGroup,
        strict: Boolean,
        ready: Set<Int> = emptySet(),
        exited: Map<Int, Int> = emptyMap(),
        error: String? = "Connection refused",
    ) = group.hops.mapIndexed { i, hop ->
        HopReadiness(hop, strict, i in ready, exited[i], if (i in ready) null else error)
    }

    @Test
    fun `全部就绪时没有失败也没有警告`() {
        val outcome = externalReadyOutcome(readiness(xrayGroup, true, ready = setOf(0, 1, 2)), 5000)
        assertNull(outcome.failure)
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun `内置核心一个节点未就绪时报成「节点名 - 原因」`() {
        val failure = externalReadyOutcome(readiness(xrayGroup, true, ready = setOf(0, 2)), 5000).failure
        assertTrue(failure is ProfileBuildException)
        assertEquals("x1: local inbound 127.0.0.1:21001 not ready after 5000 ms (Connection refused)", failure!!.message)
    }

    @Test
    fun `内置核心多个节点未就绪时列出节点名`() {
        assertEquals(
            "local inbounds of 2 profiles not ready after 5000 ms: m0, m2",
            externalReadyOutcome(readiness(mihomoGroup, true, ready = setOf(1)), 5000).failure!!.message,
        )
        val many = plan(*Array(8) { vless("n$it") }).groups.single()
        assertEquals(
            "local inbounds of 8 profiles not ready after 5000 ms: n0, n1, n2, n3, n4 and 3 more",
            externalReadyOutcome(readiness(many, true), 5000).failure!!.message,
        )
    }

    @Test
    fun `内置核心的进程退出时报退出，不列节点`() {
        val outcome = externalReadyOutcome(readiness(xrayGroup, true, ready = setOf(0), exited = mapOf(1 to 255)), 5000)
        assertEquals(
            "Xray exited with code 255 before its local inbounds were ready; see the log for details",
            outcome.failure!!.message,
        )
    }

    @Test
    fun `就绪等待的报错文案里没有凭据`() {
        val texts = listOf(
            externalReadyOutcome(readiness(xrayGroup, true, ready = setOf(0, 2), error = "unexpected SOCKS5 reply 05 ff"), 5000),
            externalReadyOutcome(readiness(mihomoGroup, true), 5000),
            externalReadyOutcome(readiness(xrayGroup, true, exited = mapOf(0 to 23)), 5000),
            externalReadyOutcome(readiness(mihomoGroup, false), 5000),
        ).flatMap { listOfNotNull(it.failure?.message) + it.warnings }
        assertTrue(texts.isNotEmpty())
        for (text in texts) {
            assertFalse(text, auth.username in text || auth.password in text)
        }
    }

    @Test
    fun `插件核心未就绪或退出只警告`() {
        val group = plan(NaiveBean().apply {
            name = "nv"
            serverAddress = "naive.example.com"
            serverPort = 443
            initializeDefaultValues()
        }, anytls("m0")).groups
        val results = listOf(
            HopReadiness(group[0].hops[0], false, false, null, "Connection refused"),
            HopReadiness(group[1].hops[0], false, false, 1, null),
        )
        val outcome = externalReadyOutcome(results, 5000)
        assertNull(outcome.failure)
        assertEquals(
            listOf(
                "naive-plugin: nv (127.0.0.1:21000) not ready after 5000 ms (Connection refused); starting anyway",
                "mihomo-plugin exited with code 1 before m0 (127.0.0.1:21001) was ready; starting anyway",
            ),
            outcome.warnings,
        )
    }

    // ---- 认证检查（K0b）

    private fun naive(name: String) = NaiveBean().apply {
        this.name = name
        serverAddress = "$name.example.com"
        serverPort = 443
        initializeDefaultValues()
    }

    @Test
    fun `每个 strict 进程恰好查第一个跳实例，非 strict 的不查`() {
        val processes = plan(vless("x0"), anytls("m0"), naive("nv"), vless("x1"), anytls("m1"), naive("nw"))
            .groups.map { ExternalCoreProcess(it, "") }
        assertEquals(listOf("xray-plugin", "mihomo-plugin", "naive-plugin", "naive-plugin"), processes.map { it.group.pluginId })
        // 内置的 Xray、mihomo 都是 strict
        assertEquals(
            listOf("x0", "m0"),
            localAuthCheckHops(processes) { it.group.core.check != null }.map { it.bean.displayName() },
        )
        // 外部插件 app 提供的 mihomo 不是 strict
        assertEquals(
            listOf("x0"),
            localAuthCheckHops(processes) { it.group.pluginId == "xray-plugin" }.map { it.bean.displayName() },
        )
        assertTrue(localAuthCheckHops(processes) { false }.isEmpty())
        assertTrue(localAuthCheckHops(emptyList()) { true }.isEmpty())
    }

    @Test
    fun `错误的密码被接受时失败，消息写明核心、不带节点名与凭据`() {
        val outcome = externalAuthOutcome(
            listOf(
                xrayGroup.hops[0] to WrongPasswordReply.Accepted,
                mihomoGroup.hops[0] to WrongPasswordReply.Rejected("05 02 01 01"),
            )
        )
        val failure = outcome.failure
        assertTrue("$failure", failure is ExternalCoreAuthException)
        val message = failure!!.message!!
        assertEquals(
            "Xray: local inbound accepted a wrong password, so local SOCKS authentication is not in effect; " +
                "refusing to start",
            message,
        )
        assertFalse(message, "x0" in message || auth.username in message || auth.password in message)
        assertTrue(outcome.warnings.isEmpty())
        // 两个核心都被接受时都列出
        assertEquals(
            "Xray, mihomo: local inbound accepted a wrong password, so local SOCKS authentication is not in effect; " +
                "refusing to start",
            externalAuthOutcome(
                listOf(xrayGroup.hops[0] to WrongPasswordReply.Accepted, mihomoGroup.hops[0] to WrongPasswordReply.Accepted)
            ).failure!!.message,
        )
    }

    @Test
    fun `明确拒绝即通过，其它情况只警告`() {
        externalAuthOutcome(
            listOf(
                xrayGroup.hops[0] to WrongPasswordReply.Rejected("05 02 01 ff"),
                mihomoGroup.hops[0] to WrongPasswordReply.Rejected("05 02 01 01"),
            )
        ).let {
            assertNull(it.failure)
            assertTrue(it.warnings.isEmpty())
        }
        val outcome = externalAuthOutcome(
            listOf(
                xrayGroup.hops[0] to WrongPasswordReply.Unclear("unexpected SOCKS5 reply 05 ff (connection closed)"),
                mihomoGroup.hops[0] to WrongPasswordReply.Rejected("05 02 01 01"),
            )
        )
        assertNull(outcome.failure)
        assertEquals(
            listOf(
                "Xray: local inbound 127.0.0.1:21000 gave no clear rejection of a wrong password " +
                    "(unexpected SOCKS5 reply 05 ff (connection closed)); starting anyway",
            ),
            outcome.warnings,
        )
        assertTrue(externalAuthOutcome(emptyList()).let { it.failure == null && it.warnings.isEmpty() })
    }

    // ---- 校验输出按值遮蔽

    @Test
    fun `校验输出先按值遮蔽，解析出的报错里搜不到凭据`() {
        val result = ConfigBuildResult("{}", emptyList(), 1L, emptyMap(), emptyMap(), -1L, localAuth = auth)
        val creds = "user ${auth.username} password ${auth.password}"
        val outputs = listOf(
            // 对得回节点
            xray to "Failed to start: main: failed to load config files: [xray_1.json] > infra/conf: failed to build " +
                "inbound config with tag in-0 > infra/conf: invalid $creds",
            mihomo to mihomoOutput("listener 1: bad users: $creds"),
            // 对不回节点
            xray to "Failed to start: main: failed to load config files: [xray_1.json] > infra/conf/serial: $creds",
            mihomo to mihomoOutput("yaml: $creds"),
            // 认不出的输出，取最后几行
            xray to "panic: $creds",
        )
        for ((check, output) in outputs) {
            val group = if (check === xray) xrayGroup else mihomoGroup
            // 不遮蔽时凭据会进消息：这条用例确实覆盖了回显的情况
            val raw = failure(check.result(group, if (check === xray) 23 else 1, output)).message!!
            assertTrue(raw, auth.password in raw)
            val message = failure(check.result(group, if (check === xray) 23 else 1, result.redactLocalAuth(output))).message!!
            assertFalse(message, auth.username in message || auth.password in message)
            assertTrue(message, "***" in message)
        }
    }
}
