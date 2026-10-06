package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.WallpaperOrder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Lightweight, zero-network ranking layer.
 *
 * Important:
 * - Does NOT download/probe candidates.
 * - Unknown cloud dimensions remain neutral.
 * - 9:20 processing still belongs to WallpaperController.
 * - Explicit A-Z/date/size ordering is never overridden.
 */
object DecisionEngine {

    data class Ranked(
        val candidate: WallpaperSourceEngine.Candidate,
        val score: Int,
        val reason: String,
    )

    private data class SourceHealth(
        val failStreak: Int,
        val latencyMs: Long,
        val backoffUntil: Long,
    )

    fun rank(
        context: Context,
        settings: AppSettings,
        candidates: List<WallpaperSourceEngine.Candidate>,
        seed: Long,
    ): List<WallpaperSourceEngine.Candidate> {
        if (
            !settings.decisionEngineEnabled ||
            candidates.size < 2
        ) {
            return candidates
        }

        if (
            settings.wallpaperOrder !=
                WallpaperOrder.RANDOM_SHUFFLE &&
            settings.wallpaperOrder !=
                WallpaperOrder.SURPRISE
        ) {
            return candidates
        }

        val decisionStartedNs =
            System.nanoTime()

        val now =
            System.currentTimeMillis()

        val recentStartedNs =
            System.nanoTime()

        val recentIds =
            runCatching {
                if (
                    !WallpaperFiles.seenIds.exists()
                ) {
                    emptySet()
                } else {
                    WallpaperFiles.seenIds
                        .readLines()
                        .asSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .toList()
                        .takeLast(300)
                        .toSet()
                }
            }.getOrDefault(
                emptySet()
            )

        val recentMs =
            (System.nanoTime() - recentStartedNs) / 1_000_000L

        val day =
            now / 86_400_000L

        val salt =
            if (
                settings.wallpaperOrder ==
                    WallpaperOrder.SURPRISE
            ) {
                seed xor day
            } else {
                seed
            }

        val currentIds =
            TasteLearning.currentIds(
                settings.targetMode
            )

        val styleConfidence =
            WallpaperStyleLearning
                .profileConfidence(context)

        val mood =
            SessionMoodLearning.snapshot(
                context,
                styleConfidence,
            )

        val deviceContext =
            ContextAwareness.snapshot(
                context
            )

        val adaptiveMood =
            AdaptiveMoodEngine.snapshot(
                context = context,
                settings = settings,
            )

        val weatherMood =
            WeatherMoodEngine.snapshot(
                context = context,
                settings = settings,
            )

        Phase1FinalEngine.publishMoodProfile(
            context = context,
            settings = settings,
            adaptive = adaptiveMood,
            weather = weatherMood,
        )

        val styleIds =
            buildList {
                candidates.forEach {
                    add(it.id)
                }

                addAll(currentIds)
                addAll(mood.recentIds)
            }

        val traitsStartedNs =
            System.nanoTime()

        val traitsById =
            WallpaperStyleLearning
                .traitsSnapshot(
                    context,
                    styleIds,
                )

        val traitsMs =
            (System.nanoTime() - traitsStartedNs) / 1_000_000L

        val styleKnownCount =
            candidates.count {
                traitsById.containsKey(
                    it.id
                )
            }

        val tasteStartedNs =
            System.nanoTime()

        val tasteScores =
            candidates.associate { candidate ->
                candidate.id to
                    TasteLearning.score(
                        context,
                        candidate.id,
                    )
            }

        val tasteMs =
            (System.nanoTime() - tasteStartedNs) / 1_000_000L

        val learnedTaste =
            tasteScores
                .filterValues {
                    it != 0
                }

        val styleStartedNs =
            System.nanoTime()

        val styleDecisions =
            candidates.associate { candidate ->
                candidate.id to
                    WallpaperStyleLearning
                        .decisionInfluence(
                            context,
                            candidate.id,
                        )
            }

        val styleMs =
            (System.nanoTime() - styleStartedNs) / 1_000_000L

        val visualProfiles =
            VisualIntelligenceEngine.profiles(
                context,
                candidates.map {
                    it.id
                },
            )

        val visualDecisions =
            candidates.associate { candidate ->
                candidate.id to
                    VisualIntelligenceEngine.decision(
                        visualProfiles[
                            candidate.id
                        ]
                    )
            }

        val autonomousDecisions =
            candidates.associate { candidate ->
                candidate.id to
                    AutonomousIntelligenceEngine
                        .decision(
                            context = context,
                            settings = settings,
                            candidateId =
                                candidate.id,
                            source =
                                candidate.source,
                            visual =
                                visualProfiles[
                                    candidate.id
                                ],
                        )
            }

        val tiesStartedNs =
            System.nanoTime()

        val stableTies =
            candidates.associate { candidate ->
                candidate.id to
                    stableTie(
                        candidate.id,
                        salt,
                    )
            }

        val tiesMs =
            (System.nanoTime() - tiesStartedNs) / 1_000_000L

        val sourceStartedNs =
            System.nanoTime()

        val sourceHealth =
            candidates
                .asSequence()
                .map {
                    it.source.lowercase()
                }
                .distinct()
                .associateWith { key ->
                    SourceHealth(
                        failStreak =
                            RuntimeStatus.get(
                                context,
                                "${key}_fail_streak",
                                "0",
                            )
                                .toIntOrNull()
                                ?: 0,
                        latencyMs =
                            RuntimeStatus.getLong(
                                context,
                                "${key}_last_latency_ms",
                                0L,
                            ),
                        backoffUntil =
                            RuntimeStatus.getLong(
                                context,
                                "${key}_backoff_until",
                                0L,
                            ),
                    )
                }

        val sourceMs =
            (System.nanoTime() - sourceStartedNs) / 1_000_000L

        val diversityIds =
            (
                currentIds +
                    mood.recentIds
            )
                .distinct()

        val diversityTraits =
            diversityIds
                .mapNotNull { id ->
                    traitsById[id]
                        ?.let {
                            id to it
                        }
                }

        RuntimeStatus.set(
            context,
            "decision_style_known_count",
            styleKnownCount.toString(),
        )

        RuntimeStatus.set(
            context,
            "decision_style_confidence",
            "%.2f".format(
                styleConfidence
            ),
        )

        RuntimeStatus.set(
            context,
            "decision_taste_learned_count",
            learnedTaste.size.toString(),
        )

        learnedTaste
            .maxByOrNull {
                it.value
            }
            ?.let { best ->
                RuntimeStatus.set(
                    context,
                    "decision_taste_best_positive",
                    "${best.value} • ${best.key.take(120)}",
                )
            }

        learnedTaste
            .minByOrNull {
                it.value
            }
            ?.let { worst ->
                RuntimeStatus.set(
                    context,
                    "decision_taste_best_negative",
                    "${worst.value} • ${worst.key.take(120)}",
                )
            }

        RuntimeStatus.set(
            context,
            "decision_session_mood",
            "weight=${"%.2f".format(mood.weight)} • " +
                "recent=${mood.recentIds.size} • " +
                "explore=${mood.explorationPercent}% • " +
                "+${mood.positiveSignals} " +
                "-${mood.negativeSignals}",
        )

        RuntimeStatus.set(
            context,
            "decision_device_context",
            ContextAwareness.describe(
                deviceContext
            ),
        )

        RuntimeStatus.set(
            context,
            "decision_session_vector",
            "b=${"%.3f".format(mood.brightness)} • " +
                "sat=${"%.3f".format(mood.saturation)} • " +
                "con=${"%.3f".format(mood.contrast)} • " +
                "warm=${"%.3f".format(mood.warmth)} • " +
                "dark=${"%.3f".format(mood.dark)}",
        )

        val scoringStartedNs =
            System.nanoTime()

        val ranked =
            candidates.map { candidate ->
                score(
                    settings = settings,
                    candidate = candidate,
                    recentIds = recentIds,
                    sourceHealth =
                        sourceHealth[
                            candidate.source
                                .lowercase()
                        ] ?: SourceHealth(
                            0,
                            0L,
                            0L,
                        ),
                    tasteRaw =
                        tasteScores[
                            candidate.id
                        ] ?: 0,
                    styleDecision =
                        styleDecisions[
                            candidate.id
                        ],
                    visualDecision =
                        visualDecisions[
                            candidate.id
                        ],
                    autonomousDecision =
                        autonomousDecisions[
                            candidate.id
                        ],
                    candidateTraits =
                        traitsById[
                            candidate.id
                        ],
                    diversityTraits =
                        diversityTraits,
                    mood = mood,
                    deviceContext =
                        deviceContext,
                    adaptiveMood =
                        adaptiveMood,
                    weatherMood =
                        weatherMood,
                    tie =
                        stableTies[
                            candidate.id
                        ] ?: 0L,
                    now = now,
                )
            }
                .sortedWith(
                    compareByDescending<Ranked> {
                        it.score
                    }
                        .thenBy {
                            stableTies[
                                it.candidate.id
                            ] ?: 0L
                        }
                )

        val scoringMs =
            (System.nanoTime() - scoringStartedNs) / 1_000_000L

        val moodActivity =
            ranked.count {
                "mood+" in it.reason ||
                    "mood-" in it.reason
            }

        val visualActivity =
            ranked.count {
                "visual+" in it.reason ||
                    "visual-" in it.reason
            }

        val diversityActivity =
            ranked.count {
                "diversity-" in it.reason
            }

        val explorationActivity =
            ranked.count {
                "explore+" in it.reason
            }

        val contextActivity =
            ranked.count {
                "context+" in it.reason ||
                    "context-" in it.reason
            }

        RuntimeStatus.set(
            context,
            "decision_feature_activity",
            "mood=$moodActivity • " +
                "visual=$visualActivity • " +
                "diversity=$diversityActivity • " +
                "explore=$explorationActivity • " +
                "context=$contextActivity",
        )

        ranked
            .firstOrNull()
            ?.let { top ->
                RuntimeStatus.set(
                    context,
                    "decision_last_id",
                    top.candidate.id
                        .take(180),
                )

                RuntimeStatus.set(
                    context,
                    "decision_last_source",
                    top.candidate.source,
                )

                RuntimeStatus.set(
                    context,
                    "decision_last_score",
                    top.score.toString(),
                )


                visualProfiles[
                    top.candidate.id
                ]?.let { visual ->
                    RuntimeStatus.set(
                        context,
                        "visual_last_decision",
                        VisualIntelligenceEngine.describe(
                            visual
                        ),
                    )

                    RuntimeStatus.set(
                        context,
                        "visual_last_palette",
                        visual.palette,
                    )
                }

                RuntimeStatus.set(
                    context,
                    "adaptive_mood_last_decision",
                    adaptiveMood.label,
                )

                RuntimeStatus.set(
                    context,
                    "decision_last_reason",
                    top.reason,
                )

                RuntimeStatus.set(
                    context,
                    "mood_last_factors",
                    Phase1FinalEngine.factorSummary(
                        top.reason
                    ),
                )

                RuntimeStatus.set(
                    context,
                    "decision_why_selected",
                    explainReason(
                        top.reason
                    ),
                )

                RuntimeStatus.set(
                    context,
                    "selection_reason",
                    explainReason(
                        top.reason
                    ),
                )

                val alternatives =
                    ranked
                        .drop(1)
                        .take(3)
                        .mapIndexed { index, item ->
                            "#${index + 2} " +
                                "${item.score}: " +
                                explainReason(
                                    item.reason
                                )
                        }
                        .joinToString(" || ")

                RuntimeStatus.set(
                    context,
                    "decision_alternatives",
                    alternatives,
                )

                val runner =
                    ranked.getOrNull(1)

                val shadow =
                    autonomousDecisions
                        .entries
                        .filter {
                            it.value != null
                        }
                        .maxByOrNull {
                            it.value
                                ?.influence
                                ?: Int.MIN_VALUE
                        }

                AutonomousIntelligenceEngine
                    .publishDecisionTrace(
                        context = context,
                        topId =
                            top.candidate.id,
                        topSource =
                            top.candidate.source,
                        topScore =
                            top.score,
                        topReason =
                            top.reason,
                        runnerId =
                            runner
                                ?.candidate
                                ?.id,
                        runnerScore =
                            runner
                                ?.score,
                        runnerReason =
                            runner
                                ?.reason,
                        shadowId =
                            shadow
                                ?.key,
                        shadowInfluence =
                            shadow
                                ?.value
                                ?.influence,
                    )

                RuntimeStatus.setLong(
                    context,
                    "decision_last_at",
                    now,
                )
            }

        val elapsedMs =
            (
                System.nanoTime() -
                    decisionStartedNs
            ) / 1_000_000L

        RuntimeStatus.setLong(
            context,
            "decision_rank_latency_ms",
            elapsedMs,
        )

        RuntimeStatus.set(
            context,
            "decision_rank_profile",
            "candidates=${candidates.size} • " +
                "styleKnown=$styleKnownCount • " +
                "recentMemory=${mood.recentIds.size} • " +
                "explore=${mood.explorationPercent}% • " +
                "elapsed=${elapsedMs}ms",
        )

        RuntimeStatus.set(
            context,
            "decision_stage_latency",
            "recent=${recentMs}ms • " +
                "traits=${traitsMs}ms • " +
                "taste=${tasteMs}ms • " +
                "style=${styleMs}ms • " +
                "ties=${tiesMs}ms • " +
                "source=${sourceMs}ms • " +
                "scoreSort=${scoringMs}ms",
        )

        RuntimeStatus.set(
            context,
            "decision_hotpath_reuse",
            "taste=${tasteScores.size} • " +
                "style=${styleDecisions.size} • " +
                "traits=${traitsById.size} • " +
                "ties=${stableTies.size} • " +
                "sources=${sourceHealth.size}",
        )

        return ranked.map {
            it.candidate
        }
    }

