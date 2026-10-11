package moe.matsuri.nb4a

import org.junit.Assert.assertEquals
import org.junit.Test

// autoDetectInterfaceControl 的分流判定（NativeInterface.kt）。注册表里的服务是 Android Service，JVM 里构造不了，
// 这里只测抽出来的纯判定；入口对它的调用靠模拟器上两种模式的拨号核对
class DialProtectActionTest {

    @Test
    fun `已登记 VpnService：一律 protect，不论登记的基础服务是什么`() {
        assertEquals(DialProtectAction.PROTECT, dialProtectAction(vpnRegistered = true, baseIsProxyService = false))
        // 停止与启动重叠的窗口：旧 VpnService 还在注册表里，保持原行为
        assertEquals(DialProtectAction.PROTECT, dialProtectAction(vpnRegistered = true, baseIsProxyService = true))
    }

    @Test
    fun `没有 VpnService、登记的是 ProxyService（仅代理模式）：不 protect`() {
        assertEquals(DialProtectAction.SKIP, dialProtectAction(vpnRegistered = false, baseIsProxyService = true))
    }

    @Test
    fun `两者都没有：失败，不放行（VPN 模式下 VpnService 尚未登记时不能 fail-open）`() {
        assertEquals(DialProtectAction.FAIL, dialProtectAction(vpnRegistered = false, baseIsProxyService = false))
    }
}
