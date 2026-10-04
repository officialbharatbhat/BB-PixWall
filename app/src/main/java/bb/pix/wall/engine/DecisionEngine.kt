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

    fun rank(
        context: Context,
        settings: AppSettings,
        candidates: List<WallpaperSourceEngine.Candidate>,
        seed: Long,
    ): List<WallpaperSourceEngine.Candidate> {
        if (!settings.decisionEngineEnabled || candidates.size < 2) {
            return candidates
        }

        if (
            settings.wallpaperOrder != WallpaperOrder.RANDOM_SHUFFLE &&
            settings.wallpaperOrder != WallpaperOrder.SURPRISE
        ) {
            return candidates
        }

        val recentIds = runCatching {
            if (!WallpaperFiles.seenIds.exists()) emptySet()
            else WallpaperFiles.seenIds
                .readLines()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
                .takeLast(300)
                .toSet()
        }.getOrDefault(emptySet())

        val day = System.currentTimeMillis() / 86_400_000L
        val salt = if (settings.wallpaperOrder == WallpaperOrder.SURPRISE) {
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

        val styleKnownCount =
            candidates.count { candidate ->
                WallpaperStyleLearning.hasTraits(
                    context,
                    candidate.id,
                )
            }

        RuntimeStatus.set(
            context,
            "decision_style_known_count",
            styleKnownCount.toString(),
        )

        RuntimeStatus.set(
            context,
            "decision_style_confidence",
            "%.2f".format(styleConfidence),
        )

        val learnedTaste = candidates.mapNotNull { candidate ->
            val raw = TasteLearning.score(
                context,
                candidate.id,
            )

            if (raw != 0) {
                candidate.id to raw
            } else {
                null
            }
        }

        RuntimeStatus.set(
            context,
            "decision_taste_learned_count",
            learnedTaste.size.toString()
        )

        learnedTaste.maxByOrNull { it.second }?.let { best ->
            RuntimeStatus.set(
                context,
                "decision_taste_best_positive",
                "${best.second} • ${best.first.take(120)}"
            )
        }

        learnedTaste.minByOrNull { it.second }?.let { worst ->
            RuntimeStatus.set(
                context,
                "decision_taste_best_negative",
                "${worst.second} • ${worst.first.take(120)}"
            )
        }

        val ranked: List<Ranked> = candidates.map { candidate ->
            score(
                context = context,
                settings = settings,
                candidate = candidate,
                recentIds = recentIds,
                currentIds = currentIds,
                styleConfidence = styleConfidence,
                salt = salt,
            )
        }.sortedWith(
            compareByDescending<Ranked> { it.score }
                .thenBy { stableTie(it.candidate.id, salt) }
        )

        ranked.firstOrNull()?.let { top: Ranked ->
            RuntimeStatus.set(
                context,
                "decision_last_id",
                top.candidate.id.take(180)
            )
            RuntimeStatus.set(
                context,
                "decision_last_source",
                top.candidate.source
            )
            RuntimeStatus.set(
                context,
                "decision_last_score",
                top.score.toString()
            )
            RuntimeStatus.set(
                context,
                "decision_last_reason",
                top.reason
            )
            RuntimeStatus.setLong(
                context,
                "decision_last_at",
                System.currentTimeMillis()
            )

            RuntimeStatus.set(
                context,
                "decision_last_log",
                "DECISION selected=${top.candidate.id.take(90)} " +
                    "score=${top.score} reason=${top.reason}"
            )
        }

        return ranked.map { it.candidate }
    }

    private fun score(
        context: Context,
        settings: AppSettings,
        candidate: WallpaperSourceEngine.Candidate,
        recentIds: Set<String>,
        currentIds: Set<String>,
        styleConfidence: Float,
        salt: Long,
    ): Ranked {
        var score = 0
        val reasons = mutableListOf<String>()

        // Preserve cloud-first design.
        val sourceScore = when (candidate.source.lowercase()) {
            "photos" -> 36
            "drive" -> 24
            "local" -> 10
            else -> 0
        }

        score += sourceScore
        reasons += "source+$sourceScore"

        // Source health, based only on existing diagnostics.
        val key = candidate.source.lowercase()
        val failStreak =
            RuntimeStatus.get(context, "${key}_fail_streak", "0")
                .toIntOrNull() ?: 0

        val latency =
            RuntimeStatus.getLong(
                context,
                "${key}_last_latency_ms",
                0L
            )

        val backoffUntil =
            RuntimeStatus.getLong(
                context,
                "${key}_backoff_until",
                0L
            )

        if (failStreak > 0) {
            val penalty = (failStreak * 7).coerceAtMost(28)
            score -= penalty
            reasons += "fail-$penalty"
        }

        when {
            latency in 1..1_500 -> {
                score += 8
                reasons += "fast+8"
            }
            latency > 4_500 -> {
                score -= 8
                reasons += "slow-8"
            }
        }

        if (backoffUntil > System.currentTimeMillis()) {
            score -= 30
            reasons += "backoff-30"
        }

        // Strong preference for Bharat's native 9:20 library,
        // but only when dimensions are already known.
        if (candidate.width > 0 && candidate.height > 0) {
            val actual =
                candidate.width.toDouble() /
                    candidate.height.toDouble()

            val target = 9.0 / 20.0
            val deltaPct =
                abs(actual - target) / target * 100.0

            if (
                deltaPct <=
                settings.smartCropTolerancePct
                    .coerceIn(0.2f, 5f)
            ) {
                score += 35
                reasons += "9:20+35"
            } else if (candidate.height >= candidate.width) {
                score += 5
                score -= 16
                reasons += "portrait+5"
                reasons += "ratio-16"
            } else {
                score -= 30
                reasons += "landscape-30"
            }
        } else {
            reasons += "ratio=unknown"
        }

        // Recently applied source IDs should sink to the bottom even
        // before the hard duplicate guard gets involved.
        if (candidate.id in recentIds) {
            score -= 80
            reasons += "recent-80"
        }

        // Prefer reasonable original resolution when metadata exists.
        if (
            candidate.width >= 1080 &&
            candidate.height >= 2000
        ) {
            score += 8
            reasons += "resolution+8"
        }

        // Local Taste Learning influence.
        //
        // TasteLearning stores a bounded raw score (-24..+24).
        // Decision influence is deliberately capped at ±12 so a few
        // Save/Skip actions cannot overpower source health, 9:20 quality,
        // anti-repeat or novelty.
        val tasteRaw = TasteLearning.score(
            context,
            candidate.id,
        )

        if (tasteRaw != 0) {
            val tasteInfluence =
                (tasteRaw * 2).coerceIn(-12, 12)

            score += tasteInfluence

            reasons += when {
                tasteInfluence > 0 ->
                    "taste+$tasteInfluence(raw=$tasteRaw)"

                tasteInfluence < 0 ->
                    "taste$tasteInfluence(raw=$tasteRaw)"

                else ->
                    "taste=neutral"
            }
        }

        // Generalized visual-style preference.
        //
        // Only previously analyzed candidates receive this score.
        // Influence is capped at ±10 and requires a mature profile.
        val styleDecision =
            WallpaperStyleLearning.decisionInfluence(
                context,
                candidate.id,
            )

        if (
            styleDecision != null &&
            styleDecision.influence != 0
        ) {
            score += styleDecision.influence

            reasons += if (styleDecision.influence > 0) {
                "style+${styleDecision.influence}" +
                    "(${styleDecision.hue}; ${styleDecision.explain})"
            } else {
                "style${styleDecision.influence}" +
                    "(${styleDecision.hue}; ${styleDecision.explain})"
            }
        }

        /*
         * Consecutive visual diversity.
         */
        val diversitySimilarity =
            currentIds
                .asSequence()
                .filter { it != candidate.id }
                .mapNotNull { currentId ->
                    WallpaperStyleLearning
                        .visualSimilarity(
                            context,
                            candidate.id,
                            currentId,
                        )
                }
                .maxOrNull()

        if (diversitySimilarity != null) {
            val diversityPenalty =
                when {
                    diversitySimilarity >= 0.92f -> 8
                    diversitySimilarity >= 0.84f -> 4
                    diversitySimilarity >= 0.78f -> 2
                    else -> 0
                }

            if (diversityPenalty > 0) {
                score -= diversityPenalty

                reasons +=
                    "diversity-$diversityPenalty" +
                        "(sim=${"%.2f".format(diversitySimilarity)})"
            }
        }

        /*
         * Deterministic exploration quota.
         * Roughly 20% of unknown-style candidates get a tiny bonus.
         */
        val styleKnown =
            WallpaperStyleLearning.hasTraits(
                context,
                candidate.id,
            )

        val explorationBucket =
            (
                (stableTie(candidate.id, salt) ushr 1) %
                    5L
            ).toInt()

        if (
            !styleKnown &&
            styleConfidence >= 0.65f &&
            explorationBucket == 0
        ) {
            score += 4
            reasons += "explore+4"
        }

        // Small deterministic novelty contribution.
        // No random() calls, so ordering remains reproducible.
        val novelty =
            ((stableTie(candidate.id, salt) ushr 1) % 17L)
                .toInt()

        score += novelty
        reasons += "novelty+$novelty"

        return Ranked(
            candidate = candidate,
            score = score,
            reason = reasons.joinToString(" • "),
        )
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
