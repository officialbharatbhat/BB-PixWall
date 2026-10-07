package bb.pix.wall.network

import android.content.Context
import java.net.InetAddress

object GlobalRemoteAccess {
    private const val PREFS =
        "bb_pixwall_global_remote"

    private const val KEY_ENABLED =
        "enabled"

    fun isEnabled(
        context: Context,
    ): Boolean =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).getBoolean(
            KEY_ENABLED,
            false,
        )

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).edit()
            .putBoolean(
                KEY_ENABLED,
                enabled,
            )
            .apply()
    }

    fun toggle(
        context: Context,
    ): Boolean {
        val enabled =
            !isEnabled(context)

        setEnabled(
            context,
            enabled,
        )

        return enabled
    }

    fun isAllowedClient(
        context: Context,
        address: InetAddress,
    ): Boolean {
        if (
            address.isLoopbackAddress ||
            address.isSiteLocalAddress
        ) {
            return true
        }

        if (!isEnabled(context)) {
            return false
        }

        return isTailscaleAddress(
            address
        )
    }

    private fun isTailscaleAddress(
        address: InetAddress,
    ): Boolean {
        val bytes =
            address.address

        if (bytes.size == 4) {
            val first =
                bytes[0].toInt() and 0xff

            val second =
                bytes[1].toInt() and 0xff

            /*
             * Tailscale IPv4 CGNAT range:
             * 100.64.0.0/10
             */
            return first == 100 &&
                second in 64..127
        }

        if (bytes.size == 16) {
            /*
             * Tailscale IPv6 ULA:
             * fd7a:115c:a1e0::/48
             */
            return (bytes[0].toInt() and 0xff) == 0xfd &&
                (bytes[1].toInt() and 0xff) == 0x7a &&
                (bytes[2].toInt() and 0xff) == 0x11 &&
                (bytes[3].toInt() and 0xff) == 0x5c &&
                (bytes[4].toInt() and 0xff) == 0xa1 &&
                (bytes[5].toInt() and 0xff) == 0xe0
        }

        return false
    }
}
