package io.nekohasekai.sagernet.ui

import android.os.SystemClock
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepository
import io.nekohasekai.sagernet.database.ProfileRepository
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.displayName
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.lookupServerAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import moe.matsuri.nb4a.utils.Util
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

// runGroupTest 在主线程置位；测试结束、读库失败或 fragment 已销毁时清除（主线程
// 与后台都有）。CAS 防止连点两下起两个测试。放在进程级而不是 fragment 字段：
// 重建后的 fragment 不能再起第二个
private val runningTest = AtomicBoolean(false)

/**
 * 配置页的 TCP ping / URL 测试调度（从 ConfigurationFragment 拆出，逻辑与协程作用域不变）。
 * 由 ConfigurationFragment 的字段持有，只存 fragment 引用，生命周期与原先这些方法写在 fragment 里一致；
 * 测试任务跑在 appScope，捕获的仍是同一个 fragment
 */
class GroupTestRunner(private val fragment: ConfigurationFragment) {

    fun pingTest() {
        // 同组节点常共用一个域名，而下面的解析不带缓存、失败还要等满超时，
        // 所以按本轮测试缓存结果（失败时缓存域名本身，后面照常报解析失败）。
        // 缓存的是进行中的解析：并发 worker 碰上同一域名时等第一个的结果，不各解析一遍
        val resolvedAddresses = ConcurrentHashMap<String, CompletableDeferred<String>>()
        runGroupTest({ it.requireBean().canTCPing() }) { group, profile ->
            val domain = profile.requireBean().serverAddress
            val address = if (domain.isIpAddress()) domain else {
                val mine = CompletableDeferred<String>()
                val existing = resolvedAddresses.putIfAbsent(domain, mine)
                if (existing != null) existing.await() else try {
                    // 组里配了节点解析 DNS（如 DoH）时优先使用，伪造域名也能解析
                    // 测试不受 IPv6 模式限制（测速构建同样固定为启用），IPv4 优先
                    (lookupServerAddress(
                        domain, group.proxyServerNameserver, SagerNet.underlyingNetwork, IPv6Mode.ENABLE
                    )
                        .firstOrNull()?.hostAddress ?: domain).also { mine.complete(it) }
                } catch (e: Throwable) {
                    // 取消或意外异常都要让等待者拿到同一结果，否则它们会一直挂着
                    mine.completeExceptionally(e)
                    throw e
                }
            }
            if (!isActive) return@runGroupTest false
            // 在后台线程运行，fragment 可能已分离，getString() 会抛异常，所以用 app
            if (!address.isIpAddress()) {
                profile.status = 2
                profile.error = app.getString(R.string.connection_test_domain_not_found)
                return@runGroupTest true
            }
            try {
                val socket =
                    SagerNet.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
                try {
                    socket.soTimeout = 3000
                    socket.bind(InetSocketAddress(0))
                    val start = SystemClock.elapsedRealtime()
                    socket.connect(
                        InetSocketAddress(address, profile.requireBean().serverPort), 3000
                    )
                    if (!isActive) return@runGroupTest false
                    profile.status = 1
                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                    profile.error = null
                } finally {
                    // OkHttp 5 的 okhttp3.internal.closeQuietly 不是公开 API
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                if (!isActive) return@runGroupTest false
                val message = e.readableMessage
                profile.status = 2
                when {
                    !message.contains("failed:") -> profile.error =
                        app.getString(R.string.connection_test_timeout)

                    message.contains("ECONNREFUSED") -> profile.error =
                        app.getString(R.string.connection_test_refused)

                    message.contains("ENETUNREACH") -> profile.error =
                        app.getString(R.string.connection_test_unreachable)

                    // 报错原文会存库并显示在卡片上，按键名脱敏后再存
                    else -> {
                        profile.status = 3
                        profile.error = Util.redactSecrets(message)
                    }
                }
            }
            true
        }
    }

    fun urlTest() {
        // 整轮测试用同一个地址：中途改设置不影响剩下的节点，也不必每个节点读一次库。
        // lazy 让第一次读发生在测试的后台线程上
        val testURL by lazy { DataStore.connectionTestURL }
        runGroupTest({ true }) { _, profile ->
            try {
                // 注意：这里不在 bg 进程
                val result = TestInstance(profile, testURL, 5000).doTest()
                profile.status = 1
                profile.ping = result
                profile.error = null
            } catch (e: PluginManager.PluginNotFoundException) {
                profile.status = 2
                profile.error = e.readableMessage
            } catch (e: Exception) {
                // TestInstance 抛出前已按本次构建的凭据脱敏
                profile.status = 3
                profile.error = e.readableMessage
            }
            true
        }
    }

    // 两种测试共用的入口：currentGroup 有读库兜底，先后台取好分组再回主线程
    // 建对话框；读库失败时复位 runningTest，否则此后所有测试都被静默拒绝
    private fun runGroupTest(
        filter: (ProxyEntity) -> Boolean,
        testOne: suspend CoroutineScope.(ProxyGroup, ProxyEntity) -> Boolean,
    ) {
        if (!runningTest.compareAndSet(false, true)) return
        runOnDefaultDispatcher {
            val group = try {
                GroupRepository.currentGroup()
            } catch (e: Exception) {
                Logs.w(e)
                runningTest.set(false)
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                // 等读库的间隙里 fragment 可能已销毁
                if (!fragment.isAdded) {
                    runningTest.set(false)
                    return@onMainDispatcher
                }
                startGroupTest(group, filter, testOne)
            }
        }
    }

    // 按并发数起 worker 逐个测组里 filter 通过的节点，结束或取消时把状态写回
    // 数据库。testOne 填好 profile 的测试结果，返回 false 表示已被取消、不要
    // 上报这一条
    @OptIn(DelicateCoroutinesApi::class)
    private fun startGroupTest(
        group: ProxyGroup,
        filter: (ProxyEntity) -> Boolean,
        testOne: suspend CoroutineScope.(ProxyGroup, ProxyEntity) -> Boolean,
    ) {
        val test = TestDialog(fragment)
        val testJobs = mutableListOf<Job>()

        val mainJob = runOnDefaultDispatcher {
            // 这里抛异常会直达 appScope 的默认 handler 崩溃，且 test.cancel()
            // 不再执行、runningTest 永久卡 true；走与取消相同的复位路径
            try {
                val profilesList = ProfileRepository.getProfilesByGroup(group.id).filter(filter)
                test.proxyN = profilesList.size
                val profiles = ConcurrentLinkedQueue(profilesList)
                repeat(DataStore.connectionTestConcurrent) {
                    testJobs.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0
                            if (!testOne(group, profile)) break
                            test.update(profile)
                        }
                    })
                }

                testJobs.joinAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            test.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                // 只写状态：快照是测试开始时读的，整行更新会把 :bg 之后
                // 写入的 tx/rx 回滚；测试期间配置已变的行不写（旧结果对不上新配置）。
                // 整批一个事务，随后整组重读一次刷新列表。
                // 重读被测的分组而不是当前分组：最小化后用户可能已翻到别的组，
                // 批量写不像逐行写那样发 onUpdated，读错组被测组就停在旧结果
                try {
                    ProfileRepository.updateTestResults(test.results.toList())
                } catch (e: Exception) {
                    Logs.w(e)
                }
                GroupRepository.postReload(group.id)
                runningTest.set(false)
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            // 通知用 application context：测试任务可能比当前 Activity 活得久
            test.notification = ConnectionTestNotification(
                app,
                "[${group.displayName()}] ${app.getString(R.string.connection_test)}"
            )
            test.hide()
        }
    }
}
