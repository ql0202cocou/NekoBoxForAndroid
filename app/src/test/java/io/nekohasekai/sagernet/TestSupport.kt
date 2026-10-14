package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.ktx.Logs
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/**
 * 测试期间关掉 Logs（开着就走 JNI 的 Libcore，JVM 上没有），结束后恢复原值。
 * 用法：`@get:Rule val logs = SilencedLogs()`
 */
class SilencedLogs : ExternalResource() {
    private var wasEnabled = true

    override fun before() {
        wasEnabled = Logs.enabled
        Logs.enabled = false
    }

    override fun after() {
        Logs.enabled = wasEnabled
    }
}

/** 收集式断言：把逐项检查出的问题一次全部列出来 */
fun assertNoFailures(failures: List<String>) {
    assertTrue("${failures.size} 处问题：\n" + failures.joinToString("\n"), failures.isEmpty())
}
