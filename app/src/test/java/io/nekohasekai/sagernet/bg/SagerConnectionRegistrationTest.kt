package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.uitest.UiTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration

/** 使用生成的 AIDL Proxy / Stub 和真实服务注册表，仅在 transact 边界注入通信失败。 */
class SagerConnectionRegistrationTest : UiTestBase() {
    private val foreground = SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
    private val background = SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name get() = ComponentName(context, SagerConnection.serviceClass)
    private val bindingContext by lazy {
        object : ContextWrapper(context) {
            override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int) = true
            override fun unbindService(conn: ServiceConnection) {}
        }
    }

    @Test
    fun `前后台注册短暂失败后无需再次切页即可恢复服务端状态`() {
        for ((initial, target) in listOf(background to foreground, foreground to background)) {
            val connection = SagerConnection(initial)
            val remote = RemoteRegistration()
            try {
                assertTrue(connection.connect(bindingContext, Callback()))
                connection.onServiceConnected(name, remote.binder)
                remote.assertRegistered(initial)
                remote.failures = 1
                connection.updateConnectionId(target)
                remote.assertRegistered(initial)
                // 模拟缓冲区短暂不足：服务与 binder 仍存活，不会收到死亡或重连通知。
                assertTrue(remote.binder.isBinderAlive)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                remote.assertRegistered(target)
                assertEquals(3, remote.attempts)
            } finally {
                connection.disconnect(bindingContext)
                assertTrue(remote.service.callbackIdMap.isEmpty())
                remote.close()
            }
        }
    }

    @Test
    fun `实际主页面停止与返回时的失败均会恢复最新前后台状态`() {
        val activity = ui.launch<MainActivity>()
        val connection = activity.get().connection
        val remote = RemoteRegistration()
        try {
            assertTrue(connection.connect(bindingContext, activity.get()))
            connection.onServiceConnected(name, remote.binder)
            remote.assertRegistered(foreground)
            remote.failures = 1
            activity.pause().stop()
            remote.assertRegistered(foreground)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            remote.assertRegistered(background)
            remote.failures = 1
            activity.start().resume()
            remote.assertRegistered(background)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            remote.assertRegistered(foreground)
        } finally {
            ui.destroyNow(activity)
            assertTrue(remote.service.callbackIdMap.isEmpty())
            remote.close()
        }
    }

    @Test
    fun `重试期间再次切换只提交最新状态且成功后停止重试`() {
        withConnection(foreground) { connection, remote ->
            remote.failures = 2
            connection.updateConnectionId(background)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(2, remote.attempts)
            connection.updateConnectionId(foreground)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            remote.assertRegistered(foreground)
            assertEquals(listOf(foreground, background, foreground, foreground), remote.requestedIds)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals(4, remote.attempts)
        }
    }

    @Test
    fun `连续通信失败有退避且故障解除后无需生命周期事件即可恢复`() {
        withConnection(background) { connection, remote ->
            remote.failures = Int.MAX_VALUE
            connection.updateConnectionId(foreground)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertEquals(2, remote.attempts)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
            // 持续失败不能紧密循环或每秒一直唤醒，也不能有限次放弃后永久停留在旧状态。
            assertTrue(remote.attempts in 8..20)
            remote.assertRegistered(background)
            remote.failures = 0
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            remote.assertRegistered(foreground)
        }
    }

    @Test
    fun `断开服务断连及死亡均取消旧重试且重连沿用最新状态`() {
        for (end in listOf("disconnect", "disconnected", "death")) {
            withConnection(background) { connection, remote ->
                remote.failures = 1
                connection.updateConnectionId(foreground)
                when (end) {
                    "disconnect" -> connection.disconnect(bindingContext)
                    "disconnected" -> connection.onServiceDisconnected(name)
                    else -> connection.binderDied()
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(2, remote.attempts)
                assertNull(connection.service)
                if (end != "death") assertTrue(remote.service.callbackIdMap.isEmpty())
                // 换 Binder 后不能再向旧服务注册，也不能覆盖新服务的后台状态。
                RemoteRegistration().use { next ->
                    connection.connect(bindingContext, Callback())
                    connection.onServiceConnected(name, next.binder)
                    next.assertRegistered(foreground)
                    connection.updateConnectionId(background)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
                    next.assertRegistered(background)
                    assertEquals(2, next.attempts)
                    assertEquals(2, remote.attempts)
                    connection.disconnect(bindingContext)
                    assertTrue(next.service.callbackIdMap.isEmpty())
                }
                // 死亡的服务在系统中已不存在；此处释放用于注入死亡通知的本地替身。
                if (end == "death") remote.close()
            }
        }
    }

    @Test
    fun `注册在服务端成功但回复失败时重试不会重复登记`() {
        withConnection(background) { connection, remote ->
            remote.failures = 1
            remote.failAfterCommit = true
            connection.updateConnectionId(foreground)
            remote.assertRegistered(foreground)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            remote.assertRegistered(foreground)
            assertEquals(3, remote.attempts)
        }
    }

    @Test
    fun `首次注册失败可恢复且未知执行结果在断开时仍被清理`() {
        for (applied in listOf(false, true)) for (recover in listOf(false, true)) {
            val connection = SagerConnection(foreground)
            RemoteRegistration().use { remote ->
                remote.failures = 1
                remote.failAfterCommit = applied
                try {
                    connection.connect(bindingContext, Callback())
                    connection.onServiceConnected(name, remote.binder)
                    if (applied) remote.assertRegistered(foreground)
                    else assertTrue(remote.service.callbackIdMap.isEmpty())
                    if (recover) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                        remote.assertRegistered(foreground)
                    }
                } finally {
                    connection.disconnect(bindingContext)
                }
                assertTrue(remote.service.callbackIdMap.isEmpty())
                val attempts = remote.attempts
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(attempts, remote.attempts)
            }
        }
    }

    @Test
    fun `未连接时仅保存最新状态且非通信异常不反复重试`() {
        val connection = SagerConnection(foreground)
        RemoteRegistration().use { remote ->
            try {
                connection.updateConnectionId(background)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(0, remote.attempts)
                connection.connect(bindingContext, Callback())
                connection.onServiceConnected(name, remote.binder)
                remote.assertRegistered(background)
                remote.failures = 1
                remote.failure = { SecurityException("synthetic permanent refusal") }
                connection.updateConnectionId(foreground)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
                assertEquals(2, remote.attempts)
                remote.assertRegistered(background)
                // 后续显式更新仍可尝试，不把当前目标回退为服务端的旧状态。
                connection.updateConnectionId(foreground)
                remote.assertRegistered(foreground)
            } finally {
                connection.disconnect(bindingContext)
            }
            assertTrue(remote.service.callbackIdMap.isEmpty())
        }
    }

    private fun withConnection(initial: Int, test: (SagerConnection, RemoteRegistration) -> Unit) {
        val connection = SagerConnection(initial)
        RemoteRegistration().use { remote ->
            try {
                assertTrue(connection.connect(bindingContext, Callback()))
                connection.onServiceConnected(name, remote.binder)
                remote.assertRegistered(initial)
                test(connection, remote)
            } finally {
                connection.disconnect(bindingContext)
            }
            assertTrue(remote.service.callbackIdMap.isEmpty())
        }
    }

    private class Callback : SagerConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
        override fun onServiceConnected(service: ISagerNetService) {}
    }

    private class RemoteRegistration : AutoCloseable {
        val service = BaseService.Binder()
        var failures = 0
        var attempts = 0
        var failAfterCommit = false
        var failure: () -> Exception = { DeadObjectException("synthetic transient transaction failure") }
        val requestedIds = mutableListOf<Int>()
        private val registrationCode: Int = ReflectionHelpers.getStaticField(
            ISagerNetService.Stub::class.java, "TRANSACTION_registerCallback")
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, args ->
            when {
                // 强制走生成的 AIDL Proxy，而非直接调用本地 Stub。
                method.name == "queryLocalInterface" -> null
                method.name == "transact" && args!![0] == registrationCode -> {
                    attempts++
                    val request = args[1] as Parcel
                    val position = request.dataPosition()
                    request.setDataPosition(0)
                    request.enforceInterface(ISagerNetService.DESCRIPTOR)
                    request.readStrongBinder()
                    requestedIds += request.readInt()
                    request.setDataPosition(position)
                    if (failures > 0) {
                        failures--
                        if (failAfterCommit) invoke(method, args)
                        throw failure()
                    }
                    invoke(method, args)
                }
                else -> invoke(method, args)
            }
        } as IBinder

        private fun invoke(method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            method.invoke(service, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        fun assertRegistered(id: Int) {
            assertEquals(listOf(id), service.callbackIdMap.values.toList())
            var callbacks = 0
            runBlocking { service.broadcast { callbacks++ } }
            assertEquals(1, callbacks)
        }
        override fun close() = service.close()
    }
}
