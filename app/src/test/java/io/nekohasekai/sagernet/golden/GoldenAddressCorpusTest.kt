package io.nekohasekai.sagernet.golden

import io.nekohasekai.sagernet.golden.GoldenAddressCorpus.Companion.quoted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM 黄金测试的地址解析：语料之外的输入走 StrictNumericAddress，它必须在全部语料上与设备结果一致
class GoldenAddressCorpusTest {

    private fun hex(text: String) = StrictNumericAddress.parse(text)?.address?.joinToString("") { "%02x".format(it) }

    @Test
    fun `回退实现在全部语料上与设备上的 parseNumericAddress 一致`() {
        val corpus = GoldenAddressCorpus.of(GoldenBaseline.load())
        assertTrue("语料是空的", corpus.entries.isNotEmpty())
        val mismatches = corpus.entries.mapNotNull { (input, expected) ->
            val actual = hex(input)
            if (actual == expected) null else "${input.quoted()}：设备 $expected，回退实现 $actual"
        }
        assertTrue("与设备结果不一致 ${mismatches.size} 条：\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun `基线构建会查到的语料之外的输入`() {
        // 名单就是 JVM 黄金测试查到的语料之外的输入（GoldenJvmBuildTest 核对），这里核对回退实现在它们上的结果
        val corpus = GoldenAddressCorpus.of(GoldenBaseline.load())
        for ((input, expected) in GoldenAddressCorpus.KNOWN_OUTSIDE) {
            assertTrue("${input.quoted()} 已经在语料里，从名单删掉", input !in corpus.entries)
            assertEquals(input.quoted(), expected, hex(input))
        }
    }

    @Test
    fun `IPv4 前导零按十进制，IPv6 每组至多 4 位`() {
        assertEquals("b1000001", hex("0177.0.0.1"))
        assertNull(hex("256.0.0.01"))
        assertNull(hex("02001:db8::1"))
        assertEquals("00000000000000000000000000000000", hex("::"))
        assertEquals("20010db8000000000000000000000000", hex("2001:db8::"))
        assertNull(hex("2001:db8:"))
        assertNull(hex(":2001:db8::1"))
        // 结尾的点分 IPv4 占满 8 组时没有位置
        assertNull(hex("1:2:3:4:5:6:7:1.2.3.4"))
        assertEquals("00010002000300040005000601020304", hex("1:2:3:4:5:6:1.2.3.4"))
    }
}
