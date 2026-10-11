package bb.pix.wall.engine

import android.content.Context

/**
 * Persistent manual freeze. Safe Wall is independent and takes precedence.
 * Do not change any saved wallpaper pair when toggling the freeze.
 */
object WallpaperLock {
    private const val PREFS = "bb_wallpaper_lock"
    fun active(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)

    fun toggle(context: Context): Boolean {
        val next = !active(context)
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("active", next).commit()
        if (!saved) return active(context)
        RuntimeStatus.set(context, "wallpaper_lock", if (next) "ON" else "OFF")
        return next
    }
}
