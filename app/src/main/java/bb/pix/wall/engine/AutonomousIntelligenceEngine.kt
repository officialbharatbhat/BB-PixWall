package bb.pix.wall.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.os.StatFs
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.WallpaperTargetMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.Locale
import java.util.Properties
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mega Phase 3 autonomous coordinator.
 *
 * Responsibilities:
 * - self-heal / watchdog / crash journal
 * - source trust + circuit breaker
 * - storage pressure
 * - Wallpaper DNA + visual families
 * - transition/fatigue/diversity intelligence
 * - Home/Lock role intelligence
 * - context confidence + smoothing
 * - taste confidence
 * - explainability + decision traces
 *
 * Selection hot-path:
 * - NO bitmap decoding
 * - NO network I/O
 * - reads only already-persisted Visual Intelligence profiles
 */
object AutonomousIntelligenceEngine {

    private const val PREFS =
        "bb_pixwall_autonomous_v3"

    private const val FAMILY_HISTORY =
        14

    private const val STALE_TEMP_MS =
        15L * 60L * 1000L

    private const val CRASH_JOURNAL_MS =
        2L * 60L * 1000L

    private const val CIRCUIT_BASE_MS =
        5L * 60L * 1000L

    data class Decision(
        val influence: Int,
        val family: String,
        val dna: String,
        val reason: String,
        val fatigue: Int,
        val homeRole: Int,
        val lockRole: Int,
        val entropy: Int,
    )

    data class AuditReport(
        val storageReady: Boolean,
        val invalid: Int,
        val stale: Int,
        val orphanMeta: Int,
        val pressureTrimmed: Int,
        val nextReady: Boolean,
        val cacheCount: Int,
        val engineScore: Int,
        val grade: String,
        val action: String,
    )

    data class SourceState(
        val trust: Int,
        val successes: Int,
        val failures: Int,
        val consecutiveFailures: Int,
        val latencyEwmaMs: Long,
        val circuitUntil: Long,
        val grade: String,
    )

    // --------------------------------------------------------
    // SOURCE RESILIENCE
    // --------------------------------------------------------

    fun sourceAllowed(
        context: Context,
        source: String,
    ): Boolean {
        val key =
            sourceKey(source)

        val prefs =
            prefs(context)

        val until =
            prefs.getLong(
                "source_${key}_circuit_until",
                0L,
            )

        val allowed =
            System.currentTimeMillis() >=
                until

        if (!allowed) {
            RuntimeStatus.set(
                context,
                "source_${key}_autonomy",
                "Circuit open until " +
                    formatAge(
                        until -
                            System.currentTimeMillis()
                    ),
            )
        }

        return allowed
    }

    @Synchronized
    fun recordSourceSuccess(
        context: Context,
        source: String,
        latencyMs: Long,
        itemCount: Int,
    ) {
        val key =
            sourceKey(source)

        val p =
            prefs(context)

        val successes =
            p.getInt(
                "source_${key}_success",
                0,
            ) + 1

        val failures =
            p.getInt(
                "source_${key}_failure",
                0,
            )

        val oldEwma =
            p.getLong(
                "source_${key}_latency_ewma",
                latencyMs,
            )

        val ewma =
            if (successes <= 1) {
                latencyMs
            } else {
                (
                    oldEwma *
                        0.82 +
                        latencyMs *
                            0.18
                    ).toLong()
            }

        p.edit()
            .putInt(
                "source_${key}_success",
                successes,
            )
            .putInt(
                "source_${key}_consecutive_fail",
                0,
            )
            .putLong(
                "source_${key}_latency_ewma",
                ewma,
            )
            .putLong(
                "source_${key}_last_ok",
                System.currentTimeMillis(),
            )
            .putInt(
                "source_${key}_last_count",
                itemCount,
            )
            .putLong(
                "source_${key}_circuit_until",
                0L,
            )
            .apply()

        publishSourceState(
            context,
            key,
            sourceState(
                context,
                key,
            ),
        )

        RuntimeStatus.set(
            context,
            "source_resilience_last",
            "$source recovered/healthy • ${ewma}ms",
        )
    }

    @Synchronized
    fun recordSourceFailure(
        context: Context,
        source: String,
        latencyMs: Long,
        detail: String,
    ) {
        val key =
            sourceKey(source)

        val p =
            prefs(context)

        val failures =
            p.getInt(
                "source_${key}_failure",
                0,
            ) + 1

        val consecutive =
            p.getInt(
                "source_${key}_consecutive_fail",
                0,
            ) + 1

        val oldEwma =
            p.getLong(
                "source_${key}_latency_ewma",
                latencyMs,
            )

        val ewma =
            if (oldEwma <= 0L) {
                latencyMs
            } else {
                (
                    oldEwma *
                        0.75 +
                        latencyMs *
                            0.25
                    ).toLong()
            }

        val circuitUntil =
            if (consecutive >= 4) {
                val multiplier =
                    (
                        consecutive -
                            3
                        )
                        .coerceIn(
                            1,
                            6,
                        )

                System.currentTimeMillis() +
                    (
                        CIRCUIT_BASE_MS *
                            multiplier
                        )
                        .coerceAtMost(
                            30L *
                                60L *
                                1000L
                        )
            } else {
                p.getLong(
                    "source_${key}_circuit_until",
                    0L,
                )
            }

        p.edit()
            .putInt(
                "source_${key}_failure",
                failures,
            )
            .putInt(
                "source_${key}_consecutive_fail",
                consecutive,
            )
            .putLong(
                "source_${key}_latency_ewma",
                ewma,
            )
            .putLong(
                "source_${key}_last_fail",
                System.currentTimeMillis(),
            )
            .putLong(
                "source_${key}_circuit_until",
                circuitUntil,
            )
            .putString(
                "source_${key}_last_error",
                detail.take(300),
            )
            .apply()

        publishSourceState(
            context,
            key,
            sourceState(
                context,
                key,
            ),
        )

        if (
            circuitUntil >
                System.currentTimeMillis()
        ) {
            RuntimeStatus.set(
                context,
                "source_resilience_last",
                "$source circuit opened • fail streak=$consecutive",
            )
        }
    }

