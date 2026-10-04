package bb.pix.wall.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Lightweight local device-context snapshot.
 *
 * No network requests, no sensors, no permissions beyond
 * what BB-PixWall already needs for network state.
 */
object ContextAwareness {

    data class Snapshot(
        val darkMode: Boolean,
        val batteryPct: Int,
        val charging: Boolean,
        val powerSave: Boolean,
        val network: String,
    )

    fun snapshot(
        context: Context,
    ): Snapshot {
        val darkMode =
            (
                context.resources
                    .configuration
                    .uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            ) ==
                Configuration.UI_MODE_NIGHT_YES

        val batteryManager =
            context.getSystemService(
                BatteryManager::class.java
            )

        val capacity =
            runCatching {
                batteryManager
                    ?.getIntProperty(
                        BatteryManager
                            .BATTERY_PROPERTY_CAPACITY
                    )
                    ?: -1
            }.getOrDefault(-1)
                .coerceIn(-1, 100)

        val batteryIntent =
            runCatching {
                context.registerReceiver(
                    null,
                    IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED
                    ),
                )
            }.getOrNull()

        val status =
            batteryIntent
                ?.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    -1,
                )
                ?: -1

        val charging =
            status ==
                BatteryManager
                    .BATTERY_STATUS_CHARGING ||
                status ==
                BatteryManager
                    .BATTERY_STATUS_FULL

        val powerManager =
            context.getSystemService(
                PowerManager::class.java
            )

        val powerSave =
            powerManager
                ?.isPowerSaveMode
                ?: false

        val cm =
            context.getSystemService(
                ConnectivityManager::class.java
            )

        val network =
            runCatching {
                val active =
                    cm?.activeNetwork
                        ?: return@runCatching "offline"

                val caps =
                    cm.getNetworkCapabilities(
                        active
                    )
                        ?: return@runCatching "offline"

                when {
                    caps.hasTransport(
                        NetworkCapabilities
                            .TRANSPORT_WIFI
                    ) ->
                        "wifi"

                    caps.hasTransport(
                        NetworkCapabilities
                            .TRANSPORT_CELLULAR
                    ) ->
                        "mobile"

                    caps.hasTransport(
                        NetworkCapabilities
                            .TRANSPORT_ETHERNET
                    ) ->
                        "ethernet"

                    else ->
                        "other"
                }
            }.getOrDefault("unknown")

        return Snapshot(
            darkMode = darkMode,
            batteryPct = capacity,
            charging = charging,
            powerSave = powerSave,
            network = network,
        )
    }

    /**
     * Extremely small visual bias.
     *
     * Context must never overpower source quality,
     * anti-repeat, long-term taste or session mood.
     */
    fun visualInfluence(
        snapshot: Snapshot,
        traits: WallpaperStyleLearning.Traits?,
    ): Int {
        if (traits == null) {
            return 0
        }

        return if (snapshot.darkMode) {
            when {
                traits.darkRatio >= 0.72f ->
                    2

                traits.darkRatio >= 0.58f ->
                    1

                traits.brightness >= 0.78f ->
                    -1

                else ->
                    0
            }
        } else {
            when {
                traits.brightness >= 0.72f &&
                    traits.darkRatio <= 0.30f ->
                    1

                traits.darkRatio >= 0.86f ->
                    -1

                else ->
                    0
            }
        }
    }

    fun describe(
        snapshot: Snapshot,
    ): String =
        buildString {
            append(
                if (snapshot.darkMode) {
                    "dark"
                } else {
                    "light"
                }
            )

            append(" • battery=")

            append(
                if (snapshot.batteryPct >= 0) {
                    "${snapshot.batteryPct}%"
                } else {
                    "unknown"
                }
            )

            append(
                if (snapshot.charging) {
                    " charging"
                } else {
                    ""
                }
            )

            if (snapshot.powerSave) {
                append(" • saver")
            }

            append(" • ")
            append(snapshot.network)
        }
}
