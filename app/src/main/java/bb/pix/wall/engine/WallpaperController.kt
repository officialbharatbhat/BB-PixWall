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
import java.util.concurrent.ConcurrentHashMap

object WallpaperController {
    private val lock = Any()
    private val generation = AtomicLong(0L)
    private val applying = AtomicBoolean(false)
    private val preparingNext = AtomicBoolean(false)
    private val cachePriming = AtomicBoolean(false)

    /*
     * primeCache() has many callers. If a refill is already active,
     * do not discard later requests. Coalesce them into one follow-up.
     */
    private val cachePrimeRequested = AtomicBoolean(false)
    @Volatile private var lastApplyStartedAt = 0L
    private data class CachedMetaId(
        val modified: Long,
        val length: Long,
        val id: String?,
    )

    private val metaIdCache =
        ConcurrentHashMap<String, CachedMetaId>()

    private val logLock = Any()

    private const val STALE_PART_MS =
        15L * 60L * 1000L

    private const val LOG_ROTATE_BYTES =
        2L * 1024L * 1024L


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

        /*
         * Capture the queue generation for this entire preparation pass.
         *
         * invalidateQueue() increments generation whenever the source
         * contract changes. A network request started under Photos must
         * never be allowed to commit its result after switching to
         * WEB_ONLY.
         */
        val ensureGeneration =
            generation.get()

        fun staleEnsure(): Boolean =
            generation.get() !=
                ensureGeneration