    private fun score(
        settings: AppSettings,
        candidate: WallpaperSourceEngine.Candidate,
        recentIds: Set<String>,
        sourceHealth: SourceHealth,
        tasteRaw: Int,
        styleDecision: WallpaperStyleLearning.StyleDecision?,
        visualDecision: VisualIntelligenceEngine.Decision?,
        autonomousDecision: AutonomousIntelligenceEngine.Decision?,
        candidateTraits: WallpaperStyleLearning.Traits?,
        diversityTraits: List<Pair<String, WallpaperStyleLearning.Traits>>,
        mood: SessionMoodLearning.Snapshot,
        deviceContext: ContextAwareness.Snapshot,
        adaptiveMood: AdaptiveMoodEngine.Snapshot,
        weatherMood: WeatherMoodEngine.Snapshot,
        tie: Long,
        now: Long,
    ): Ranked {
        var score = 0

        val reasons =
            mutableListOf<String>()

        val sourceScore =
            Phase1FinalEngine.sourceBaseScore(
                settings = settings,
                source = candidate.source,
            )

        score += sourceScore
        reasons +=
            "source+$sourceScore"

        if (
            sourceHealth.failStreak > 0
        ) {
            val penalty =
                (
                    sourceHealth.failStreak *
                        7
                ).coerceAtMost(28)

            score -= penalty
            reasons +=
                "fail-$penalty"
        }

        when {
            sourceHealth.latencyMs in
                1L..1_500L -> {
                score += 8
                reasons += "fast+8"
            }

            sourceHealth.latencyMs >
                4_500L -> {
                score -= 8
                reasons += "slow-8"
            }
        }

        if (
            sourceHealth.backoffUntil >
            now
        ) {
            score -= 30
            reasons += "backoff-30"
        }

        if (
            candidate.width > 0 &&
            candidate.height > 0
        ) {
            val actual =
                candidate.width.toDouble() /
                    candidate.height.toDouble()

            val target =
                9.0 / 20.0

            val deltaPct =
                abs(
                    actual -
                        target
                ) /
                    target *
                    100.0

            if (
                deltaPct <=
                settings
                    .smartCropTolerancePct
                    .coerceIn(
                        0.2f,
                        5f,
                    )
            ) {
                score += 35
                reasons += "9:20+35"
            } else if (
                candidate.height >=
                candidate.width
            ) {
                score += 5
                score -= 16
                reasons += "portrait+5"
                reasons += "ratio-16"
            } else {
                score -= 30
                reasons +=
                    "landscape-30"
            }
        } else {
            reasons +=
                "ratio=unknown"
        }

        if (
            candidate.id in recentIds
        ) {
            score -= 80
            reasons += "recent-80"
        }

        if (
            candidate.width >= 1080 &&
            candidate.height >= 2000
        ) {
            score += 8
            reasons +=
                "resolution+8"
        }

        if (tasteRaw != 0) {
            val tasteInfluence =
                (tasteRaw * 2)
                    .coerceIn(
                        -12,
                        12,
                    )

            score +=
                tasteInfluence

            reasons +=
                when {
                    tasteInfluence > 0 ->
                        "taste+$tasteInfluence(raw=$tasteRaw)"

                    tasteInfluence < 0 ->
                        "taste$tasteInfluence(raw=$tasteRaw)"

                    else ->
                        "taste=neutral"
                }
        }

        if (
            styleDecision != null &&
            styleDecision.influence != 0
        ) {
            score +=
                styleDecision.influence

            reasons +=
                if (
                    styleDecision.influence >
                    0
                ) {
                    "style+${styleDecision.influence}" +
                        "(${styleDecision.hue}; " +
                        "${styleDecision.explain})"
                } else {
                    "style${styleDecision.influence}" +
                        "(${styleDecision.hue}; " +
                        "${styleDecision.explain})"
                }
        }

        if (
            visualDecision != null &&
            visualDecision.influence != 0
        ) {
            score +=
                visualDecision.influence

            reasons +=
                if (
                    visualDecision.influence > 0
                ) {
                    "visual+${visualDecision.influence}" +
                        "(${visualDecision.label}; " +
                        "${visualDecision.explanation})"
                } else {
                    "visual${visualDecision.influence}" +
                        "(${visualDecision.label}; " +
                        "${visualDecision.explanation})"
                }
        }

        if (
            autonomousDecision != null &&
            autonomousDecision.influence != 0
        ) {
            score +=
                autonomousDecision.influence

            reasons +=
                if (
                    autonomousDecision.influence > 0
                ) {
                    "autonomy+${autonomousDecision.influence}" +
                        "(${autonomousDecision.reason})"
                } else {
                    "autonomy${autonomousDecision.influence}" +
                        "(${autonomousDecision.reason})"
                }
        }

        val moodInfluence =
            candidateTraits
                ?.let {
                    SessionMoodLearning
                        .influence(
                            mood,
                            it,
                        )
                }
                ?: 0

        if (moodInfluence != 0) {
            score += moodInfluence

            reasons +=
                if (
                    moodInfluence > 0
                ) {
                    "mood+$moodInfluence"
                } else {
                    "mood$moodInfluence"
                }
        }

        val contextInfluence =
            if (settings.moodEngineEnabled) {
                0
            } else {
                ContextAwareness.visualInfluence(
                    deviceContext,
                    candidateTraits,
                )
            }

        if (contextInfluence != 0) {
            score += contextInfluence

            reasons +=
                if (contextInfluence > 0) {
                    "context+$contextInfluence"
                } else {
                    "context$contextInfluence"
                }
        }

        val adaptiveInfluence =
            AdaptiveMoodEngine.influence(
                snapshot = adaptiveMood,
                traits = candidateTraits,
                settings = settings,
            )

        if (adaptiveInfluence != 0) {
            score += adaptiveInfluence

            reasons +=
                if (adaptiveInfluence > 0) {
                    "adaptive+$adaptiveInfluence"
                } else {
                    "adaptive$adaptiveInfluence"
                }
        }

        val weatherInfluence =
            WeatherMoodEngine.influence(
                snapshot = weatherMood,
                traits = candidateTraits,
                settings = settings,
            )

        if (weatherInfluence != 0) {
            score += weatherInfluence

            reasons +=
                if (weatherInfluence > 0) {
                    "weather+$weatherInfluence"
                } else {
                    "weather$weatherInfluence"
                }
        }

        val similarity =
            candidateTraits
                ?.let { candidateStyle ->
                    diversityTraits
                        .asSequence()
                        .filter {
                            it.first !=
                                candidate.id
                        }
                        .map {
                            visualSimilarity(
                                candidateStyle,
                                it.second,
                            )
                        }
                        .maxOrNull()
                }

        if (similarity != null) {
            val penalty =
                when {
                    similarity >= 0.92f ->
                        8

                    similarity >= 0.84f ->
                        4

                    similarity >= 0.78f ->
                        2

                    else ->
                        0
                }

            if (penalty > 0) {
                score -= penalty

                reasons +=
                    "diversity-$penalty" +
                        "(sim=${"%.2f".format(similarity)})"
            }
        }

        val styleKnown =
            candidateTraits != null

        val bucket =
            (
                (tie ushr 1) %
                    100L
            ).toInt()

        if (
            !styleKnown &&
            bucket <
                mood.explorationPercent
        ) {
            val bonus =
                (
                    2 +
                        mood.explorationPercent /
                            10
                ).coerceIn(
                    2,
                    5,
                )

            score += bonus

            reasons +=
                "explore+$bonus" +
                    "(${mood.explorationPercent}%)"
        }

        val novelty =
            (
                (tie ushr 1) %
                    17L
            ).toInt()

        score += novelty
        reasons +=
            "novelty+$novelty"

        return Ranked(
            candidate = candidate,
            score = score,
            reason =
                reasons.joinToString(
                    " • "
                ),
        )
    }

