package bb.pix.wall.engine

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Original-byte safe wallpaper vault. Never included in ordinary cache/history pruning. */
object SafeWall {
    private const val PREFS = "bb_safe_wall"
    private val gate = Any()
    private fun pref(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context, name: String) = File(c.filesDir, name)
    private fun safeHome(c: Context) = file(c, "safe-home.original")
    private fun safeLock(c: Context) = file(c, "safe-lock.original")
    private fun restoreHome(c: Context) = file(c, "restore-home.original")
    private fun restoreLock(c: Context) = file(c, "restore-lock.original")

    fun active(c: Context): Boolean = pref(c).getBoolean("active", false)
    fun configured(c: Context): Boolean = safeHome(c).length() > 0 && safeLock(c).length() > 0
    fun selectedFile(c: Context, home: Boolean): File = if (home) safeHome(c) else safeLock(c)
    fun selected(c: Context, home: Boolean): Boolean =
        (if (home) safeHome(c) else safeLock(c)).length() > 0

    fun importImage(c: Context, uri: Uri, forHome: Boolean): Boolean = synchronized(gate) {
        if (active(c)) return@synchronized false
        val target = if (forHome) safeHome(c) else safeLock(c)
        val temp = file(c, target.name + ".part")
        runCatching {
            c.contentResolver.openInputStream(uri)?.use { source ->
                temp.outputStream().use { output -> source.copyTo(output) }
            } ?: error("Image unavailable")
            require(temp.length() > 0L)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        }.getOrElse { temp.delete(); false }
    }

    /** Toggle is serialized against itself; normal wallpaper triggers check the persistent gate. */
    fun toggle(c: Context): Boolean = synchronized(gate) {
        WallpaperFiles.ensure()
        val p = pref(c)
        val wm = WallpaperManager.getInstance(c)
        if (active(c)) {
            if (!restoreHome(c).isFile || !restoreLock(c).isFile) return@synchronized false
            val restored = runCatching {
                restoreLock(c).inputStream().use {
                    wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK)
                }
                restoreHome(c).inputStream().use {
                    wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
                }
                // Do not unfreeze unless both wallpaper transactions succeed.
                check(p.edit().putBoolean("active", false).commit())
                true
            }.getOrDefault(false)
            if (restored) {
                restoreHome(c).delete()
                restoreLock(c).delete()
            }
            return@synchronized restored
        }

        if (!configured(c)) return@synchronized false
        val home = WallpaperFiles.currentHome
        val lock = WallpaperFiles.currentLock
        if (!home.isFile || !lock.isFile) return@synchronized false
        return@synchronized runCatching {
            home.copyTo(restoreHome(c), overwrite = true)
            lock.copyTo(restoreLock(c), overwrite = true)
            check(p.edit().putBoolean("active", true).commit())
            safeLock(c).inputStream().use {
                wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK)
            }
            safeHome(c).inputStream().use {
                wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            true
        }.getOrDefault(false) // Fail closed: if interrupted, safe mode remains active.
    }

    /** Boot recovery: reassert selected safe wallpapers if Safe Wall was active at shutdown. */
    fun reassert(c: Context): Boolean = synchronized(gate) {
        if (!active(c) || !configured(c)) return@synchronized false
        runCatching {
            val wm = WallpaperManager.getInstance(c)
            safeLock(c).inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
            safeHome(c).inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
            true
        }.getOrDefault(false)
    }
}
