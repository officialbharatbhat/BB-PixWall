package bb.pix.wall.engine

import java.io.File

object WallpaperFiles {
    val root = File("/sdcard/BB-PixWall-Lite")
    val local = File("/sdcard/wallpaper")
    val backup = File(root, "backup")
    val cache = File(root, "cache")
    val hotCache = File(cache, "hot")
    val warmCache = File(cache, "warm")
    val queue = File(root, "queue")
    val legacyQueue = File(root, "quee")
    val saved = File(root, "saved")
    val history = File(root, "history")
    val favorites = File(root, "favorites")
    val logs = File(root, "logs")
    val quarantine = File(root, "quarantine")

    val currentHome = File(backup, "current_home.jpg")
    val currentLock = File(backup, "current_lock.jpg")

    val previousHome = File(backup, "previous_home.jpg")
    val previousLock = File(backup, "previous_lock.jpg")
    val nextHome = File(backup, "next_home.jpg")
    val nextLock = File(backup, "next_lock.jpg")
    val seenHashes = File(logs, "seen_hashes.txt")
    val seenIds = File(logs, "seen_ids.txt")
    val seenPerceptual = File(logs, "seen_phash.txt")
    val runtimeLog = File(logs, "runtime.log")
    val debugReport = File(logs, "debug_report.txt")
    val metadata = File(logs, "queue_meta.properties")
    val cacheIndex = File(logs, "cache_index.properties")
    val triggerHistory = File(logs, "trigger_history.log")
    val blockedIds = File(logs, "blocked_ids.txt")
    val blockedHashes = File(logs, "blocked_hashes.txt")

    fun ensure(): Boolean {
        val ok = root.exists() || root.mkdirs()
        if (!ok) return false
        val dirsOk = listOf(
            local,
            backup,
            cache,
            hotCache,
            warmCache,
            queue,
            legacyQueue,
            saved,
            history,
            favorites,
            logs,
            quarantine,
        ).all { it.exists() || it.mkdirs() }
        if (!dirsOk) return false
        // Migrate beta-era cache images that were stored directly under /cache into warm/.
        // This keeps the cache layout predictable without deleting user-ready assets.
        val allowed = setOf("jpg", "jpeg", "png", "webp", "avif")
        cache.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in allowed }.forEach { f ->
            val target = File(warmCache, f.name)
            if (!target.exists()) runCatching { f.renameTo(target) }
            val meta = File(f.absolutePath + ".meta")
            if (meta.exists()) {
                val mt = File(target.absolutePath + ".meta")
                if (!mt.exists()) runCatching { meta.renameTo(mt) }
            }
        }
        return true
    }
}
