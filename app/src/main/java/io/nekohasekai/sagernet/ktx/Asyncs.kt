package io.nekohasekai.sagernet.ktx

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.Executors

// process-wide scope for work that must outlive any component; never
// cancelled, it lives and dies with the process
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    appScope.launch(Dispatchers.Default, block = block)

// single thread for callbacks that must run in order and may block
// (DefaultNetworkListener -> Libcore.resetAllConnections)
private val serialDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

fun runOnSerialDispatcher(block: suspend CoroutineScope.() -> Unit) =
    appScope.launch(serialDispatcher, block = block)

fun Fragment.runOnLifecycleDispatcher(block: suspend CoroutineScope.() -> Unit) =
    lifecycleScope.launch(Dispatchers.Default, block = block)

fun runOnIoDispatcher(block: suspend CoroutineScope.() -> Unit) =
    appScope.launch(Dispatchers.IO, block = block)

suspend fun <T> onIoDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.IO, block = block)

fun runOnMainDispatcher(block: suspend CoroutineScope.() -> Unit) =
    appScope.launch(Dispatchers.Main.immediate, block = block)

suspend fun <T> onMainDispatcher(block: suspend CoroutineScope.() -> T) =
    withContext(Dispatchers.Main.immediate, block = block)
