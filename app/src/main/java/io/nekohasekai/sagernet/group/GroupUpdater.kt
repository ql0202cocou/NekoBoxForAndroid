package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.fillSniFromServerAddress
import io.nekohasekai.sagernet.fmt.supportsAddressRewrite
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.channels.FileLock
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import io.nekohasekai.sagernet.bg.ServiceRegistry

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        // incremented from the concurrent DNS lookup threads; keep the
        // AtomicInteger reachable so incrementAndGet() can be used — ++ on
        // the delegated Int is a non-atomic get+set and loses increments
        val progressAtomic = AtomicInteger()
        var progress by progressAtomic
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?, groupNameserver: String? = null
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupThreadIndex = AtomicInteger()
        val lookupPool = Executors.newFixedThreadPool(5) { runnable ->
            Thread(runnable, "DNS Lookup-${lookupThreadIndex.incrementAndGet()}")
        }.asCoroutineDispatcher()
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER

        try {
            coroutineScope {
                for (profile in profiles) {
                    if (!supportsAddressRewrite(profile)) continue
                    if (profile.serverAddress.isIpAddress()) continue

                    launch(lookupPool) {
                        try {
                            val results = if (
                                SagerNet.underlyingNetwork != null &&
                                DataStore.enableFakeDns &&
                                ServiceRegistry.state.started &&
                                DataStore.serviceMode == Key.MODE_VPN
                            ) {
                                // FakeDNS
                                lookupBlocking(
                                    profile.serverAddress,
                                    SagerNet.underlyingNetwork!!::getAllByName
                                )
                            } else {
                                // 分组指定了节点解析 DNS 时优先使用，失败回退系统 DNS
                                // System DNS is enough (when VPN connected, it uses v2ray-core)
                                lookupViaNameserver(groupNameserver, profile.serverAddress)
                                    ?: lookupBlocking(profile.serverAddress, InetAddress::getAllByName)
                            }
                            if (results.isEmpty()) error("empty response")
                            rewriteAddress(profile, results, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
                        }
                        if (groupId != null) {
                            progress.progressAtomic.incrementAndGet()
                            GroupManager.postReload(groupId)
                        }
                    }
                }
            }
        } finally {
            lookupPool.close()
        }
    }

    // 系统解析器与 FakeDNS 下的 underlyingNetwork 两条 getAllByName 都是没有
    // 超时的阻塞 JNI 调用，协程取消也打不断，会一直攥住 lookupPool 线程
    //（coroutineScope 挂住期间更新持有跨进程文件锁）。把它挂到 appScope 上跑、
    // 这里只等 10 秒（与 lookupViaNameserver 的原生十秒预算一致）：超时按解析
    // 失败处理并取消 lookup——还没开跑的不再执行，已在跑的在解析器返回后
    // 自行结束，结果直接丢弃。
    // Job.cancel() 成员与同名 CoroutineScope 扩展（通配 import）同时可见：编译期
    // 恒解析到成员，lint 的跨环境歧义警告在此不适用（同 BaseService.destroyRunner）
    @Suppress("MemberExtensionConflict")
    private suspend fun lookupBlocking(
        domain: String, resolve: (String) -> Array<InetAddress>
    ): List<InetAddress> {
        val lookup = appScope.async(Dispatchers.IO) {
            runCatching { resolve(domain).filterNotNull() }.getOrDefault(emptyList())
        }
        return withTimeoutOrNull(10_000L) { lookup.await() } ?: run {
            lookup.cancel()
            Logs.w("DNS lookup for $domain timed out")
            emptyList()
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) == ipv6First }[0].hostAddress
        fillSniFromServerAddress(bean)
        bean.serverAddress = address
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) {
                    // another update for this group is already running
                    return@coroutineScope false
                }

                // cross-process mutex: periodic WorkManager updates run in :bg while
                // manual updates run in the main process
                val lockPair = try {
                    val channel = RandomAccessFile(
                        File(SagerNet.application.filesDir, "group_update_${proxyGroup.id}.lock"), "rw"
                    ).channel
                    // tryLock can return null or throw (e.g. EMFILE); close the channel
                    // unless we hand it out, or each failed attempt leaks an fd
                    var lock: FileLock? = null
                    try {
                        lock = channel.tryLock()
                    } finally {
                        if (lock == null) channel.close()
                    }
                    lock?.let { channel to it }
                } catch (e: Throwable) {
                    Logs.w(e)
                    null
                }
                if (lockPair == null) {
                    // another process is updating this group: nothing happened here, so
                    // only drop the in-process marker — finishUpdate would also broadcast
                    // a spurious "update finished" to the UI
                    updating.remove(proxyGroup.id)
                    return@coroutineScope false
                }
                val (lockChannel, fileLock) = lockPair

                try {
                    try {
                        GroupManager.postReload(proxyGroup.id)

                        val connected = ServiceRegistry.state.connected
                        val userInterface = GroupManager.userInterface
                        val subscription = proxyGroup.subscription
                        if (subscription == null) {
                            // a corrupted backup restore can leave a subscription group without one
                            Logs.w("Group ${proxyGroup.id} has no subscription")
                            userInterface?.onUpdateFailure(proxyGroup, "group has no subscription")
                            return@coroutineScope false
                        }

                        if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                            if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                                return@coroutineScope true
                            }
                        }

                        try {
                            RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                            true
                        } catch (e: CancellationException) {
                            // don't swallow cancellation (nor report it as a failure)
                            throw e
                        } catch (e: SubscriptionFoundException) {
                            // 订阅内容本身是订阅导入链接（clash://install-config /
                            // sn://subscription），该异常没有 message，直接展示会
                            // 退化成裸类名，换成本地化提示
                            Logs.w(e)
                            userInterface?.onUpdateFailure(
                                proxyGroup, app.getString(R.string.subscription_is_link)
                            )
                            false
                        } catch (e: Throwable) {
                            Logs.w(e)
                            userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                            false
                        }
                    } finally {
                        runCatching { fileLock.release() }
                        runCatching { lockChannel.close() }
                    }
                } finally {
                    withContext(NonCancellable) {
                        try {
                            finishUpdate(proxyGroup)
                        } catch (e: Throwable) {
                            Logs.w(e)
                        }
                    }
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            // 传入的是更新开始前的旧快照，直接广播会把用户期间的编辑视觉回滚；
            // 按 id 的通知路径会重读当前行（分组已被删除时则不再广播）
            GroupManager.postUpdate(proxyGroup.id)
        }

    }

}
