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

    private const val HARD_SKIP_MS = 30L * 1000L
    private const val MEDIUM_SKIP_MS = 90L * 1000L
    private const val LIGHT_SKIP_MS = 3L * 60L * 1000L

    private const val RETAINED_MS = 30L * 60L * 1000L
    private const val STRONG_RETAINED_MS = 2L * 60L * 60L * 1000L
    private const val VERY_STRONG_RETAINED_MS = 12L * 60L * 60L * 1000L

    private const val RAPID_NEXT_CHAIN_MS = 8L * 1000L

    private const val SAVE_DELTA = 5
    private const val HARD_SKIP_DELTA = -5
    private const val MEDIUM_SKIP_DELTA = -3
    private const val LIGHT_SKIP_DELTA = -1

    private const val RETAINED_DELTA = 1
    private const val STRONG_RETAINED_DELTA = 2
    private const val VERY_STRONG_RETAINED_DELTA = 3

    /*
     * Exact-ID taste slowly fades toward neutral.
     * One point every 14 days after the last meaningful signal.
     */
    private const val DECAY_STEP_MS =
        14L * 24L * 60L * 60L * 1000L

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

        val previousManualAt =
            prefs.getLong(
                "last_manual_advance_at",
                0L,
            )

        val rapidChain =
            previousManualAt > 0L &&
                now - previousManualAt <= RAPID_NEXT_CHAIN_MS

        var skipCount = 0
        var retainedCount = 0
        var neutralCount = 0
        var rapidDampenedCount = 0
        var changedCount = 0

        var strongestNegative = 0
        var strongestPositive = 0
        var lastAge = 0L
        var lastDelta = 0

        previousIds.forEach { id ->
            val key = token(id)
            val appliedAt =
                prefs.getLong(
                    "applied_$key",
                    0L,
                )

            if (appliedAt <= 0L) {
                return@forEach
            }

            val age =
                (now - appliedAt)
                    .coerceAtLeast(0L)

            lastAge = age

            val baseDelta =
                when {
                    age <= HARD_SKIP_MS ->
                        HARD_SKIP_DELTA

                    age <= MEDIUM_SKIP_MS ->
                        MEDIUM_SKIP_DELTA

                    age <= LIGHT_SKIP_MS ->
                        LIGHT_SKIP_DELTA

                    age >= VERY_STRONG_RETAINED_MS ->
                        VERY_STRONG_RETAINED_DELTA

                    age >= STRONG_RETAINED_MS ->
                        STRONG_RETAINED_DELTA

                    age >= RETAINED_MS ->
                        RETAINED_DELTA

                    else ->
                        0
                }

            val delta =
                if (
                    rapidChain &&
                    baseDelta < 0
                ) {
                    maxOf(
                        baseDelta,
                        -1,
                    )
                } else {
                    baseDelta
                }

            if (
                rapidChain &&
                baseDelta < -1 &&
                delta == -1
            ) {
                rapidDampenedCount++
            }

            when {
                delta < 0 -> {
                    val event =
                        when {
                            age <= HARD_SKIP_MS ->
                                "skip_hard"

                            age <= MEDIUM_SKIP_MS ->
                                "skip_medium"

                            else ->
                                "skip_light"
                        }

                    adjust(
                        context = context,
                        id = id,
                        delta = delta,
                        event = event,
                    )

                    skipCount++
                    changedCount++
                    strongestNegative =
                        minOf(
                            strongestNegative,
                            delta,
                        )
                }

                delta > 0 -> {
                    val event =
                        when {
                            delta >= VERY_STRONG_RETAINED_DELTA ->
                                "retained_very_strong"

                            delta >= STRONG_RETAINED_DELTA ->
                                "retained_strong"

                            else ->
                                "retained"
                        }

                    adjust(
                        context = context,
                        id = id,
                        delta = delta,
                        event = event,
                    )

                    retainedCount++
                    changedCount++
                    strongestPositive =
                        maxOf(
                            strongestPositive,
                            delta,
                        )
                }

                else -> {
                    neutralCount++
                }
            }

            lastDelta = delta
        }

        prefs.edit()
            .putLong(
                "last_manual_advance_at",
                now,
            )
            .putInt(
                "manual_actions",
                prefs.getInt(
                    "manual_actions",
                    0,
                ) + 1,
            )
            .putInt(
                "neutral_signals",
                prefs.getInt(
                    "neutral_signals",
                    0,
                ) + neutralCount,
            )
            .putInt(
                "rapid_dampened",
                prefs.getInt(
                    "rapid_dampened",
                    0,
                ) + rapidDampenedCount,
            )
            .apply()

        val label =
            buildString {
                if (skipCount > 0) {
                    append("skip=$skipCount")
                }

                if (retainedCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("retained=$retainedCount")
                }

                if (neutralCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("neutral=$neutralCount")
                }

                if (rapidDampenedCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(
                        "rapid-damped=" +
                            rapidDampenedCount
                    )
                }

                if (isEmpty()) {
                    append("neutral")
                }
            }

        RuntimeStatus.set(
            context,
            "taste_last_signal",
            label,
        )

        RuntimeStatus.setLong(
            context,
            "taste_last_signal_at",
            now,
        )

        RuntimeStatus.set(
            context,
            "taste_last_elapsed_ms",
            lastAge.toString(),
        )

        RuntimeStatus.set(
            context,
            "taste_last_delta",
            lastDelta.toString(),
        )

        RuntimeStatus.set(
            context,
            "taste_last_manual_summary",
            "skip=$skipCount • " +
                "retained=$retainedCount • " +
                "neutral=$neutralCount • " +
                "rapid=$rapidDampenedCount",
        )

        return Signal(
            ids = previousIds,
            delta =
                when {
                    strongestNegative < 0 &&
                        strongestPositive == 0 ->
                        strongestNegative

                    strongestPositive > 0 &&
                        strongestNegative == 0 ->
                        strongestPositive

                    else ->
                        0
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

        RuntimeStatus.set(
            context,
            "taste_last_delta",
            SAVE_DELTA.toString(),
        )

        RuntimeStatus.set(
            context,
            "taste_last_manual_summary",
            "save=${ids.size}",
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

        val key = token(candidateId)

        val raw =
            prefs.getInt(
                "score_$key",
                0,
            )

        if (raw == 0) return 0

        val eventAt =
            prefs.getLong(
                "event_at_$key",
                0L,
            )

        if (eventAt <= 0L) return raw

        val age =
            (
                System.currentTimeMillis() -
                    eventAt
            ).coerceAtLeast(0L)

        val steps =
            (age / DECAY_STEP_MS)
                .toInt()

        if (steps <= 0) return raw

        return when {
            raw > 0 ->
                (raw - steps)
                    .coerceAtLeast(0)

            raw < 0 ->
                (raw + steps)
                    .coerceAtMost(0)

            else ->
                0
        }
    }

    fun diagnosticsSummary(
        context: Context,
    ): String {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val hard =
            prefs.getInt(
                "global_skip_hard",
                0,
            )

        val medium =
            prefs.getInt(
                "global_skip_medium",
                0,
            )

        val light =
            prefs.getInt(
                "global_skip_light",
                0,
            )

        val retained =
            prefs.getInt(
                "global_retained",
                0,
            ) +
                prefs.getInt(
                    "global_retained_strong",
                    0,
                ) +
                prefs.getInt(
                    "global_retained_very_strong",
                    0,
                )

        val saves =
            prefs.getInt(
                "global_save",
                0,
            )

        val neutral =
            prefs.getInt(
                "neutral_signals",
                0,
            )

        val rapid =
            prefs.getInt(
                "rapid_dampened",
                0,
            )

        return "save=$saves • " +
            "skip=${hard + medium + light} • " +
            "retained=$retained • " +
            "neutral=$neutral • " +
            "rapid-damped=$rapid • " +
            "best=${prefs.getInt("global_best_score", 0)} • " +
            "worst=${prefs.getInt("global_worst_score", 0)}"
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

        val oldScore =
            score(
                context,
                id,
            )

        val newScore =
            (oldScore + delta)
                .coerceIn(
                    MIN_SCORE,
                    MAX_SCORE,
                )

        val count =
            prefs.getInt(
                "${event}_$key",
                0,
            ) + 1

        val globalEventCount =
            prefs.getInt(
                "global_$event",
                0,
            ) + 1

        val oldBest =
            prefs.getInt(
                "global_best_score",
                0,
            )

        val oldWorst =
            prefs.getInt(
                "global_worst_score",
                0,
            )

        val bestScore =
            maxOf(
                oldBest,
                newScore,
            )

        val worstScore =
            minOf(
                oldWorst,
                newScore,
            )

        val edit =
            prefs.edit()
                .putInt(
                    "score_$key",
                    newScore,
                )
                .putInt(
                    "${event}_$key",
                    count,
                )
                .putInt(
                    "global_$event",
                    globalEventCount,
                )
                .putInt(
                    "global_best_score",
                    bestScore,
                )
                .putInt(
                    "global_worst_score",
                    worstScore,
                )
                .putString(
                    "id_$key",
                    id.take(220),
                )
                .putLong(
                    "event_at_$key",
                    System.currentTimeMillis(),
                )

        if (newScore >= oldBest) {
            edit.putString(
                "global_best_id",
                id.take(220),
            )
        }

        if (newScore <= oldWorst) {
            edit.putString(
                "global_worst_id",
                id.take(220),
            )
        }

        edit.apply()

        WallpaperStyleLearning.recordSignal(
            context = context,
            candidateId = id,
            delta = delta,
            event = event,
        )

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
