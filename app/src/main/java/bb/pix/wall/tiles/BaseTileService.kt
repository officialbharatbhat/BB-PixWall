package bb.pix.wall.tiles

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

abstract class BaseTileService : TileService() {
    protected fun setState(active: Boolean, subtitle: String? = null) = setTile(if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, subtitle)
    protected fun setBusy(subtitle: String = "Working…") = setTile(Tile.STATE_UNAVAILABLE, subtitle)
    private fun setTile(tileState: Int, subtitle: String?) {
        Handler(Looper.getMainLooper()).post {
            qsTile?.apply { state = tileState; this.subtitle = subtitle; updateTile() }
        }
    }
    protected fun toast(text: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() } }
}
