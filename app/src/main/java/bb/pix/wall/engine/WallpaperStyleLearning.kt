package bb.pix.wall.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import bb.pix.wall.settings.WallpaperTargetMode
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight local image-style learning.
 *
 * Phase 3 foundation:
 * - Extracts tiny sampled visual traits from files already downloaded.
 * - Never performs network I/O.
 * - Never changes wallpaper selection yet.
 * - Stores only compact numerical traits/profile data.
 */
object WallpaperStyleLearning {

    private const val PREFS = "bb_pixwall_style_v1"

    data class Traits(
        val brightness: Float,
        val saturation: Float,
        val contrast: Float,
        val warmth: Float,
        val darkRatio: Float,
        val hue: String,
    )

    data class StyleDecision(
        val influence: Int,
        val raw: Float,
        val hue: String,
    )

    fun analyzeAndStore(
        context: Context,
        candidateId: String,
        file: File,
    ): Boolean {
        if (!file.exists()) return false

        val traits = analyze(file) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = token(candidateId)

        val isNew = !prefs.getBoolean("has_$key", false)

        prefs.edit()
            .putBoolean("has_$key", true)
            .putString("id_$key", candidateId.take(220))
            .putFloat("brightness_$key", traits.brightness)
            .putFloat("saturation_$key", traits.saturation)
            .putFloat("contrast_$key", traits.contrast)
            .putFloat("warmth_$key", traits.warmth)
            .putFloat("dark_$key", traits.darkRatio)
            .putString("hue_$key", traits.hue)
            .putLong("analyzed_at_$key", System.currentTimeMillis())
            .apply()

        if (isNew) {
            prefs.edit()
                .putInt(
                    "traits_count",
                    prefs.getInt("traits_count", 0) + 1
                )
                .apply()
        }

        val pending = prefs.getInt("pending_$key", 0)
        if (pending != 0) {
            applyProfileDelta(
                context = context,
                candidateId = candidateId,
                traits = traits,
                delta = pending,
                event = "pending",
            )

            prefs.edit()
                .remove("pending_$key")
                .apply()
        }

        RuntimeStatus.set(
            context,
            "style_last_traits",
            "b=${fmt(traits.brightness)} • " +
                "sat=${fmt(traits.saturation)} • " +
                "con=${fmt(traits.contrast)} • " +
                "warm=${fmt(traits.warmth)} • " +
                "dark=${fmt(traits.darkRatio)} • " +
                traits.hue
        )

        RuntimeStatus.set(
            context,
            "style_traits_count",
            prefs.getInt("traits_count", 0).toString()
        )

        RuntimeStatus.set(
            context,
            "style_last_id",
            candidateId.take(180)
        )

        return true
    }

    /**
     * TasteLearning calls this for save/skip/retained signals.
     *
     * If traits are not ready yet, the signal is kept pending and
     * automatically consumed once that wallpaper is analyzed.
     */
    @Synchronized
    fun recordSignal(
        context: Context,
        candidateId: String,
        delta: Int,
        event: String,
    ) {
        if (delta == 0) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = token(candidateId)
        val traits = readTraits(prefs, key)

        if (traits == null) {
            val pending = prefs.getInt("pending_$key", 0)

            prefs.edit()
                .putInt(
                    "pending_$key",
                    (pending + delta).coerceIn(-48, 48)
                )
                .putString("id_$key", candidateId.take(220))
                .apply()

            RuntimeStatus.set(
                context,
                "style_last_learning",
                "$event pending • delta=$delta"
            )
            return
        }

        applyProfileDelta(
            context = context,
            candidateId = candidateId,
            traits = traits,
            delta = delta,
            event = event,
        )
    }

    /**
     * Async-safe helper for current Home/Lock files.
     * Useful for cache/legacy/local sources that were not analyzed earlier.
     */
    fun analyzeCurrent(
        context: Context,
        targetMode: WallpaperTargetMode,
    ) {
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

        files.filter(File::exists)
            .forEach { file ->
                val beforeId = candidateId(file) ?: return@forEach
                analyzeAndStore(context, beforeId, file)
            }
    }

