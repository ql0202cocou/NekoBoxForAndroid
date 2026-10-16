package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.fillSniFromServerAddress
import io.nekohasekai.sagernet.fmt.supportsAddressRewrite
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.nio.channels.FileLock
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import io.nekohasekai.sagernet.bg.ServiceRegistry

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    // startedGeneration：拿到更新锁后、读分组行之前记下的数据世代号，写库事务里比对（DataGenerationCheck.kt）
    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean,
        startedGeneration: Long,
    )

    data class Progress(
        var max: Int
    ) {
        // 并发的 DNS 解析线程都在累加：保留 AtomicInteger 以便用 addAndGet()，
        // 对委托出来的 Int 做 += 是非原子的读改写，会丢计数
        val progressAtomic = AtomicInteger()
        var progress by progressAtomic
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long, groupNameserver: String
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val progress = Progress(profiles.size)
        GroupUpdater.progress[groupId] = progress
        GroupManager.postProgress(groupId)

        // 同一订阅里的节点常共用域名：每个域名只解析一次，结果套用到所有同域名节点
        val byDomain = profiles.filter {
            supportsAddressRewrite(it) && !it.serverAddress.isIpAddress()
        }.groupBy { it.serverAddress }

        resolveDomains(
            byDomain = byDomain,
            maxConcurrent = FORCE_RESOLVE_CONCURRENCY,
            lookup = { domain ->
                if (
                    DataStore.enableFakeDns &&
                    ServiceRegistry.state.started &&
                    DataStore.serviceMode == Key.MODE_VPN
                ) {
                    // FakeDNS：app 自身流量也走 VPN，默认网络上的系统解析会拿到
                    // fakeip 段的假 IP 并被写进库。没有底层网络（如切换网络途中）
                    // 就判这个域名解析失败，不回退默认解析器；不用分组的节点解析 DNS
                    lookupServerAddress(
                        domain, null, SagerNet.underlyingNetwork ?: error("no underlying network"), ipv6Mode
                    )
                } else {
                    // 分组配了节点解析 DNS 时优先用它，没配或失败时回退系统 DNS
                    //（VPN 连着时系统 DNS 由内核解析）
                    lookupServerAddress(domain, groupNameserver, null, ipv6Mode)
                }
            },
            apply = { profile, results -> rewriteAddress(profile, results) },
            onFailure = { domain, e -> Logs.d("Lookup $domain failed: ${e.readableMessage}", e) },
            onDone = { count ->
                progress.progressAtomic.addAndGet(count)
                GroupManager.postProgress(groupId)
            },
        )
    }

    // addresses 已按 IPv6 模式过滤排序且非空（lookupServerAddress 的结果，空的按解析失败在调用方处理）：取第一个
    protected fun rewriteAddress(bean: AbstractBean, addresses: List<InetAddress>) {
        val address = addresses.first().hostAddress
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
                        // 世代号必须先于下面的分组行与 doUpdate 里的节点快照读取：在两次读取之间发生的恢复
                        // 也要让写库时的比对发现，反过来先读快照再读世代号就会漏掉
                        val startedGeneration = SagerDatabase.dataGeneration()
                        // 调用方传入的可能是界面里的旧对象（或自动更新排队时读的快照）：
                        // 订阅身份以拿到更新锁后读到的当前行为准，读不到（已删除）时沿用传入的，
                        // 由写库事务报告分组已删除
                        val group = SagerDatabase.groupDao.getById(proxyGroup.id) ?: proxyGroup
                        val subscription = group.subscription
                        if (subscription == null) {
                            // a corrupted backup restore can leave a subscription group without one
                            Logs.w("Group ${proxyGroup.id} has no subscription")
                            userInterface?.onUpdateFailure(group, "group has no subscription")
                            return@coroutineScope false
                        }

                        if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                            if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                                return@coroutineScope true
                            }
                        }

                        try {
                            RawUpdater.doUpdate(group, subscription, userInterface, byUser, startedGeneration)
                            true
                        } catch (e: CancellationException) {
                            // don't swallow cancellation (nor report it as a failure)
                            throw e
                        } catch (e: SubscriptionSupersededException) {
                            // 下载期间订阅被改了，结果已整体放弃：既不是失败也不是取消。
                            // 只告诉手动更新的用户一声，自动更新静默跳过，下个周期按新订阅更新
                            Logs.d(e.message ?: "subscription superseded")
                            if (byUser) userInterface?.onUpdateSuperseded(group)
                            false
                        } catch (e: DataRestoredDuringUpdateException) {
                            // 期间恢复了备份，结果已整体放弃。不提示：恢复成功后两个进程马上重启，提示也看不到；
                            // 自动更新下个周期按恢复后的数据重跑
                            Logs.i(e.message ?: "data restored during the update")
                            false
                        } catch (e: SubscriptionFoundException) {
                            // 订阅内容本身是订阅导入链接（clash://install-config /
                            // sn://subscription），该异常没有 message，直接展示会
                            // 退化成裸类名，换成本地化提示
                            Logs.w(e)
                            userInterface?.onUpdateFailure(group, app.getString(R.string.subscription_is_link))
                            false
                        } catch (e: Throwable) {
                            Logs.w(e)
                            userInterface?.onUpdateFailure(group, e.readableMessage)
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