    fun sourceState(
        context: Context,
        source: String,
    ): SourceState {
        val key =
            sourceKey(source)

        val p =
            prefs(context)

        val successes =
            p.getInt(
                "source_${key}_success",
                0,
            )

        val failures =
            p.getInt(
                "source_${key}_failure",
                0,
            )

        val consecutive =
            p.getInt(
                "source_${key}_consecutive_fail",
                0,
            )

        val latency =
            p.getLong(
                "source_${key}_latency_ewma",
                0L,
            )

        val circuit =
            p.getLong(
                "source_${key}_circuit_until",
                0L,
            )

        var trust = 70

        if (
            successes +
                failures >
                0
        ) {
            val ratio =
                successes.toFloat() /
                    (
                        successes +
                            failures
                        )
                        .toFloat()

            trust =
                (
                    ratio *
                        70f +
                        30f
                    )
                    .roundToInt()
        }

        trust -=
            (
                consecutive *
                    9
                )
                .coerceAtMost(
                    36
                )

        when {
            latency in 1L..1500L ->
                trust += 8

            latency > 6000L ->
                trust -= 12

            latency > 4000L ->
                trust -= 7
        }

        if (
            circuit >
                System.currentTimeMillis()
        ) {
            trust -= 30
        }

        trust =
            trust.coerceIn(
                0,
                100,
            )

        val grade =
            when {
                circuit >
                    System.currentTimeMillis() ->
                    "CIRCUIT"

                trust >= 90 ->
                    "A+"

                trust >= 80 ->
                    "A"

                trust >= 68 ->
                    "B"

                trust >= 52 ->
                    "C"

                else ->
                    "D"
            }

        return SourceState(
            trust = trust,
            successes = successes,
            failures = failures,
            consecutiveFailures =
                consecutive,
            latencyEwmaMs =
                latency,
            circuitUntil =
                circuit,
            grade = grade,
        )
    }

    fun resetSourceReputation(
        context: Context,
        source: String,
    ) {
        val key =
            sourceKey(source)

        val p =
            prefs(context)

        p.edit()
            .remove("source_${key}_success")
            .remove("source_${key}_failure")
            .remove("source_${key}_consecutive_fail")
            .remove("source_${key}_latency_ewma")
            .remove("source_${key}_last_ok")
            .remove("source_${key}_last_fail")
            .remove("source_${key}_last_count")
            .remove("source_${key}_last_error")
            .remove("source_${key}_circuit_until")
            .apply()

        publishSourceState(
            context,
            key,
            sourceState(
                context,
                key,
            ),
        )

        RuntimeStatus.set(
            context,
            "source_resilience_last",
            "$source reputation reset",
        )
    }

    private fun publishSourceState(
        context: Context,
        key: String,
        state: SourceState,
    ) {
        RuntimeStatus.set(
            context,
            "source_${key}_trust",
            "${state.trust}/100 • ${state.grade}",
        )

        RuntimeStatus.set(
            context,
            "source_${key}_resilience",
            "ok=${state.successes} • " +
                "fail=${state.failures} • " +
                "streak=${state.consecutiveFailures} • " +
                "ewma=${state.latencyEwmaMs}ms",
        )
    }

    // --------------------------------------------------------
    // WALLPAPER DNA / FAMILY / TRANSITIONS
    // --------------------------------------------------------

    fun decision(
        context: Context,
        settings: AppSettings,
        candidateId: String,
        source: String,
        visual: VisualIntelligenceEngine.Profile?,
    ): Decision? {
        visual ?: return null

        val p =
            prefs(context)

        val family =
            family(visual)

        val dna =
            dna(visual)

        val recent =
            recentFamilies(
                context
            )

        val fatigue =
            recent.count {
                it ==
                    family
            }

        val sameStyle =
            recent.count {
                it.substringBefore(':') ==
                    visual.styleClass
            }

        val entropy =
            visualEntropy(
                visual
            )

        val homeRole =
            homeRoleScore(
                visual
            )

        val lockRole =
            lockRoleScore(
                visual
            )

        var score = 0

        val reasons =
            mutableListOf<String>()

        // 1. Visual family fatigue.
        if (fatigue > 0) {
            val penalty =
                (
                    fatigue *
                        2
                    )
                    .coerceAtMost(
                        8
                    )

            score -= penalty

            reasons +=
                "family fatigue -$penalty"
        } else {
            score += 2
            reasons +=
                "fresh family +2"
        }

        // 2. Aesthetic drift guard.
        if (sameStyle >= 4) {
            score -= 3
            reasons +=
                "style drift guard -3"
        }

        // 3. Recent current-wall comparison.
        val currentProfiles =
            currentProfiles(
                context
            )

        if (currentProfiles.isNotEmpty()) {
            val brightnessDiff =
                currentProfiles
                    .map {
                        abs(
                            visual.brightness -
                                it.brightness
                        )
                    }
                    .minOrNull()
                    ?: 0f

            when {
                brightnessDiff >= .62f -> {
                    score -= 6
                    reasons +=
                        "brightness shock -6"
                }

                brightnessDiff >= .48f -> {
                    score -= 4
                    reasons +=
                        "brightness shock -4"
                }

                brightnessDiff >= .36f -> {
                    score -= 2
                    reasons +=
                        "brightness transition -2"
                }
            }

            val paletteDistance =
                currentProfiles
                    .map {
                        paletteDistance(
                            visual.palette,
                            it.palette,
                        )
                    }
                    .minOrNull()
                    ?: .5f

            when {
                paletteDistance >= .80f -> {
                    score -= 3
                    reasons +=
                        "color shock -3"
                }

                paletteDistance in
                    .28f.. .67f -> {
                    score += 1
                    reasons +=
                        "palette flow +1"
                }
            }

            val nearClone =
                currentProfiles.any {
                    visual.styleClass ==
                        it.styleClass &&
                        visual.dominantHue ==
                        it.dominantHue &&
                        abs(
                            visual.brightness -
                                it.brightness
                        ) <= .08f &&
                        abs(
                            visual.saturation -
                                it.saturation
                        ) <= .09f &&
                        abs(
                            visual.contrast -
                                it.contrast
                        ) <= .10f
                }

            if (nearClone) {
                score -= 5
                reasons +=
                    "near-clone family -5"
            }
        }

        // 4. Entropy sweet spot.
        when {
            entropy in 28..72 -> {
                score += 2
                reasons +=
                    "balanced entropy +2"
            }

            entropy >= 90 -> {
                score -= 2
                reasons +=
                    "visual overload -2"
            }
        }

        // 5. Role-aware intelligence.
        val roleScore =
            when (
                settings.targetMode
            ) {
                WallpaperTargetMode.HOME ->
                    homeRole

                WallpaperTargetMode.LOCK ->
                    lockRole

                WallpaperTargetMode.BOTH_SAME ->
                    (
                        homeRole +
                            lockRole
                        ) / 2

                WallpaperTargetMode.BOTH_DIFFERENT ->
                    (
                        homeRole +
                            lockRole
                        ) / 2
            }

        when {
            roleScore >= 86 -> {
                score += 3
                reasons +=
                    "screen-role fit +3"
            }

            roleScore < 48 -> {
                score -= 3
                reasons +=
                    "screen-role fit -3"
            }
        }

        // 6. Favorite is a super-signal.
        if (
            candidateId in
                favoriteCandidateIds()
        ) {
            score += 4

            reasons +=
                "favorite super-signal +4"
        }

        // 7. Never-show family gives a mild family-level negative.
        if (
            family in
                blockedFamilies(
                    context
                )
        ) {
            score -= 2

            reasons +=
                "blocked-family memory -2"
        }

        // 8. Source reputation.
        if (
            source.isNotBlank() &&
            source.lowercase(
                Locale.US
            ) != "cache"
        ) {
            val trust =
                sourceState(
                    context,
                    source,
                ).trust

            when {
                trust >= 88 -> {
                    score += 2
                    reasons +=
                        "trusted source +2"
                }

                trust < 45 -> {
                    score -= 3
                    reasons +=
                        "weak source trust -3"
                }
            }
        }

        // 9. Exploration / exploitation.
        val tasteConfidence =
            tasteConfidence(
                context
            )

        val explorationMode =
            when {
                tasteConfidence < 40 ->
                    "Explore"

                tasteConfidence < 75 ->
                    "Balanced"

                else ->
                    "Exploit"
            }

        if (
            explorationMode ==
                "Explore" &&
            family !in recent
        ) {
            score += 2

            reasons +=
                "exploration +2"
        }

        if (
            explorationMode ==
                "Exploit" &&
            fatigue >= 2
        ) {
            score -= 2

            reasons +=
                "mature diversity -2"
        }

        // 10. Smoothed context biases.
        val conflict =
            p.getBoolean(
                "context_conflict",
                false,
            )

        val contextScale =
            if (conflict) {
                .5f
            } else {
                1f
            }

        val smoothedLux =
            p.getFloat(
                "lux_ewma",
                -1f,
            )

        if (
            smoothedLux >= 0f &&
            smoothedLux < 18f &&
            visual.amoledScore >= 75
        ) {
            val bonus =
                (
                    2f *
                        contextScale
                    )
                    .roundToInt()

            score += bonus

            if (bonus > 0) {
                reasons +=
                    "dark-room AMOLED +$bonus"
            }
        }

        val hour =
            Calendar.getInstance()
                .get(
                    Calendar.HOUR_OF_DAY
                )

        if (
            hour >= 22 ||
            hour <= 5
        ) {
            if (
                visual.amoledScore >=
                    78
            ) {
                val bonus =
                    (
                        2f *
                            contextScale
                        )
                        .roundToInt()

                score += bonus

                if (bonus > 0) {
                    reasons +=
                        "night AMOLED +$bonus"
                }
            }

            if (
                visual.brightness >
                    .78f
            ) {
                score -= 2

                reasons +=
                    "night brightness -2"
            }
        }

        val temp =
            p.getFloat(
                "stable_temp_c",
                Float.NaN,
            )

        if (!temp.isNaN()) {
            if (
                temp >=
                    settings
                        .moodHotTemperatureC
            ) {
                if (
                    visual.dominantHue in
                    setOf(
                        "blue",
                        "cyan",
                        "green",
                    )
                ) {
                    score += 1

                    reasons +=
                        "hot-weather cool palette +1"
                }
            } else if (
                temp <=
                    settings
                        .moodColdTemperatureC
            ) {
                if (
                    visual.warmth >
                        .08f ||
                    visual.dominantHue in
                    setOf(
                        "red",
                        "yellow",
                        "magenta",
                    )
                ) {
                    score += 1

                    reasons +=
                        "cold-weather warm palette +1"
                }
            }
        }

        val influence =
            score.coerceIn(
                -14,
                14,
            )

        return Decision(
            influence = influence,
            family = family,
            dna = dna,
            reason =
                reasons
                    .take(7)
                    .joinToString(
                        ", "
                    )
                    .ifBlank {
                        "balanced autonomous profile"
                    },
            fatigue = fatigue,
            homeRole = homeRole,
            lockRole = lockRole,
            entropy = entropy,
        )
    }

