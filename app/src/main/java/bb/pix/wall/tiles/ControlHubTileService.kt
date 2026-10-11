package bb.pix.wall.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One-tap entry into the optional, on-demand QS control screen. */
class ControlHubTileService : TileService() {
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
        val launch = Intent(this, ControlHubActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            (SystemClock.elapsedRealtime() and 0x7fffffff).toInt(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}
