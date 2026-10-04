package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenMihomoTest {

    @Test
    fun `mihomo 配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("mihomo-plugin")
}
