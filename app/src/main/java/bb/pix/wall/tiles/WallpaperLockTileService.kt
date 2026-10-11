package bb.pix.wall.tiles

import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.WallpaperLock

class WallpaperLockTileService : BaseTileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (SafeWall.active(this)) {
            toast("Disable Safe Wall before changing Wallpaper Lock")
            return
        }
        val locked = WallpaperLock.toggle(applicationContext)
        refresh()
        toast(if (locked) "Wallpaper locked" else "Wallpaper rotation resumed")
    }

    private fun refresh() {
        val locked = WallpaperLock.active(this)
        setState(locked, if (locked) "Locked" else "Auto allowed")
    }
}
