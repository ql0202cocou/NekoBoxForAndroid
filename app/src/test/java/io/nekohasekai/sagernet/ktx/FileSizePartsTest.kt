package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertEquals
import org.junit.Test

/** 状态卡片速率的「数字 / 单位」拆分：只测纯函数，格式化本身（SI 进制、本地化小数点）是系统的事 */
class FileSizePartsTest {

    private fun parts(text: String) = splitFileSize(text).let { it.number to it.unit }

    @Test
    fun splitsNumberAndUnit() {
        assertEquals("1.30" to "GB", parts("1.30 GB"))
        assertEquals("0" to "B", parts("0 B"))
        assertEquals("999" to "kB", parts("999 kB"))
        assertEquals("1.00" to "MB", parts("1.00 MB"))
    }

    @Test
    fun acceptsNoBreakSpaces() {
        assertEquals("12.5" to "MB", parts("12.5 MB"))
        assertEquals("12.5" to "MB", parts("12.5 MB"))
    }

    @Test
    fun keepsLocalizedDecimalSeparatorAndDigits() {
        assertEquals("1,30" to "GB", parts("1,30 GB"))
        assertEquals("١٫٣٠" to "GB", parts("١٫٣٠ GB"))
    }

    @Test
    fun handlesUnitFirstOrder() {
        assertEquals("1.5" to "kB", parts("kB 1.5"))
    }

    @Test
    fun handlesRepeatedAndSurroundingWhitespace() {
        assertEquals("3.2" to "MB", parts("  3.2   MB  "))
    }

    @Test
    fun fallsBackToWholeTextAsNumber() {
        assertEquals("42" to "", parts("42"))
        assertEquals("" to "", parts(""))
        assertEquals("abc def" to "", parts("abc def"))
    }
}
