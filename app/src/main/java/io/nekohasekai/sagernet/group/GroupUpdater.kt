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
                                DataStore.serviceState.started &&
                                DataStore.serviceMode == Key.MODE_VPN
                            ) {
                                // FakeDNS
                                SagerNet.underlyingNetwork!!
                                    .getAllByName(profile.serverAddress)
                                    .filterNotNull()
                            } else {
                                // 分组指定了节点解析 DNS 时优先使用，失败回退系统 DNS
                                // System DNS is enough (when VPN connected, it uses v2ray-core)
                                lookupViaNameserver(groupNameserver, profile.serverAddress)
                                    ?: InetAddress.getAllByName(profile.serverAddress).filterNotNull()
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

                        val connected = DataStore.serviceState.connected
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
            GroupManager.postUpdate(proxyGroup)
        }

    }

}