    fun dna(
        profile: VisualIntelligenceEngine.Profile,
    ): String {
        val light =
            when {
                profile.brightness < .28f ->
                    "Dark"

                profile.brightness > .70f ->
                    "Bright"

                else ->
                    "Mid"
            }

        val detail =
            when {
                profile.busyScore <= 28 ->
                    "Clean"

                profile.busyScore >= 78 ->
                    "Busy"

                else ->
                    "Balanced"
            }

        return "${profile.styleClass} • " +
            "${profile.dominantHue} • " +
            "$light • $detail • " +
            "Q${profile.quality} • " +
            "A${profile.amoledScore} • " +
            "R${profile.lockReadability}"
    }

    fun family(
        profile: VisualIntelligenceEngine.Profile,
    ): String {
        val light =
            when {
                profile.brightness <
                    .30f ->
                    "dark"

                profile.brightness >
                    .68f ->
                    "bright"

                else ->
                    "mid"
            }

        return "${profile.styleClass}:" +
            "${profile.dominantHue}:" +
            light
    }

    fun visualEntropy(
        profile: VisualIntelligenceEngine.Profile,
    ): Int =
        (
            profile.edgeDensity *
                45f +
                profile.colorfulness *
                    30f +
                profile.contrast *
                    25f
            )
            .times(
                100f
            )
            .roundToInt()
            .coerceIn(
                0,
                100,
            )

    fun homeRoleScore(
        p: VisualIntelligenceEngine.Profile,
    ): Int =
        (
            (
                100 -
                    p.busyScore
                ) *
                .34f +
                p.cropSafety *
                    .24f +
                p.centerBalance *
                    .17f +
                p.quality *
                    .25f
            )
            .roundToInt()
            .coerceIn(
                0,
                100,
            )

    fun lockRoleScore(
        p: VisualIntelligenceEngine.Profile,
    ): Int =
        (
            p.lockReadability *
                .43f +
                (
                    100 -
                        p.busyScore
                    ) *
                    .18f +
                p.amoledScore *
                    .14f +
                p.quality *
                    .25f
            )
            .roundToInt()
            .coerceIn(
                0,
                100,
            )

    // --------------------------------------------------------
    // APPLY JOURNAL / POST-APPLY LEARNING
    // --------------------------------------------------------

    @Synchronized
    fun beginApply(
        context: Context,
        target: String,
    ) {
        val journal =
            journalFile()

        journal.parentFile
            ?.mkdirs()

        val props =
            Properties().apply {
                setProperty(
                    "active",
                    "true",
                )

                setProperty(
                    "started_at",
                    System.currentTimeMillis()
                        .toString(),
                )

                setProperty(
                    "target",
                    target,
                )

                setProperty(
                    "home_before",
                    candidateId(
                        WallpaperFiles.currentHome
                    ).orEmpty(),
                )

                setProperty(
                    "lock_before",
                    candidateId(
                        WallpaperFiles.currentLock
                    ).orEmpty(),
                )
            }

        runCatching {
            journal.outputStream()
                .use {
                    props.store(
                        it,
                        "BB-PixWall apply journal",
                    )
                }
        }

        RuntimeStatus.set(
            context,
            "apply_journal",
            "Active • $target",
        )
    }