        var made = false
        synchronized(lock) {
            if (!WallpaperFiles.nextHome.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock)
                made = consumeCache(context, WallpaperFiles.nextHome, avoid) || made
            }
            if (!WallpaperFiles.nextLock.exists()) {
                val avoid = existingHashes(WallpaperFiles.currentHome, WallpaperFiles.currentLock, WallpaperFiles.nextHome)
                made = consumeCache(context, WallpaperFiles.nextLock, avoid) || made
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
            if (staleEnsure()) {
                log(
                    "PREPARE stale pass stopped before collect"
                )
                return made
            }

            val candidates =
                WallpaperSourceEngine.collect(
                    context,
                    settings,
                    allowNetwork = true,
                )

            if (staleEnsure()) {
                log(
                    "PREPARE stale pass stopped after collect"
                )
                return made
            }

            if (candidates.isEmpty()) {
                return made
            }

            fun prepare(
                dest: File,
                avoid: Set<String>,
            ): Boolean {
                if (dest.exists()) {
                    return true
                }

                if (staleEnsure()) {
                    log(
                        "PREPARE stale ${dest.name} stopped before fetch"
                    )
                    return false
                }

                val tmp =
                    File(
                        WallpaperFiles.backup,
                        ".${dest.name}.${System.nanoTime()}.part",
                    )

                return try {
                    fetchWithHistoryReset(
                        context,
                        candidates,
                        tmp,
                        avoid,
                    )

                    if (staleEnsure()) {
                        log(
                            "PREPARE stale download discarded ${dest.name}"
                        )
                        false
                    } else {
                        validateImage(tmp)

                        synchronized(lock) {
                            if (staleEnsure()) {
                                log(
                                    "PREPARE stale commit blocked ${dest.name}"
                                )
                                false
                            } else {
                                if (!dest.exists()) {
                                    tmp.copyTo(
                                        dest,
                                        overwrite = true,
                                    )

                                    val tm =
                                        File(
                                            tmp.absolutePath +
                                                ".meta"
                                        )

                                    if (tm.exists()) {
                                        tm.copyTo(
                                            File(
                                                dest.absolutePath +
                                                    ".meta"
                                            ),
                                            overwrite = true,
                                        )
                                    }
                                }

                                true
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log(
                        "PREPARE ${dest.name} failed: ${t.message}"
                    )
                    false
                } finally {
                    tmp.delete()

                    File(
                        tmp.absolutePath +
                            ".meta"
                    ).delete()
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

    private fun prepareNextFromPredictiveCache(
        context: Context,
        settings: AppSettings,
    ): Boolean {
        requireStorage()

        fun targetReady(): Boolean =
            when (settings.targetMode) {
                WallpaperTargetMode.HOME ->
                    WallpaperFiles.nextHome.exists()

                WallpaperTargetMode.LOCK ->
                    WallpaperFiles.nextLock.exists()

                WallpaperTargetMode.BOTH_SAME ->
                    WallpaperFiles.nextHome.exists() ||
                        WallpaperFiles.nextLock.exists()

                WallpaperTargetMode.BOTH_DIFFERENT ->
                    WallpaperFiles.nextHome.exists() &&
                        WallpaperFiles.nextLock.exists()
            }

        if (targetReady()) {
            return true
        }

        fun cacheSourceCompatible(
            file: File,
        ): Boolean {
            if (
                settings.webSourceMode !=
                bb.pix.wall.web.model.WebSourceMode.WEB_ONLY
            ) {
                return true
            }

            val meta =
                File(
                    file.absolutePath +
                        ".meta"
                )

            if (!meta.exists()) {
                return false
            }

            val source =
                runCatching {
                    meta.useLines { lines ->
                        lines.firstOrNull {
                            it.startsWith(
                                "source="
                            )
                        }
                            ?.substringAfter(
                                "source="
                            )
                            ?.trim()
                    }
                }.getOrNull()
                    .orEmpty()

            return source.startsWith(
                "Web",
                ignoreCase = true,
            ) ||
                source.contains(
                    "wallhaven",
                    ignoreCase = true,
                )
        }

        /*
         * HOT first, then WARM.
         *
         * Strict WEB_ONLY mode must never consume a Photos/Drive cache
         * entry left behind by an older asynchronous refill.
         */
        val cached =
            cacheImageFiles()
                .filter {
                    cacheSourceCompatible(it)
                }
                .sortedWith(
                    compareBy<File> {
                        when (it.parentFile) {
                            WallpaperFiles.hotCache -> 0
                            WallpaperFiles.warmCache -> 1
                            else -> 2
                        }
                    }.thenBy {
                        it.lastModified()
                    }
                )
                .toMutableList()

        if (cached.isEmpty()) {
            RuntimeStatus.set(
                context,
                "next_cache_fastpath",
                "empty",
            )
            return false
        }

        fun consumeInto(
            dest: File,
        ): Boolean {
            if (dest.exists()) {
                return true
            }

            while (cached.isNotEmpty()) {
                val src =
                    cached.removeAt(0)

                if (
                    !src.exists() ||
                    !src.isFile
                ) {
                    continue
                }

                val srcMeta =
                    File(
                        src.absolutePath +
                            ".meta"
                    )

                val destMeta =
                    File(
                        dest.absolutePath +
                            ".meta"
                    )

                return try {
                    dest.parentFile?.mkdirs()

                    dest.delete()
                    destMeta.delete()

                    /*
                     * Same filesystem in normal BB-PixWall layout,
                     * so rename is nearly instant. Copy fallback keeps
                     * this safe if Android/storage decides to be dramatic.
                     */
                    val moved =
                        src.renameTo(dest)

                    if (!moved) {
                        src.copyTo(
                            dest,
                            overwrite = true,
                        )
                        src.delete()
                    }

                    if (srcMeta.exists()) {
                        val metaMoved =
                            srcMeta.renameTo(
                                destMeta
                            )

                        if (!metaMoved) {
                            srcMeta.copyTo(
                                destMeta,
                                overwrite = true,
                            )
                            srcMeta.delete()
                        }
                    }

                    if (
                        dest.exists() &&
                        dest.length() > 0L
                    ) {
                        log(
                            "NEXT cache-fast ${src.parentFile?.name} -> ${dest.name}"
                        )

                        RuntimeStatus.set(
                            context,
                            "next_cache_fastpath",
                            "${src.parentFile?.name ?: "cache"} -> ${dest.name}",
                        )

                        true
                    } else {
                        dest.delete()
                        destMeta.delete()
                        false
                    }
                } catch (t: Throwable) {
                    dest.delete()
                    destMeta.delete()

                    log(
                        "NEXT cache-fast failed ${src.name}: ${t.message}"
                    )

                    false
                }
            }

            return false
        }

        when (settings.targetMode) {
            WallpaperTargetMode.HOME -> {
                consumeInto(
                    WallpaperFiles.nextHome
                )
            }

            WallpaperTargetMode.LOCK -> {
                consumeInto(
                    WallpaperFiles.nextLock
                )
            }

            WallpaperTargetMode.BOTH_SAME -> {
                if (
                    !WallpaperFiles.nextHome.exists() &&
                    !WallpaperFiles.nextLock.exists()
                ) {
                    consumeInto(
                        WallpaperFiles.nextHome
                    )
                }
            }

            WallpaperTargetMode.BOTH_DIFFERENT -> {
                if (
                    !WallpaperFiles.nextHome.exists()
                ) {
                    consumeInto(
                        WallpaperFiles.nextHome
                    )
                }

                if (
                    !WallpaperFiles.nextLock.exists()
                ) {
                    consumeInto(
                        WallpaperFiles.nextLock
                    )
                }
            }
        }

        val ready =
            targetReady()

        RuntimeStatus.set(
            context,
            "next_cache_fastpath_ready",
            ready.toString(),
        )

        return ready
    }

    fun nextWall(
        context: Context,
        allowNetwork: Boolean = true,
        userInitiated: Boolean = false,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastApplyStartedAt < 900L) return false
        if (!applying.compareAndSet(false, true)) return false
        lastApplyStartedAt = now
        return try {
            val settings =
                SettingsStore(context).load()

            /*
             * Defense in depth for strict Web-only mode.
             *
             * Even if an old build or interrupted source switch left a
             * prepared Photos/Drive .next file behind, never apply it.
             */
            if (
                settings.webSourceMode ==
                bb.pix.wall.web.model.WebSourceMode.WEB_ONLY
            ) {
                synchronized(lock) {
                    fun discardNonWebNext(
                        file: File,
                    ) {
                        if (!file.exists()) {
                            return
                        }

                        val meta =
                            File(
                                file.absolutePath +
                                    ".meta"
                            )

                        val source =
                            if (meta.exists()) {
                                runCatching {
                                    meta.useLines { lines ->
                                        lines.firstOrNull {
                                            it.startsWith(
                                                "source="
                                            )
                                        }
                                            ?.substringAfter(
                                                "source="
                                            )
                                            ?.trim()
                                    }
                                }.getOrNull()
                                    .orEmpty()
                            } else {
                                ""
                            }

                        val web =
                            source.startsWith(
                                "Web",
                                ignoreCase = true,
                            ) ||
                                source.contains(
                                    "wallhaven",
                                    ignoreCase = true,
                                )

                        if (!web) {
                            log(
                                "NEXT WEB_ONLY rejected ${file.name} source=${source.ifBlank { "unknown" }}"
                            )

                            file.delete()
                            meta.delete()
                        }
                    }

                    discardNonWebNext(
                        WallpaperFiles.nextHome
                    )

                    discardNonWebNext(
                        WallpaperFiles.nextLock
                    )
                }
            }

            val previousTasteIds = if (userInitiated) {
                TasteLearning.currentIds(settings.targetMode)
            } else {
                emptySet()
            }

            // Real fast path:
            // 1. already prepared next files
            // 2. predictive HOT/WARM cache
            // 3. existing offline/local ensureNext fallback
            //
            // Manual Next must not hit cloud while ready cached
            // wallpapers are sitting unused on disk.
            if (
                !prepareNextFromPredictiveCache(
                    context,
                    settings,
                )
            ) {
                ensureNext(
                    context,
                    settings,
                    allowNetwork = false,
                )
            }

            fun targetPrepared(): Boolean =
                when (settings.targetMode) {
                    WallpaperTargetMode.HOME ->
                        WallpaperFiles.nextHome.exists()

                    WallpaperTargetMode.LOCK ->
                        WallpaperFiles.nextLock.exists()

                    WallpaperTargetMode.BOTH_SAME ->
                        WallpaperFiles.nextHome.exists() ||
                            WallpaperFiles.nextLock.exists()

                    WallpaperTargetMode.BOTH_DIFFERENT ->
                        WallpaperFiles.nextHome.exists() &&
                            WallpaperFiles.nextLock.exists()
                }

            var preparedForTarget =
                targetPrepared()

            /*
             * Manual/QS Next must behave as one press = one change.
             *
             * Fast path still consumes prepared/cache content first.
             * Only when that queue is empty do we allow this SAME
             * user-initiated request to prepare from network.
             *
             * Background/automatic triggers remain non-blocking.
             */
            if (
                !preparedForTarget &&
                allowNetwork &&
                userInitiated
            ) {
                RuntimeStatus.set(
                    context,
                    "next_state",
                    "Fetching next wallpaper"
                )

                runCatching {
                    ensureNext(
                        context,
                        settings,
                        allowNetwork = true,
                    )
                }.onFailure {
                    log(
                        "NEXT manual prepare failed: ${it.message}"
                    )
                }

                preparedForTarget =
                    targetPrepared()
            }

            if (!preparedForTarget) {
                if (
                    allowNetwork &&
                    !userInitiated
                ) {
                    EngineExecutors.io {
                        runCatching {
                            ensureNext(
                                context,
                                settings,
                                allowNetwork = true,
                            )
                        }.onFailure {
                            log(
                                "NEXT background prepare failed: ${it.message}"
                            )
                        }
                    }
                }

                RuntimeStatus.set(
                    context,
                    "next_state",
                    if (userInitiated) {
                        "Unable to prepare next wallpaper"
                    } else {
                        "Preparing next wallpaper"
                    }
                )

                log(
                    if (userInitiated) {
                        "NEXT manual: no prepared wallpaper available after refill"
                    } else {
                        "NEXT deferred: prepared/cache queue empty; cloud refill moved to background"
                    }
                )

                return false
            }

            synchronized(lock) {
                val wm = WallpaperManager.getInstance(context)
            val blurMaster = blurMasterEnabled(context)
            var changed = false
            fun applyNext(src: File, flag: Int, blur: Boolean, radius: Int, current: File): Boolean {
                if (!src.exists()) return false
                return applyFile(context, settings, wm, src, flag, blur, radius, current)
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

                  if (userInitiated) {
                      TasteLearning.recordManualAdvance(
                          context,
                          previousTasteIds,
                      )?.let { signal ->
                          SessionMoodLearning.recordSignal(
                              context,
                              signal,
                          )
                      }
                  }

                  TasteLearning.recordAppliedCurrent(
                      context,
                      settings.targetMode,
                  )

                  SessionMoodLearning.recordApplied(
                      context,
                      TasteLearning.currentIds(
                          settings.targetMode
                      ),
                  )

                  EngineExecutors.io {
                      runCatching {
                          WallpaperStyleLearning.analyzeCurrent(
                              context,
                              settings.targetMode,
                          )
                      }
                  }
                WallpaperFiles.nextHome.delete()
                File(
                    WallpaperFiles.nextHome.absolutePath +
                        ".meta"
                ).delete()

                WallpaperFiles.nextLock.delete()
                File(
                    WallpaperFiles.nextLock.absolutePath +
                        ".meta"
                ).delete()
                RuntimeStatus.success(context, "Wallpaper applied: ${settings.targetMode.label}")

                RuntimeStatus.set(
                    context,
                    "next_state",
                    "Ready",
                )
                RuntimeStatus.setLong(context, "last_change", System.currentTimeMillis())
                EngineExecutors.io {
                    /*
                     * Keep the next pair continuously ready.
                     * Cache promotion is local/instant; cloud refill comes after.
                     */
                    runCatching {
                        prepareNextFromPredictiveCache(
                            context,
                            settings,
                        )
                    }

                    runCatching {
                        primeCache(
                            context,
                            settings,
                        )
                    }

                    if (
                        !prepareNextFromPredictiveCache(
                            context,
                            settings,
                        )
                    ) {
                        runCatching {
                            ensureNext(
                                context,
                                settings,
                                allowNetwork =
                                    WallpaperSourceEngine
                                        .networkAvailable(
                                            context
                                        ),
                            )
                        }
                    }
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

          if (out.isNotEmpty()) {
              TasteLearning.recordSaveCurrent(
                  context,
                  settings.targetMode,
              )?.let { signal ->
                  SessionMoodLearning.recordSignal(
                      context,
                      signal,
                  )
              }
          }

          if (out.isNotEmpty()) {
              RuntimeStatus.set(
                  context,
                  "cache_predictive_save_refresh",
                  "scheduled"
              )

              EngineExecutors.io {
                  runCatching {
                      rebalancePredictiveCache(
                          context,
                          adaptiveHotTarget(
                              settings.cacheTarget
                                  .coerceIn(4, 36)
                          ),
                      )
                  }.onSuccess {
                      RuntimeStatus.set(
                          context,
                          "cache_predictive_save_refresh",
                          "complete"
                      )
                  }.onFailure {
                      RuntimeStatus.set(
                          context,
                          "cache_predictive_save_refresh",
                          "failed"
                      )
                  }
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
            reapply(context, settings, wm, WallpaperFiles.currentHome, WallpaperManager.FLAG_SYSTEM,
                master && settings.homeBlurEnabled, settings.homeBlurRadius)
        }
        runCatching {
            reapply(context, settings, wm, WallpaperFiles.currentLock, WallpaperManager.FLAG_LOCK,
                master && settings.lockBlurEnabled, settings.lockBlurRadius)
        }
    }

    private fun reapply(
        context: Context,
        settings: AppSettings,
        wm: WallpaperManager,
        src: File,
        flag: Int,
        doBlur: Boolean,
        radius: Int,
    ) {
        if (!src.exists()) return
        if (!doBlur || radius <= 0) {
            val aspect = inspectAspect(src, settings.smartCropTolerancePct)
            if (settings.smartCropEnabled && aspect.valid && !aspect.matchesNineByTwenty) {
                val cropped = cropOriginalNineByTwenty(src)
                if (cropped != null) {
                    try { wm.setBitmap(cropped, null, true, flag) } finally { cropped.recycle() }
                    RuntimeStatus.set(context, "last_pipeline", "Smart crop 9:20 • source preserved")
                    return
                }
            }
            FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
            RuntimeStatus.set(context, "last_pipeline", if (aspect.matchesNineByTwenty) "Original 9:20 • untouched" else "Original stream • smart crop off/quality fallback")
            return
        }
        val bitmap = decodeForProcessing(src, 2200) ?: return
        val rendered = blur(bitmap, radius)
        try { wm.setBitmap(rendered, null, true, flag) }
        finally {
            rendered.recycle()
            bitmap.recycle()
        }
        RuntimeStatus.set(context, "last_pipeline", "Blur processed • original source preserved")
    }

    private fun applyFile(
        context: Context,
        settings: AppSettings,
        wm: WallpaperManager,
        src: File,
        flag: Int,
        doBlur: Boolean,
        radius: Int,
        current: File,
    ): Boolean {
        if (!src.exists()) return false
        return try {
            if (!doBlur || radius <= 0) {
                val aspect = inspectAspect(src, settings.smartCropTolerancePct)
                RuntimeStatus.set(context, "last_aspect", aspect.status)
                if (settings.smartCropEnabled && !aspect.matchesNineByTwenty && aspect.valid) {
                    val cropped = cropOriginalNineByTwenty(src)
                    if (cropped != null) {
                        val appliedCrop = try {
                            wm.setBitmap(cropped, null, true, flag)
                            true
                        } catch (_: Throwable) {
                            false
                        } finally {
                            cropped.recycle()
                        }
                        if (appliedCrop) {
                            RuntimeStatus.set(context, "last_pipeline", "Smart crop 9:20 • source preserved")
                            log("PIPELINE smart-crop ${src.name} ${aspect.width}x${aspect.height} -> 9:20")
                        } else {
                            FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
                            RuntimeStatus.set(context, "last_pipeline", "Original stream • crop apply fallback")
                        }
                    } else {
                        // Quality-first fallback: if a full-quality crop cannot be made safely,
                        // stream the original instead of silently downsampling/recompressing it.
                        FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
                        RuntimeStatus.set(context, "last_pipeline", "Original stream • crop skipped for quality")
                        log("PIPELINE crop skipped quality-first ${src.name}")
                    }
                } else {
                    // The normal 9:20 path is byte-preserving inside BB-PixWall: no crop, resize,
                    // bitmap decode or app-side recompression. WallpaperManager receives the source stream.
                    FileInputStream(src).use { input -> wm.setStream(input, null, true, flag) }
                    RuntimeStatus.set(context, "last_pipeline", if (aspect.matchesNineByTwenty) "Original 9:20 • untouched" else "Original stream • smart crop off")
                }
            } else {
                val original = decodeForProcessing(src, 2200) ?: return false
                val applied = blur(original, radius)
                try { wm.setBitmap(applied, null, true, flag) }
                finally { applied.recycle(); original.recycle() }
                RuntimeStatus.set(context, "last_pipeline", "Blur processed • original source preserved")
            }
            // Always retain the exact downloaded/source file for preview, unblur and Save Wall.
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

    private data class AspectInfo(
        val width: Int,
        val height: Int,
        val valid: Boolean,
        val matchesNineByTwenty: Boolean,
        val status: String,
    )

    private fun inspectAspect(file: File, tolerancePct: Float): AspectInfo {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        val w = o.outWidth
        val h = o.outHeight
        if (w <= 0 || h <= 0) return AspectInfo(w, h, false, false, "Unknown aspect")
        val actual = w.toDouble() / h.toDouble()
        val target = 9.0 / 20.0
        val deltaPct = kotlin.math.abs(actual - target) / target * 100.0
        val match = deltaPct <= tolerancePct.coerceIn(0.2f, 5f)
        return AspectInfo(w, h, true, match, if (match) "${w}x${h} • 9:20 compatible • untouched" else "${w}x${h} • mismatch ${"%.2f".format(java.util.Locale.US, deltaPct)}%")
    }

    private fun cropOriginalNineByTwenty(file: File): Bitmap? {
        return try {
            // Mismatch-only path. Decode original resolution so BB-PixWall does not trade quality
            // for convenience. OOM falls back to untouched streaming in applyFile().
            val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val target = 9.0 / 20.0
            val current = source.width.toDouble() / source.height.toDouble()
            val cropW: Int
            val cropH: Int
            if (current > target) {
                cropH = source.height
                cropW = (cropH * target).toInt().coerceAtMost(source.width)
            } else {
                cropW = source.width
                cropH = (cropW / target).toInt().coerceAtMost(source.height)
            }
            val x = ((source.width - cropW) / 2).coerceAtLeast(0)
            val y = ((source.height - cropH) / 2).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(source, x, y, cropW, cropH)
            if (cropped !== source) source.recycle()
            cropped
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
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
        /*
         * Any refill already running under the previous source
         * contract becomes stale immediately. primeCacheImpl checks
         * this generation before committing downloaded bytes.
         */
        generation.incrementAndGet()

        WallpaperFiles.ensure()
        var n = 0

        cacheImageFiles().forEach { f ->
            if (f.delete()) n++
            File(f.absolutePath + ".meta").delete()
            File(f.absolutePath + ".part").delete()
        }
        log("CACHE cleared=$n"); n
    }

    private fun cleanupStaleParts(
        context: Context,
    ): Int {
        val now =
            System.currentTimeMillis()

        /*
         * Scan every storage area that may contain temporary engine
         * artifacts. walkTopDown() also reaches cache/hot, cache/warm
         * and backup/settings without needing fragile hard-coded child
         * folder assumptions.
         */
        val roots =
            listOf(
                WallpaperFiles.cache,
                WallpaperFiles.queue,
                WallpaperFiles.legacyQueue,
                WallpaperFiles.backup,
            )
                .distinctBy {
                    runCatching {
                        it.canonicalPath
                    }.getOrDefault(
                        it.absolutePath
                    )
                }

        val visited =
            mutableSetOf<String>()

        var removedTemp = 0
        var removedMeta = 0

        roots.forEach { root ->
            if (!root.exists()) {
                return@forEach
            }

            root.walkTopDown()
                .filter {
                    it.isFile
                }
                .forEach { file ->
                    val canonical =
                        runCatching {
                            file.canonicalPath
                        }.getOrDefault(
                            file.absolutePath
                        )

                    if (!visited.add(canonical)) {
                        return@forEach
                    }

                    val ageMs =
                        now - file.lastModified()

                    val isStaleTemp =
                        (
                            file.name.endsWith(".part") ||
                                file.name.endsWith(".tmp")
                        ) &&
                            ageMs >= STALE_PART_MS

                    if (isStaleTemp) {
                        if (file.delete()) {
                            removedTemp++
                        }

                        File(
                            file.absolutePath + ".meta"
                        ).delete()

                        return@forEach
                    }

                    /*
                     * Metadata is valid only while its corresponding
                     * image exists. Remove old orphan metadata left by
                     * interrupted moves, cache trimming or crashes.
                     */
                    if (
                        file.name.endsWith(".meta") &&
                        ageMs >= STALE_PART_MS
                    ) {
                        val image =
                            File(
                                file.absolutePath
                                    .removeSuffix(".meta")
                            )

                        if (
                            !image.exists() &&
                            file.delete()
                        ) {
                            removedMeta++
                        }
                    }
                }
        }

        RuntimeStatus.set(
            context,
            "stale_part_cleanup",
            removedTemp.toString(),
        )

        RuntimeStatus.set(
            context,
            "stale_temp_cleanup",
            removedTemp.toString(),
        )

        RuntimeStatus.set(
            context,
            "orphan_meta_cleanup",
            removedMeta.toString(),
        )

        RuntimeStatus.setLong(
            context,
            "stale_part_cleanup_at",
            now,
        )

        if (
            removedTemp > 0 ||
            removedMeta > 0
        ) {
            log(
                "CLEAN stale temp=$removedTemp" +
                    " orphan-meta=$removedMeta"
            )
        }

        return removedTemp + removedMeta
    }


    private fun adaptiveHotTarget(
        target: Int,
    ): Int =
        when {
            target >= 20 -> 8
            target >= 12 -> 6
            else -> target.coerceAtMost(4)
        }

    fun primeCache(
        context: Context,
        settings: AppSettings = SettingsStore(context).load()
    ) {
        if (
            !cachePriming.compareAndSet(
                false,
                true,
            )
        ) {
            cachePrimeRequested.set(true)

            RuntimeStatus.set(
                context,
                "cache_refill_queue",
                "queued",
            )

            log(
                "CACHE prime queued: already running"
            )

            return
        }

        RuntimeStatus.set(
            context,
            "cache_refill_queue",
            "running",
        )

        val primeStartedNs =
            System.nanoTime()

        try {
            cleanupStaleParts(context)
            primeCacheImpl(context, settings)
        } finally {
            val elapsedMs =
                (
                    System.nanoTime() -
                        primeStartedNs
                ) / 1_000_000L

            RuntimeStatus.setLong(
                context,
                "cache_prime_latency_ms",
                elapsedMs,
            )

            RuntimeStatus.setLong(
                context,
                "cache_prime_finished_at",
                System.currentTimeMillis(),
            )

            /*
             * Capture requests both before and immediately after
             * releasing the priming guard. This closes the tiny race
             * where another caller can queue a refill between the
             * first request check and cachePriming becoming false.
             */
            val followUpBeforeRelease =
                cachePrimeRequested
                    .getAndSet(false)

            cachePriming.set(false)

            val followUpAfterRelease =
                cachePrimeRequested
                    .getAndSet(false)

            val followUp =
                followUpBeforeRelease ||
                    followUpAfterRelease

            RuntimeStatus.set(
                context,
                "cache_refill_queue",
                if (followUp) {
                    "follow-up scheduled"
                } else {
                    "idle"
                },
            )

            if (followUp) {
                EngineExecutors.io {
                    /*
                     * Tiny yield lets filesystem/cache moves settle
                     * before recalculating the live inventory.
                     */
                    runCatching {
                        Thread.sleep(250L)

                        primeCache(
                            context,
                            SettingsStore(context)
                                .load(),
                        )
                    }.onFailure {
                        log(
                            "CACHE follow-up failed: ${it.message}"
                        )
                    }
                }
            }
        }
    }

    private fun primeCacheImpl(context: Context, settings: AppSettings) {
        requireStorage()

        val primeGeneration =
            generation.get()

        fun stalePrime(): Boolean =
            generation.get() !=
                primeGeneration

        verifyCacheIntegrity()
        val reserve = settings.lowStorageReserveMb.coerceIn(256, 8192).toLong() * 1024L * 1024L
        if (freeBytes() < reserve) {
            trimCache((settings.cacheTarget / 2).coerceAtLeast(2))
            log("CACHE paused: low storage reserve=${settings.lowStorageReserveMb}MB")
            return
        }
        val target = when {
            settings.leanStorageMode -> settings.cacheTarget.coerceIn(4, 36)
            settings.engineMode == EngineMode.ADVANCED -> max(settings.cacheTarget, 16)
            else -> settings.cacheTarget.coerceIn(4, 36)
        }
        val hotTarget =
            adaptiveHotTarget(target)

        trimCache(target)

        val existing =
            cacheImageFiles()
                .toMutableList()

        /*
         * Incremental refill policy:
         *
         * cold cache        -> bootstrap up to 8 this pass
         * below half target -> add up to 4 this pass
         * normal depletion  -> add up to 2 this pass
         *
         * Every caller can continue requesting primeCache(), but one
         * invocation no longer turns into a 20-image network marathon.
         */
        val missing =
            (target - existing.size)
                .coerceAtLeast(0)

        /*
         * BOTH_DIFFERENT consumes two cached images per Next press,
         * so refill batches must understand consumption rate.
         */
        val pairCost =
            when (settings.targetMode) {
                WallpaperTargetMode.BOTH_DIFFERENT -> 2
                else -> 1
            }

        val refillBatch =
            when {
                missing <= 0 ->
                    0

                existing.isEmpty() ->
                    minOf(
                        missing,
                        8,
                    )

                existing.size <
                    (target / 2)
                        .coerceAtLeast(
                            pairCost * 2
                        ) ->
                    minOf(
                        missing,
                        maxOf(
                            6,
                            pairCost * 3,
                        ),
                    )

                else ->
                    minOf(
                        missing,
                        maxOf(
                            2,
                            pairCost * 2,
                        ),
                    )
            }

        /*
         * This is only a diagnostic target for this pass.
         * Actual stop condition below uses successfulAdds so cache
         * consumption happening in parallel cannot confuse the loop.
         */
        val cycleTarget =
            (
                existing.size +
                    refillBatch
            ).coerceAtMost(
                target
            )

        RuntimeStatus.set(
            context,
            "cache_refill_plan",
            "existing=${existing.size}" +
                " • target=$target" +
                " • batch=$refillBatch" +
                " • cycle=$cycleTarget"
        )

        if (existing.size >= target) {
            rebalancePredictiveCache(
                context,
                hotTarget,
            )
            return
        }

        if (stalePrime()) {
            RuntimeStatus.set(
                context,
                "cache_refill_contract",
                "stale-before-collect",
            )

            log(
                "CACHE stale refill aborted before collect"
            )

            return
        }

        val candidates =
            WallpaperSourceEngine.collect(
                context,
                settings,
                allowNetwork = true,
            )

        if (stalePrime()) {
            RuntimeStatus.set(
                context,
                "cache_refill_contract",
                "stale-after-collect",
            )

            log(
                "CACHE stale refill aborted after collect"
            )

            return
        }

        if (candidates.isEmpty()) return

        /*
         * The public Google Photos surface may expose only a bounded
         * candidate window even when the shared album itself is much
         * larger. Once almost every currently discoverable candidate
         * has been seen, strict seen-history filtering can starve cache
         * refill below its configured target.
         *
         * Treat seen history as a wallpaper cycle rather than a
         * permanent ban. Reset only when the remaining unseen pool
         * cannot satisfy this refill pass.
         *
         * Current Home/Lock/Next and existing cache bytes remain
         * protected by exact hashes, so a cycle reset cannot immediately
         * duplicate wallpapers already active or already cached.
         */
        /*
         * Active/current/cache files are protected independently from
         * long-term seen history. This matters when a finite cloud
         * discovery window exhausts and the seen cycle is reset.
         *
         * Exact hashes prevent byte-for-byte repeats while perceptual
         * hashes protect against the same image arriving recompressed,
         * resized or through another source URL.
         */
        val protectedFiles =
            (
                listOf(
                    WallpaperFiles.currentHome,
                    WallpaperFiles.currentLock,
                    WallpaperFiles.nextHome,
                    WallpaperFiles.nextLock,
                ) +
                    existing
            )
                .filter {
                    it.exists() &&
                        it.isFile
                }
                .distinctBy {
                    runCatching {
                        it.canonicalPath
                    }.getOrDefault(
                        it.absolutePath
                    )
                }

        val protectedHashes =
            protectedFiles
                .mapNotNull { file ->
                    runCatching {
                        sha256(file)
                    }.getOrNull()
                }
                .toMutableSet()

        val protectedPhash =
            protectedFiles
                .mapNotNull { file ->
                    runCatching {
                        perceptualHash(file)
                    }
                        .getOrNull()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                }
                .toMutableSet()

        var seenHashes =
            readSeenHashes()

        var seenIds =
            readLinesSet(
                WallpaperFiles.seenIds
            )

        var seenPhash =
            readLinesSet(
                WallpaperFiles.seenPerceptual
            )

        val cachedIds =
            existing.mapNotNull(
                ::cacheCandidateId
            ).toMutableSet()

        val unseenAvailable =
            candidates.count { candidate ->
                candidate.id !in seenIds &&
                    candidate.id !in cachedIds
            }

        if (
            refillBatch > 0 &&
            unseenAvailable < refillBatch &&
            seenIds.isNotEmpty()
        ) {
            val previousSeen =
                seenIds.size

            clearSeenHashes()

            writeLinesSet(
                WallpaperFiles.seenIds,
                emptySet(),
            )

            writeLinesSet(
                WallpaperFiles.seenPerceptual,
                emptySet(),
            )

            seenHashes = emptySet()
            seenIds = emptySet()
            seenPhash = emptySet()

            RuntimeStatus.set(
                context,
                "cache_seen_cycle",
                "reset • seen=$previousSeen" +
                    " • pool=${candidates.size}" +
                    " • available=$unseenAvailable",
            )

            log(
                "CACHE seen-cycle reset: " +
                    "seen=$previousSeen " +
                    "pool=${candidates.size} " +
                    "available=$unseenAvailable " +
                    "needed=$refillBatch"
            )
        }

        val knownHashes =
            (
                protectedHashes +
                    seenHashes
            ).toMutableSet()

        val knownPhash =
            (
                protectedPhash +
                    seenPhash
            ).toMutableSet()
        var index = existing.size
        var successfulAdds = 0
        var skippedSeenId = 0
        var skippedCachedId = 0
        var skippedDuplicate = 0

        var hotCount =
            existing.count {
                it.parentFile == WallpaperFiles.hotCache
            }

        var directHot = 0
        var directWarm = 0

        for (candidate in candidates) {
            if (stalePrime()) {
                RuntimeStatus.set(
                    context,
                    "cache_refill_contract",
                    "stale-before-fetch",
                )

                log(
                    "CACHE stale refill stopped before ${candidate.id}"
                )

                break
            }

            if (
                successfulAdds >=
                    refillBatch
            ) break
            if (candidate.id in seenIds) {
                skippedSeenId++
                continue
            }

            if (candidate.id in cachedIds) {
                skippedCachedId++
                continue
            }

            val ext =
                candidate.file
                    ?.extension
                    ?.takeIf { it.isNotBlank() }
                    ?: "jpg"

            /*
             * Stage outside hot/warm so we can analyze the freshly
             * downloaded image before choosing its final cache tier.
             *
             * ".part" keeps this invisible to normal cache consumers.
             */
            val staging =
                File(
                    WallpaperFiles.cache,
                    ".prime_${System.currentTimeMillis()}_${index}.$ext.part"
                )

            var finalFile: File? = null

            try {
                fetchTo(candidate, staging)

                /*
                 * Source mode may have changed while this network
                 * request was in flight. Never let bytes fetched
                 * under an old contract enter Hot/Warm cache.
                 */
                if (stalePrime()) {
                    staging.delete()

                    RuntimeStatus.set(
                        context,
                        "cache_refill_contract",
                        "stale-after-fetch",
                    )

                    log(
                        "CACHE stale download discarded ${candidate.id}"
                    )

                    break
                }

                val hash = sha256(staging)
                val ph = perceptualHash(staging)

                val duplicate =
                    hash in knownHashes ||
                        perceptuallySeen(
                            ph,
                            knownPhash,
                            settings.perceptualDistance,
                        )

                if (duplicate) {
                    skippedDuplicate++
                    staging.delete()
                    continue
                }

                /*
                 * Analyze the already-downloaded bytes first.
                 * No additional network and no second decode is required
                 * later merely to decide Hot vs Warm.
                 */
                runCatching {
                    WallpaperStyleLearning.analyzeAndStore(
                        context,
                        candidate.id,
                        staging,
                    )
                }

                val predictiveScore =
                    predictiveCacheScore(
                        context,
                        candidate.id,
                    )

                recordPredictiveSample(
                    context,
                    predictiveScore,
                )

                val hotThreshold =
                    predictiveHotThreshold(
                        context,
                        hotTarget,
                    )

                /*
                 * Adaptive direct placement.
                 *
                 * During early learning the threshold stays permissive.
                 * As confidence/history mature it can become selective.
                 */
                val directToHot =
                    predictiveScore >= hotThreshold &&
                        hotCount < hotTarget

                val dir =
                    if (directToHot)
                        WallpaperFiles.hotCache
                    else
                        WallpaperFiles.warmCache

                dir.mkdirs()

                val cacheFile =
                    File(
                        dir,
                        "queue_${System.currentTimeMillis()}_${index}.$ext"
                    )

                val committed =
                    synchronized(lock) {
                        /*
                         * invalidateQueue()/clearCache() also use this
                         * lock, so generation check + cache commit is
                         * atomic against a source-contract mutation.
                         */
                        if (stalePrime()) {
                            false
                        } else {
                            val moved =
                                staging.renameTo(cacheFile) ||
                                    runCatching {
                                        staging.copyTo(
                                            cacheFile,
                                            overwrite = false,
                                        )
                                        staging.delete()
                                        true
                                    }.getOrDefault(false)

                            if (
                                !moved ||
                                !cacheFile.exists()
                            ) {
                                cacheFile.delete()

                                throw IllegalStateException(
                                    "Predictive cache finalization failed"
                                )
                            }

                            finalFile = cacheFile

                            writeMeta(
                                cacheFile,
                                candidate.source,
                                candidate.id,
                            )

                            true
                        }
                    }

                if (!committed) {
                    staging.delete()
                    cacheFile.delete()

                    File(
                        cacheFile.absolutePath + ".meta"
                    ).delete()

                    RuntimeStatus.set(
                        context,
                        "cache_refill_contract",
                        "stale-before-commit",
                    )

                    log(
                        "CACHE stale commit blocked ${candidate.id}"
                    )

                    break
                }

                cachedIds += candidate.id
                knownHashes += hash

                if (ph.isNotBlank()) {
                    knownPhash += ph
                }

                if (directToHot) {
                    hotCount++
                    directHot++
                } else {
                    directWarm++
                }

                index++
                successfulAdds++

                RuntimeStatus.activeSource(
                    context,
                    candidate.source
                )

                RuntimeStatus.set(
                    context,
                    "cache_progress",
                    "live=${cacheImageFiles().size}/$target" +
                        " • added=$successfulAdds/$refillBatch"
                )

                RuntimeStatus.set(
                    context,
                    "cache_predictive_direct_last",
                    "${if (directToHot) "hot" else "warm"}" +
                        " • score=$predictiveScore" +
                        " • threshold=$hotThreshold"
                )

                RuntimeStatus.set(
                    context,
                    "cache_predictive_direct_counts",
                    "hot=$directHot • warm=$directWarm"
                )

                log(
                    "CACHE + ${cacheFile.name} <= ${candidate.id} " +
                        "tier=${if (directToHot) "hot" else "warm"} " +
                        "predict=$predictiveScore " +
                        "threshold=$hotThreshold"
                )
            } catch (t: Throwable) {
                staging.delete()

                finalFile?.let { file ->
                    if (file.exists()) {
                        quarantine(
                            file,
                            "cache_${candidate.source}"
                        )
                    }

                    File(
                        file.absolutePath + ".meta"
                    ).delete()
                }

                log(
                    "CACHE fail ${candidate.id}: ${t.message}"
                )
            }
        }
        if (!stalePrime()) {
            RuntimeStatus.set(
                context,
                "cache_refill_contract",
                "current",
            )
        }

        RuntimeStatus.set(
            context,
            "cache_duplicate_guard",
            "seen=$skippedSeenId" +
                " • cached=$skippedCachedId" +
                " • duplicate=$skippedDuplicate" +
                " • added=$successfulAdds",
        )

        trimCache(target)
        rebalancePredictiveCache(
            context,
            hotTarget,
        )

        val refillFinalCount =
            cacheImageFiles().size

        RuntimeStatus.set(
            context,
            "cache_refill_result",
            "before=${existing.size}" +
                " • after=$refillFinalCount" +
                " • downloaded=$successfulAdds" +
                " • net=${
                    refillFinalCount -
                        existing.size
                }" +
                " • remaining=${
                    (target - refillFinalCount)
                        .coerceAtLeast(0)
                }"
        )

        /*
         * A rapid manual burst may consume cache while this refill is
         * downloading. Critical depletion keeps the aggressive
         * recovery behaviour, while a partially recovered cache now
         * continues through small coalesced passes until the configured
         * target is reached.
         *
         * Automatic chaining stops when a pass makes no progress, so a
         * network/source failure cannot create an infinite retry loop.
         */
        val criticalFloor =
            (target / 2)
                .coerceAtLeast(
                    pairCost * 2
                )

        val needsMore =
            refillFinalCount < target

        val madeProgress =
            successfulAdds > 0

        if (
            needsMore &&
            madeProgress
        ) {
            cachePrimeRequested.set(true)

            val recoveryMode =
                if (
                    refillFinalCount <
                        criticalFloor
                ) {
                    "critical"
                } else {
                    "settling"
                }

            RuntimeStatus.set(
                context,
                "cache_refill_recovery",
                "$recoveryMode • $refillFinalCount/$target",
            )

            log(
                "CACHE recovery $recoveryMode: " +
                    "$refillFinalCount/$target"
            )
        } else {
            RuntimeStatus.set(
                context,
                "cache_refill_recovery",
                if (needsMore) {
                    "paused-no-progress • " +
                        "$refillFinalCount/$target"
                } else {
                    "stable • $refillFinalCount/$target"
                },
            )
        }
    }


    private fun predictiveCacheScore(
        context: Context,
        candidateId: String,
    ): Int {
        val taste =
            (TasteLearning.score(context, candidateId) * 2)
                .coerceIn(-12, 12)

        val style =
            WallpaperStyleLearning
                .decisionInfluence(
                    context,
                    candidateId,
                )
                ?.influence
                ?: 0

        return (taste + style)
            .coerceIn(-22, 22)
    }

    private fun recordPredictiveSample(
        context: Context,
        score: Int,
    ) {
        val prefs =
            context.getSharedPreferences(
                "bb_pixwall_predictive_v1",
                Context.MODE_PRIVATE,
            )

        val oldCount =
            prefs.getInt("sample_count", 0)

        val count =
            (oldCount + 1)
                .coerceAtMost(100000)

        val oldEwma =
            prefs.getFloat(
                "score_ewma",
                score.toFloat(),
            )

        val ewma =
            if (oldCount == 0) {
                score.toFloat()
            } else {
                oldEwma * 0.90f +
                    score.toFloat() * 0.10f
            }

        val positive =
            prefs.getInt("positive", 0) +
                if (score > 0) 1 else 0

        val neutral =
            prefs.getInt("neutral", 0) +
                if (score == 0) 1 else 0

        val negative =
            prefs.getInt("negative", 0) +
                if (score < 0) 1 else 0

        val oldBest =
            if (oldCount == 0)
                score
            else
                prefs.getInt("best", score)

        val oldWorst =
            if (oldCount == 0)
                score
            else
                prefs.getInt("worst", score)

        prefs.edit()
            .putInt("sample_count", count)
            .putFloat("score_ewma", ewma)
            .putInt("positive", positive)
            .putInt("neutral", neutral)
            .putInt("negative", negative)
            .putInt("best", maxOf(oldBest, score))
            .putInt("worst", minOf(oldWorst, score))
            .apply()

        RuntimeStatus.set(
            context,
            "cache_predictive_history",
            "n=$count • +" +
                "$positive • 0=$neutral • -=$negative • " +
                "ewma=${"%.2f".format(ewma)}"
        )
    }

    private fun predictiveHotThreshold(
        context: Context,
        hotTarget: Int,
    ): Int {
        val allowed =
            setOf(
                "jpg",
                "jpeg",
                "png",
                "webp",
                "avif",
            )

        val scores =
            listOf(
                WallpaperFiles.hotCache,
                WallpaperFiles.warmCache,
            )
                .flatMap {
                    it.listFiles()
                        .orEmpty()
                        .toList()
                }
                .filter {
                    it.isFile &&
                        it.extension.lowercase() in allowed
                }
                .mapNotNull { file ->
                    cacheCandidateId(file)
                        ?.let { id ->
                            predictiveCacheScore(
                                context,
                                id,
                            )
                        }
                }
                .sortedDescending()

        val poolCut =
            if (scores.isEmpty()) {
                0
            } else {
                scores[
                    minOf(
                        hotTarget - 1,
                        scores.lastIndex,
                    )
                ]
            }

        val confidence =
            WallpaperStyleLearning
                .profileConfidence(context)

        val prefs =
            context.getSharedPreferences(
                "bb_pixwall_predictive_v1",
                Context.MODE_PRIVATE,
            )

        val sampleCount =
            prefs.getInt(
                "sample_count",
                0,
            )

        val ewma =
            prefs.getFloat(
                "score_ewma",
                0f,
            )

        /*
         * Early learning:
         * neutral wallpapers are allowed into Hot.
         *
         * Mature learning:
         * require a genuinely positive score when history confirms
         * that the model can meaningfully separate preferences.
         */
        val maturityFloor =
            if (
                confidence >= 0.75f &&
                sampleCount >= 24 &&
                ewma >= 0.35f
            ) {
                1
            } else {
                0
            }

        return maxOf(
            maturityFloor,
            poolCut.coerceAtLeast(0),
        )
            .coerceIn(0, 6)
    }

    private fun refreshPredictiveDiagnostics(
        context: Context,
        hotTarget: Int = 4,
    ) {
        val allowed =
            setOf(
                "jpg",
                "jpeg",
                "png",
                "webp",
                "avif",
            )

        fun images(dir: File): List<File> =
            dir.listFiles()
                .orEmpty()
                .filter {
                    it.isFile &&
                        it.extension.lowercase() in allowed
                }

        val hot =
            images(WallpaperFiles.hotCache)

        val warm =
            images(WallpaperFiles.warmCache)

        val scores =
            (hot + warm)
                .mapNotNull { file ->
                    cacheCandidateId(file)
                        ?.let { id ->
                            predictiveCacheScore(
                                context,
                                id,
                            )
                        }
                }

        val positive =
            scores.count { it > 0 }

        val neutral =
            scores.count { it == 0 }

        val negative =
            scores.count { it < 0 }

        val average =
            if (scores.isEmpty()) {
                0f
            } else {
                scores.average()
                    .toFloat()
            }

        val threshold =
            predictiveHotThreshold(
                context,
                hotTarget,
            )

        val confidence =
            WallpaperStyleLearning
                .profileConfidence(context)

        RuntimeStatus.set(
            context,
            "cache_actual_state",
            "hot=${hot.size} • warm=${warm.size}"
        )

        RuntimeStatus.set(
            context,
            "cache_predictive_pool_stats",
            "n=${scores.size} • +$positive • " +
                "0=$neutral • -=$negative • " +
                "avg=${"%.2f".format(average)} • " +
                "best=${scores.maxOrNull() ?: 0} • " +
                "worst=${scores.minOrNull() ?: 0}"
        )

        RuntimeStatus.set(
            context,
            "cache_predictive_threshold",
            threshold.toString()
        )

        RuntimeStatus.set(
            context,
            "cache_predictive_confidence",
            "%.2f".format(confidence)
        )

        val snapshotAt =
            System.currentTimeMillis()

        RuntimeStatus.set(
            context,
            "cache_predictive_snapshot",
            "hot=${hot.size} • warm=${warm.size} • " +
                "n=${scores.size} • threshold=$threshold • " +
                "confidence=${"%.2f".format(confidence)} • " +
                "best=${scores.maxOrNull() ?: 0} • " +
                "worst=${scores.minOrNull() ?: 0}",
        )

        RuntimeStatus.setLong(
            context,
            "cache_predictive_snapshot_at",
            snapshotAt,
        )
    }

    private fun cacheCandidateId(
        file: File,
    ): String? {
        val meta =
            File(
                file.absolutePath + ".meta"
            )

        if (!meta.exists()) {
            metaIdCache.remove(
                meta.absolutePath
            )
            return null
        }

        val modified =
            meta.lastModified()

        val length =
            meta.length()

        val key =
            meta.absolutePath

        val cached =
            metaIdCache[key]

        if (
            cached != null &&
            cached.modified == modified &&
            cached.length == length
        ) {
            return cached.id
        }

        val id =
            runCatching {
                Properties().apply {
                    meta.inputStream().use(::load)
                }
                    .getProperty("id")
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
            }.getOrNull()

        metaIdCache[key] =
            CachedMetaId(
                modified = modified,
                length = length,
                id = id,
            )

        return id
    }

    private data class PredictiveCacheEntry(
        val file: File,
        val id: String?,
        val score: Int,
    )

    /**
     * Rebalances existing cache only.
     *
     * No network, no bitmap decode, no new cache allocation.
     * Top-scoring entries live in hot/, remaining entries in warm/.
     */
    private fun rebalancePredictiveCache(
        context: Context,
        hotTarget: Int,
    ) = synchronized(lock) {
        WallpaperFiles.ensure()

        val allowed =
            setOf("jpg", "jpeg", "png", "webp", "avif")

        val files =
            listOf(
                WallpaperFiles.hotCache,
                WallpaperFiles.warmCache,
            )
                .flatMap {
                    it.listFiles()
                        .orEmpty()
                        .toList()
                }
                .filter {
                    it.isFile &&
                        it.extension.lowercase() in allowed
                }
                .distinctBy {
                    it.absolutePath
                }

        if (files.isEmpty()) {
            RuntimeStatus.set(
                context,
                "cache_predictive_state",
                "empty"
            )
            return@synchronized
        }

        val entries =
            files.map { file ->
                val id = cacheCandidateId(file)

                PredictiveCacheEntry(
                    file = file,
                    id = id,
                    score =
                        if (id == null) 0
                        else predictiveCacheScore(
                            context,
                            id,
                        ),
                )
            }
                .sortedWith(
                    compareByDescending<PredictiveCacheEntry> {
                        /*
                         * Small retention bonus prevents 0/-1 jitter
                         * from moving files back and forth every refresh.
                         *
                         * A meaningfully better Warm candidate can still
                         * displace a weaker Hot candidate.
                         */
                        it.score +
                            if (
                                it.file.parentFile ==
                                    WallpaperFiles.hotCache
                            ) {
                                2
                            } else {
                                0
                            }
                    }.thenByDescending {
                        it.score
                    }.thenBy {
                        it.file.lastModified()
                    }
                )

        val desiredHot =
            hotTarget
                .coerceAtLeast(1)
                .coerceAtMost(entries.size)

        var promoted = 0
        var demoted = 0

        fun uniqueTarget(
            dir: File,
            originalName: String,
        ): File {
            val first = File(dir, originalName)
            if (!first.exists()) return first

            val base =
                originalName.substringBeforeLast(
                    '.',
                    originalName
                )

            val ext =
                originalName.substringAfterLast(
                    '.',
                    ""
                )

            var n = 1

            while (true) {
                val name =
                    if (ext.isBlank()) {
                        "${base}_$n"
                    } else {
                        "${base}_$n.$ext"
                    }

                val candidate = File(dir, name)

                if (!candidate.exists()) {
                    return candidate
                }

                n++
            }
        }

        entries.forEachIndexed { index, entry ->
            val desiredDir =
                if (index < desiredHot)
                    WallpaperFiles.hotCache
                else
                    WallpaperFiles.warmCache

            if (entry.file.parentFile == desiredDir) {
                return@forEachIndexed
            }

            desiredDir.mkdirs()

            val src = entry.file
            val srcMeta =
                File(src.absolutePath + ".meta")

            val target =
                uniqueTarget(
                    desiredDir,
                    src.name,
                )

            val targetMeta =
                File(target.absolutePath + ".meta")

            val oldModified =
                src.lastModified()

            val moved =
                runCatching {
                    src.copyTo(
                        target,
                        overwrite = false,
                    )

                    if (srcMeta.exists()) {
                        srcMeta.copyTo(
                            targetMeta,
                            overwrite = false,
                        )
                    }

                    target.setLastModified(oldModified)

                    src.delete()
                    srcMeta.delete()

                    true
                }.getOrElse {
                    target.delete()
                    targetMeta.delete()
                    false
                }

            if (moved) {
                if (desiredDir == WallpaperFiles.hotCache) {
                    promoted++
                } else {
                    demoted++
                }
            }
        }

        val hotCount =
            WallpaperFiles.hotCache
                .listFiles()
                .orEmpty()
                .count {
                    it.isFile &&
                        it.extension.lowercase() in allowed
                }

        val warmCount =
            WallpaperFiles.warmCache
                .listFiles()
                .orEmpty()
                .count {
                    it.isFile &&
                        it.extension.lowercase() in allowed
                }

        val best =
            entries.firstOrNull()

        RuntimeStatus.set(
            context,
            "cache_predictive_state",
            "hot=$hotCount • warm=$warmCount • " +
                "promoted=$promoted • demoted=$demoted"
        )

        RuntimeStatus.set(
            context,
            "cache_predictive_best_score",
            (best?.score ?: 0).toString()
        )

        RuntimeStatus.set(
            context,
            "cache_predictive_best_id",
            best?.id?.take(180) ?: "unknown"
        )

        log(
            "CACHE predictive hot=$hotCount warm=$warmCount " +
                "promoted=$promoted demoted=$demoted " +
                "best=${best?.score ?: 0}"
        )

        refreshPredictiveDiagnostics(
            context,
            hotTarget,
        )
    }

    private fun consumeCache(context: Context, dest: File, avoidHashes: Set<String>): Boolean {
        WallpaperFiles.ensure()
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        data class PoolFile(
            val file: File,
            val consume: Boolean,
            val source: String,
        )

        val styleScoreCache = mutableMapOf<String, Int>()

        fun cacheStyleScore(file: File): Int =
            styleScoreCache.getOrPut(file.absolutePath) {
                runCatching {
                    val meta = File(
                        file.absolutePath + ".meta"
                    )

                    if (!meta.exists()) {
                        return@runCatching 0
                    }

                    val props = Properties().apply {
                        meta.inputStream().use(::load)
                    }

                    val id =
                        props.getProperty("id")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: return@runCatching 0

                    WallpaperStyleLearning
                        .decisionInfluence(
                            context,
                            id,
                        )
                        ?.influence
                        ?: 0
                }.getOrDefault(0)
            }

        fun pool(): List<PoolFile> = buildList {
            listOf(WallpaperFiles.hotCache, WallpaperFiles.warmCache, WallpaperFiles.cache).forEach { cacheDir ->
                cacheDir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in allowed }
                    .sortedWith(
                        compareByDescending<File> {
                            cacheStyleScore(it)
                        }.thenBy {
                            it.lastModified()
                        }
                    )
                    .forEach {
                        add(
                            PoolFile(
                                it,
                                true,
                                if (cacheDir == WallpaperFiles.hotCache)
                                    "HotCache"
                                else
                                    "Cache"
                            )
                        )
                    }
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

                val styleScore = cacheStyleScore(f)

                val ok = runCatching {
                    f.copyTo(dest, overwrite = true)
                    val side = File(f.absolutePath + ".meta")
                    if (side.exists()) side.copyTo(File(dest.absolutePath + ".meta"), overwrite = true)
                    else writeMeta(dest, entry.source, "offline:${f.name}")
                    validateImage(dest)
                    if (entry.consume) {
                        f.delete()
                        side.delete()

                        refreshPredictiveDiagnostics(
                            context,
                            4,
                        )
                    }

                    RuntimeStatus.set(
                        context,
                        "cache_style_last_score",
                        styleScore.toString()
                    )

                    RuntimeStatus.set(
                        context,
                        "selection_reason",
                        "Offline-ready ${entry.source} • " +
                            "style=$styleScore • unseen SHA-256 • " +
                            dest.name
                    )

                    log(
                        "QUEUE ${dest.name} <= ${entry.source}:${f.name} " +
                            "style=$styleScore"
                    )
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

                runCatching {
                    WallpaperStyleLearning.analyzeAndStore(
                        context,
                        candidate.id,
                        dest,
                    )
                }
                RuntimeStatus.activeSource(context, candidate.source)
                val ids = readLinesSet(WallpaperFiles.seenIds).toMutableSet().apply { add(candidate.id) }
                writeLinesSet(WallpaperFiles.seenIds, ids.toList().takeLast(10000).toSet())
                val phv = perceptualHash(dest)
                if (phv.isNotBlank()) {
                    val ph = readLinesSet(WallpaperFiles.seenPerceptual).toMutableSet().apply { add(phv) }
                    writeLinesSet(WallpaperFiles.seenPerceptual, ph.toList().takeLast(10000).toSet())
                }
                RuntimeStatus.set(context, "selection_reason", "${candidate.source} candidate • unseen source ID/hash • ${dest.name}")
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
            WallpaperTargetMode.HOME -> listOf(WallpaperFiles.currentHome)
            WallpaperTargetMode.LOCK -> listOf(WallpaperFiles.currentLock)
            WallpaperTargetMode.BOTH_SAME, WallpaperTargetMode.BOTH_DIFFERENT ->
                listOf(WallpaperFiles.currentHome, WallpaperFiles.currentLock)
        }
        val hashes = readSeenHashes().toMutableSet()
        val ids = readLinesSet(WallpaperFiles.seenIds).toMutableSet()
        files.filter(File::exists).forEach { file ->
            runCatching { hashes += sha256(file) }
            runCatching {
                val meta = File(file.absolutePath + ".meta")
                if (meta.exists()) {
                    val props = Properties().apply { meta.inputStream().use(::load) }
                    props.getProperty("id")?.trim()?.takeIf { it.isNotEmpty() }?.let(ids::add)
                }
            }
        }
        writeSeenHashes(hashes.toList().takeLast(10_000).toSet())
        writeLinesSet(WallpaperFiles.seenIds, ids.toList().takeLast(10_000).toSet())
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
            appendLine("engineHealth=${RuntimeStatus.get(context,"engine_health","Unknown")}")
            appendLine("smartCrop=${s.smartCropEnabled} tolerancePct=${s.smartCropTolerancePct}")
            appendLine("leanStorage=${s.leanStorageMode}")
            appendLine("lastPipeline=${RuntimeStatus.get(context,"last_pipeline","Unknown")}")
            appendLine("lastAspect=${RuntimeStatus.get(context,"last_aspect","Unknown")}")
            appendLine("rootState=${RuntimeStatus.get(context,"root_state","Not checked")}")
            appendLine("rootProvider=${RuntimeStatus.get(context,"root_provider","Unknown")}")
            appendLine("rootCaps=${RuntimeStatus.get(context,"root_caps","Not scanned")}")
            appendLine("rootPriority=${RuntimeStatus.get(context,"root_priority","Not tuned")}")
            appendLine("rootDoze=${RuntimeStatus.get(context,"root_doze_whitelist","Unknown")}")
            appendLine("rootAppOps=${RuntimeStatus.get(context,"root_appops_verified","Unknown")}")
            appendLine("rootOom=${RuntimeStatus.get(context,"root_oom_score","Unknown")}")
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

    private fun log(
        message: String,
    ) {
        runCatching {
            synchronized(logLock) {
                if (!WallpaperFiles.ensure()) {
                    return@synchronized
                }

                val log =
                    WallpaperFiles.runtimeLog

                if (
                    log.exists() &&
                    log.length() >= LOG_ROTATE_BYTES
                ) {
                    val rotated =
                        File(
                            log.parentFile,
                            "${log.name}.1",
                        )

                    rotated.delete()

                    if (!log.renameTo(rotated)) {
                        log.delete()
                    }
                }

                log.appendText(
                    "${System.currentTimeMillis()} $message\n"
                )
            }
        }
    }
}
