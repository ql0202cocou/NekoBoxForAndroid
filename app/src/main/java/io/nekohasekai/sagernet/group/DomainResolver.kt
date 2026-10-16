package io.nekohasekai.sagernet.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

/** 强制解析同时在解析的域名数上限 */
internal const val FORCE_RESOLVE_CONCURRENCY = 5

/**
 * 按域名逐个解析，同时进行中的域名数不超过 [maxConcurrent]，并把结果回写给同域名的各个元素。
 *
 * 并发上限靠信号量保证，permit 持有到 [lookup] 的挂起等待结束为止；
 * 不能靠限制调度线程数：[lookup] 内部会切到别的调度器挂起，线程让出后上限就失效了。
 * 不读 DataStore、不碰 Android，解析动作与回写都由调用方注入。
 *
 * - 解析抛异常或结果为空：只让这个域名失败，调用 [onFailure]，其余域名照常；
 * - 每个域名（不论成败）结束后调用一次 [onDone]，参数为该域名下的元素个数；
 * - 外层取消时挂起中的解析被取消，本函数抛 [CancellationException]。
 */
internal suspend fun <T> resolveDomains(
    byDomain: Map<String, List<T>>,
    maxConcurrent: Int,
    lookup: suspend (domain: String) -> List<InetAddress>,
    apply: (item: T, addresses: List<InetAddress>) -> Unit,
    onFailure: (domain: String, error: Exception) -> Unit,
    onDone: suspend (count: Int) -> Unit,
) {
    val semaphore = Semaphore(maxConcurrent)
    coroutineScope {
        for ((domain, items) in byDomain) {
            launch {
                semaphore.withPermit {
                    try {
                        val results = lookup(domain)
                        if (results.isEmpty()) error("empty response")
                        for (item in items) apply(item, results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailure(domain, e)
                    }
                    onDone(items.size)
                }
            }
        }
    }
}