    @Synchronized
    fun completeApply(
        context: Context,
        success: Boolean,
    ) {
        val journal =
            journalFile()

        val props =
            readProperties(
                journal
            )

        props.setProperty(
            "active",
            "false",
        )

        props.setProperty(
            "finished_at",
            System.currentTimeMillis()
                .toString(),
        )

        props.setProperty(
            "result",
            if (success) {
                "success"
            } else {
                "failed"
            },
        )

        runCatching {
            journal.outputStream()
                .use {
                    props.store(
                        it,
                        "BB-PixWall apply journal",
                    )
                }
        }

        RuntimeStatus.set(
            context,
            "apply_journal",
            if (success) {
                "Committed"
            } else {
                "Failed safely"
            },
        )
    }

    fun onApplied(
        context: Context,
        settings: AppSettings,
    ) {
        updateContext(
            context,
            settings,
        )

        val homeId =
            candidateId(
                WallpaperFiles.currentHome
            )

        val lockId =
            candidateId(
                WallpaperFiles.currentLock
            )

        val home =
            homeId?.let {
                VisualIntelligenceEngine
                    .profile(
                        context,
                        it,
                    )
            }

        val lock =
            lockId?.let {
                VisualIntelligenceEngine
                    .profile(
                        context,
                        it,
                    )
            }

        val activeProfiles =
            listOfNotNull(
                home,
                lock,
            )

        if (
            activeProfiles.isNotEmpty()
        ) {
            val families =
                activeProfiles.map(
                    ::family
                )

            families.forEach {
                pushFamily(
                    context,
                    it,
                )
            }

            val primary =
                home ?: lock!!

            RuntimeStatus.set(
                context,
                "wallpaper_dna",
                dna(primary),
            )

            RuntimeStatus.set(
                context,
                "wallpaper_family",
                family(primary),
            )

            RuntimeStatus.set(
                context,
                "wallpaper_entropy",
                visualEntropy(
                    primary
                ).toString(),
            )
        }

        if (
            home != null &&
            lock != null
        ) {
            val pair =
                VisualIntelligenceEngine
                    .pairScore(
                        context,
                        homeId.orEmpty(),
                        lockId.orEmpty(),
                    )

            val story =
                buildString {
                    append(
                        "${home.styleClass} Home + " +
                            "${lock.styleClass} Lock"
                    )

                    append(
                        " • Home icons ${homeRoleScore(home)}"
                    )

                    append(
                        " • Lock readability ${lockRoleScore(lock)}"
                    )

                    if (pair != null) {
                        append(
                            " • harmony $pair"
                        )
                    }

                    val pd =
                        paletteDistance(
                            home.palette,
                            lock.palette,
                        )

                    when {
                        pd < .20f ->
                            append(
                                " • closely related palette"
                            )

                        pd < .62f ->
                            append(
                                " • complementary variation"
                            )

                        else ->
                            append(
                                " • bold contrast"
                            )
                    }
                }

            RuntimeStatus.set(
                context,
                "pair_story",
                story,
            )
        }

        publishTasteState(
            context
        )

        PremiumIntelligenceCenter
            .refresh(
                context,
                "wallpaper-applied",
            )
    }

    // --------------------------------------------------------
    // CONTEXT CONFIDENCE / SMOOTHING
    // --------------------------------------------------------

    fun updateContext(
        context: Context,
        settings: AppSettings,
    ) {
        val p =
            prefs(context)

        val adaptive =
            runCatching {
                AdaptiveMoodEngine.snapshot(
                    context,
                    settings,
                )
            }.getOrNull()

        val weather =
            runCatching {
                WeatherMoodEngine.snapshot(
                    context,
                    settings,
                )
            }.getOrNull()

        var confidence = 100

        val lux =
            adaptive?.lux

        if (
            settings.moodEngineEnabled &&
            settings.moodAmbientLightEnabled &&
            lux == null
        ) {
            confidence -= 20
        }

        if (
            settings.moodWeatherEnabled &&
            (
                weather == null ||
                    !weather.available
                )
        ) {
            confidence -= 20
        }

        if (
            adaptive?.batteryPct ==
                null ||
            adaptive.batteryPct < 0
        ) {
            confidence -= 5
        }

        confidence =
            confidence.coerceIn(
                0,
                100,
            )

        if (lux != null) {
            val old =
                p.getFloat(
                    "lux_ewma",
                    lux,
                )

            val smoothed =
                if (
                    p.contains(
                        "lux_ewma"
                    )
                ) {
                    old *
                        .72f +
                        lux *
                            .28f
                } else {
                    lux
                }

            p.edit()
                .putFloat(
                    "lux_ewma",
                    smoothed,
                )
                .apply()

            RuntimeStatus.set(
                context,
                "context_lux_smoothed",
                "${smoothed.roundToInt()} lux",
            )
        }

        if (
            weather != null &&
            weather.available
        ) {
            val oldCondition =
                p.getString(
                    "weather_observed",
                    "",
                ).orEmpty()

            val currentCondition =
                weather.condition.name

            val streak =
                if (
                    oldCondition ==
                        currentCondition
                ) {
                    p.getInt(
                        "weather_streak",
                        0,
                    ) + 1
                } else {
                    1
                }

            val stable =
                if (streak >= 2) {
                    currentCondition
                } else {
                    p.getString(
                        "weather_stable",
                        currentCondition,
                    ) ?: currentCondition
                }

            val edit =
                p.edit()
                    .putString(
                        "weather_observed",
                        currentCondition,
                    )
                    .putInt(
                        "weather_streak",
                        streak,
                    )
                    .putString(
                        "weather_stable",
                        stable,
                    )

            weather.temperatureC
                ?.let {
                    val oldTemp =
                        p.getFloat(
                            "stable_temp_c",
                            it,
                        )

                    val smoothTemp =
                        if (
                            p.contains(
                                "stable_temp_c"
                            )
                        ) {
                            oldTemp *
                                .75f +
                                it *
                                    .25f
                        } else {
                            it
                        }

                    edit.putFloat(
                        "stable_temp_c",
                        smoothTemp,
                    )

                    RuntimeStatus.set(
                        context,
                        "context_temp_smoothed",
                        String.format(
                            Locale.US,
                            "%.1f°C",
                            smoothTemp,
                        ),
                    )
                }

            edit.apply()

            RuntimeStatus.set(
                context,
                "context_weather_stable",
                "$stable • confidence streak=$streak",
            )
        }

        val conflict =
            if (
                adaptive != null &&
                weather != null &&
                weather.available &&
                lux != null
            ) {
                (
                    lux <
                        settings
                            .moodDarkLuxThreshold &&
                        weather.isDay ==
                            true &&
                        weather.condition ==
                            WeatherMoodEngine
                                .Condition
                                .CLEAR
                    ) ||
                    (
                        lux >
                            settings
                                .moodBrightLuxThreshold &&
                            weather.isDay ==
                                false
                        )
            } else {
                false
            }

        p.edit()
            .putBoolean(
                "context_conflict",
                conflict,
            )
            .putInt(
                "context_confidence",
                confidence,
            )
            .apply()

        RuntimeStatus.set(
            context,
            "context_confidence",
            "$confidence/100",
        )

        RuntimeStatus.set(
            context,
            "context_conflict",
            if (conflict) {
                "Conflict resolved • local light gets priority"
            } else {
                "No meaningful conflict"
            },
        )
    }

