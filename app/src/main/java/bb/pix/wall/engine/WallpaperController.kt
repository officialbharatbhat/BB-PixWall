package bb.pix.wall.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import bb.pix.wall.model.WallpaperState
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.settings.WallpaperTargetMode
import bb.pix.wall.settings.EngineMode
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import android.os.StatFs
import android.graphics.Color
import java.util.Properties
import java.security.MessageDigest
import kotlin.math.max
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperController {
    private val cachePriming =
        java.util.concurrent.atomic.AtomicBoolean(false)

    private val lock = Any()
    private val generation = AtomicLong(0L)
    private val applying = AtomicBoolean(false)
    private val preparingNext = AtomicBoolean(false)
    @Volatile private var lastApplyStartedAt = 0L

    fun state(): WallpaperState {
        WallpaperFiles.ensure()
        return WallpaperState(
            currentHome = WallpaperFiles.currentHome.takeIf(File::exists)?.absolutePath,
            currentLock = WallpaperFiles.currentLock.takeIf(File::exists)?.absolutePath,
            nextHome = WallpaperFiles.nextHome.takeIf(File::exists)?.absolutePath,
            nextLock = WallpaperFiles.nextLock.takeIf(File::exists)?.absolutePath,
        )
    }

    fun invalidateQueue() = synchronized(lock) {
        generation.incrementAndGet()
        WallpaperFiles.nextHome.delete()
        WallpaperFiles.nextLock.delete()
    }

    fun ensureNext(context: Context, settings: AppSettings = SettingsStore(context).load(), allowNetwork: Boolean = true): Boolean {
        requireStorage()
        var made = false
        synchronized(lock) {
            if (!WallpaperFiles.nextHome.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock)
                made = consumeCache(WallpaperFiles.nextHome, avoid) || made
            }
            if (!WallpaperFiles.nextLock.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock, WallpaperFiles.nextHome)
                made = consumeCache(WallpaperFiles.nextLock, avoid) || made
            }
            if (WallpaperFiles.nextHome.exists() && WallpaperFiles.nextLock.exists()) return true
            if (!allowNetwork) return made
        }

        // Never hold the apply/file lock while parsing or downloading a cloud album.
        // A previous build could block SCREEN_OFF for many seconds behind network I/O.
        if (!preparingNext.compareAndSet(false, true)) {
            return synchronized(lock) { made || WallpaperFiles.nextHome.exists() || WallpaperFiles.nextLock.exists() }
        }
        try {
            val candidates = WallpaperSourceEngine.collect(context, settings, allowNetwork = true)
            if (candidates.isEmpty()) return made

            fun prepare(dest: File, avoid: Set<String>): Boolean {
                if (dest.exists()) return true
                val tmp = File(WallpaperFiles.backup, ".${dest.name}.${System.nanoTime()}.part")
                return try {
                    fetchWithHistoryReset(context, candidates, tmp, avoid)
                    validateImage(tmp)
                    synchronized(lock) {
                        if (!dest.exists()) {
                            tmp.copyTo(dest, overwrite = true)
                            val tm = File(tmp.absolutePath + ".meta")
                            if (tm.exists()) tm.copyTo(File(dest.absolutePath + ".meta"), overwrite = true)
                        }
                    }
                    true
                } catch (t: Throwable) {
                    log("PREPARE ${dest.name} failed: ${t.message}")
                    false
                } finally {
                    tmp.delete(); File(tmp.absolutePath + ".meta").delete()
                }
            }

            if (!WallpaperFiles.nextHome.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock)
                made = prepare(WallpaperFiles.nextHome, avoid) || made
            }
            if (!WallpaperFiles.nextLock.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock, WallpaperFiles.nextHome)
                made = prepare(WallpaperFiles.nextLock, avoid) || made
            }
        } finally {
            preparingNext.set(false)
        }
        if (WallpaperFiles.nextHome.exists() || WallpaperFiles.nextLock.exists()) {
            EngineExecutors.io { runCatching { primeCache(context, settings) } }
        }
        return made || WallpaperFiles.nextHome.exists() || WallpaperFiles.nextLock.exists()
    }

    fun nextWall(context: Context, allowNetwork: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastApplyStartedAt < 900L) return false
        if (!applying.compareAndSet(false, true)) return false
        lastApplyStartedAt = now
        return try {
            synchronized(lock) {
                val settings = SettingsStore(context).load()
            ensureNext(context, settings, allowNetwork)
            val wm = WallpaperManager.getInstance(context)
            val blurMaster = blurMasterEnabled(context)
            var changed = false
            fun applyNext(src: File, flag: Int, blur: Boolean, radius: Int, current: File): Boolean {
                if (!src.exists()) return false
                return applyFile(wm, src, flag, blur, radius, current)
            }
            when (settings.targetMode) {
                WallpaperTargetMode.HOME -> changed = applyNext(WallpaperFiles.nextHome, WallpaperManager.FLAG_SYSTEM, blurMaster && settings.homeBlurEnabled, settings.homeBlurRadius, WallpaperFiles.currentHome)
                WallpaperTargetMode.LOCK -> changed = applyNext(WallpaperFiles.nextLock, WallpaperManager.FLAG_LOCK, blurMaster && settings.lockBlurEnabled, settings.lockBlurRadius, WallpaperFiles.currentLock)
                WallpaperTargetMode.BOTH_SAME -> {
                    val src = WallpaperFiles.nextHome.takeIf { it.exists() } ?: WallpaperFiles.nextLock
                    val a = applyNext(src, WallpaperManager.FLAG_SYSTEM, blurMaster && settings.homeBlurEnabled, settings.homeBlurRadius, WallpaperFiles.currentHome)
                    val b = applyNext(src, WallpaperManager.FLAG_LOCK, blurMaster && settings.lockBlurEnabled, settings.lockBlurRadius, WallpaperFiles.currentLock)
                    changed = a || b
                }
                WallpaperTargetMode.BOTH_DIFFERENT -> {
                    val a = applyNext(WallpaperFiles.nextHome, WallpaperManager.FLAG_SYSTEM, blurMaster && settings.homeBlurEnabled, settings.homeBlurRadius, WallpaperFiles.currentHome)
                    val b = applyNext(WallpaperFiles.nextLock, WallpaperManager.FLAG_LOCK, blurMaster && settings.lockBlurEnabled, settings.lockBlurRadius, WallpaperFiles.currentLock)
                    changed = a || b
                }
            }
            if (changed) {
                recordSeenForCurrent(settings.targetMode)
                WallpaperFiles.nextHome.delete(); WallpaperFiles.nextLock.delete()
                RuntimeStatus.success(context, "Wallpaper applied: ${settings.targetMode.label}")
                RuntimeStatus.setLong(context, "last_change", System.currentTimeMillis())
                EngineExecutors.io {
                    runCatching { primeCache(context, settings) }
                    runCatching { ensureNext(context, settings, allowNetwork = WallpaperSourceEngine.networkAvailable(context)) }
                }
                log("NEXT applied target=${settings.targetMode}")
            } else if (!allowNetwork) {
                RuntimeStatus.failure(context, "Offline queue empty")
                log("NEXT skipped: offline queue empty")
            }
            changed
            }
        } catch (t: Throwable) {
            RuntimeStatus.failure(context, t.message ?: t.javaClass.simpleName)
            log("NEXT failed: ${t.message}")
            false
        } finally {
            applying.set(false)
        }
    }

    fun saveCurrent(context: Context): List<File> = synchronized(lock) {
        requireStorage()
        val settings = SettingsStore(context).load()
        val out = mutableListOf<File>()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val master = blurMasterEnabled(context)

        fun save(src: File, suffix: String, blurEnabled: Boolean, radius: Int) {
            if (!src.exists()) return
            val blurred = master && blurEnabled && radius > 0
            if (!blurred) {
                val ext = detectImageExtension(src)
                val dest = uniqueFile(WallpaperFiles.saved, "BBPixWall_${stamp}_${suffix}_wall", ext)
                src.copyTo(dest, overwrite = false)
                out += dest
                return
            }

            val bitmap = decodeForProcessing(src) ?: return
            val rendered = blur(bitmap, radius)
            val dest = uniqueFile(WallpaperFiles.saved, "BBPixWall_${stamp}_${suffix}_wall_blurred", "png")
            FileOutputStream(dest).use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rendered.recycle()
            bitmap.recycle()
            out += dest
        }

        when (settings.targetMode) {
            WallpaperTargetMode.HOME -> save(WallpaperFiles.currentHome, "home", settings.homeBlurEnabled, settings.homeBlurRadius)
            WallpaperTargetMode.LOCK -> save(WallpaperFiles.currentLock, "lock", settings.lockBlurEnabled, settings.lockBlurRadius)
            WallpaperTargetMode.BOTH_SAME, WallpaperTargetMode.BOTH_DIFFERENT -> {
                save(WallpaperFiles.currentHome, "home", settings.homeBlurEnabled, settings.homeBlurRadius)
                save(WallpaperFiles.currentLock, "lock", settings.lockBlurEnabled, settings.lockBlurRadius)
            }
        }
        log("SAVE ${out.size} file(s)")
        out
    }

    fun toggleBlur(context: Context): Boolean = synchronized(lock) {
        val settings = SettingsStore(context).load()
        if (!settings.homeBlurEnabled && !settings.lockBlurEnabled) {
            context.getSharedPreferences("bb_pixwall_runtime", Context.MODE_PRIVATE).edit().putBoolean("blur_master", false).apply()
            log("BLUR ignored: no Home/Lock blur target configured")
            return@synchronized false
        }
        val enabled = !blurMasterEnabled(context)
        context.getSharedPreferences("bb_pixwall_runtime", Context.MODE_PRIVATE)
            .edit().putBoolean("blur_master", enabled).apply()
        reapplyCurrentLocked(context)
        log("BLUR master=$enabled")
        enabled
    }

    fun effectiveBlurEnabled(context: Context): Boolean {
        val s = SettingsStore(context).load()
        return blurMasterEnabled(context) && (s.homeBlurEnabled || s.lockBlurEnabled)
    }

    fun setBlurMasterAndReapply(context: Context, enabled: Boolean = true) = synchronized(lock) {
        context.getSharedPreferences("bb_pixwall_runtime", Context.MODE_PRIVATE)
            .edit().putBoolean("blur_master", enabled).apply()
        reapplyCurrentLocked(context)
    }

    fun blurMasterEnabled(context: Context): Boolean =
        context.getSharedPreferences("bb_pixwall_runtime", Context.MODE_PRIVATE)
            .getBoolean("blur_master", false)

    private fun reapplyCurrentLocked(context: Context) {
        val settings = SettingsStore(context).load()
        val master = blurMasterEnabled(context)
        val wm = WallpaperManager.getInstance(context)
        runCatching {
            reapply(wm, WallpaperFiles.currentHome, WallpaperManager.FLAG_SYSTEM,
                master && settings.homeBlurEnabled, settings.homeBlurRadius)
        }
        runCatching {
            reapply(wm, WallpaperFiles.currentLock, WallpaperManager.FLAG_LOCK,
                master && settings.lockBlurEnabled, settings.lockBlurRadius)
        }
    }

    private fun reapply(wm: WallpaperManager, src: File, flag: Int, doBlur: Boolean, radius: Int) {
        if (!src.exists()) return
        if (!doBlur || radius <= 0) {
            FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
            return
        }
        val bitmap = decodeForProcessing(src, 2200) ?: return
        val rendered = blur(bitmap, radius)
        try { wm.setBitmap(rendered, null, true, flag) }
        finally {
            rendered.recycle()
            bitmap.recycle()
        }
    }

    private fun applyFile(wm: WallpaperManager, src: File, flag: Int, doBlur: Boolean, radius: Int, current: File): Boolean {
        if (!src.exists()) return false
        return try {
            if (!doBlur || radius <= 0) {
                // Preserve the source stream. Avoid app-side bitmap downsampling for normal wallpapers.
                FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
            } else {
                val original = decodeForProcessing(src, 2200) ?: return false
                val applied = blur(original, radius)
                try { wm.setBitmap(applied, null, true, flag) }
                finally { applied.recycle(); original.recycle() }
            }
            // Keep an exact source copy for previews, unblur, and lossless Save Wall.
            src.copyTo(current, overwrite = true)
            val meta = File(src.absolutePath + ".meta")
            val currentMeta = File(current.absolutePath + ".meta")
            if (meta.exists()) meta.copyTo(currentMeta, overwrite = true) else currentMeta.delete()
            true
        } catch (t: Throwable) {
            log("APPLY failed ${src.name}: ${t.message}")
            false
        }
    }


    fun cacheCount(): Int {
        WallpaperFiles.ensure()
        return cacheImageFiles().size
    }

    fun offlineReadyCount(): Int {
        WallpaperFiles.ensure()
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        return listOf(WallpaperFiles.hotCache, WallpaperFiles.warmCache, WallpaperFiles.cache, WallpaperFiles.queue, WallpaperFiles.legacyQueue)
            .sumOf { dir -> dir.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() in allowed } }
    }

    fun cacheBytes(): Long = cacheImageFiles().sumOf { it.length() }

    fun cacheBreakdown(): String {
        WallpaperFiles.ensure()
        fun count(dir: File): Int = dir.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() in setOf("jpg","jpeg","png","webp","avif") }
        return "hot=${count(WallpaperFiles.hotCache)} warm=${count(WallpaperFiles.warmCache)} queue=${count(WallpaperFiles.queue) + count(WallpaperFiles.legacyQueue)}"
    }

    fun cycleProgress(context: Context): String {
        val seen = readLinesSet(WallpaperFiles.seenIds).size
        val total = RuntimeStatus.get(context, "candidate_count", "0").toIntOrNull() ?: 0
        return if (total > 0) "$seen / $total" else "$seen seen"
    }

    fun clearCache(): Int = synchronized(lock) {
        WallpaperFiles.ensure(); var n = 0
        cacheImageFiles().forEach { f ->
            if (f.delete()) n++
            File(f.absolutePath + ".meta").delete()
            File(f.absolutePath + ".part").delete()
        }
        log("CACHE cleared=$n"); n
    }

    fun primeCache(
        context: Context,
        settings: AppSettings = SettingsStore(context).load()
    ) {
        if (!cachePriming.compareAndSet(false, true)) {
            log("CACHE prime skipped: already running")
            return
        }

        try {
            primeCacheImpl(context, settings)
        } finally {
            cachePriming.set(false)
        }
    }

    private fun primeCacheImpl(context: Context, settings: AppSettings) {
        requireStorage()
        verifyCacheIntegrity()
        val reserve = settings.lowStorageReserveMb.coerceIn(256, 8192).toLong() * 1024L * 1024L
        if (freeBytes() < reserve) {
            trimCache((settings.cacheTarget / 2).coerceAtLeast(2))
            log("CACHE paused: low storage reserve=${settings.lowStorageReserveMb}MB")
            return
        }
        val target = if (settings.engineMode == EngineMode.ADVANCED) max(settings.cacheTarget, 16) else settings.cacheTarget.coerceIn(4, 36)
        val hotTarget = target.coerceAtMost(4)
        trimCache(target)
        val existing = cacheImageFiles().toMutableList()
        if (existing.size >= target) return
        val candidates = WallpaperSourceEngine.collect(context, settings, allowNetwork = true)
        if (candidates.isEmpty()) return
        val knownHashes = (existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock, WallpaperFiles.nextHome, WallpaperFiles.nextLock) +
            readSeenHashes() + existing.mapNotNull { runCatching { sha256(it) }.getOrNull() }).toMutableSet()
        val seenIds = readLinesSet(WallpaperFiles.seenIds)

        val cachedIds = existing.mapNotNull { f ->
            runCatching {
                val meta = File(f.absolutePath + ".meta")
                if (!meta.exists()) {
                    null
                } else {
                    val props = java.util.Properties()
                    meta.inputStream().use { props.load(it) }
                    props.getProperty("id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }.toMutableSet()

        val knownPhash = readLinesSet(WallpaperFiles.seenPerceptual).toMutableSet()
        var index = existing.size
        for (candidate in candidates) {
            if (index >= target) break
            if (candidate.id in seenIds || candidate.id in cachedIds) continue
            val dir = if (index < hotTarget) WallpaperFiles.hotCache else WallpaperFiles.warmCache
            dir.mkdirs()
            val ext = candidate.file?.extension?.takeIf { it.isNotBlank() } ?: "jpg"
            val tmp = File(dir, "queue_${System.currentTimeMillis()}_${index}.$ext")
            try {
                fetchTo(candidate, tmp)
                val hash = sha256(tmp)
                val ph = perceptualHash(tmp)
                val duplicate = hash in knownHashes || perceptuallySeen(ph, knownPhash, settings.perceptualDistance)
                if (duplicate) { tmp.delete(); continue }
                writeMeta(tmp, candidate.source, candidate.id)
                cachedIds += candidate.id
                knownHashes += hash
                if (ph.isNotBlank()) knownPhash += ph
                index++
                RuntimeStatus.activeSource(context, candidate.source)
                RuntimeStatus.set(context, "cache_progress", "$index/$target")
                log("CACHE + ${tmp.name} <= ${candidate.id}")
            } catch (t: Throwable) {
                quarantine(tmp, "cache_${candidate.source}")
                log("CACHE fail ${candidate.id}: ${t.message}")
            }
        }
        trimCache(target)
    }

    private fun consumeCache(dest: File, avoidHashes: Set<String>): Boolean {
        WallpaperFiles.ensure()
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        data class PoolFile(val file: File, val consume: Boolean, val source: String)

        fun pool(): List<PoolFile> = buildList {
            listOf(WallpaperFiles.hotCache, WallpaperFiles.warmCache, WallpaperFiles.cache).forEach { cacheDir ->
                cacheDir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in allowed }
                    .sortedBy { it.lastModified() }
                    .forEach { add(PoolFile(it, true, if (cacheDir == WallpaperFiles.hotCache) "HotCache" else "Cache")) }
            }
            listOf(WallpaperFiles.queue, WallpaperFiles.legacyQueue).forEach { dir ->
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in allowed }
                    .sortedBy { it.name.lowercase() }
                    .forEach { add(PoolFile(it, false, "Queue")) }
            }
        }

        fun tryPick(respectSeen: Boolean): Boolean {
            val seen = if (respectSeen) readSeenHashes() else emptySet()
            for (entry in pool()) {
                val f = entry.file
                val hash = runCatching { sha256(f) }.getOrNull() ?: continue
                if (hash in avoidHashes || hash in seen) continue
                val ok = runCatching {
                    f.copyTo(dest, overwrite = true)
                    val side = File(f.absolutePath + ".meta")
                    if (side.exists()) side.copyTo(File(dest.absolutePath + ".meta"), overwrite = true)
                    else writeMeta(dest, entry.source, "offline:${f.name}")
                    validateImage(dest)
                    if (entry.consume) { f.delete(); side.delete() }
                    log("QUEUE ${dest.name} <= ${entry.source}:${f.name}")
                    true
                }.getOrElse { dest.delete(); false }
                if (ok) return true
            }
            return false
        }

        if (tryPick(true)) return true
        // If every persistent offline queue item has already been seen, begin a new cycle.
        if (WallpaperFiles.queue.listFiles().orEmpty().any { it.isFile } || WallpaperFiles.legacyQueue.listFiles().orEmpty().any { it.isFile }) {
            clearSeenHashes()
            writeLinesSet(WallpaperFiles.seenPerceptual, emptySet())
            if (tryPick(false)) return true
        }
        return false
    }

    private fun trimCache(maxFiles: Int) {
        val protected = setOf(WallpaperFiles.currentHome.absolutePath, WallpaperFiles.currentLock.absolutePath, WallpaperFiles.nextHome.absolutePath, WallpaperFiles.nextLock.absolutePath)
        val images = cacheImageFiles().filter { it.absolutePath !in protected }.sortedByDescending { it.lastModified() }
        images.drop(maxFiles).forEach { f -> f.delete(); File(f.absolutePath + ".meta").delete(); File(f.absolutePath + ".part").delete() }
    }

    private fun cacheImageFiles(): List<File> {
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        return listOf(WallpaperFiles.hotCache, WallpaperFiles.warmCache, WallpaperFiles.cache)
            .flatMap { d -> d.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension.lowercase() in allowed }
            .distinctBy { it.absolutePath }
    }

    fun verifyCacheIntegrity(): Int {
        WallpaperFiles.ensure(); var bad = 0
        cacheImageFiles().forEach { f ->
            val ok = runCatching { validateImage(f); true }.getOrDefault(false)
            if (!ok) { bad++; quarantine(f, "integrity"); File(f.absolutePath + ".meta").delete() }
        }
        if (bad > 0) log("CACHE integrity quarantined=$bad")
        return bad
    }

    private fun fetchWithHistoryReset(context: Context, candidates: List<WallpaperSourceEngine.Candidate>, dest: File, avoidCurrent: Set<String>) {
        val seenHashes = readSeenHashes()
        val seenIds = readLinesSet(WallpaperFiles.seenIds)
        try {
            fetchFirstWorking(context, candidates, dest, avoidCurrent + seenHashes, seenIds)
        } catch (first: Throwable) {
            // Reset only after the whole candidate pool has been exhausted. This allows
            // 2k+ albums to complete a full cycle before repeating.
            if (seenHashes.isEmpty() && seenIds.isEmpty()) throw first
            clearSeenHashes()
            writeLinesSet(WallpaperFiles.seenIds, emptySet())
            writeLinesSet(WallpaperFiles.seenPerceptual, emptySet())
            fetchFirstWorking(context, candidates, dest, avoidCurrent, emptySet())
        }
    }

    private fun fetchFirstWorking(
        context: Context,
        candidates: List<WallpaperSourceEngine.Candidate>,
        dest: File,
        avoidHashes: Set<String>,
        seenIds: Set<String>,
    ) {
        val ordered = candidates
        var last: Throwable? = null
        var attempted = 0
        for (candidate in ordered) {
            if (candidate.id in seenIds) continue
            attempted++
            try {
                fetchTo(candidate, dest)
                val hash = sha256(dest)
                if (hash in avoidHashes && ordered.size > 1) {
                    dest.delete()
                    continue
                }
                writeMeta(dest, candidate.source, candidate.id)
                RuntimeStatus.activeSource(context, candidate.source)
                val ids = readLinesSet(WallpaperFiles.seenIds).toMutableSet().apply { add(candidate.id) }
                writeLinesSet(WallpaperFiles.seenIds, ids.toList().takeLast(10000).toSet())
                val phv = perceptualHash(dest)
                if (phv.isNotBlank()) {
                    val ph = readLinesSet(WallpaperFiles.seenPerceptual).toMutableSet().apply { add(phv) }
                    writeLinesSet(WallpaperFiles.seenPerceptual, ph.toList().takeLast(10000).toSet())
                }
                log("QUEUE ${dest.name} <= ${candidate.id}")
                return
            } catch (t: Throwable) {
                last = t
                dest.delete()
            }
        }
        if (attempted == 0) throw IllegalStateException("Candidate cycle exhausted")
        throw last ?: IllegalStateException("No unseen/usable wallpaper source")
    }

    private fun recordSeenForCurrent(targetMode: WallpaperTargetMode) {
        val files = when (targetMode) {
            WallpaperTargetMode.HOME ->
                listOf(WallpaperFiles.currentHome)

            WallpaperTargetMode.LOCK ->
                listOf(WallpaperFiles.currentLock)

            WallpaperTargetMode.BOTH_SAME,
            WallpaperTargetMode.BOTH_DIFFERENT ->
                listOf(
                    WallpaperFiles.currentHome,
                    WallpaperFiles.currentLock
                )
        }

        val hashes = readSeenHashes().toMutableSet()
        val ids = readLinesSet(WallpaperFiles.seenIds).toMutableSet()

        files.filter { it.exists() }.forEach { file ->
            runCatching {
                hashes.add(sha256(file))
            }

            runCatching {
                val meta = File(file.absolutePath + ".meta")
                if (meta.exists()) {
                    val props = Properties().apply {
                        meta.inputStream().use(::load)
                    }

                    props.getProperty("id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(ids::add)
                }
            }
        }

        writeSeenHashes(
            hashes.toList()
                .takeLast(10_000)
                .toSet()
        )

        writeLinesSet(
            WallpaperFiles.seenIds,
            ids.toList()
                .takeLast(10_000)
                .toSet()
        )
    }

    private fun readSeenHashes(): Set<String> = runCatching {
        if (!WallpaperFiles.seenHashes.exists()) emptySet()
        else WallpaperFiles.seenHashes.readLines().map(String::trim).filter { it.length == 64 }.toSet()
    }.getOrDefault(emptySet())

    private fun writeSeenHashes(hashes: Set<String>) {
        if (!WallpaperFiles.ensure()) return
        WallpaperFiles.seenHashes.writeText(hashes.joinToString("\n", postfix = if (hashes.isEmpty()) "" else "\n"))
    }

    private fun clearSeenHashes() { runCatching { WallpaperFiles.seenHashes.delete() } }

    private fun existingHashes(vararg files: File): Set<String> =
        files.filter { it.exists() }.mapNotNull { runCatching { sha256(it) }.getOrNull() }.toSet()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchToOnce(candidate: WallpaperSourceEngine.Candidate, dest: File) {
        dest.parentFile?.mkdirs()
        candidate.file?.let {
            it.copyTo(dest, overwrite = true)
            validateImage(dest)
            return
        }

        val raw = candidate.url ?: error("No source")
        val tmp = File(dest.parentFile, dest.name + ".part")
        val previous = if (tmp.exists()) tmp.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(raw).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 35_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                if (previous > 0L) setRequestProperty("Range", "bytes=$previous-")
            }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val type = connection.contentType.orEmpty().lowercase()
            if (type.contains("text/html") || type.contains("application/json") || type.contains("text/plain")) error("Server returned $type instead of image")
            val append = previous > 0L && code == HttpURLConnection.HTTP_PARTIAL
            connection.inputStream.use { input -> FileOutputStream(tmp, append).use { output -> input.copyTo(output) } }
            require(tmp.length() > 8_192) { "Downloaded file too small" }
            validateImage(tmp)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchTo(candidate: WallpaperSourceEngine.Candidate, dest: File) {
        var last: Throwable? = null
        repeat(3) { n ->
            try { fetchToOnce(candidate, dest); return }
            catch (t: Throwable) { last = t; dest.delete(); if (n < 2) Thread.sleep(400L * (1L shl n)) }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    private fun decodeForProcessing(file: File, maxSide: Int = 2560): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxMemory = Runtime.getRuntime().maxMemory()
        val safePixels = (maxMemory / 12L / 4L).coerceAtLeast(1_000_000L)
        var sample = 1
        fun pixelsAt(s: Int) = (bounds.outWidth / s).toLong() * (bounds.outHeight / s).toLong()
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2 || pixelsAt(sample) > safePixels) sample *= 2
        return try {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (_: OutOfMemoryError) { null }
    }

    private fun perceptualHash(file: File): String {
        val b = decodeForProcessing(file, 512) ?: return ""
        return try {
            val small = Bitmap.createScaledBitmap(b, 9, 8, true)
            var bits = 0L; var bit = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                val a = Color.luminance(small.getPixel(x, y)); val c = Color.luminance(small.getPixel(x + 1, y))
                if (a > c) bits = bits or (1L shl bit); bit++
            }
            small.recycle(); java.lang.Long.toUnsignedString(bits, 16)
        } finally { b.recycle() }
    }

    private fun perceptuallySeen(hash: String, seen: Set<String>, threshold: Int): Boolean {
        if (hash.isBlank() || threshold <= 0) return hash in seen
        val a = runCatching { java.lang.Long.parseUnsignedLong(hash, 16) }.getOrNull() ?: return hash in seen
        return seen.any { old ->
            val b = runCatching { java.lang.Long.parseUnsignedLong(old, 16) }.getOrNull() ?: return@any false
            java.lang.Long.bitCount(a xor b) <= threshold
        }
    }

    private fun readLinesSet(file: File): Set<String> = runCatching { if (file.exists()) file.readLines().map(String::trim).filter(String::isNotBlank).toSet() else emptySet() }.getOrDefault(emptySet())
    private fun writeLinesSet(file: File, values: Set<String>) { runCatching { file.parentFile?.mkdirs(); file.writeText(values.joinToString("\n", postfix=if(values.isEmpty())"" else "\n")) } }
    private fun freeBytes(): Long = runCatching { StatFs(WallpaperFiles.root.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
    private fun quarantine(file: File, prefix: String) { if (!file.exists()) return; runCatching { WallpaperFiles.quarantine.mkdirs(); file.renameTo(File(WallpaperFiles.quarantine, "${prefix}_${System.currentTimeMillis()}.bad")) } }
    private fun writeMeta(file: File, source: String, id: String) { runCatching { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.absolutePath, bounds); File(file.absolutePath + ".meta").writeText("source=$source\nid=$id\nbytes=${file.length()}\nsha256=${sha256(file)}\nwidth=${bounds.outWidth}\nheight=${bounds.outHeight}\n") } }
    fun sourceFor(path: String?): String {
        if (path == null) return "None"
        val meta = File(path + ".meta")
        return runCatching { Properties().apply { meta.inputStream().use(::load) }.getProperty("source", "Cache") }.getOrDefault("Cache")
    }

    fun exportDebugReport(context: Context): File {
        WallpaperFiles.ensure(); val s = SettingsStore(context).load(); val st = state()
        WallpaperFiles.debugReport.writeText(buildString {
            appendLine("BB-PixWall debug report")
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("engine=${s.engineMode}")
            appendLine("target=${s.targetMode}")
            appendLine("trigger=${s.triggerMode} interval=${s.intervalMinutes}")
            appendLine("cache=${cacheCount()} bytes=${cacheBytes()} integrityBad=${verifyCacheIntegrity()}")
            appendLine("cacheProgress=${RuntimeStatus.get(context,"cache_progress","")}")
            appendLine("photosLatencyMs=${RuntimeStatus.getLong(context,"photos_last_latency_ms")}")
            appendLine("driveLatencyMs=${RuntimeStatus.getLong(context,"drive_last_latency_ms")}")
            appendLine("fallbackReason=${RuntimeStatus.get(context,"fallback_reason","")}")
            appendLine("activeSource=${RuntimeStatus.get(context,"active_source")}")
            appendLine("lastError=${RuntimeStatus.get(context,"last_error","")}")
            appendLine("state=$st")
            appendLine("photos=${RuntimeStatus.get(context,"source_photos")}")
            appendLine("drive=${RuntimeStatus.get(context,"source_drive")}")
            appendLine("local=${RuntimeStatus.get(context,"source_local")}")
            appendLine("--- logs ---")
            if (WallpaperFiles.runtimeLog.exists()) append(WallpaperFiles.runtimeLog.readLines().takeLast(50).joinToString("\n"))
        }); return WallpaperFiles.debugReport
    }

    private fun validateImage(file: File) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Source is not a decodable image" }
    }

    private fun requireStorage() {
        require(WallpaperFiles.ensure()) { "Storage access required for /sdcard/wallpaper" }
    }

    private fun detectImageExtension(file: File): String {
        val header = ByteArray(16)
        val n = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        if (n >= 12) {
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return "jpg"
            if (header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A))) return "png"
            if (String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        }
        return file.extension.lowercase().takeIf { it in setOf("jpg","jpeg","png","webp","avif") } ?: "jpg"
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var i = 2
        while (candidate.exists()) { candidate = File(dir, "${base}_$i.$ext"); i++ }
        return candidate
    }

    private fun blur(source: Bitmap, requestedRadius: Int): Bitmap {
        val radius = requestedRadius.coerceIn(1, 64)
        val scale = 0.22f
        val w = max((source.width * scale).toInt(), 64)
        val h = max((source.height * scale).toInt(), 64)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = max((radius * scale).toInt(), 1)
        boxBlur(pixels, w, h, r)
        val blurredSmall = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val result = Bitmap.createScaledBitmap(blurredSmall, source.width, source.height, true)
        if (small !== source) small.recycle()
        blurredSmall.recycle()
        return result
    }

    private fun boxBlur(p: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val tmp = IntArray(p.size)
        val size = radius * 2 + 1
        for (y in 0 until h) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (x in -radius..radius) {
                val c = p[y * w + x.coerceIn(0, w - 1)]
                a += c ushr 24; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
            }
            for (x in 0 until w) {
                tmp[y * w + x] = (a / size shl 24) or (r / size shl 16) or (g / size shl 8) or (b / size)
                val out = p[y * w + (x - radius).coerceIn(0, w - 1)]
                val inn = p[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                a += (inn ushr 24) - (out ushr 24)
                r += (inn shr 16 and 255) - (out shr 16 and 255)
                g += (inn shr 8 and 255) - (out shr 8 and 255)
                b += (inn and 255) - (out and 255)
            }
        }
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (y in -radius..radius) {
                val c = tmp[y.coerceIn(0, h - 1) * w + x]
                a += c ushr 24; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
            }
            for (y in 0 until h) {
                p[y * w + x] = (a / size shl 24) or (r / size shl 16) or (g / size shl 8) or (b / size)
                val out = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                val inn = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                a += (inn ushr 24) - (out ushr 24)
                r += (inn shr 16 and 255) - (out shr 16 and 255)
                g += (inn shr 8 and 255) - (out shr 8 and 255)
                b += (inn and 255) - (out and 255)
            }
        }
    }

    private fun log(message: String) {
        runCatching {
            if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