    /**
     * Lightweight selection-time style score.
     *
     * No bitmap decode and no network access occur here.
     * Only candidates whose traits were previously analyzed can score.
     */
    fun decisionInfluence(
        context: Context,
        candidateId: String,
    ): StyleDecision? {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        val profileWeight =
            prefs.getFloat("profile_weight", 0f)

        val signals =
            prefs.getInt("profile_signals", 0)

        // Avoid steering selection while the profile is still immature.
        if (profileWeight < 20f || signals < 5) {
            return null
        }

        val traits =
            readTraits(
                prefs,
                token(candidateId),
            ) ?: return null

        fun pref(name: String): Float =
            (
                prefs.getFloat("profile_$name", 0f) /
                    profileWeight
            ).coerceIn(-1f, 1f)

        fun centered(value: Float): Float =
            ((value - 0.5f) * 2f)
                .coerceIn(-1f, 1f)

        val huePreference =
            if (traits.hue == "neutral") {
                0f
            } else {
                (
                    prefs.getFloat(
                        "hue_pref_${traits.hue}",
                        0f
                    ) / profileWeight
                ).coerceIn(-1f, 1f)
            }

        /*
         * Weighted similarity:
         *
         * dark       28%
         * saturation 18%
         * hue        18%
         * brightness 12%
         * contrast   12%
         * warmth     12%
         *
         * Total = 100%.
         */
        val raw =
            pref("dark") *
                centered(traits.darkRatio) * 0.28f +
            pref("saturation") *
                centered(traits.saturation) * 0.18f +
            huePreference * 0.18f +
            pref("brightness") *
                centered(traits.brightness) * 0.12f +
            pref("contrast") *
                centered(traits.contrast) * 0.12f +
            pref("warmth") *
                traits.warmth.coerceIn(-1f, 1f) * 0.12f

        /*
         * Style authority grows with actual learning maturity.
         *
         * A barely-ready profile should not have the same authority
         * as one trained by dozens of deliberate user signals.
         */
        val confidence =
            profileConfidence(
                profileWeight = profileWeight,
                signals = signals,
            )

        val authority =
            0.35f + (confidence * 0.65f)

        val influence =
            kotlin.math.round(
                raw * 10f * authority
            )
                .toInt()
                .coerceIn(-10, 10)

        return StyleDecision(
            influence = influence,
            raw = raw.coerceIn(-1f, 1f),
            hue = traits.hue,
        )
    }

    private fun profileConfidence(
        profileWeight: Float,
        signals: Int,
    ): Float {
        if (profileWeight < 20f || signals < 5) {
            return 0f
        }

        val weightConfidence =
            ((profileWeight - 20f) / 80f)
                .coerceIn(0f, 1f)

        val signalConfidence =
            ((signals - 5f) / 35f)
                .coerceIn(0f, 1f)

        return minOf(
            weightConfidence,
            signalConfidence,
        )
    }

    fun profileConfidence(context: Context): Float {
        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        return profileConfidence(
            profileWeight =
                prefs.getFloat(
                    "profile_weight",
                    0f,
                ),
            signals =
                prefs.getInt(
                    "profile_signals",
                    0,
                ),
        )
    }

    fun profileReady(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

        return prefs.getFloat(
            "profile_weight",
            0f
        ) >= 20f &&
            prefs.getInt(
                "profile_signals",
                0
            ) >= 5
    }

    fun profileSummary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val weight = prefs.getFloat("profile_weight", 0f)

        if (weight <= 0f) return "No style profile yet"

        fun pref(name: String): Float =
            (prefs.getFloat("profile_$name", 0f) / weight)
                .coerceIn(-1f, 1f)