    // --------------------------------------------------------
    // TASTE / DIVERSITY
    // --------------------------------------------------------

    fun tasteConfidence(
        context: Context,
    ): Int {
        val style =
            WallpaperStyleLearning
                .profileConfidence(
                    context
                )

        val recent =
            recentFamilies(
                context
            )
                .size

        val activity =
            RuntimeStatus.get(
                context,
                "taste_last_signal",
                "",
            ).let {
                if (
                    it.isBlank()
                ) {
                    0
                } else {
                    12
                }
            }

        return (
            style *
                78f +
                min(
                    recent,
                    FAMILY_HISTORY,
                ) *
                    1.1f +
                activity
            )
            .roundToInt()
            .coerceIn(
                0,
                100,
            )
    }

    fun publishTasteState(
        context: Context,
    ) {
        val confidence =
            tasteConfidence(
                context
            )

        val mode =
            when {
                confidence < 35 ->
                    "Explore"

                confidence < 75 ->
                    "Balanced"

                else ->
                    "Exploit"
            }

        RuntimeStatus.set(
            context,
            "taste_confidence_v2",
            "$confidence/100",
        )

        RuntimeStatus.set(
            context,
            "learning_mode",
            when (mode) {
                "Explore" ->
                    "Explore • favor diversity/new families"

                "Balanced" ->
                    "Balanced • taste + discovery"

                else ->
                    "Exploit • mature taste with diversity guard"
            },
        )

        RuntimeStatus.set(
            context,
            "taste_diagnostics_v2",
            TasteLearning
                .diagnosticsSummary(
                    context
                ),
        )

        RuntimeStatus.set(
            context,
            "diversity_budget",
            when (mode) {
                "Explore" ->
                    "High • 65% discovery"

                "Balanced" ->
                    "Medium • 40% discovery"

                else ->
                    "Guarded • 22% discovery"
            },
        )
    }

    // --------------------------------------------------------
    // SELF-HEAL / WATCHDOG
    // --------------------------------------------------------

    fun auditAndRepair(
        context: Context,
        settings: AppSettings,
        allowNetworkRefill: Boolean,
    ): AuditReport {
        val storageReady =
            WallpaperFiles.ensure()

        if (!storageReady) {
            RuntimeStatus.set(
                context,
                "autonomous_grade",
                "RECOVERING",
            )

            RuntimeStatus.set(
                context,
                "autonomous_health",
                "Storage unavailable",
            )

            return AuditReport(
                storageReady = false,
                invalid = 0,
                stale = 0,
                orphanMeta = 0,
                pressureTrimmed = 0,
                nextReady = false,
                cacheCount = 0,
                engineScore = 15,
                grade = "RECOVERING",
                action = "storage-unavailable",
            )
        }

        var stale = 0

        var orphan = 0

        var invalid = 0

        var trimmed = 0

        val now =
            System.currentTimeMillis()

        // Stale job / partial cleanup.
        maintenanceRoots()
            .forEach { root ->
                root.listFiles()
                    .orEmpty()
                    .forEach { file ->
                        if (
                            file.isFile &&
                            (
                                file.name.endsWith(
                                    ".part"
                                ) ||
                                    file.name.endsWith(
                                        ".commit"
                                    )
                                ) &&
                            now -
                                file.lastModified() >
                                STALE_TEMP_MS
                        ) {
                            if (
                                file.delete()
                            ) {
                                stale++
                            }

                            File(
                                file.absolutePath +
                                    ".meta"
                            ).delete()
                        }
                    }
            }

        // Cache integrity + orphan sidecars.
        listOf(
            WallpaperFiles.hotCache,
            WallpaperFiles.warmCache,
        ).forEach { dir ->
            val files =
                dir.listFiles()
                    .orEmpty()

            files
                .filter {
                    it.isFile &&
                        isImageName(
                            it.name
                        )
                }
                .forEach { file ->
                    if (!validImage(file)) {
                        quarantine(
                            file,
                            "invalid_cache",
                        )

                        invalid++
                    }
                }

            files
                .filter {
                    it.isFile &&
                        it.name.endsWith(
                            ".meta"
                        )
                }
                .forEach { meta ->
                    val base =
                        File(
                            meta.absolutePath
                                .removeSuffix(
                                    ".meta"
                                )
                        )

                    if (!base.exists()) {
                        if (
                            meta.delete()
                        ) {
                            orphan++
                        }
                    }
                }
        }

        // Existing controller integrity checker remains authoritative too.
        invalid +=
            runCatching {
                WallpaperController
                    .verifyCacheIntegrity()
            }.getOrDefault(0)

        // Next queue self-heal.
        listOf(
            WallpaperFiles.nextHome,
            WallpaperFiles.nextLock,
        ).forEach { file ->
            if (
                file.exists() &&
                !validImage(file)
            ) {
                quarantine(
                    file,
                    "invalid_next",
                )

                File(
                    file.absolutePath +
                        ".meta"
                ).delete()

                invalid++
            }
        }

        // Blocked cache purge.
        runCatching {
            WallpaperController
                .purgeBlockedCached(
                    context
                )
        }

        // Crash journal recovery.
        val crashRecovered =
            recoverJournalIfNeeded(
                context
            )

        // Storage pressure controller.
        val freeBefore =
            freeBytes()

        val reserve =
            settings
                .lowStorageReserveMb
                .toLong() *
                1024L *
                1024L

        if (
            freeBefore <
                reserve
        ) {
            val target =
                reserve +
                    128L *
                        1024L *
                        1024L

            val cache =
                buildList<File> {
                    addAll(
                        WallpaperFiles
                            .warmCache
                            .listFiles()
                            ?.toList()
                            .orEmpty()
                    )

                    addAll(
                        WallpaperFiles
                            .hotCache
                            .listFiles()
                            ?.toList()
                            .orEmpty()
                    )
                }
                    .filter { file ->
                        file.isFile &&
                            isImageName(
                                file.name
                            )
                    }
                    .sortedBy { file ->
                        file.lastModified()
                    }

            for (file in cache) {
                if (
                    freeBytes() >=
                        target
                ) {
                    break
                }

                val meta =
                    File(
                        file.absolutePath +
                            ".meta"
                    )

                if (file.delete()) {
                    meta.delete()

                    trimmed++
                }
            }
        }

        val policy =
            Phase1FinalEngine
                .resourcePolicy(
                    context,
                    settings,
                )

        val nextReady =
            runCatching {
                WallpaperController
                    .ensureNext(
                        context,
                        settings,
                        allowNetwork = false,
                    )
            }.getOrDefault(false)

        val cacheCount =
            WallpaperController
                .cacheCount()

        val desiredCache =
            (
                settings.cacheTarget *
                    policy.targetScale
                )
                .roundToInt()
                .coerceIn(
                    2,
                    settings
                        .cacheTarget
                        .coerceAtLeast(2),
                )

        RuntimeStatus.set(
            context,
            "adaptive_cache_target_v3",
            "$desiredCache/${settings.cacheTarget} • ${policy.label}",
        )

        val sourceCircuits =
            listOf(
                "photos",
                "drive",
            ).count {
                !sourceAllowed(
                    context,
                    it,
                )
            }

        var score = 100

        score -=
            invalid
                .coerceAtMost(5) *
                8

        score -=
            sourceCircuits *
                9

        if (!nextReady) {
            score -= 16
        }

        if (
            cacheCount <= 1
        ) {
            score -= 10
        }

        if (
            freeBytes() <
                reserve
        ) {
            score -= 12
        }

        if (crashRecovered) {
            score -= 5
        }

        score =
            score.coerceIn(
                0,
                100,
            )

        val grade =
            when {
                score >= 94 ->
                    "A+"

                score >= 86 ->
                    "A"

                score >= 74 ->
                    "B"

                score >= 58 ->
                    "C"

                else ->
                    "RECOVERING"
            }

        val action =
            buildList {
                if (invalid > 0) {
                    add(
                        "quarantine=$invalid"
                    )
                }

                if (stale > 0) {
                    add(
                        "stale=$stale"
                    )
                }

                if (orphan > 0) {
                    add(
                        "orphan=$orphan"
                    )
                }

                if (trimmed > 0) {
                    add(
                        "pressure-trim=$trimmed"
                    )
                }

                if (crashRecovered) {
                    add(
                        "journal-recovered"
                    )
                }

                if (nextReady) {
                    add(
                        "queue-ready"
                    )
                }

                if (isEmpty()) {
                    add(
                        "clean"
                    )
                }
            }.joinToString(
                " • "
            )

        RuntimeStatus.set(
            context,
            "autonomous_grade",
            "$grade • $score/100",
        )

        RuntimeStatus.set(
            context,
            "autonomous_health",
            "next=$nextReady • cache=$cacheCount • " +
                "invalid=$invalid • stale=$stale • " +
                "orphans=$orphan • trimmed=$trimmed",
        )

        RuntimeStatus.set(
            context,
            "self_heal_last",
            action,
        )

        RuntimeStatus.setLong(
            context,
            "autonomous_audit_at",
            now,
        )

        RuntimeStatus.set(
            context,
            "watchdog_state",
            "Healthy heartbeat • ${System.currentTimeMillis()}",
        )

        RuntimeStatus.set(
            context,
            "storage_pressure",
            storageSummary(
                settings
            ),
        )

        publishTasteState(
            context
        )

        updateContext(
            context,
            settings,
        )

        listOf(
            "photos",
            "drive",
        ).forEach {
            publishSourceState(
                context,
                it,
                sourceState(
                    context,
                    it,
                ),
            )
        }

        if (
            allowNetworkRefill &&
            policy.allowNetworkRefill &&
            cacheCount <
                desiredCache &&
            WallpaperSourceEngine
                .networkAvailable(
                    context
                )
        ) {
            EngineExecutors.io {
                runCatching {
                    WallpaperController
                        .primeCache(
                            context,
                            settings,
                        )
                }

                runCatching {
                    WallpaperController
                        .ensureNext(
                            context,
                            settings,
                            allowNetwork = true,
                        )
                }
            }

            RuntimeStatus.set(
                context,
                "watchdog_refill",
                "Scheduled",
            )
        } else {
            RuntimeStatus.set(
                context,
                "watchdog_refill",
                "Not needed • ${policy.label}",
            )
        }

        PremiumIntelligenceCenter
            .refresh(
                context,
                "autonomous-audit",
            )

        PremiumIntelligenceCenter
            .recordEvent(
                context,
                "HEALTH",
                "$grade • $score/100 • $action",
            )

        return AuditReport(
            storageReady = true,
            invalid = invalid,
            stale = stale,
            orphanMeta = orphan,
            pressureTrimmed = trimmed,
            nextReady = nextReady,
            cacheCount = cacheCount,
            engineScore = score,
            grade = grade,
            action = action,
        )
    }

