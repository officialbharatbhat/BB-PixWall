package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.SafeWall

class SafeWallTileService : BaseTileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick()
        setBusy("Switching…")
        EngineExecutors.io {
            val ok = SafeWall.toggle(applicationContext)
            refresh()
            toast(if (ok) {
                if (SafeWall.active(applicationContext)) "Safe Wall ON" else "Safe Wall OFF"
            } else "Choose Safe Home and Lock in BB-PixWall Lite first")
        }
    }
    private fun refresh() { setState(SafeWall.active(this), if (SafeWall.active(this)) "Protected" else "Off") }
}
