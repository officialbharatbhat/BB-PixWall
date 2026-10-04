package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.settings.WallpaperTargetMode
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Local, privacy-first taste learning.
 *
 * Phase 1 only COLLECTS behavioral signals.
 * DecisionEngine does not consume these scores yet.
 */
object TasteLearning {

    private const val PREFS = "bb_pixwall_taste_v1"

    private const val QUICK_SKIP_MS = 2L * 60L * 1000L
    private const val RETAINED_MS = 30L * 60L * 1000L

    private const val SAVE_DELTA = 5
    private const val QUICK_SKIP_DELTA = -3
    private const val RETAINED_DELTA = 1

    private const val MIN_SCORE = -24
    private const val MAX_SCORE = 24

    data class Signal(
        val ids: Set<String>,
        val delta: Int,
        val label: String,
    )

    fun currentIds(
        targetMode: WallpaperTargetMode
    ): Set<String> {
        WallpaperFiles.ensure()

        val files = when (targetMode) {
            WallpaperTargetMode.HOME ->
                listOf(WallpaperFiles.currentHome)

            WallpaperTargetMode.LOCK ->
                listOf(WallpaperFiles.currentLock)

            WallpaperTargetMode.BOTH_SAME,
            WallpaperTargetMode.BOTH_DIFFERENT ->
                listOf(
                    WallpaperFiles.currentHome,
                    WallpaperFiles.currentLock,
                )
        }

        return files.mapNotNull(::candidateId)
            .toSet()
    }

    /**
     * Call after a successful wallpaper apply.
     * Stores the moment each newly active candidate became current.
     */
    @Synchronized
    fun recordAppliedCurrent(
        context: Context,
        targetMode: WallpaperTargetMode,
    ) {
        val ids = currentIds(targetMode)
        if (ids.isEmpty()) return

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        val edit = prefs.edit()

        ids.forEach { id ->
            val key = token(id)
            edit.putLong("applied_$key", now)
            edit.putString("id_$key", id.take(220))
        }

        edit.apply()

        RuntimeStatus.set(
            context,
            "taste_active",
            "${ids.size} candidate(s)"
        )
        RuntimeStatus.setLong(
            context,
            "taste_last_applied_at",
            now
        )
    }

    /**
     * Evaluate wallpapers that were active immediately before
     * a MANUAL Next Wall action successfully replaced them.
     *
     * Automatic scheduled changes must never call this.
     */
    @Synchronized
    fun recordManualAdvance(
        context: Context,
        previousIds: Set<String>,
    ): Signal? {
        if (previousIds.isEmpty()) return null

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        val now = System.currentTimeMillis()

        var quickCount = 0
        var retainedCount = 0
        var changedCount = 0

        previousIds.forEach { id ->
            val key = token(id)
            val appliedAt = prefs.getLong("applied_$key", 0L)

            if (appliedAt <= 0L) return@forEach

            val age = (now - appliedAt).coerceAtLeast(0L)

            when {
                age <= QUICK_SKIP_MS -> {
                    adjust(
                        context = context,
                        id = id,
                        delta = QUICK_SKIP_DELTA,
                        event = "quick_skip",
                    )
                    quickCount++
                    changedCount++
                }

                age >= RETAINED_MS -> {
                    adjust(
                        context = context,
                        id = id,
                        delta = RETAINED_DELTA,
                        event = "retained",
                    )
                    retainedCount++
                    changedCount++
                }
            }
        }

        if (changedCount == 0) {
            RuntimeStatus.set(
                context,
                "taste_last_signal",
                "Manual next • neutral"
            )
            RuntimeStatus.setLong(
                context,
                "taste_last_signal_at",
                now
            )
            return Signal(
                ids = previousIds,
                delta = 0,
                label = "neutral",
            )
        }

        val label = buildString {
            if (quickCount > 0) {
                append("quick-skip=$quickCount")
            }
            if (retainedCount > 0) {
                if (isNotEmpty()) append(" • ")
                append("retained=$retainedCount")
            }
        }

        RuntimeStatus.set(
            context,
            "taste_last_signal",
            label
        )
        RuntimeStatus.setLong(
            context,
            "taste_last_signal_at",
            now
        )

        return Signal(
            ids = previousIds,
            delta = when {
                quickCount > 0 && retainedCount == 0 ->
                    QUICK_SKIP_DELTA

                retainedCount > 0 && quickCount == 0 ->
                    RETAINED_DELTA

                else -> 0
            },
            label = label,
        )
    }

    /**
     * Saving means the user explicitly valued the current wallpaper.
     */
    @Synchronized
    fun recordSaveCurrent(
        context: Context,
        targetMode: WallpaperTargetMode,
    ): Signal? {
        val ids = currentIds(targetMode)
        if (ids.isEmpty()) return null

        ids.forEach { id ->
            adjust(
                context = context,
                id = id,
                delta = SAVE_DELTA,
                event = "save",
            )
        }

        val now = System.currentTimeMillis()

        RuntimeStatus.set(
            context,
            "taste_last_signal",
            "Save +$SAVE_DELTA • ${ids.size} candidate(s)"
        )
        RuntimeStatus.setLong(
            context,
            "taste_last_signal_at",
            now
        )

        return Signal(
            ids = ids,
            delta = SAVE_DELTA,
            label = "save",
        )
    }

    fun score(
        context: Context,
        candidateId: String,
    ): Int {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        return prefs.getInt(
            "score_${token(candidateId)}",
            0,
        )
    }

    fun eventCount(
        context: Context,
        candidateId: String,
        event: String,
    ): Int {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        return prefs.getInt(
            "${event}_${token(candidateId)}",
            0,
        )
    }

    @Synchronized
    private fun adjust(
        context: Context,
        id: String,
        delta: Int,
        event: String,
    ) {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        val key = token(id)
        val oldScore = prefs.getInt("score_$key", 0)

        val newScore = (oldScore + delta)
            .coerceIn(MIN_SCORE, MAX_SCORE)

        val count = prefs.getInt(
            "${event}_$key",
            0,
        ) + 1

        prefs.edit()
            .putInt("score_$key", newScore)
            .putInt("${event}_$key", count)
            .putString("id_$key", id.take(220))
            .putLong("event_at_$key", System.currentTimeMillis())
            .apply()

        RuntimeStatus.set(
            context,
            "taste_last_score",
            "$oldScore → $newScore"
        )
        RuntimeStatus.set(
            context,
            "taste_last_event",
            event
        )
        RuntimeStatus.set(
            context,
            "taste_last_id",
            id.take(180)
        )
    }

    private fun candidateId(file: File): String? {
        if (!file.exists()) return null

        val meta = File(file.absolutePath + ".meta")
        if (!meta.exists()) return null

        return runCatching {
            Properties().apply {
                meta.inputStream().use(::load)
            }
                .getProperty("id")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    private fun token(id: String): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))

        return digest
            .take(12)
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
