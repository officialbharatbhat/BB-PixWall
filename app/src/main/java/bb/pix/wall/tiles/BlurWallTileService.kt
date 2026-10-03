package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.settings.SettingsStore

class BlurWallTileService : BaseTileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick(); setBusy("Rendering…")
        EngineExecutors.io {
            val on = runCatching { WallpaperController.toggleBlur(applicationContext) }.getOrDefault(false)
            refresh(); toast(if(on) "Configured blur enabled" else "Blur disabled")
        }
    }
    private fun refresh(){ val s=SettingsStore(this).load(); setState(WallpaperController.effectiveBlurEnabled(this), "${s.homeBlurRadius}/${s.lockBlurRadius}px") }
}
