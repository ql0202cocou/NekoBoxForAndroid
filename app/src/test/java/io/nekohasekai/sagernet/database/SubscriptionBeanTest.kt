package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

// SubscriptionBean 的 Kryo 存储格式：version 5 追加 nameserverFromSubscription
class SubscriptionBeanTest {

    private fun defaultBean() = SubscriptionBean().apply { initializeDefaultValues() }

    private fun serialize(bean: SubscriptionBean) = KryoConverters.serialize(bean)

    // 不用 KryoConverters.deserialize：它会补默认值，v4 用例要先断言读出的是 null
    private fun deserialize(bytes: ByteArray) = SubscriptionBean().apply {
        deserializeFromBuffer(bytes.byteBuffer())
    }

    @Test
    fun `version 5 往返保留 nameserver 来源标记`() {
        val bean = defaultBean().apply {
            link = "https://example.com/sub"
            lastUpdated = 1_700_000_000L
            nameserverFromSubscription = true
        }
        val read = deserialize(serialize(bean))
        assertEquals(true, read.nameserverFromSubscription)
        assertEquals("https://example.com/sub", read.link)
        assertEquals(1_700_000_000L, read.lastUpdated)
    }

    @Test
    fun `version 4 数据没有来源标记，按默认值视为用户手填`() {
        val bytes = serialize(defaultBean().apply { nameserverFromSubscription = true })
        // 去掉末尾的 v5 布尔字节并把版本号改回 4，即 v4 写出的数据
        val v4 = bytes.copyOf(bytes.size - 1)
        ByteBufferOutput(ByteBuffer.wrap(v4)).writeInt(4)
        val read = deserialize(v4)
        assertNull(read.nameserverFromSubscription)
        read.initializeDefaultValues()
        assertEquals(false, read.nameserverFromSubscription)
    }

    @Test
    fun `来源标记为 null 时按 false 写出而不是抛 NPE`() {
        // 分享导入的 bean 不走 initializeDefaultValues
        val bean = defaultBean().apply { nameserverFromSubscription = null }
        assertEquals(false, deserialize(serialize(bean)).nameserverFromSubscription)
    }
}
