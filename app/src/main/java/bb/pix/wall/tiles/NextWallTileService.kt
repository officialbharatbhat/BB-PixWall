package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.settings.SettingsStore

class NextWallTileService : BaseTileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick(); if (bb.pix.wall.engine.SafeWall.active(this)) { toast("Safe Wall is active"); return }; setBusy("Applying…")
        EngineExecutors.io {
            var ok = WallpaperController.nextWall(applicationContext, allowNetwork=false, userInitiated=true)
            if (!ok) ok = WallpaperController.nextWall(applicationContext, allowNetwork=true, userInitiated=true)
            refresh(); toast(if(ok) "Wallpaper changed" else "No prepared/source wallpaper")
        }
    }
    private fun refresh() { setState(false, SettingsStore(this).load().targetMode.label) }
}
