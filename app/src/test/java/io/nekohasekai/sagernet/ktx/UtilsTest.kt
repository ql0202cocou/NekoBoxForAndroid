package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 只覆盖不依赖 Android 框架的纯函数；parseNumericAddress（android.system.Os）等
// 在本地 JVM 单测里不可用，不在此测试
class UtilsTest {

    @Test
    fun `blankAsNull 空白字符串返回 null`() {
        assertNull("  ".blankAsNull())
    }

    @Test
    fun `blankAsNull null 返回 null`() {
        assertNull((null as String?).blankAsNull())
    }

    @Test
    fun `blankAsNull 非空白字符串原样返回`() {
        assertEquals(" abc ", " abc ".blankAsNull())
    }

    @Test
    fun `readableMessage 有 message 时返回 message`() {
        assertEquals("出错了", IllegalStateException("出错了").readableMessage)
    }

    @Test
    fun `readableMessage 无 message 时返回类名`() {
        assertEquals("IllegalStateException", IllegalStateException().readableMessage)
    }

    @Test
    fun `parsePort 合法端口返回解析值`() {
        assertEquals(8080, parsePort("8080", 1080))
    }

    @Test
    fun `parsePort 非数字返回默认值`() {
        assertEquals(1080, parsePort("abc", 1080))
    }

    @Test
    fun `parsePort null 返回默认值`() {
        assertEquals(1080, parsePort(null, 1080))
    }

    @Test
    fun `parsePort 小于最小值返回默认值`() {
        assertEquals(1080, parsePort("80", 1080))
    }

    @Test
    fun `parsePort 超出 65535 返回默认值`() {
        assertEquals(1080, parsePort("65536", 1080))
    }

    @Test
    fun `parsePort 边界值原样返回`() {
        assertEquals(1025, parsePort("1025", 1080))
        assertEquals(65535, parsePort("65535", 1080))
    }

    @Test
    fun `parsePort 自定义最小值生效`() {
        assertEquals(53, parsePort("53", 1080, min = 1))
    }

    @Test
    fun `urlSafe 空格不会被编码成加号`() {
        assertEquals("a%20b", "a b".urlSafe())
    }

    @Test
    fun `urlSafe 与 unUrlSafe 往返一致`() {
        val raw = "密码 & co/?+"
        assertEquals(raw, raw.urlSafe().unUrlSafe())
    }
}
