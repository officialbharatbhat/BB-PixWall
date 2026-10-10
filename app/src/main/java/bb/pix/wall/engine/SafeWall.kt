package bb.pix.wall.engine

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Privacy pause for wallpaper rotation. Original picked streams are copied byte-for-byte.
 * Preferences live in private app storage; image files are outside normal history eviction.
 */
object SafeWall {
    private const val PREF = "bb_safe_wall"
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun home(context: Context) = File(context.filesDir, "safe-home.original")
    private fun lock(context: Context) = File(context.filesDir, "safe-lock.original")
    private fun savedHome(context: Context) = File(context.filesDir, "safe-restored-home.original")
    private fun savedLock(context: Context) = File(context.filesDir, "safe-restored-lock.original")

    fun active(context: Context): Boolean = prefs(context).getBoolean("active", false)
    fun configured(context: Context): Boolean = home(context).isFile && lock(context).isFile

    fun importImage(context: Context, uri: Uri, forHome: Boolean): Boolean {
        if (active(context)) return false
        val target = if (forHome) home(context) else lock(context)
        val temp = File(context.filesDir, target.name + ".part")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { source ->
                temp.outputStream().use { source.copyTo(it) }
            } ?: error("Cannot open selected image")
            require(temp.length() > 0L)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        }.getOrElse { temp.delete(); false }
    }

    @Synchronized fun toggle(context: Context): Boolean {
        val p = prefs(context)
        val wm = WallpaperManager.getInstance(context)
        if (active(context)) {
            // Restore only when both backups are intact; never release freeze accidentally.
            val oldHome = savedHome(context)
            val oldLock = savedLock(context)
            if (!oldHome.isFile || !oldLock.isFile) return false
            val ok = runCatching {
                oldLock.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
                oldHome.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
                p.edit().putBoolean("active", false).commit()
            }.getOrDefault(false)
            if (ok) {
                oldHome.delete()
                oldLock.delete()
            }
            return ok
        }
        if (!configured(context)) return false
        val currHome = WallpaperFiles.currentHome
        val currLock = WallpaperFiles.currentLock
        if (!currHome.isFile || !currLock.isFile) return false
        return runCatching {
            currHome.copyTo(savedHome(context), overwrite = true)
            currLock.copyTo(savedLock(context), overwrite = true)
            // Persist freeze BEFORE any wallpaper transaction to block concurrent triggers.
            check(p.edit().putBoolean("active", true).commit())
            lock(context).inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
            home(context).inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
            true
        }.getOrElse { false } // If apply fails, stay frozen; toggle again restores previous.
    }
}
