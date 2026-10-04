package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenNaiveTest {

    @Test
    fun `naive 配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("naive-plugin")
}
