package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.RunGeneration
import android.net.Network
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TrafficBindings
import io.nekohasekai.sagernet.uitest.UiSettle
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowService

class VpnUnderlyingNetworkTest : UiTestBase() {

    @Implements(android.net.VpnService::class)
    class ShadowVpn : ShadowService() {
        companion object {
            val reports = mutableListOf<List<Network>?>()
        }
        @Implementation
        fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
            reports += networks?.toList()
            return true
        }
    }

    @Implements(android.net.VpnService.Builder::class)
    class ShadowVpnBuilder {
        companion object {
            val initial = mutableListOf<List<Network>?>()
            lateinit var establishTun: () -> ParcelFileDescriptor
        }
        @RealObject lateinit var builder: android.net.VpnService.Builder

        // Android 的字符串重载调用桌面 JDK 没有的 parseNumericAddress，转给同一 Builder 的地址重载。
        @Implementation
        fun addAddress(address: String, prefix: Int) = builder.addAddress(java.net.InetAddress.getByName(address), prefix)

        @Implementation
        fun addRoute(address: String, prefix: Int) = builder.addRoute(java.net.InetAddress.getByName(address), prefix)

        @Implementation
        fun addDnsServer(address: String) = builder.addDnsServer(java.net.InetAddress.getByName(address))

        @Implementation
        fun setUnderlyingNetworks(networks: Array<Network>?): android.net.VpnService.Builder {
            initial += networks?.toList()
            return builder
        }

        @Implementation
        fun establish(): ParcelFileDescriptor = establishTun()
    }

    @Config(shadows = [ShadowVpn::class, ShadowVpnBuilder::class])
    @Test
    fun `VPN 首次建立使用系统默认，建立期间断网及再次建立都能报告空上游`() {
        val controller = Robolectric.buildService(VpnService::class.java).create()
        val vpn = controller.get()
        val proxy = ProxyInstance(ProxyEntity(), vpn, "vpn", RunGeneration(0L, 0L))
        proxy.config = ConfigBuildResult(
            "{}", emptyList(), 0L, TrafficBindings(emptyMap(), emptyMap(), emptySet()), emptyMap(), -1L,
        )
        vpn.data.proxy = proxy
        vpn.data.state = BaseService.State.Connecting
        val first = ShadowNetwork.newInstance(73)
        fun descriptor(): ParcelFileDescriptor = ParcelFileDescriptor.createPipe().let { (read, write) ->
            write.close()
            read
        }
        ShadowVpnBuilder.initial.clear()
        ShadowVpn.reports.clear()
        try {
            ShadowVpnBuilder.establishTun = {
                vpn.onDefaultNetworkChanged(first)
                vpn.onDefaultNetworkChanged(null)
                descriptor()
            }
            vpn.startVpn("{}", "{}")
            assertEquals(listOf(null), ShadowVpnBuilder.initial)
            assertEquals(listOf(emptyList<Network>()), ShadowVpn.reports)

            ShadowVpnBuilder.establishTun = ::descriptor
            vpn.startVpn("{}", "{}")
            assertEquals(listOf(null, emptyList<Network>()), ShadowVpnBuilder.initial)
        } finally {
            controller.destroy()
            UiSettle.settle()
        }
    }

    @Config(shadows = [ShadowVpn::class])
    @Test
    fun `上游丢失和随后恢复均报告给系统，无链路属性也能报告`() {
        val controller = Robolectric.buildService(VpnService::class.java).create()
        val vpn = controller.get()
        val pipe = ParcelFileDescriptor.createPipe()
        vpn.conn = pipe[0]
        val first = ShadowNetwork.newInstance(71)
        val next = ShadowNetwork.newInstance(72)
        ShadowVpn.reports.clear()
        try {
            vpn.onDefaultNetworkChanged(first)
            vpn.onDefaultNetworkChanged(null)
            assertNull(SagerNet.underlyingNetwork)
            vpn.onDefaultNetworkChanged(next)
            assertEquals(next, SagerNet.underlyingNetwork)
            assertEquals(listOf(listOf(first), emptyList(), listOf(next)), ShadowVpn.reports)
        } finally {
            controller.destroy()
            pipe[1].close()
            SagerNet.underlyingNetwork = null
            UiSettle.settle()
        }
    }
}
