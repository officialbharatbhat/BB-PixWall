package bb.pix.wall.tiles

import android.app.PendingIntent
import android.os.SystemClock
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One-tap entry into the optional, on-demand QS control screen. */
class ControlHubTileService : TileService() {
    companion object { @Volatile private var lastLaunch = 0L }
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = "Wallpaper controls"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val now = SystemClock.elapsedRealtime()
        synchronized(ControlHubTileService::class.java) {
            if (now - lastLaunch < 900L || ControlHubActivity.isVisible) return
            lastLaunch = now
        }
        val launch = Intent(this, ControlHubActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}
