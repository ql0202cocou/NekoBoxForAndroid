package io.nekohasekai.sagernet.bg

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.QuickDisableShortcut
import io.nekohasekai.sagernet.ui.QuickEnableShortcut
import io.nekohasekai.sagernet.uitest.UiTestBase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * 运行 SDK 35 的真实 ContextImpl / LoadedApk 绑定与解绑，只替换 ActivityManager 的远程调用。
 * 因而异常转换、框架回调登记及释放都由 Android 实现完成，不在桩里复制这些行为。
 */
@Config(shadows = [SagerConnectionTest.BindingContext::class, SagerConnectionTest.BindingActivityManager::class])
class SagerConnectionTest : UiTestBase() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepareBinding() {
        RemoteBinding.reset()
        DataStore.serviceMode = Key.MODE_VPN
        SagerConnection.restartingApp = false
    }

    @After
    fun resetBinding() {
        SagerConnection.restartingApp = false
        RemoteBinding.reset()
    }

    @Test
    fun `系统拒绝绑定后释放框架登记并允许重试`() = verifyFailure { 0 }

    @Test
    fun `系统通信异常后释放框架登记并允许重试`() = verifyFailure {
        throw RemoteException("synthetic binder failure")
    }

    @Test
    fun `框架安全异常后释放登记并允许重试`() = verifyFailure { -1 }

    private fun verifyFailure(bind: () -> Int) {
        for (mode in listOf(Key.MODE_VPN, Key.MODE_PROXY)) {
            DataStore.serviceMode = mode
            RemoteBinding.reset()
            verifyFailureInCurrentMode(bind)
        }
    }

    private fun verifyFailureInCurrentMode(bind: () -> Int) {
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        val abandoned = Callback()
        val retried = Callback()
        RemoteBinding.bind = bind
        SagerConnection.restartingApp = true
        try {
            assertFalse(connection.connect(context, abandoned))
            assertFalse(SagerConnection.restartingApp)
            assertFalse(isRegistered(connection))
            assertEquals(1, RemoteBinding.binds)
            assertEquals(1, RemoteBinding.unbinds)
            val oldDispatcher = RemoteBinding.dispatchers.single()
            assertNull(connection.service)
            connection.binderDied()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, abandoned.deaths)
            RemoteBinding.bind = { 1 }
            assertTrue(connection.connect(context, retried))
            assertEquals(2, RemoteBinding.binds)
            assertTrue(isRegistered(connection))
            // 失败请求的迟到回调不能投递给重试的新 callback。
            deliverConnected(oldDispatcher, Service())
            assertTrue(abandoned.connected.isEmpty())
            assertTrue(retried.connected.isEmpty())
            val service = Service()
            deliverConnected(RemoteBinding.dispatchers.last(), service)
            assertEquals(listOf(service), retried.connected)
            assertSame(service, connection.service)
            assertEquals(listOf(SagerConnection.CONNECTION_ID_RESTART_BG), service.registrations)
            // 已绑定时重复 connect 不重新请求系统，也不替换 callback。
            assertTrue(connection.connect(context, abandoned))
            assertEquals(2, RemoteBinding.binds)
            connection.binderDied()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, retried.deaths)
            assertEquals(0, abandoned.deaths)
        } finally {
            connection.disconnect(context)
        }
        assertFalse(isRegistered(connection))
        assertEquals(2, RemoteBinding.unbinds)
        connection.disconnect(context)
        assertEquals(2, RemoteBinding.unbinds)
    }

    @Test
    fun `解绑也遇到系统异常时仍清除本地状态与框架登记`() {
        RemoteBinding.bind = { throw RemoteException("synthetic bind failure") }
        RemoteBinding.unbind = { throw RemoteException("synthetic unbind failure") }
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        SagerConnection.restartingApp = true
        assertFalse(connection.connect(context, Callback()))
        assertFalse(SagerConnection.restartingApp)
        assertFalse(isRegistered(connection))
        assertEquals(1, RemoteBinding.unbinds)
        RemoteBinding.bind = { 1 }
        RemoteBinding.unbind = { true }
        try {
            assertTrue(connection.connect(context, Callback()))
            assertEquals(2, RemoteBinding.binds)
        } finally {
            connection.disconnect(context)
        }
    }

    @Test
    fun `登记前抛异常时允许无登记解绑并能再次绑定`() {
        val failingContext = object : ContextWrapper(context) {
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
                throw SecurityException("synthetic early failure")
            }
        }
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
        assertFalse(connection.connect(failingContext, Callback()))
        assertFalse(isRegistered(connection))
        assertEquals(0, RemoteBinding.binds)
        assertEquals(0, RemoteBinding.unbinds)
        RemoteBinding.bind = { 1 }
        try {
            assertTrue(connection.connect(context, Callback()))
            assertEquals(1, RemoteBinding.binds)
        } finally {
            connection.disconnect(context)
        }
    }

    @Test
    fun `成功绑定保留重启标记且正常注销与解绑`() {
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        val callback = Callback()
        val service = Service()
        RemoteBinding.bind = { 1 }
        SagerConnection.restartingApp = true
        try {
            assertTrue(connection.connect(context, callback))
            assertTrue(SagerConnection.restartingApp)
            deliverConnected(RemoteBinding.dispatchers.single(), service)
            assertEquals(listOf(service), callback.connected)
            assertEquals(0, RemoteBinding.unbinds)
        } finally {
            connection.disconnect(context)
        }
        assertEquals(1, service.unregistrations)
        assertEquals(1, RemoteBinding.unbinds)
        assertFalse(isRegistered(connection))
        assertNull(connection.service)
    }

    @Test
    fun `绑定异常时三个无界面快捷方式均正常结束`() {
        RemoteBinding.bind = { throw RemoteException("synthetic shortcut bind failure") }
        for (type in listOf(QuickToggleShortcut::class.java, QuickEnableShortcut::class.java, QuickDisableShortcut::class.java)) {
            val activity = ui.launchCreatedOnly(type, Intent(context, type).setAction(Intent.ACTION_MAIN))
            assertTrue(activity.get().isFinishing)
            assertFalse(isRegistered(RemoteBinding.connections.last()))
            ui.destroyNow(activity)
        }
        assertEquals(3, RemoteBinding.binds)
        assertEquals(3, RemoteBinding.unbinds)
    }

    @Test
    fun `绑定已接受但无回调时三个快捷方式超时退出且迟到回调无效`() {
        RemoteBinding.bind = { 1 }
        for (type in listOf(QuickToggleShortcut::class.java, QuickEnableShortcut::class.java, QuickDisableShortcut::class.java)) {
            val activity = ui.launchCreatedOnly(type, Intent(context, type).setAction(Intent.ACTION_MAIN))
            assertFalse(activity.get().isFinishing)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertTrue(activity.get().isFinishing)
            val connection = RemoteBinding.connections.last()
            assertFalse(isRegistered(connection))
            val late = Service()
            deliverConnected(RemoteBinding.dispatchers.last(), late)
            assertTrue(late.registrations.isEmpty())
            ui.destroyNow(activity)
        }
        assertEquals(3, RemoteBinding.unbinds)
    }

    @Test
    fun `快捷方式按时回连或提前销毁不会遗留超时解绑`() {
        RemoteBinding.bind = { 1 }
        val type = QuickDisableShortcut::class.java
        val connected = ui.launchCreatedOnly(type, Intent(context, type))
        val service = Service()
        deliverConnected(RemoteBinding.dispatchers.last(), service)
        assertTrue(connected.get().isFinishing)
        ui.destroyNow(connected)
        val destroyed = ui.launchCreatedOnly(type, Intent(context, type))
        ui.destroyNow(destroyed)
        val unbinds = RemoteBinding.unbinds
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(unbinds, RemoteBinding.unbinds)
        assertEquals(1, service.unregistrations)
    }

    private fun isRegistered(connection: ServiceConnection): Boolean {
        val base = (context as ContextWrapper).baseContext
        val loadedApk: Any = ReflectionHelpers.getField(base, "mPackageInfo")
        val services: Map<*, *> = ReflectionHelpers.getField(loadedApk, "mServices")
        return services.values.any { (it as Map<*, *>).containsKey(connection) }
    }

    private class Callback : SagerConnection.Callback {
        var deaths = 0
        val connected = mutableListOf<ISagerNetService>()
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
        override fun onServiceConnected(service: ISagerNetService) { connected += service }
        override fun onBinderDied() { deaths++ }
    }

    private class Service : ISagerNetService.Stub() {
        val registrations = mutableListOf<Int>()
        var unregistrations = 0
        override fun getState() = BaseService.State.Stopped.ordinal
        override fun getProfileName() = "binding-test"
        override fun urlTest() = 0
        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) { registrations += id }
        override fun unregisterCallback(cb: ISagerNetServiceCallback) { unregistrations++ }
    }

    private fun deliverConnected(dispatcher: Any, service: Service) {
        ReflectionHelpers.callInstanceMethod<Unit>(dispatcher, "connected",
            from(ComponentName::class.java, ComponentName(context, SagerConnection.serviceClass)),
            from(IBinder::class.java, service), from(Boolean::class.javaPrimitiveType!!, false))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private object RemoteBinding {
        var bind: () -> Int = { 0 }
        var unbind: () -> Boolean = { true }
        var binds = 0
        var unbinds = 0
        val connections = mutableListOf<ServiceConnection>()
        val dispatchers = mutableListOf<Any>()
        fun reset() {
            bind = { 0 }
            unbind = { true }
            binds = 0
            unbinds = 0
            connections.clear()
            dispatchers.clear()
        }
        val manager: Any by lazy {
            val type = Class.forName("android.app.IActivityManager")
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "bindServiceInstance" -> {
                        binds++
                        val index = method.parameterTypes.indexOfFirst { it.name == "android.app.IServiceConnection" }
                        dispatchers += args!![index]
                        bind()
                    }
                    "unbindService" -> { unbinds++; unbind() }
                    else -> error("非预期系统调用：${method.name}")
                }
            }
        }
    }

    @Implements(ActivityManager::class)
    class BindingActivityManager : ShadowActivityManager() {
        companion object {
            @JvmStatic
            @Implementation
            fun getService(): Any = RemoteBinding.manager
        }
    }

    @Implements(className = ShadowContextImpl.CLASS_NAME, isInAndroidSdk = false)
    class BindingContext : ShadowContextImpl() {
        @RealObject
        private lateinit var realContext: Context

        @Implementation
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            // Room 等无关服务保持未绑定，仅让生产 SagerConnection 进入真实框架实现。
            if (intent.action != Action.SERVICE) return false
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            assertEquals(ComponentName(realContext, SagerConnection.serviceClass), intent.component)
            RemoteBinding.connections += conn
            return Shadow.directlyOn(realContext, ShadowContextImpl.CLASS_NAME, "bindService",
                from(Intent::class.java, intent), from(ServiceConnection::class.java, conn),
                from(Int::class.javaPrimitiveType!!, flags))
        }

        @Implementation
        override fun unbindService(conn: ServiceConnection) {
            if (conn !is SagerConnection) return
            Shadow.directlyOn<Unit>(realContext, ShadowContextImpl.CLASS_NAME, "unbindService",
                from(ServiceConnection::class.java, conn))
        }
    }
}
