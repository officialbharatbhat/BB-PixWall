package bb.pix.wall.network

import java.net.Inet4Address
import java.net.NetworkInterface

object LanInfo {
    fun localIpv4(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .firstOrNull { ip ->
                    ip.startsWith("192.168.") || ip.startsWith("10.") ||
                        ip.matches(Regex("172\\.(1[6-9]|2\\d|3[0-1])\\..*"))
                }
                ?: "Unavailable"
        }.getOrDefault("Unavailable")
    }
}
