package bb.pix.wall.engine

import java.io.File

object WallpaperFiles {
    val root = File("/sdcard/BB-PixWall-Lite")
    val local = File("/sdcard/wallpaper")
    val backup = File(root, "backup")
    val cache = File(root, "cache")
    val hotCache = File(cache, "hot")
    val warmCache = File(cache, "warm")
    // Legacy/offline queue paths remain available but do not create extra top-level folders.
    val queue = File(backup, ".queue")
    val legacyQueue = File(backup, ".quee")
    val saved = File(root, "saved")
    val history = File(root, "history")
    val favorites = File(root, "favorites")
    val logs = File(root, "logs")
    val quarantine = File(backup, ".quarantine")

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

    @Volatile private var migrated = false

    // Old installs placed unused folders beside backup/cache. Preserve nonempty
    // legacy queue files inside backup and only delete now-empty directories.
    private fun migrateUnusedTopLevelDirs() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            listOf(
                File(root, "queue") to queue,
                File(root, "quee") to legacyQueue,
                File(root, "quarantine") to quarantine,
            ).forEach { (old, destination) ->
                if (!old.isDirectory) return@forEach
                val entries = old.listFiles().orEmpty()
                if (entries.isNotEmpty()) {
                    destination.mkdirs()
                    entries.forEach { item ->
                        val target = File(destination, item.name)
                        if (!target.exists()) {
                            if (!item.renameTo(target) && item.isFile) {
                                runCatching {
                                    item.copyTo(target, overwrite = false)
                                    if (item.length() == target.length()) item.delete()
                                }
                            }
                        }
                    }
                }
                // delete() only succeeds if every item was safely moved.
                old.delete()
            }
            // /sdcard/wallpaper was previously created by the root setup.
            // Only remove the generated, empty directory tree; never delete local images.
            if (local.isDirectory) {
                val allowed = setOf("backup", "cache", "local", "queue", "quee", "saved", "logs", "quarantine")
                if (local.listFiles().orEmpty().all { it.isDirectory && it.name in allowed } &&
                    local.walkTopDown().none { it.isFile }) {
                    local.walkBottomUp().forEach { if (it.isDirectory) it.delete() }
                }
            }
            migrated = true
        }
    }

    fun ensure(): Boolean {
        val ok = root.exists() || root.mkdirs()
        if (!ok) return false
        val dirsOk = listOf(
            backup,
            cache,
            hotCache,
            warmCache,
            saved,
            history,
            favorites,
            logs,
        ).all { it.exists() || it.mkdirs() }
        if (!dirsOk) return false
        migrateUnusedTopLevelDirs()
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
