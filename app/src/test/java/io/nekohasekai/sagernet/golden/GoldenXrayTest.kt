package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenXrayTest {

    @Test
    fun `xray 配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("xray-plugin")
}
