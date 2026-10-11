package bb.pix.wall.engine

import android.content.Context

/** Successfully acquired source originals since this counter was introduced; not album size. */
internal object HubFetchCounters {
    @Synchronized fun recordWallpaperTransfer(context: Context, bytes: Long) {
        if (bytes <= 0L) return
        val pref = context.getSharedPreferences("bb_wallpaper_transfer", Context.MODE_PRIVATE)
        pref.edit().putLong("bytes", pref.getLong("bytes", 0L) + bytes).apply()
    }
    fun downloadedBytes(context: Context): Long =
        context.getSharedPreferences("bb_wallpaper_transfer", Context.MODE_PRIVATE).getLong("bytes", 0L)

    @Synchronized fun record(context: Context, rawSource: String) {
        val source = rawSource.lowercase()
        val category = when {
            source.contains("photo") -> "photos"
            source.contains("drive") -> "drive"
            source.contains("local") || source.contains("file") -> "local"
            else -> return
        }
        RuntimeStatus.increment(context, "download_$category")
    }
}
