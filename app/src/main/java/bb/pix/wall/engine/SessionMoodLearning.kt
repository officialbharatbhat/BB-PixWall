package bb.pix.wall.engine

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

object SessionMoodLearning {

    private const val PREFS =
        "bb_pixwall_session_mood_v1"

    private const val HALF_LIFE_MS =
        60L * 60L * 1000L

    private const val RECENT_LIMIT = 8

    private const val RECENT_NEGATIVE_MS =
        10L * 60L * 1000L

    private const val RECENT_POSITIVE_MS =
        30L * 60L * 1000L

    data class Snapshot(
        val brightness: Float,
        val saturation: Float,
        val contrast: Float,
        val warmth: Float,
        val dark: Float,
        val weight: Float,
        val recentIds: List<String>,
        val explorationPercent: Int,
        val positiveSignals: Int,
        val negativeSignals: Int,
    )

    private fun centered(
        value: Float,
    ): Float =
        ((value - 0.5f) * 2f)
            .coerceIn(-1f, 1f)

    private fun decayFactor(
        elapsedMs: Long,
    ): Float {
        if (elapsedMs <= 0L) return 1f

        return Math.pow(
            0.5,
            elapsedMs.toDouble() /
                HALF_LIFE_MS.toDouble(),
        ).toFloat()
            .coerceIn(0f, 1f)
    }

    @Synchronized
    fun recordSignal(
        context: Context,
        signal: TasteLearning.Signal,
    ) {
        if (
            signal.ids.isEmpty() ||
            signal.delta == 0
        ) {
            return
        }

        val now =
            System.currentTimeMillis()

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val previousAt =
            prefs.getLong(
                "updated_at",
                now,
            )

        val decay =
            decayFactor(
                (now - previousAt)
                    .coerceAtLeast(0L)
            )

        var brightness =
            prefs.getFloat(
                "brightness",
                0f,
            ) * decay

        var saturation =
            prefs.getFloat(
                "saturation",
                0f,
            ) * decay

        var contrast =
            prefs.getFloat(
                "contrast",
                0f,
            ) * decay

        var warmth =
            prefs.getFloat(
                "warmth",
                0f,
            ) * decay

        var dark =
            prefs.getFloat(
                "dark",
                0f,
            ) * decay

        var weight =
            prefs.getFloat(
                "weight",
                0f,
            ) * decay

        val traits =
            WallpaperStyleLearning
                .traitsSnapshot(
                    context,
                    signal.ids,
                )

        traits.values.forEach { t ->
            val strength =
                signal.delta.toFloat()

            brightness +=
                centered(t.brightness) *
                    strength

            saturation +=
                centered(t.saturation) *
                    strength

            contrast +=
                centered(t.contrast) *
                    strength

            warmth +=
                t.warmth
                    .coerceIn(-1f, 1f) *
                    strength

            dark +=
                centered(t.darkRatio) *
                    strength

            weight += abs(strength)
        }

        val positiveSignals =
            prefs.getInt(
                "positive_signals",
                0,
            ) +
                if (signal.delta > 0) 1 else 0

        val negativeSignals =
            prefs.getInt(
                "negative_signals",
                0,
            ) +
                if (signal.delta < 0) 1 else 0

        val edit =
            prefs.edit()
                .putFloat(
                    "brightness",
                    brightness,
                )
                .putFloat(
                    "saturation",
                    saturation,
                )
                .putFloat(
                    "contrast",
                    contrast,
                )
                .putFloat(
                    "warmth",
                    warmth,
                )
                .putFloat(
                    "dark",
                    dark,
                )
                .putFloat(
                    "weight",
                    weight.coerceAtMost(100f),
                )
                .putLong(
                    "updated_at",
                    now,
                )
                .putInt(
                    "positive_signals",
                    positiveSignals,
                )
                .putInt(
                    "negative_signals",
                    negativeSignals,
                )

        if (signal.delta > 0) {
            edit.putLong(
                "last_positive_at",
                now,
            )
        }

        if (signal.delta < 0) {
            edit.putLong(
                "last_negative_at",
                now,
            )
        }

        edit.apply()

        RuntimeStatus.set(
            context,
            "session_mood_last_signal",
            "${signal.label} • delta=${signal.delta}",
        )

        RuntimeStatus.setLong(
            context,
            "session_mood_updated_at",
            now,
        )
    }

