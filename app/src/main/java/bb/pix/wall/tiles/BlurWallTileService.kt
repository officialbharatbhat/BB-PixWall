package bb.pix.wall.tiles

import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.settings.SettingsStore

class BlurWallTileService : BaseTileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick()
        if (bb.pix.wall.engine.SafeWall.active(this)) { toast("Safe Wall is active"); return }
        setBusy("Rendering…")

        /*
         * toggleBlur() itself only flips state and queues the expensive
         * wallpaper work in the background. Do not put this call behind
         * the shared IO queue, otherwise a busy cache/download worker
         * makes the Quick Settings tile feel delayed.
         */
        val on =
            runCatching {
                WallpaperController.toggleBlur(applicationContext)
            }.getOrDefault(false)

        refresh()
        toast(
            if (on) {
                "Configured blur enabled"
            } else {
                "Blur disabled"
            }
        )
    }
    private fun refresh(){ val s=SettingsStore(this).load(); setState(WallpaperController.effectiveBlurEnabled(this), "${s.homeBlurRadius}/${s.lockBlurRadius}px") }
}
