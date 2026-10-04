package io.nekohasekai.sagernet.golden

import org.junit.Test

class GoldenMieruTest {

    @Test
    fun `mieru 配置与基线一致`() = GoldenExternalCoreCheck.assertMatchesBaseline("mieru-plugin")
}
