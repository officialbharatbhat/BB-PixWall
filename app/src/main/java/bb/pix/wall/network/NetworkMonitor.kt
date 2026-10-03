package bb.pix.wall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList

object NetworkMonitor {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var registered = false
    private var cm: ConnectivityManager? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = fire()
        override fun onLost(network: Network) = fire()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = fire()
        private fun fire() = listeners.forEach { runCatching { it() } }
    }

    @Synchronized fun register(context: Context) {
        if (registered) return
        cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.registerDefaultNetworkCallback(callback); registered = true }
    }
    fun add(listener: () -> Unit) { listeners += listener }
    fun remove(listener: () -> Unit) { listeners -= listener }
}