        return "brightness=${fmt(pref("brightness"))} • " +
            "saturation=${fmt(pref("saturation"))} • " +
            "contrast=${fmt(pref("contrast"))} • " +
            "warmth=${fmt(pref("warmth"))} • " +
            "dark=${fmt(pref("dark"))}"
    }

    @Synchronized
    private fun applyProfileDelta(
        context: Context,
        candidateId: String,
        traits: Traits,
        delta: Int,
        event: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val strength = delta.toFloat()
        val weightAdd = abs(strength)

        fun centered(value: Float): Float =
            ((value - 0.5f) * 2f).coerceIn(-1f, 1f)

        val brightnessFeature = centered(traits.brightness)
        val saturationFeature = centered(traits.saturation)
        val contrastFeature = centered(traits.contrast)
        val darkFeature = centered(traits.darkRatio)
        val warmthFeature = traits.warmth.coerceIn(-1f, 1f)

        val edit = prefs.edit()

        edit.putFloat(
            "profile_brightness",
            prefs.getFloat("profile_brightness", 0f) +
                brightnessFeature * strength
        )

        edit.putFloat(
            "profile_saturation",
            prefs.getFloat("profile_saturation", 0f) +
                saturationFeature * strength
        )

        edit.putFloat(
            "profile_contrast",
            prefs.getFloat("profile_contrast", 0f) +
                contrastFeature * strength
        )

        edit.putFloat(
            "profile_warmth",
            prefs.getFloat("profile_warmth", 0f) +
                warmthFeature * strength
        )

        edit.putFloat(
            "profile_dark",
            prefs.getFloat("profile_dark", 0f) +
                darkFeature * strength
        )

        edit.putFloat(
            "profile_weight",
            (prefs.getFloat("profile_weight", 0f) + weightAdd)
                .coerceAtMost(10_000f)
        )

        val hueKey = "hue_pref_${traits.hue}"
        edit.putFloat(
            hueKey,
            prefs.getFloat(hueKey, 0f) + strength
        )

        edit.putInt(
            "profile_signals",
            prefs.getInt("profile_signals", 0) + 1
        )

        edit.putLong(
            "profile_updated_at",
            System.currentTimeMillis()
        )

        edit.apply()

        RuntimeStatus.set(
            context,
            "style_last_learning",
            "$event • delta=$delta • ${traits.hue}"
        )

        RuntimeStatus.set(
            context,
            "style_profile",
            profileSummary(context)
        )

        RuntimeStatus.set(
            context,
            "style_profile_signals",
            prefs.getInt("profile_signals", 0).toString()
        )

        RuntimeStatus.set(
            context,
            "style_profile_last_id",
            candidateId.take(180)
        )
    }

    private fun readTraits(
        prefs: android.content.SharedPreferences,
        key: String,
    ): Traits? {
        if (!prefs.getBoolean("has_$key", false)) return null

        return Traits(
            brightness = prefs.getFloat("brightness_$key", 0.5f),
            saturation = prefs.getFloat("saturation_$key", 0.5f),
            contrast = prefs.getFloat("contrast_$key", 0.5f),
            warmth = prefs.getFloat("warmth_$key", 0f),
            darkRatio = prefs.getFloat("dark_$key", 0.5f),
            hue = prefs.getString("hue_$key", "neutral") ?: "neutral",
        )
    }

    private fun analyze(file: File): Traits? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)

        while (maxSide / sample > 160) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return null

        return try {
            val totalPixels = bitmap.width * bitmap.height
            if (totalPixels <= 0) return null

            val step = sqrt(
                totalPixels.toDouble() / 4096.0
            ).toInt().coerceAtLeast(1)

            var samples = 0
            var lumSum = 0.0
            var lumSqSum = 0.0
            var satSum = 0.0
            var warmthSum = 0.0
            var darkCount = 0

            val hueBins = DoubleArray(6)
            val hsv = FloatArray(3)

            var y = 0
            while (y < bitmap.height) {
                var x = 0

                while (x < bitmap.width) {
                    val color = bitmap.getPixel(x, y)

                    val r = Color.red(color) / 255.0
                    val g = Color.green(color) / 255.0
                    val b = Color.blue(color) / 255.0

                    val lum =
                        0.2126 * r +
                            0.7152 * g +
                            0.0722 * b

                    lumSum += lum
                    lumSqSum += lum * lum

                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)

                    val sat =
                        if (max <= 0.0001) 0.0
                        else (max - min) / max

                    satSum += sat
                    warmthSum += r - b

                    if (lum < 0.18) {
                        darkCount++
                    }

                    Color.RGBToHSV(
                        Color.red(color),
                        Color.green(color),
                        Color.blue(color),
                        hsv
                    )

                    if (hsv[1] >= 0.12f) {
                        val bin =
                            ((hsv[0] / 60f).toInt() % 6)
                                .coerceIn(0, 5)

                        hueBins[bin] += hsv[1].toDouble()
                    }

                    samples++
                    x += step
                }

                y += step
            }

            if (samples <= 0) return null

            val brightness =
                (lumSum / samples).toFloat()
                    .coerceIn(0f, 1f)

            val saturation =
                (satSum / samples).toFloat()
                    .coerceIn(0f, 1f)

            val variance =
                (lumSqSum / samples) -
                    (lumSum / samples) * (lumSum / samples)

            val contrast =
                (sqrt(variance.coerceAtLeast(0.0)) / 0.5)
                    .toFloat()
                    .coerceIn(0f, 1f)

            val warmth =
                (warmthSum / samples)
                    .toFloat()
                    .coerceIn(-1f, 1f)

            val darkRatio =
                (darkCount.toFloat() / samples.toFloat())
                    .coerceIn(0f, 1f)

            val hueNames = arrayOf(
                "red",
                "yellow",
                "green",
                "cyan",
                "blue",
                "magenta",
            )

            val strongest =
                hueBins.indices.maxByOrNull {
                    hueBins[it]
                } ?: 0

            val hue =
                if (saturation < 0.12f) {
                    "neutral"
                } else {
                    hueNames[strongest]
                }

            Traits(
                brightness = brightness,
                saturation = saturation,
                contrast = contrast,
                warmth = warmth,
                darkRatio = darkRatio,
                hue = hue,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun candidateId(file: File): String? {
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

    private fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", value)
}
