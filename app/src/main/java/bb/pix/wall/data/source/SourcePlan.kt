package bb.pix.wall.data.source

/**
 * Runtime priority for the final engine:
 * 1) Google Photos shared album
 * 2) Google Drive public mirror
 * 3) /sdcard/wallpaper/Backup offline fallback
 */
object SourcePlan {
    const val LOCAL_ROOT = "/sdcard/wallpaper"
    const val LOCAL_BACKUP = "/sdcard/wallpaper/Backup"
    const val LOCAL_FAVORITES = "/sdcard/wallpaper/Favorites"
    const val LOCAL_LOGS = "/sdcard/wallpaper/Logs"
}