    fun onBoot(
        context: Context,
        settings: AppSettings,
    ) {
        RuntimeStatus.set(
            context,
            "boot_recovery",
            "Running",
        )

        val report =
            runCatching {
                auditAndRepair(
                    context,
                    settings,
                    allowNetworkRefill =
                        false,
                )
            }.getOrNull()

        RuntimeStatus.set(
            context,
            "boot_recovery",
            if (report != null) {
                "Complete • ${report.grade} • ${report.action}"
            } else {
                "Deferred"
            },
        )
    }

    // --------------------------------------------------------
    // DECISION EXPLAINABILITY
    // --------------------------------------------------------

    fun publishDecisionTrace(
        context: Context,
        topId: String,
        topSource: String,
        topScore: Int,
        topReason: String,
        runnerId: String?,
        runnerScore: Int?,
        runnerReason: String?,
        shadowId: String?,
        shadowInfluence: Int?,
    ) {
        val now =
            System.currentTimeMillis()

        val trace =
            "D-" +
                now.toString(36)
                    .uppercase(
                        Locale.US
                    ) +
                "-" +
                (
                    topId.hashCode() and
                        0xffff
                    )
                    .toString(16)
                    .uppercase(
                        Locale.US
                    )
                    .padStart(
                        4,
                        '0',
                    )

        val gap =
            if (
                runnerScore != null
            ) {
                topScore -
                    runnerScore
            } else {
                99
            }

        val confidence =
            when {
                gap >= 20 ->
                    "High"

                gap >= 8 ->
                    "Medium"

                else ->
                    "Exploratory"
            }

        val breakdown =
            topReason
                .split(" • ")
                .take(12)
                .joinToString(
                    " | "
                )

        val why =
            humanizeReason(
                topReason
            )

        val whyNot =
            if (
                runnerId != null &&
                runnerScore != null
            ) {
                "Runner-up gap +$gap • " +
                    humanizeReason(
                        runnerReason.orEmpty()
                    )
                        .take(220)
            } else {
                "No comparable runner-up"
            }

        RuntimeStatus.set(
            context,
            "decision_trace_id",
            trace,
        )

        RuntimeStatus.set(
            context,
            "decision_confidence_v2",
            "$confidence • gap=$gap",
        )

        RuntimeStatus.set(
            context,
            "decision_breakdown_v2",
            breakdown,
        )

        RuntimeStatus.set(
            context,
            "decision_why_v2",
            "$topSource • $why",
        )

        RuntimeStatus.set(
            context,
            "decision_why_not_runner",
            whyNot,
        )

        RuntimeStatus.set(
            context,
            "shadow_rank",
            when {
                shadowId == null ->
                    "Shadow observer waiting"

                shadowId ==
                    topId ->
                    "Shadow agrees • influence=${shadowInfluence ?: 0}"

                else ->
                    "Shadow prefers ${shadowId.take(50)} • " +
                        "influence=${shadowInfluence ?: 0} • " +
                        "production winner unchanged"
            },
        )

        PremiumIntelligenceCenter
            .recordEvent(
                context,
                "DECISION",
                "$confidence • gap=$gap • $topSource",
            )

        PremiumIntelligenceCenter
            .refresh(
                context,
                "decision-ranked",
            )
    }