    private fun visualSimilarity(
        a: WallpaperStyleLearning.Traits,
        b: WallpaperStyleLearning.Traits,
    ): Float {
        fun distance(
            x: Float,
            y: Float,
        ): Float =
            abs(x - y)
                .coerceIn(
                    0f,
                    1f,
                )

        val brightness =
            distance(
                a.brightness,
                b.brightness,
            )

        val saturation =
            distance(
                a.saturation,
                b.saturation,
            )

        val contrast =
            distance(
                a.contrast,
                b.contrast,
            )

        val dark =
            distance(
                a.darkRatio,
                b.darkRatio,
            )

        val warmth =
            (
                abs(
                    a.warmth -
                        b.warmth
                ) / 2f
            ).coerceIn(
                0f,
                1f,
            )

        val hue =
            when {
                a.hue == b.hue ->
                    0f

                a.hue == "neutral" ||
                    b.hue == "neutral" ->
                    0.45f

                else ->
                    1f
            }

        val distance =
            brightness * 0.18f +
                saturation * 0.18f +
                contrast * 0.16f +
                dark * 0.18f +
                warmth * 0.12f +
                hue * 0.18f

        return (
            1f -
                distance
        ).coerceIn(
            0f,
            1f,
        )
    }

    private fun explainReason(
        reason: String,
    ): String {
        val parts =
            reason.split(" • ")

        val out =
            mutableListOf<String>()

        parts.forEach { part ->
            when {
                part.startsWith("source+36") ->
                    out +=
                        "Google Photos preferred"

                part.startsWith("source+24") ->
                    out +=
                        "Drive fallback preferred"

                part.startsWith("source+10") ->
                    out +=
                        "Local fallback"

                part.startsWith("9:20+") ->
                    out +=
                        "native 9:20 fit"

                part.startsWith("resolution+") ->
                    out +=
                        "high-resolution source"

                part.startsWith("taste+") ->
                    out +=
                        "matches long-term taste"

                part.startsWith("taste-") ->
                    out +=
                        "weak exact-wall history"

                part.startsWith("style+") ->
                    out +=
                        "matches learned visual style"

                part.startsWith("style-") ->
                    out +=
                        "resembles disliked style"

                part.startsWith("visual+") ->
                    out +=
                        "strong visual quality/composition"

                part.startsWith("visual-") ->
                    out +=
                        "visual quality/composition penalty"

                part.startsWith("autonomy+") ->
                    out +=
                        "autonomous diversity/transition fit"

                part.startsWith("autonomy-") ->
                    out +=
                        "autonomous fatigue/transition guard"

                part.startsWith("mood+") ->
                    out +=
                        "matches current session mood"

                part.startsWith("mood-") ->
                    out +=
                        "does not match current mood"

                part.startsWith("context+") ->
                    out +=
                        "fits current device theme"

                part.startsWith("context-") ->
                    out +=
                        "less suited to current theme"

                part.startsWith("adaptive+") ->
                    out +=
                        "fits current environment"

                part.startsWith("adaptive-") ->
                    out +=
                        "less suited to current environment"

                part.startsWith("weather+") ->
                    out +=
                        "matches current weather"

                part.startsWith("weather-") ->
                    out +=
                        "less suited to current weather"

                part.startsWith("diversity-") ->
                    out +=
                        "similar to recent wallpapers"

                part.startsWith("explore+") ->
                    out +=
                        "exploration candidate"

                part.startsWith("recent-") ->
                    out +=
                        "recently shown"

                part.startsWith("fast+") ->
                    out +=
                        "healthy fast source"

                part.startsWith("slow-") ->
                    out +=
                        "source currently slow"

                part.startsWith("backoff-") ->
                    out +=
                        "source temporarily backed off"
            }
        }

        if (out.isEmpty()) {
            return "Selected by balanced ranking"
        }

        return out
            .distinct()
            .take(5)
            .joinToString(" • ")
    }

    private fun stableTie(id: String, salt: Long): Long {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$salt|$id".toByteArray())

        var result = 0L

        for (i in 0 until minOf(8, digest.size)) {
            result =
                (result shl 8) or
                    (digest[i].toLong() and 0xff)
        }

        return result
    }
}
