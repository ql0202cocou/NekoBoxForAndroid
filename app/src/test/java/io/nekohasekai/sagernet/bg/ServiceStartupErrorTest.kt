package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.BoxInstanceLifecycleTest.ShadowLifecycleLibcore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.uitest.UiFixtures
import io.nekohasekai.sagernet.uitest.UiSettle
import io.nekohasekai.sagernet.uitest.UiTestBase
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@Config(shadows = [ShadowLifecycleLibcore::class])
class ServiceStartupErrorTest : UiTestBase() {

    @Test
    fun `启动失败后服务回调及日志中的异常链都按本次配置脱敏`() {
        val group = UiFixtures.install().normalGroup
        val secret = "synthetic-short-id"
        ui.offMain {
            val bean = ConfigBean().apply {
                initializeDefaultValues()
                name = "startup-test"
                config = """{"reality":{"short_id":["$secret"]}}"""
            }
            DataStore.selectedProxy = SagerDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = group.id, type = ProxyEntity.TYPE_CONFIG, configBean = bean)
            )
            DataStore.serviceMode = Key.MODE_PROXY
        }
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        val logs = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        val logging = Logs.enabled
        try {
            service.data.binder.registerCallback(object : ISagerNetServiceCallback.Stub() {
                override fun stateChanged(state: Int, profileName: String?, msg: String?) {
                    if (msg != null) messages += msg
                }
                override fun missingPlugin(profileName: String?, pluginName: String?) {}
                override fun cbSelectorUpdate(previousId: Long, id: Long) {}
                override fun cbSpeedUpdate(stats: SpeedDisplayData?) {}
                override fun cbTrafficUpdate(stats: TrafficData?) {}
            }, SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            Logs.enabled = true
            ShadowLifecycleLibcore.log = { logs += it }
            ShadowLifecycleLibcore.create = {
                throw IllegalArgumentException("decode short_id[0]: $secret", IllegalStateException("cause $secret")).apply {
                    addSuppressed(IllegalArgumentException("suppressed $secret"))
                }
            }
            service.onStartCommand(null, 0, 1)
            UiSettle.settle()
            assertTrue(messages.toString(), messages.any { "decode short_id[0]: ***" in it })
            assertTrue(logs.any { "cause ***" in it && "suppressed ***" in it })
            assertFalse((messages + logs).any { secret in it })
        } finally {
            controller.destroy()
            UiSettle.settle()
            Logs.enabled = logging
            ShadowLifecycleLibcore.reset()
        }
    }
}