    private fun humanizeReason(
        raw: String,
    ): String {
        val out =
            mutableListOf<String>()

        raw.split(" • ")
            .forEach { part ->
                when {
                    part.startsWith(
                        "source+"
                    ) ->
                        out +=
                            "preferred source"

                    part.startsWith(
                        "9:20+"
                    ) ->
                        out +=
                            "native screen fit"

                    part.startsWith(
                        "res+"
                    ) ||
                        part.startsWith(
                            "resolution+"
                        ) ->
                        out +=
                            "high resolution"

                    part.startsWith(
                        "taste+"
                    ) ->
                        out +=
                            "matches learned taste"

                    part.startsWith(
                        "taste-"
                    ) ->
                        out +=
                            "taste penalty"

                    part.startsWith(
                        "style+"
                    ) ->
                        out +=
                            "learned visual style"

                    part.startsWith(
                        "style-"
                    ) ->
                        out +=
                            "style avoidance"

                    part.startsWith(
                        "visual+"
                    ) ->
                        out +=
                            "strong visual composition"

                    part.startsWith(
                        "visual-"
                    ) ->
                        out +=
                            "visual quality penalty"

                    part.startsWith(
                        "autonomy+"
                    ) ->
                        out +=
                            "autonomous diversity/transition fit"

                    part.startsWith(
                        "autonomy-"
                    ) ->
                        out +=
                            "autonomous fatigue/transition guard"

                    part.startsWith(
                        "weather+"
                    ) ->
                        out +=
                            "weather fit"

                    part.startsWith(
                        "adaptive+"
                    ) ->
                        out +=
                            "environment fit"

                    part.startsWith(
                        "diversity+"
                    ) ->
                        out +=
                            "healthy visual variety"

                    part.startsWith(
                        "recent-"
                    ) ->
                        out +=
                            "repeat protection"

                    part.startsWith(
                        "fast+"
                    ) ->
                        out +=
                            "healthy source latency"

                    part.startsWith(
                        "slow-"
                    ) ->
                        out +=
                            "source latency penalty"

                    part.startsWith(
                        "fail-"
                    ) ->
                        out +=
                            "source reliability penalty"
                }
            }

        return out
            .distinct()
            .take(7)
            .joinToString(
                " • "
            )
            .ifBlank {
                "balanced candidate"
            }
    }

    // --------------------------------------------------------
    // ATOMIC APP-OWNED FILE COMMIT
    // --------------------------------------------------------

    fun atomicReplace(
        src: File,
        dest: File,
    ): Boolean {
        if (
            !src.exists() ||
            !src.isFile
        ) {
            return false
        }

        dest.parentFile
            ?.mkdirs()

        val temp =
            File(
                dest.parentFile,
                ".${dest.name}.${System.nanoTime()}.commit",
            )

        return try {
            src.copyTo(
                temp,
                overwrite = true,
            )

            try {
                Files.move(
                    temp.toPath(),
                    dest.toPath(),
                    StandardCopyOption
                        .ATOMIC_MOVE,
                    StandardCopyOption
                        .REPLACE_EXISTING,
                )
            } catch (_: Throwable) {
                Files.move(
                    temp.toPath(),
                    dest.toPath(),
                    StandardCopyOption
                        .REPLACE_EXISTING,
                )
            }

            dest.exists() &&
                dest.length() ==
                src.length()
        } catch (_: Throwable) {
            temp.delete()
            false
        } finally {
            temp.delete()
        }
    }

    // --------------------------------------------------------
    // INTERNAL HELPERS
    // --------------------------------------------------------

    private fun prefs(
        context: Context,
    ) =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    private fun sourceKey(
        value: String,
    ): String =
        value.trim()
            .lowercase(
                Locale.US
            )
            .replace(
                Regex(
                    "[^a-z0-9]+"
                ),
                "_",
            )
            .trim('_')
            .ifBlank {
                "unknown"
            }

    private fun currentProfiles(
        context: Context,
    ): List<VisualIntelligenceEngine.Profile> =
        listOf(
            WallpaperFiles.currentHome,
            WallpaperFiles.currentLock,
        )
            .mapNotNull { file ->
                candidateId(file)
                    ?.let {
                        VisualIntelligenceEngine
                            .profile(
                                context,
                                it,
                            )
                    }
            }

