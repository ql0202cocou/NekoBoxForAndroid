package io.nekohasekai.sagernet.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomainResolverTest {

    private val addr = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))

    private fun domains(n: Int) = (1..n).associate { "d$it.example" to listOf("n$it-a", "n$it-b") }

    @Test
    fun `30 个域名同时解析的最大数恰为 5 且每个域名只解析一次`() = runBlocking {
        val byDomain = domains(30)
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val applied = ConcurrentHashMap.newKeySet<String>()
        val progress = AtomicInteger()

        val job = launch(Dispatchers.Default) {
            resolveDomains(
                byDomain, FORCE_RESOLVE_CONCURRENCY,
                lookup = { domain ->
                    calls.computeIfAbsent(domain) { AtomicInteger() }.incrementAndGet()
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    try {
                        // 与真实的 lookupServerAddress 一样：切到别的调度器后挂起等待
                        withContext(Dispatchers.IO) {
                            gates.computeIfAbsent(domain) { CompletableDeferred() }.await()
                        }
                        addr
                    } finally {
                        inFlight.decrementAndGet()
                    }
                },
                apply = { item, _ -> applied.add(item) },
                onFailure = { d, e -> throw AssertionError("$d: $e") },
                onDone = { progress.addAndGet(it) },
            )
        }

        // 等到满额的 5 个都进入挂起；再多放一会，确认第 6 个不会进来
        withTimeout(10_000) { while (inFlight.get() < FORCE_RESOLVE_CONCURRENCY) delay(5) }
        delay(200)
        assertEquals(FORCE_RESOLVE_CONCURRENCY, inFlight.get())

        // 逐个放行，直到全部完成
        withTimeout(30_000) {
            while (job.isActive) {
                gates.values.forEach { it.complete(Unit) }
                delay(5)
            }
        }
        job.join()

        assertEquals(FORCE_RESOLVE_CONCURRENCY, maxInFlight.get())
        assertEquals(30, calls.size)
        assertTrue(calls.values.all { it.get() == 1 })
        assertEquals(60, applied.size)
        assertEquals(60, progress.get())
    }

    @Test
    fun `一个域名解析抛异常不影响其它域名且进度照样累加`() = runBlocking {
        val byDomain = domains(8)
        val failures = ConcurrentHashMap<String, Exception>()
        val applied = ConcurrentHashMap.newKeySet<String>()
        val progress = AtomicInteger()

        resolveDomains(
            byDomain, FORCE_RESOLVE_CONCURRENCY,
            lookup = { domain ->
                when (domain) {
                    "d3.example" -> error("boom")
                    "d5.example" -> emptyList()
                    else -> addr
                }
            },
            apply = { item, _ -> applied.add(item) },
            onFailure = { d, e -> failures[d] = e },
            onDone = { progress.addAndGet(it) },
        )

        assertEquals(setOf("d3.example", "d5.example"), failures.keys)
        assertEquals(12, applied.size)
        assertTrue("n3-a" !in applied && "n5-b" !in applied)
        assertEquals(16, progress.get())
    }

    @Test
    fun `取消外层作用域时挂起中的解析被取消并抛 CancellationException`() = runBlocking {
        val byDomain = domains(12)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val failures = AtomicInteger()
        val never = CompletableDeferred<Unit>()

        val deferred = async(Dispatchers.Default) {
            resolveDomains(
                byDomain, FORCE_RESOLVE_CONCURRENCY,
                lookup = {
                    started.incrementAndGet()
                    try {
                        withContext(Dispatchers.IO) { never.await() }
                        addr
                    } catch (e: CancellationException) {
                        cancelled.incrementAndGet()
                        throw e
                    }
                },
                apply = { _, _ -> },
                onFailure = { _, _ -> failures.incrementAndGet() },
                onDone = { },
            )
        }

        withTimeout(10_000) { while (started.get() < FORCE_RESOLVE_CONCURRENCY) delay(5) }
        deferred.cancelAndJoin()

        assertTrue(deferred.isCancelled)
        assertEquals(FORCE_RESOLVE_CONCURRENCY, cancelled.get())
        // 取消不当作解析失败，排队中的域名也不会再开始
        assertEquals(0, failures.get())
        assertEquals(FORCE_RESOLVE_CONCURRENCY, started.get())
    }
}
