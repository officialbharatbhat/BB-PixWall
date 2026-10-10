package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.WallpaperController

class PreviousWallTileService : BaseTileService() {
    override fun onStartListening() { super.onStartListening(); setState(false, "Restore previous") }
    override fun onClick() {
        super.onClick()
        if (SafeWall.active(this)) { toast("Safe Wall is active"); return }
        setBusy("Restoring…")
        EngineExecutors.io {
            val ok = WallpaperController.previousWall(applicationContext)
            setState(false, "Restore previous")
            toast(if (ok) "Previous wallpaper restored" else "Previous wallpaper unavailable")
        }
    }
}
