package bb.pix.wall.tiles

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.WallpaperController

class SaveWallTileService : BaseTileService() {
    override fun onStartListening(){ super.onStartListening(); setState(false,"Home/Lock") }
    override fun onClick(){
        super.onClick(); setBusy("Saving…")
        EngineExecutors.io {
            val files=runCatching{WallpaperController.saveCurrent(applicationContext)}.getOrDefault(emptyList())
            setState(false,"Home/Lock")
            toast(if(files.isNotEmpty()) "Saved ${files.size} wallpaper(s)" else "Nothing to save")
        }
    }
}
