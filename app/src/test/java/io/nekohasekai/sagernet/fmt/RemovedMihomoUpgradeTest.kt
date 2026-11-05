package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.*
import org.junit.Test

class RemovedMihomoUpgradeTest {
    @Test
    fun `历史写入器与全部不可变 v4 样本逐字节相同`() {
        val samples = KryoSamples.samples.filter { it.id.startsWith("AnyTLSBean/policy-v4/") }
        assertEquals(3, samples.size)
        for (sample in samples) {
            assertArrayEquals(sample.id, sample.bytes, LegacyAnyTLSV4.bytes(KryoSamples.read(sample) as AnyTLSBean))
        }
    }

    @Test
    fun `旧 SOCKS 覆写在三种构建模式都明确拒绝而不改为 AnyTLS 目标`() {
        val bytes = LegacyAnyTLSV4.bytes(AnyTLSBean().apply {
            initializeDefaultValues()
            serverAddress = "anytls.example.org"
            serverPort = 443
            password = "fake-password"
            customOutboundJson = """{"server":"socks-bridge.example.org","server_port":1080}"""
        })
        val profile = ProxyEntity(id = 1, groupId = 1, type = ProxyEntity.TYPE_ANYTLS, core = ProxyEntity.CORE_MIHOMO)
            .apply { putByteArray(bytes) }
        BackupRestore.upgradeLegacyProfiles(listOf(profile)) { error("不读取全局设置") }
        val decision = profile.coreDecision(false) as CoreDecision.Rejected
        assertEquals(listOf(CoreConflict.SING_BOX_ANYTLS_LOCAL_SOCKS_OVERRIDE), decision.conflicts.map { it.id })
        val saved = KryoConverters.serialize(profile.requireBean())
        for (mode in ConfigBuildMode.entries) {
            val record = ProfileRecord.of(profile)
            val source = MemoryConfigDataSource(listOf(ProxyGroup(id = 1)), listOf(profile), emptyList())
            val platform = FakeConfigPlatform()
            val input = ConfigInput(mode, record, testConfigSettings(), ConfigSnapshot.collect(source, record, mode), emptyMap(), platform)
            assertThrows(ProfileBuildException::class.java) { buildConfig(input) }
            assertTrue(platform.trialQueries.isEmpty())
            assertTrue(platform.pluginQueries.isEmpty())
            assertTrue(platform.ports.isEmpty())
            assertArrayEquals(saved, KryoConverters.serialize(profile.requireBean()))
        }
    }
}
