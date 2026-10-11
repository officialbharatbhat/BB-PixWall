package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WallpaperLock

/** One-step undo uses the same original-byte previous snapshot as Previous Wall. */
class WallpaperUndoTileService : BaseTileService() {
    override fun onStartListening() {
        super.onStartListening()
        setState(false, if (WallpaperController.previousAvailable()) "Ready to undo" else "No undo available")
    }

    override fun onClick() {
        super.onClick()
        if (SafeWall.active(this) || WallpaperLock.active(this)) {
            toast("Wallpaper protection is active")
            return
        }
        setBusy("Undoing…")
        EngineExecutors.io {
            val restored = runCatching { WallpaperController.previousWall(applicationContext) }.getOrDefault(false)
            setState(false, if (WallpaperController.previousAvailable()) "Ready to undo" else "No undo available")
            toast(if (restored) "Last wallpaper change undone" else "Undo unavailable")
        }
    }
}
