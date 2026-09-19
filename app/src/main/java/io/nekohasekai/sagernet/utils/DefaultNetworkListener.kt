package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking

@OptIn(ObsoleteCoroutinesApi::class)
object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Stop(val key: Any) : NetworkMessage()

        class Put(val source: Callback, val network: Network) : NetworkMessage()
        class Update(val source: Callback, val network: Network) : NetworkMessage()
        class Lost(val source: Callback, val network: Network) : NetworkMessage()
    }

    // A subscriber failure must not kill the process-wide network actor or prevent
    // the remaining subscribers from receiving this and subsequent network events.
    private fun notifyListener(listener: (Network?) -> Unit, network: Network?) {
        try {
            listener(network)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun notifyListeners(listeners: Collection<(Network?) -> Unit>, network: Network?) {
        for (listener in listeners) notifyListener(listener, network)
    }

    private val networkActor = appScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        var activeCallback: Callback? = null
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (activeCallback == null) {
                    val callback = Callback()
                    if (register(callback)) activeCallback = callback
                }
                listeners[message.key] = message.listener
                if (network != null) notifyListener(message.listener, network)
            }
            is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                listeners.remove(message.key) != null && listeners.isEmpty()
            ) {
                network = null
                val callback = activeCallback
                activeCallback = null
                if (callback != null) unregister(callback)
            }

            is NetworkMessage.Put -> if (message.source === activeCallback) {
                network = message.network
                notifyListeners(listeners.values, network)
            }
            is NetworkMessage.Update -> if (message.source === activeCallback && network == message.network) {
                notifyListeners(listeners.values, network)
            }
            is NetworkMessage.Lost -> if (message.source === activeCallback && network == message.network) {
                network = null
                notifyListeners(listeners.values, network)
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private class Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Put(this@Callback, network)) }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            runBlocking { networkActor.send(NetworkMessage.Update(this@Callback, network)) }
        }

        override fun onLost(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Lost(this@Callback, network)) }
    }

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register(callback: Callback): Boolean {
        return try {
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @RequiresApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, callback, mainHandler
                    )
                }
                in 28 until 31 -> @RequiresApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, callback, mainHandler)
                }
                in 26 until 28 -> @RequiresApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }
                in 24 until 26 -> @RequiresApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            true
        } catch (e: Exception) {
            Logs.w(e)
            // A partially successful platform registration must not leak.
            unregister(callback)
            false
        }
    }

    private fun unregister(callback: Callback) {
        // throws IllegalArgumentException if register() failed; an
        // uncaught throw here kills the actor and every later send fails
        runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }
            .onFailure { Logs.w(it) }
    }
}
