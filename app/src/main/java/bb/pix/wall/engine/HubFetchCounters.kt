package bb.pix.wall.engine

import android.content.Context

/** Successfully acquired source originals since this counter was introduced; not album size. */
internal object HubFetchCounters {
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
