package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenHysteriaTest {

    @Test
    fun `hysteria 1 插件配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("hysteria-plugin")
}
