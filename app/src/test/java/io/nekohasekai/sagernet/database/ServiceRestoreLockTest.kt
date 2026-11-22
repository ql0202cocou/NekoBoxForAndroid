package io.nekohasekai.sagernet.database

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceRestoreLockTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun lastServiceMustFinishBeforeRestore() {
        val guard = ServiceRestoreLock(File(temp.root, "lock"))
        val first = checkNotNull(guard.tryAcquire(forService = true))
        val second = checkNotNull(guard.tryAcquire(forService = true))
        first.close()
        first.close()
        assertNull(guard.tryAcquire(forService = false))
        second.close()
        checkNotNull(guard.tryAcquire(forService = false)).use {
            assertNull(guard.tryAcquire(forService = true))
            assertNull(guard.tryAcquire(forService = false))
        }
    }

    @Test
    fun processDeathReleasesEitherSideOfTheLock() {
        val file = File(temp.root, "lock")
        val guard = ServiceRestoreLock(file)
        for (service in listOf(true, false)) {
            val ready = File(temp.root, "ready-$service")
            val output = File(temp.root, "child-$service.log")
            val child = ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", System.getProperty("java.class.path"), ServiceRestoreLockChild::class.java.name,
                file.path, service.toString(), ready.path,
            ).redirectErrorStream(true).redirectOutput(output).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(output.readText(), ready.exists())
                assertNull(guard.tryAcquire(forService = !service))
            } finally {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            }
            checkNotNull(guard.tryAcquire(forService = !service)).close()
        }
    }
}

object ServiceRestoreLockChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val guard = ServiceRestoreLock(File(args[0]))
        checkNotNull(guard.tryAcquire(forService = args[1].toBooleanStrict())).use {
            File(args[2]).createNewFile()
            System.`in`.read()
        }
    }
}
