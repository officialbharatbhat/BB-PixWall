package bb.pix.wall.tiles

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import bb.pix.wall.network.LanInfo
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore

class RemoteTileService : BaseTileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()

        var settings = SettingsStore(this).load()

        if (!settings.lanEnabled) {
            settings = settings.copy(lanEnabled = true)
            SettingsStore(this).save(settings)
        }

        runCatching {
            startForegroundService(
                Intent(
                    this,
                    LanServerService::class.java,
                )
            )
        }

        val ip = LanInfo.localIpv4()

        if (ip == "Unavailable") {
            setState(
                true,
                "Waiting for Wi-Fi",
            )
            toast("Wi-Fi IP unavailable")
            return
        }

        val url =
            "http://$ip:${settings.lanPort}"

        val browserIntent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url),
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

        val requestCode =
            (SystemClock.elapsedRealtime() and 0x7fffffff)
                .toInt()

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                requestCode,
                browserIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        /*
         * Important:
         * one synchronous launch directly inside TileService.onClick().
         * No delayed Handler. No intermediate Activity.
         */
        startActivityAndCollapse(
            pendingIntent
        )

        setState(
            true,
            "$ip:${settings.lanPort}",
        )
    }

    private fun refresh() {
        val settings =
            SettingsStore(this).load()

        val ip =
            LanInfo.localIpv4()

        val subtitle =
            when {
                !settings.lanEnabled ->
                    "LAN off"

                ip == "Unavailable" ->
                    "Waiting for Wi-Fi"

                else ->
                    "$ip:${settings.lanPort}"
            }

        setState(
            settings.lanEnabled,
            subtitle,
        )
    }
}
