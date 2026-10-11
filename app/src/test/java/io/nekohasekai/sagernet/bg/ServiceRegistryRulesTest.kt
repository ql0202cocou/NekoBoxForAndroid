package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// 服务注册表的属主规则（ServiceRegistry.kt）。注册表本身持有 Android Service 类型，JVM 里没法构造，
// 这里只测抽出来的两条纯判定；入口方法对它们的调用靠编译与模拟器上的模式切换核对
class ServiceRegistryRulesTest {

    private val old = Any()
    private val fresh = Any()

    @Test
    fun `清理只在登记的就是属主自己时生效`() {
        assertNull(releasedBy(old, old))
        // 两实例并存：新实例已接管，旧实例的清理不动它
        assertSame(fresh, releasedBy(fresh, old))
        // 本来就是空
        assertNull(releasedBy<Any>(null, old))
    }

    @Test
    fun `状态写入：没有实例登记，或登记着的是属主自己才接受`() {
        assertTrue(mayWriteState(null, old))
        assertTrue(mayWriteState(old, old))
        // 旧实例停止完成时，新实例已登记：旧实例的 Stopped 不能覆盖新实例的状态
        assertFalse(mayWriteState(fresh, old))
    }
}
