package bb.pix.wall.engine

import android.graphics.BitmapFactory
import java.io.File

/**
 * On-demand, bounded inspection of critical wallpaper files.
 * No full directory walk and no decoding of full bitmaps.
 */
object WallpaperHealthCheck {
    data class Result(val checked: Int, val healthy: Int, val invalid: List<String>)

    fun check(): Result {
        val candidates = listOf(
            "Current Home" to WallpaperFiles.currentHome,
            "Current Lock" to WallpaperFiles.currentLock,
            "Next Home" to WallpaperFiles.nextHome,
            "Next Lock" to WallpaperFiles.nextLock,
            "Previous Home" to WallpaperFiles.previousHome,
            "Previous Lock" to WallpaperFiles.previousLock,
        )
        val invalid = ArrayList<String>()
        var healthy = 0
        for ((name, file) in candidates) {
            if (!file.exists()) continue // unpopulated slots are not corrupt
            val valid = file.isFile && file.length() > 0L && runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.outWidth > 0 && opts.outHeight > 0
            }.getOrDefault(false)
            if (valid) healthy++ else invalid += name
        }
        return Result(candidates.count { it.second.exists() }, healthy, invalid)
    }

    /** Only invalid future queue files can be discarded automatically; never delete current/previous. */
    fun repairInvalidPrepared(): Int {
        var removed = 0
        for (file in listOf(WallpaperFiles.nextHome, WallpaperFiles.nextLock)) {
            if (!file.exists()) continue
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val valid = runCatching {
                file.length() > 0L && run {
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    opts.outWidth > 0 && opts.outHeight > 0
                }
            }.getOrDefault(false)
            if (!valid && file.delete()) {
                File(file.absolutePath + ".meta").delete()
                removed++
            }
        }
        return removed
    }
}
