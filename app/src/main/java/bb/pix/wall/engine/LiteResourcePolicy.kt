package bb.pix.wall.engine

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Apply from local cache regardless of network; postpone only costly cloud refills. */
object LiteResourcePolicy {
    fun deferCloudRefill(context: Context): String? {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val pm = context.getSystemService(PowerManager::class.java)
        if (level in 0..14 && pm?.isPowerSaveMode != false) return "Low battery"
        if (pm?.isPowerSaveMode == true) return "Battery saver"
        if (Build.VERSION.SDK_INT >= 29 && (pm?.currentThermalStatus ?: 0) >=
            PowerManager.THERMAL_STATUS_MODERATE) return "Device warm"
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "Offline"
        return null
    }
}
