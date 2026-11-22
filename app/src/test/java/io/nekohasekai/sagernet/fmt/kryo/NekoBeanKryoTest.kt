package io.nekohasekai.sagernet.fmt.kryo

import android.app.Application
import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.proxy.neko.NekoBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NekoBeanKryoTest {
    @Test
    fun `首个提交的插件数据逐字段读回且可以原样写出`() {
        // 样本由 7d9798cc 的写入器在 Android 上使用 Kryo 5.2.1 生成。
        val bytes = checkNotNull(javaClass.getResourceAsStream("/kryo/android/NekoBean-v0.bin")).use { it.readBytes() }
        val bean = KryoConverters.deserialize(NekoBean(), bytes)
        assertEquals("legacy.example.org", bean.serverAddress)
        assertEquals(4043, bean.serverPort.toInt())
        assertEquals("旧节点", bean.name)
        assertEquals("example.plugin", bean.plgId)
        assertEquals("audit", bean.protocolId)
        assertEquals(2, bean.sharedStorage.length())
        assertEquals("旧字段", bean.sharedStorage.getString("label"))
        assertEquals(3, bean.sharedStorage.getInt("count"))
        assertEquals("", bean.customOutboundJson)
        assertEquals("{}", bean.customConfigJson)
        assertArrayEquals(bytes, KryoConverters.serialize(bean))
    }
}