    private fun favoriteCandidateIds(): Set<String> {
        WallpaperFiles.ensure()

        return WallpaperFiles
            .favorites
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(
                        ".library"
                    )
            }
            .mapNotNull { file ->
                runCatching {
                    Properties().apply {
                        file.inputStream()
                            .use(::load)
                    }.getProperty(
                        "candidate_id"
                    )
                        ?.trim()
                }.getOrNull()
            }
            .filter {
                it.isNotBlank()
            }
            .toSet()
    }

    private fun blockedFamilies(
        context: Context,
    ): Set<String> {
        val ids =
            WallpaperLibrary
                .blockedIds()

        if (ids.isEmpty()) {
            return emptySet()
        }

        return VisualIntelligenceEngine
            .profiles(
                context,
                ids,
            )
            .values
            .map(
                ::family
            )
            .toSet()
    }

    private fun recentFamilies(
        context: Context,
    ): List<String> {
        val p =
            prefs(context)

        return (
            0 until
                FAMILY_HISTORY
            )
            .mapNotNull {
                p.getString(
                    "recent_family_$it",
                    null,
                )
                    ?.takeIf(
                        String::isNotBlank
                    )
            }
    }

    @Synchronized
    private fun pushFamily(
        context: Context,
        family: String,
    ) {
        val p =
            prefs(context)

        val current =
            recentFamilies(
                context
            )

        val next =
            (
                listOf(
                    family
                ) +
                    current
                )
                .take(
                    FAMILY_HISTORY
                )

        val edit =
            p.edit()

        for (
            i in
            0 until
                FAMILY_HISTORY
        ) {
            if (
                i <
                    next.size
            ) {
                edit.putString(
                    "recent_family_$i",
                    next[i],
                )
            } else {
                edit.remove(
                    "recent_family_$i"
                )
            }
        }

        edit.apply()

        val fatigue =
            next.count {
                it ==
                    family
            }

        RuntimeStatus.set(
            context,
            "family_fatigue",
            "$family • recent=$fatigue/$FAMILY_HISTORY",
        )
    }

    private fun paletteDistance(
        first: String,
        second: String,
    ): Float {
        val a =
            firstColor(
                first
            )
                ?: return .5f

        val b =
            firstColor(
                second
            )
                ?: return .5f

        val dr =
            (
                a[0] -
                    b[0]
                ) /
                255f

        val dg =
            (
                a[1] -
                    b[1]
                ) /
                255f

        val db =
            (
                a[2] -
                    b[2]
                ) /
                255f

        return (
            sqrt(
                (
                    dr *
                        dr +
                        dg *
                            dg +
                        db *
                            db
                    ).toDouble()
            ) /
                sqrt(3.0)
            )
            .toFloat()
            .coerceIn(
                0f,
                1f,
            )
    }

    private fun firstColor(
        palette: String,
    ): IntArray? {
        val raw =
            Regex(
                """#[0-9A-Fa-f]{6}"""
            )
                .find(
                    palette
                )
                ?.value
                ?: return null

        return runCatching {
            intArrayOf(
                raw.substring(
                    1,
                    3,
                ).toInt(16),
                raw.substring(
                    3,
                    5,
                ).toInt(16),
                raw.substring(
                    5,
                    7,
                ).toInt(16),
            )
        }.getOrNull()
    }

    private fun candidateId(
        file: File,
    ): String? {
        if (!file.exists()) {
            return null
        }

        val meta =
            File(
                file.absolutePath +
                    ".meta"
            )

        if (!meta.exists()) {
            return null
        }

        return runCatching {
            Properties().apply {
                meta.inputStream()
                    .use(::load)
            }
                .getProperty(
                    "id"
                )
                ?.trim()
                ?.takeIf(
                    String::isNotEmpty
                )
        }.getOrNull()
    }

    private fun validImage(
        file: File,
    ): Boolean {
        if (
            !file.exists() ||
            !file.isFile ||
            file.length() <= 0L
        ) {
            return false
        }

        val bounds =
            BitmapFactory.Options()
                .apply {
                    inJustDecodeBounds =
                        true
                }

        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                bounds,
            )

            bounds.outWidth > 0 &&
                bounds.outHeight > 0
        }.getOrDefault(false)
    }

    private fun quarantine(
        file: File,
        reason: String,
    ) {
        if (!file.exists()) {
            return
        }

        WallpaperFiles
            .quarantine
            .mkdirs()

        val target =
            File(
                WallpaperFiles.quarantine,
                "${reason}_${System.currentTimeMillis()}_${file.name}.bad",
            )

        runCatching {
            if (
                !file.renameTo(
                    target
                )
            ) {
                file.copyTo(
                    target,
                    overwrite = true,
                )

                file.delete()
            }
        }

        File(
            file.absolutePath +
                ".meta"
        ).delete()
    }

    private fun maintenanceRoots():
        List<File> =
        listOf(
            WallpaperFiles.backup,
            WallpaperFiles.hotCache,
            WallpaperFiles.warmCache,
            WallpaperFiles.queue,
            WallpaperFiles.legacyQueue,
        )

    private fun isImageName(
        name: String,
    ): Boolean =
        name.substringAfterLast(
            '.',
            ""
        )
            .lowercase(
                Locale.US
            ) in
            setOf(
                "jpg",
                "jpeg",
                "png",
                "webp",
                "avif",
            )

    private fun journalFile():
        File =
        File(
            WallpaperFiles.logs,
            "autonomous_apply_journal.properties",
        )

    private fun readProperties(
        file: File,
    ): Properties =
        runCatching {
            Properties().apply {
                if (
                    file.exists()
                ) {
                    file.inputStream()
                        .use(::load)
                }
            }
        }.getOrDefault(
            Properties()
        )

    private fun recoverJournalIfNeeded(
        context: Context,
    ): Boolean {
        val file =
            journalFile()

        if (!file.exists()) {
            return false
        }

        val props =
            readProperties(
                file
            )

        if (
            !props.getProperty(
                "active",
                "false",
            ).toBoolean()
        ) {
            return false
        }

        val started =
            props.getProperty(
                "started_at",
                "0",
            )
                .toLongOrNull()
                ?: 0L

        if (
            started <= 0L ||
            System.currentTimeMillis() -
                started <
                CRASH_JOURNAL_MS
        ) {
            return false
        }

        props.setProperty(
            "active",
            "false",
        )

        props.setProperty(
            "result",
            "recovered-after-interruption",
        )

        props.setProperty(
            "recovered_at",
            System.currentTimeMillis()
                .toString(),
        )

        runCatching {
            file.outputStream()
                .use {
                    props.store(
                        it,
                        "BB-PixWall recovered journal",
                    )
                }
        }

        RuntimeStatus.increment(
            context,
            "crash_recovery_count",
        )

        RuntimeStatus.set(
            context,
            "crash_recovery",
            "Interrupted apply detected • app-owned state verified",
        )

        return true
    }

    private fun freeBytes():
        Long =
        runCatching {
            StatFs(
                WallpaperFiles
                    .root
                    .absolutePath
            ).availableBytes
        }.getOrDefault(
            Long.MAX_VALUE
        )

    private fun storageSummary(
        settings: AppSettings,
    ): String {
        val free =
            freeBytes()

        val reserve =
            settings
                .lowStorageReserveMb
                .toLong() *
                1024L *
                1024L

        return when {
            free ==
                Long.MAX_VALUE ->
                "Unknown"

            free <
                reserve ->
                "PRESSURE • free=${humanStorage(free)} • reserve=${settings.lowStorageReserveMb}MB"

            free <
                reserve *
                    2 ->
                "WATCH • free=${humanStorage(free)}"

            else ->
                "Healthy • free=${humanStorage(free)}"
        }
    }

    private fun humanStorage(
        value: Long,
    ): String =
        when {
            value >=
                1024L *
                    1024L *
                    1024L ->
                String.format(
                    Locale.US,
                    "%.1f GB",
                    value.toDouble() /
                        (
                            1024.0 *
                                1024.0 *
                                1024.0
                            ),
                )

            value >=
                1024L *
                    1024L ->
                String.format(
                    Locale.US,
                    "%.0f MB",
                    value.toDouble() /
                        (
                            1024.0 *
                                1024.0
                            ),
                )

            else ->
                "${value / 1024L} KB"
        }

    private fun mb(
        value: Long,
    ): Long =
        value /
            (
                1024L *
                    1024L
                )

    private fun formatAge(
        ms: Long,
    ): String {
        if (ms <= 0L) {
            return "now"
        }

        val seconds =
            ms /
                1000L

        return when {
            seconds < 60 ->
                "${seconds}s"

            seconds < 3600 ->
                "${seconds / 60}m"

            else ->
                "${seconds / 3600}h"
        }
    }
}
