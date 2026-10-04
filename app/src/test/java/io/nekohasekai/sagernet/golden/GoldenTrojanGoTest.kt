package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenTrojanGoTest {

    @Test
    fun `trojan-go 配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("trojan-go-plugin")
}
