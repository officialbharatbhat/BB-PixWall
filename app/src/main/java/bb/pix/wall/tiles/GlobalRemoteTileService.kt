package bb.pix.wall.tiles

import android.content.Intent
import bb.pix.wall.network.GlobalRemoteAccess
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore

class GlobalRemoteTileService : BaseTileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()

        val enabled =
            GlobalRemoteAccess.toggle(this)

        /*
         * Keep the local dashboard server alive whenever Global Remote
         * is enabled. Turning Global Remote OFF only removes WAN/VPN
         * authorization; normal same-LAN Remote remains unchanged.
         */
        if (enabled) {
            var settings =
                SettingsStore(this).load()

            if (!settings.lanEnabled) {
                settings =
                    settings.copy(
                        lanEnabled = true
                    )

                SettingsStore(this).save(
                    settings
                )
            }

            runCatching {
                startForegroundService(
                    Intent(
                        this,
                        LanServerService::class.java,
                    )
                )
            }
        }

        refresh()

        toast(
            if (enabled) {
                "Global Remote ON • Tailscale clients allowed"
            } else {
                "Global Remote OFF • LAN only"
            }
        )
    }

    private fun refresh() {
        val enabled =
            GlobalRemoteAccess.isEnabled(
                this
            )

        setState(
            enabled,
            if (enabled) {
                "Tailscale allowed"
            } else {
                "LAN only"
            },
        )
    }
}
