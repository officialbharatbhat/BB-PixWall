package bb.pix.wall.engine

import android.app.WallpaperManager
import android.content.Context
import java.io.File

/**
 * Durable best-effort journal around a normal wallpaper rotation.
 * Original files are snapshotted before Android's non-atomic Home/Lock commits.
 * Recovery is skipped when privacy Safe Wall is active.
 */
object WallpaperTransaction {
    private val gate = Any()
    private fun directory(context: Context) = File(context.filesDir, "wall_txn")
    private fun pending(context: Context) = File(directory(context), "pending")
    private fun backup(context: Context, name: String) = File(directory(context), name)

    fun begin(context: Context): Boolean = synchronized(gate) {
        val dir = directory(context)
        if (!dir.exists() && !dir.mkdirs()) return@synchronized false
        return@synchronized runCatching {
            // A failed previous operation must be recovered before starting anew.
            if (pending(context).exists()) return@synchronized false
            for ((source, name) in listOf(
                WallpaperFiles.currentHome to "home.original",
                WallpaperFiles.currentLock to "lock.original",
            )) {
                val destination = backup(context, name)
                destination.delete()
                if (source.isFile && source.length() > 0L) source.copyTo(destination, overwrite = true)
            }
            // Commit marker only after both copies complete.
            pending(context).writeText("pending")
            check(pending(context).isFile && pending(context).length() > 0L)
            true
        }.getOrDefault(false)
    }

    fun complete(context: Context) = synchronized(gate) {
        pending(context).delete()
        backup(context, "home.original").delete()
        backup(context, "lock.original").delete()
    }

    fun recover(context: Context): Boolean = synchronized(gate) {
        if (!pending(context).exists()) return@synchronized true
        if (SafeWall.active(context)) return@synchronized false
        val home = backup(context, "home.original")
        val lock = backup(context, "lock.original")
        val wm = WallpaperManager.getInstance(context)
        val ok = runCatching {
            // Do not finish the journal unless all available prior originals restore.
            if (home.isFile) home.inputStream().use {
                wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            if (lock.isFile) lock.inputStream().use {
                wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK)
            }
            if (home.isFile) home.copyTo(WallpaperFiles.currentHome, overwrite = true)
            if (lock.isFile) lock.copyTo(WallpaperFiles.currentLock, overwrite = true)
            true
        }.getOrDefault(false)
        if (ok) complete(context)
        RuntimeStatus.set(context, "transaction_recovery", if (ok) "Recovered" else "Recovery pending")
        ok
    }
}