    @Synchronized
    fun recordApplied(
        context: Context,
        ids: Collection<String>,
    ) {
        if (ids.isEmpty()) return

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val existing =
            (0 until RECENT_LIMIT)
                .mapNotNull { index ->
                    prefs.getString(
                        "recent_$index",
                        null,
                    )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                }

        val combined =
            (ids.toList() + existing)
                .distinct()
                .take(RECENT_LIMIT)

        val edit =
            prefs.edit()

        for (i in 0 until RECENT_LIMIT) {
            if (i < combined.size) {
                edit.putString(
                    "recent_$i",
                    combined[i],
                )
            } else {
                edit.remove(
                    "recent_$i"
                )
            }
        }

        edit.putLong(
            "recent_updated_at",
            System.currentTimeMillis(),
        )

        edit.apply()

        RuntimeStatus.set(
            context,
            "session_recent_memory",
            "${combined.size}/$RECENT_LIMIT",
        )
    }

    fun snapshot(
        context: Context,
        styleConfidence: Float,
    ): Snapshot {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val now =
            System.currentTimeMillis()

        val updatedAt =
            prefs.getLong(
                "updated_at",
                now,
            )

        val decay =
            decayFactor(
                (now - updatedAt)
                    .coerceAtLeast(0L)
            )

        val weight =
            prefs.getFloat(
                "weight",
                0f,
            ) * decay

        fun value(
            name: String,
        ): Float {
            if (weight < 0.01f) {
                return 0f
            }

            val decayedSum =
                prefs.getFloat(
                    name,
                    0f,
                ) * decay

            return (
                decayedSum /
                    weight
            ).coerceIn(-1f, 1f)
        }

        val recent =
            (0 until RECENT_LIMIT)
                .mapNotNull { index ->
                    prefs.getString(
                        "recent_$index",
                        null,
                    )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                }
                .distinct()

        var exploration =
            when {
                styleConfidence < 0.35f ->
                    28

                styleConfidence < 0.65f ->
                    22

                styleConfidence < 0.85f ->
                    16

                else ->
                    12
            }

        val lastNegative =
            prefs.getLong(
                "last_negative_at",
                0L,
            )

        val lastPositive =
            prefs.getLong(
                "last_positive_at",
                0L,
            )

        if (
            lastNegative > 0L &&
            now - lastNegative <=
                RECENT_NEGATIVE_MS
        ) {
            exploration += 8
        }

        if (
            lastPositive > 0L &&
            now - lastPositive <=
                RECENT_POSITIVE_MS
        ) {
            exploration -= 4
        }

        exploration =
            exploration.coerceIn(
                5,
                30,
            )

        return Snapshot(
            brightness = value("brightness"),
            saturation = value("saturation"),
            contrast = value("contrast"),
            warmth = value("warmth"),
            dark = value("dark"),
            weight = weight,
            recentIds = recent,
            explorationPercent = exploration,
            positiveSignals =
                prefs.getInt(
                    "positive_signals",
                    0,
                ),
            negativeSignals =
                prefs.getInt(
                    "negative_signals",
                    0,
                ),
        )
    }

    fun influence(
        snapshot: Snapshot,
        traits: WallpaperStyleLearning.Traits,
    ): Int {
        if (snapshot.weight < 2f) {
            return 0
        }

        val raw =
            snapshot.brightness *
                centered(traits.brightness) *
                0.18f +
            snapshot.saturation *
                centered(traits.saturation) *
                0.18f +
            snapshot.contrast *
                centered(traits.contrast) *
                0.16f +
            snapshot.warmth *
                traits.warmth
                    .coerceIn(-1f, 1f) *
                0.14f +
            snapshot.dark *
                centered(traits.darkRatio) *
                0.24f

        val authority =
            (snapshot.weight / 18f)
                .coerceIn(
                    0.20f,
                    1f,
                )

        return (
            raw *
                5f *
                authority
        )
            .roundToInt()
            .coerceIn(-5, 5)
    }
}
