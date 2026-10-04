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

        val ranked: List<Ranked> = candidates.map { candidate ->
            score(
                context = context,
                settings = settings,
                candidate = candidate,
                recentIds = recentIds,
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
