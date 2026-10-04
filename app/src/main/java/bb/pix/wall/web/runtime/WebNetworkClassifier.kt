package bb.pix.wall.web.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import bb.pix.wall.web.model.NetworkClass

object WebNetworkClassifier {
    fun detect(
        context: Context,
    ): NetworkClass {
        val cm =
            context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            cm.activeNetwork
                ?: return NetworkClass.OFFLINE

        val caps =
            cm.getNetworkCapabilities(network)
                ?: return NetworkClass.OFFLINE

        if (
            !caps.hasCapability(
                NetworkCapabilities
                    .NET_CAPABILITY_INTERNET
            )
        ) {
            return NetworkClass.OFFLINE
        }

        if (
            caps.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            )
        ) {
            return NetworkClass.WIFI
        }

        if (
            caps.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            )
        ) {
            val tm =
                context.getSystemService(
                    Context.TELEPHONY_SERVICE
                ) as TelephonyManager

            val radioType =
                runCatching {
                    @Suppress("DEPRECATION")
                    tm.dataNetworkType
                }.getOrNull()

            return when (radioType) {
                TelephonyManager.NETWORK_TYPE_NR ->
                    NetworkClass.FIVE_G

                TelephonyManager.NETWORK_TYPE_LTE ->
                    NetworkClass.FOUR_G

                else -> {
                    val downstreamKbps =
                        caps.linkDownstreamBandwidthKbps

                    when {
                        downstreamKbps >= 100_000 ->
                            NetworkClass.FIVE_G

                        downstreamKbps >= 10_000 ->
                            NetworkClass.FOUR_G

                        else ->
                            NetworkClass.SLOW
                    }
                }
            }
        }

        return NetworkClass.SLOW
    }
}
