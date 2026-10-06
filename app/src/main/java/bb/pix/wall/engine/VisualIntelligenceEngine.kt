package bb.pix.wall.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 2B local visual intelligence.
 *
 * Design:
 * - zero network
 * - bounded bitmap decode
 * - compact SharedPreferences persistence
 * - no ML model / no heavyweight dependency
 * - selection hot path performs no bitmap decoding
 */
object VisualIntelligenceEngine {

    private const val PREFS =
        "bb_pixwall_visual_intelligence_v2"

    private const val SCHEMA = 2

    data class Profile(
        val quality: Int,
        val brightness: Float,
        val saturation: Float,
        val contrast: Float,
        val warmth: Float,
        val darkRatio: Float,
        val brightRatio: Float,
        val colorfulness: Float,
        val edgeDensity: Float,
        val busyScore: Int,
        val centerBalance: Int,
        val topReadability: Int,
        val bottomReadability: Int,
        val lockReadability: Int,
        val cropSafety: Int,
        val amoledScore: Int,
        val cinematicScore: Int,
        val vibrantScore: Int,
        val pastelScore: Int,
        val monochromeScore: Int,
        val lowLightScore: Int,
        val dominantHue: String,
        val palette: String,
        val styleClass: String,
        val width: Int,
        val height: Int,
    )

    data class Decision(
        val influence: Int,
        val label: String,
        val explanation: String,
    )

    fun analyzeAndStore(
        context: Context,
        candidateId: String,
        file: File,
    ): Boolean {
        if (
            candidateId.isBlank() ||
            !file.exists() ||
            !file.isFile
        ) {
            return false
        }

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val key =
            token(candidateId)

        if (
            prefs.getInt(
                "schema_$key",
                0,
            ) >= SCHEMA &&
            prefs.getBoolean(
                "has_$key",
                false,
            )
        ) {
            return true
        }

        val profile =
            analyze(file)
                ?: return false

        prefs.edit()
            .putBoolean(
                "has_$key",
                true,
            )
            .putInt(
                "schema_$key",
                SCHEMA,
            )
            .putString(
                "id_$key",
                candidateId.take(240),
            )
            .putInt(
                "quality_$key",
                profile.quality,
            )
            .putFloat(
                "brightness_$key",
                profile.brightness,
            )
            .putFloat(
                "saturation_$key",
                profile.saturation,
            )
            .putFloat(
                "contrast_$key",
                profile.contrast,
            )
            .putFloat(
                "warmth_$key",
                profile.warmth,
            )
            .putFloat(
                "dark_$key",
                profile.darkRatio,
            )
            .putFloat(
                "bright_$key",
                profile.brightRatio,
            )
            .putFloat(
                "colorfulness_$key",
                profile.colorfulness,
            )
            .putFloat(
                "edges_$key",
                profile.edgeDensity,
            )
            .putInt(
                "busy_$key",
                profile.busyScore,
            )
            .putInt(
                "center_$key",
                profile.centerBalance,
            )
            .putInt(
                "top_readability_$key",
                profile.topReadability,
            )
            .putInt(
                "bottom_readability_$key",
                profile.bottomReadability,
            )
            .putInt(
                "lock_readability_$key",
                profile.lockReadability,
            )
            .putInt(
                "crop_safety_$key",
                profile.cropSafety,
            )
            .putInt(
                "amoled_$key",
                profile.amoledScore,
            )
            .putInt(
                "cinematic_$key",
                profile.cinematicScore,
            )
            .putInt(
                "vibrant_$key",
                profile.vibrantScore,
            )
            .putInt(
                "pastel_$key",
                profile.pastelScore,
            )
            .putInt(
                "mono_$key",
                profile.monochromeScore,
            )
            .putInt(
                "lowlight_$key",
                profile.lowLightScore,
            )
            .putString(
                "hue_$key",
                profile.dominantHue,
            )
            .putString(
                "palette_$key",
                profile.palette,
            )
            .putString(
                "class_$key",
                profile.styleClass,
            )
            .putInt(
                "width_$key",
                profile.width,
            )
            .putInt(
                "height_$key",
                profile.height,
            )
            .putLong(
                "analyzed_$key",
                System.currentTimeMillis(),
            )
            .apply()

        publish(
            context,
            candidateId,
            profile,
        )

        return true
    }

    fun analyzeCurrent(
        context: Context,
    ) {
        val home =
            WallpaperFiles.currentHome

        val lock =
            WallpaperFiles.currentLock

        val homeId =
            if (home.exists()) {
                candidateId(home)
            } else {
                null
            }

        val lockId =
            if (lock.exists()) {
                candidateId(lock)
            } else {
                null
            }

        /*
         * Analyze both current originals first.
         * Pairing is calculated only after both profiles are ready.
         */
        if (
            homeId != null &&
            home.exists()
        ) {
            analyzeAndStore(
                context,
                homeId,
                home,
            )
        }

        if (
            lockId != null &&
            lock.exists()
        ) {
            analyzeAndStore(
                context,
                lockId,
                lock,
            )
        }

        publishForFile(
            context,
            home,
            "current_home",
        )

        publishForFile(
            context,
            lock,
            "current_lock",
        )

        when {
            homeId == null &&
                lockId == null -> {
                RuntimeStatus.set(
                    context,
                    "visual_pairing",
                    "Waiting • current Home/Lock IDs unavailable",
                )
            }

            homeId == null -> {
                RuntimeStatus.set(
                    context,
                    "visual_pairing",
                    "Waiting • Home profile unavailable",
                )
            }

            lockId == null -> {
                RuntimeStatus.set(
                    context,
                    "visual_pairing",
                    "Waiting • Lock profile unavailable",
                )
            }

            homeId == lockId -> {
                val p =
                    profile(
                        context,
                        homeId,
                    )

                RuntimeStatus.set(
                    context,
                    "visual_pairing",
                    if (p != null) {
                        "${p.styleClass} • same wallpaper on Home + Lock"
                    } else {
                        "Same wallpaper • profile pending"
                    },
                )
            }

            else -> {
                val homeProfile =
                    profile(
                        context,
                        homeId,
                    )

                val lockProfile =
                    profile(
                        context,
                        lockId,
                    )

                val score =
                    pairScore(
                        context,
                        homeId,
                        lockId,
                    )

                if (
                    homeProfile != null &&
                    lockProfile != null &&
                    score != null
                ) {
                    RuntimeStatus.set(
                        context,
                        "visual_pairing",
                        "${homeProfile.styleClass} + " +
                            "${lockProfile.styleClass} • " +
                            "score=$score",
                    )

                    RuntimeStatus.set(
                        context,
                        "visual_pairing_detail",
                        "Home ${homeProfile.dominantHue} • " +
                            "Lock ${lockProfile.dominantHue} • " +
                            "Home quality ${homeProfile.quality} • " +
                            "Lock quality ${lockProfile.quality}",
                    )
                } else {
                    RuntimeStatus.set(
                        context,
                        "visual_pairing",
                        "Waiting • pair profiles pending",
                    )
                }
            }
        }
    }

    fun profile(
        context: Context,
        candidateId: String,
    ): Profile? {
        if (candidateId.isBlank()) {
            return null
        }

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val key =
            token(candidateId)

        if (
            !prefs.getBoolean(
                "has_$key",
                false,
            )
        ) {
            return null
        }

        return Profile(
            quality =
                prefs.getInt(
                    "quality_$key",
                    50,
                ),
            brightness =
                prefs.getFloat(
                    "brightness_$key",
                    .5f,
                ),
            saturation =
                prefs.getFloat(
                    "saturation_$key",
                    .5f,
                ),
            contrast =
                prefs.getFloat(
                    "contrast_$key",
                    .5f,
                ),
            warmth =
                prefs.getFloat(
                    "warmth_$key",
                    0f,
                ),
            darkRatio =
                prefs.getFloat(
                    "dark_$key",
                    .5f,
                ),
            brightRatio =
                prefs.getFloat(
                    "bright_$key",
                    .1f,
                ),
            colorfulness =
                prefs.getFloat(
                    "colorfulness_$key",
                    .5f,
                ),
            edgeDensity =
                prefs.getFloat(
                    "edges_$key",
                    .5f,
                ),
            busyScore =
                prefs.getInt(
                    "busy_$key",
                    50,
                ),
            centerBalance =
                prefs.getInt(
                    "center_$key",
                    50,
                ),
            topReadability =
                prefs.getInt(
                    "top_readability_$key",
                    50,
                ),
            bottomReadability =
                prefs.getInt(
                    "bottom_readability_$key",
                    50,
                ),
            lockReadability =
                prefs.getInt(
                    "lock_readability_$key",
                    50,
                ),
            cropSafety =
                prefs.getInt(
                    "crop_safety_$key",
                    50,
                ),
            amoledScore =
                prefs.getInt(
                    "amoled_$key",
                    50,
                ),
            cinematicScore =
                prefs.getInt(
                    "cinematic_$key",
                    50,
                ),
            vibrantScore =
                prefs.getInt(
                    "vibrant_$key",
                    50,
                ),
            pastelScore =
                prefs.getInt(
                    "pastel_$key",
                    50,
                ),
            monochromeScore =
                prefs.getInt(
                    "mono_$key",
                    50,
                ),
            lowLightScore =
                prefs.getInt(
                    "lowlight_$key",
                    50,
                ),
            dominantHue =
                prefs.getString(
                    "hue_$key",
                    "neutral",
                ) ?: "neutral",
            palette =
                prefs.getString(
                    "palette_$key",
                    "",
                ).orEmpty(),
            styleClass =
                prefs.getString(
                    "class_$key",
                    "Balanced",
                ) ?: "Balanced",
            width =
                prefs.getInt(
                    "width_$key",
                    0,
                ),
            height =
                prefs.getInt(
                    "height_$key",
                    0,
                ),
        )
    }

    fun profiles(
        context: Context,
        candidateIds: Collection<String>,
    ): Map<String, Profile> {
        if (candidateIds.isEmpty()) {
            return emptyMap()
        }

        val out =
            LinkedHashMap<String, Profile>()

        candidateIds
            .asSequence()
            .distinct()
            .forEach { id ->
                profile(
                    context,
                    id,
                )?.let {
                    out[id] = it
                }
            }

        return out
    }

    fun decision(
        profile: Profile?,
    ): Decision? {
        profile ?: return null

        var score = 0

        val reasons =
            mutableListOf<String>()

        when {
            profile.quality >= 88 -> {
                score += 5
                reasons += "premium quality"
            }

            profile.quality >= 74 -> {
                score += 3
                reasons += "strong quality"
            }

            profile.quality < 35 -> {
                score -= 7
                reasons += "weak quality"
            }

            profile.quality < 50 -> {
                score -= 3
                reasons += "below-average quality"
            }
        }

        if (
            profile.lockReadability >= 78
        ) {
            score += 2
            reasons += "lock readable"
        }

        if (
            profile.cropSafety >= 78
        ) {
            score += 2
            reasons += "crop safe"
        } else if (
            profile.cropSafety < 35
        ) {
            score -= 2
            reasons += "crop risk"
        }

        if (
            profile.busyScore >= 85
        ) {
            score -= 2
            reasons += "very busy"
        }

        if (
            profile.cinematicScore >= 82
        ) {
            score += 2
            reasons += "cinematic"
        }

        if (
            profile.amoledScore >= 84
        ) {
            score += 2
            reasons += "AMOLED friendly"
        }

        if (
            profile.vibrantScore >= 88 &&
            profile.busyScore < 78
        ) {
            score += 1
            reasons += "controlled vibrant"
        }

        return Decision(
            influence =
                score.coerceIn(
                    -12,
                    12,
                ),
            label =
                profile.styleClass,
            explanation =
                reasons
                    .take(4)
                    .joinToString(", ")
                    .ifBlank {
                        "balanced visual profile"
                    },
        )
    }

    fun pairScore(
        context: Context,
        firstId: String,
        secondId: String,
    ): Int? {
        if (
            firstId.isBlank() ||
            secondId.isBlank() ||
            firstId == secondId
        ) {
            return null
        }

        val a =
            profile(
                context,
                firstId,
            ) ?: return null

        val b =
            profile(
                context,
                secondId,
            ) ?: return null

        fun similarity(
            x: Float,
            y: Float,
        ): Float =
            (
                1f -
                    abs(x - y)
            ).coerceIn(
                0f,
                1f,
            )

        val brightness =
            similarity(
                a.brightness,
                b.brightness,
            )

        val saturation =
            similarity(
                a.saturation,
                b.saturation,
            )

        val contrast =
            similarity(
                a.contrast,
                b.contrast,
            )

        val colorfulness =
            similarity(
                a.colorfulness,
                b.colorfulness,
            )

        val classHarmony =
            if (
                a.styleClass ==
                    b.styleClass
            ) {
                1f
            } else {
                .72f
            }

        val hueHarmony =
            when {
                a.dominantHue ==
                    b.dominantHue ->
                    .92f

                a.dominantHue ==
                    "neutral" ||
                    b.dominantHue ==
                    "neutral" ->
                    .78f

                complementary(
                    a.dominantHue,
                    b.dominantHue,
                ) ->
                    .95f

                else ->
                    .68f
            }

        val rawSimilarity =
            brightness * .18f +
                saturation * .16f +
                contrast * .12f +
                colorfulness * .12f +
                classHarmony * .16f +
                hueHarmony * .26f

        /*
         * Penalize near-clones. Home + Lock should feel related,
         * not like the same frame twice.
         */
        val clonePenalty =
            when {
                rawSimilarity >= .96f ->
                    26

                rawSimilarity >= .92f ->
                    15

                else ->
                    0
            }

        val quality =
            (
                a.quality +
                    b.quality
            ) / 2

        val readability =
            (
                a.lockReadability +
                    b.lockReadability
            ) / 2

        val final =
            (
                rawSimilarity * 70f
            ).roundToInt() +
                quality / 8 +
                readability / 10 -
                clonePenalty

        RuntimeStatus.set(
            context,
            "visual_pairing",
            "${a.styleClass} + ${b.styleClass} • " +
                "harmony=${(rawSimilarity * 100).roundToInt()} • " +
                "score=${final.coerceIn(0, 100)}",
        )

        return final.coerceIn(
            0,
            100,
        )
    }

    fun severeQualityProblem(
        context: Context,
        candidateId: String,
    ): String? {
        val p =
            profile(
                context,
                candidateId,
            ) ?: return null

        return when {
            p.width <= 0 ||
                p.height <= 0 ->
                "invalid dimensions"

            min(
                p.width,
                p.height,
            ) < 320 ->
                "extremely low resolution"

            max(
                p.width,
                p.height,
            ) < 720 ->
                "extremely small image"

            p.quality < 20 ->
                "visual quality score ${p.quality}"

            else ->
                null
        }
    }

    fun describe(
        profile: Profile?,
    ): String {
        profile ?: return "Not analyzed yet"

        return buildString {
            append(profile.styleClass)

            append(
                " • quality ${profile.quality}"
            )

            append(
                " • AMOLED ${profile.amoledScore}"
            )

            append(
                " • readability ${profile.lockReadability}"
            )

            append(
                " • crop ${profile.cropSafety}"
            )

            append(
                " • ${profile.dominantHue}"
            )
        }
    }

    fun publishForFile(
        context: Context,
        file: File,
        slot: String,
    ) {
        val id =
            candidateId(file)
                ?: return

        val p =
            profile(
                context,
                id,
            ) ?: return

        RuntimeStatus.set(
            context,
            "visual_${slot}_profile",
            describe(p),
        )

        RuntimeStatus.set(
            context,
            "visual_${slot}_palette",
            p.palette.ifBlank {
                "Unavailable"
            },
        )
    }

    private fun publish(
        context: Context,
        id: String,
        p: Profile,
    ) {
        RuntimeStatus.set(
            context,
            "visual_last_profile",
            describe(p),
        )

        RuntimeStatus.set(
            context,
            "visual_last_palette",
            p.palette.ifBlank {
                "Unavailable"
            },
        )

        RuntimeStatus.set(
            context,
            "visual_last_id",
            id.take(180),
        )

        RuntimeStatus.set(
            context,
            "visual_last_detail",
            "cinematic=${p.cinematicScore} • " +
                "vibrant=${p.vibrantScore} • " +
                "pastel=${p.pastelScore} • " +
                "mono=${p.monochromeScore} • " +
                "busy=${p.busyScore}",
        )
    }

    private fun analyze(
        file: File,
    ): Profile? {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds =
                    true
            }

        BitmapFactory.decodeFile(
            file.absolutePath,
            bounds,
        )

        if (
            bounds.outWidth <= 0 ||
            bounds.outHeight <= 0
        ) {
            return null
        }

        var sample = 1

        val maxSide =
            max(
                bounds.outWidth,
                bounds.outHeight,
            )

        while (
            maxSide / sample >
                192
        ) {
            sample *= 2
        }

        val bitmap =
            try {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options()
                        .apply {
                            inSampleSize =
                                sample

                            inPreferredConfig =
                                Bitmap.Config
                                    .ARGB_8888
                        },
                )
            } catch (_: OutOfMemoryError) {
                null
            } ?: return null

        return try {
            computeProfile(
                bitmap,
                bounds.outWidth,
                bounds.outHeight,
                file.length(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun computeProfile(
        bitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        bytes: Long,
    ): Profile? {
        if (
            bitmap.width <= 1 ||
            bitmap.height <= 1
        ) {
            return null
        }

        val total =
            bitmap.width *
                bitmap.height

        val step =
            sqrt(
                total.toDouble() /
                    5000.0
            )
                .toInt()
                .coerceAtLeast(1)

        var count = 0

        var lumSum = 0.0
        var lumSq = 0.0
        var satSum = 0.0
        var warmthSum = 0.0

        var dark = 0
        var bright = 0
        var mono = 0

        var edgeCount = 0
        var edgeTests = 0

        var centerLum = 0.0
        var centerCount = 0

        var outsideLum = 0.0
        var outsideCount = 0

        var topLum = 0.0
        var topLumSq = 0.0
        var topCount = 0

        var bottomLum = 0.0
        var bottomLumSq = 0.0
        var bottomCount = 0

        val hueBins =
            DoubleArray(6)

        val paletteBins =
            HashMap<Int, Int>()

        val hsv =
            FloatArray(3)

        var y = 0

        while (y < bitmap.height) {
            var x = 0

            while (x < bitmap.width) {
                val c =
                    bitmap.getPixel(
                        x,
                        y,
                    )

                val r =
                    Color.red(c) /
                        255.0

                val g =
                    Color.green(c) /
                        255.0

                val b =
                    Color.blue(c) /
                        255.0

                val lum =
                    0.2126 * r +
                        0.7152 * g +
                        0.0722 * b

                val maxRgb =
                    max(
                        r,
                        max(g, b),
                    )

                val minRgb =
                    min(
                        r,
                        min(g, b),
                    )

                val sat =
                    if (
                        maxRgb <= .0001
                    ) {
                        0.0
                    } else {
                        (
                            maxRgb -
                                minRgb
                            ) /
                            maxRgb
                    }

                lumSum += lum
                lumSq += lum * lum
                satSum += sat
                warmthSum += r - b

                if (lum < .18) {
                    dark++
                }

                if (lum > .80) {
                    bright++
                }

                if (sat < .10) {
                    mono++
                }

                val nx =
                    x.toFloat() /
                        bitmap.width

                val ny =
                    y.toFloat() /
                        bitmap.height

                val center =
                    nx in .22f.. .78f &&
                        ny in .18f.. .82f

                if (center) {
                    centerLum += lum
                    centerCount++
                } else {
                    outsideLum += lum
                    outsideCount++
                }

                if (ny <= .24f) {
                    topLum += lum
                    topLumSq +=
                        lum * lum
                    topCount++
                }

                if (ny >= .68f) {
                    bottomLum += lum
                    bottomLumSq +=
                        lum * lum
                    bottomCount++
                }

                if (
                    x + step <
                        bitmap.width
                ) {
                    val rc =
                        bitmap.getPixel(
                            x + step,
                            y,
                        )

                    val rl =
                        luminance(rc)

                    if (
                        abs(
                            lum -
                                rl
                        ) >= .15
                    ) {
                        edgeCount++
                    }

                    edgeTests++
                }

                if (
                    y + step <
                        bitmap.height
                ) {
                    val dc =
                        bitmap.getPixel(
                            x,
                            y + step,
                        )

                    val dl =
                        luminance(dc)

                    if (
                        abs(
                            lum -
                                dl
                        ) >= .15
                    ) {
                        edgeCount++
                    }

                    edgeTests++
                }

                Color.RGBToHSV(
                    Color.red(c),
                    Color.green(c),
                    Color.blue(c),
                    hsv,
                )

                if (
                    hsv[1] >= .10f
                ) {
                    val bin =
                        (
                            hsv[0] /
                                60f
                            )
                            .toInt()
                            .coerceIn(
                                0,
                                5,
                            )

                    hueBins[bin] +=
                        hsv[1]
                            .toDouble()
                }

                /*
                 * 4-bit RGB quantization for a tiny dominant palette.
                 */
                val qr =
                    Color.red(c) /
                        32

                val qg =
                    Color.green(c) /
                        32

                val qb =
                    Color.blue(c) /
                        32

                val key =
                    (
                        qr shl 8
                        ) or
                        (
                            qg shl 4
                            ) or
                        qb

                paletteBins[key] =
                    (
                        paletteBins[key]
                            ?: 0
                        ) + 1

                count++
                x += step
            }

            y += step
        }

        if (count <= 0) {
            return null
        }

        val brightness =
            (
                lumSum /
                    count
                )
                .toFloat()
                .coerceIn(
                    0f,
                    1f,
                )

        val saturation =
            (
                satSum /
                    count
                )
                .toFloat()
                .coerceIn(
                    0f,
                    1f,
                )

        val variance =
            (
                lumSq /
                    count
                ) -
                (
                    lumSum /
                        count
                    ) *
                (
                    lumSum /
                        count
                    )

        val contrast =
            (
                sqrt(
                    variance
                        .coerceAtLeast(
                            0.0
                        )
                ) /
                    .42
                )
                .toFloat()
                .coerceIn(
                    0f,
                    1f,
                )

        val warmth =
            (
                warmthSum /
                    count
                )
                .toFloat()
                .coerceIn(
                    -1f,
                    1f,
                )

        val darkRatio =
            dark.toFloat() /
                count

        val brightRatio =
            bright.toFloat() /
                count

        val monoRatio =
            mono.toFloat() /
                count

        val edgeDensity =
            if (edgeTests <= 0) {
                0f
            } else {
                (
                    edgeCount.toFloat() /
                        edgeTests
                    )
                    .coerceIn(
                        0f,
                        1f,
                    )
            }

        val colorfulness =
            (
                saturation *
                    (
                        .55f +
                            contrast *
                                .45f
                        )
                )
                .coerceIn(
                    0f,
                    1f,
                )

        val centerMean =
            if (centerCount > 0) {
                centerLum /
                    centerCount
            } else {
                .5
            }

        val outsideMean =
            if (outsideCount > 0) {
                outsideLum /
                    outsideCount
            } else {
                .5
            }

        val centerDifference =
            abs(
                centerMean -
                    outsideMean
            )

        val centerBalance =
            (
                100 -
                    (
                        centerDifference *
                            145
                        )
                        .roundToInt()
                )
                .coerceIn(
                    0,
                    100,
                )

        fun zoneReadability(
            sum: Double,
            sq: Double,
            n: Int,
        ): Int {
            if (n <= 0) {
                return 50
            }

            val mean =
                sum / n

            val v =
                (
                    sq /
                        n
                    ) -
                    mean *
                    mean

            val localContrast =
                sqrt(
                    v.coerceAtLeast(
                        0.0
                    )
                )
                    .coerceIn(
                        0.0,
                        .5,
                    )

            /*
             * Low local detail + darker/medium tone generally gives
             * clock/status text a cleaner backdrop.
             */
            val tone =
                (
                    1.0 -
                        abs(
                            mean -
                                .30
                        )
                    )
                    .coerceIn(
                        0.0,
                        1.0,
                    )

            return (
                tone * 62 +
                    (
                        1.0 -
                            localContrast *
                                2
                        )
                        .coerceIn(
                            0.0,
                            1.0,
                        ) * 38
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )
        }

        val topReadability =
            zoneReadability(
                topLum,
                topLumSq,
                topCount,
            )

        val bottomReadability =
            zoneReadability(
                bottomLum,
                bottomLumSq,
                bottomCount,
            )

        val busyScore =
            (
                edgeDensity *
                    100f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val lockReadability =
            (
                topReadability *
                    .72f +
                    bottomReadability *
                        .12f +
                    (
                        100 -
                            busyScore
                        ) *
                        .16f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val cropSafety =
            (
                centerBalance *
                    .60f +
                    (
                        100 -
                            busyScore
                        ) *
                        .25f +
                    (
                        100 -
                            (
                                contrast *
                                    100
                                )
                                .roundToInt()
                        ) *
                        .15f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val amoledScore =
            (
                darkRatio *
                    70f +
                    (
                        1f -
                            brightness
                        ) *
                        20f +
                    contrast *
                        10f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val cinematicScore =
            (
                contrast *
                    34f +
                    (
                        1f -
                            abs(
                                brightness -
                                    .40f
                            ) *
                                2f
                        )
                        .coerceIn(
                            0f,
                            1f,
                        ) *
                        28f +
                    (
                        .45f +
                            abs(warmth) *
                                .55f
                        ) *
                        18f +
                    (
                        1f -
                            abs(
                                saturation -
                                    .48f
                            )
                        )
                        .coerceIn(
                            0f,
                            1f,
                        ) *
                        20f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val vibrantScore =
            (
                saturation *
                    65f +
                    contrast *
                        20f +
                    brightness *
                        15f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val pastelScore =
            (
                (
                    1f -
                        abs(
                            saturation -
                                .32f
                        ) *
                            2.5f
                    )
                    .coerceIn(
                        0f,
                        1f,
                    ) *
                    55f +
                    brightness *
                        35f +
                    (
                        1f -
                            contrast
                        ) *
                        10f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val monochromeScore =
            (
                monoRatio *
                    85f +
                    (
                        1f -
                            saturation
                        ) *
                        15f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val lowLightScore =
            (
                darkRatio *
                    65f +
                    (
                        1f -
                            brightness
                        ) *
                        35f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val megapixels =
            (
                originalWidth.toLong() *
                    originalHeight.toLong()
                ).toDouble() /
                1_000_000.0

        val resolutionScore =
            when {
                megapixels >= 6.0 ->
                    100

                megapixels >= 3.0 ->
                    90

                megapixels >= 2.0 ->
                    82

                megapixels >= 1.2 ->
                    70

                megapixels >= .7 ->
                    55

                else ->
                    30
            }

        val byteScore =
            when {
                bytes >=
                    1_000_000L ->
                    100

                bytes >=
                    500_000L ->
                    85

                bytes >=
                    250_000L ->
                    70

                bytes >=
                    100_000L ->
                    55

                else ->
                    35
            }

        val quality =
            (
                resolutionScore *
                    .55f +
                    byteScore *
                        .20f +
                    cropSafety *
                        .10f +
                    lockReadability *
                        .08f +
                    (
                        100 -
                            min(
                                busyScore,
                                90,
                            )
                        ) *
                        .07f
                )
                .roundToInt()
                .coerceIn(
                    0,
                    100,
                )

        val hueNames =
            arrayOf(
                "red",
                "yellow",
                "green",
                "cyan",
                "blue",
                "magenta",
            )

        val strongestHue =
            hueBins.indices
                .maxByOrNull {
                    hueBins[it]
                } ?: 0

        val dominantHue =
            if (
                saturation < .11f
            ) {
                "neutral"
            } else {
                hueNames[
                    strongestHue
                ]
            }

        val palette =
            paletteBins.entries
                .sortedByDescending {
                    it.value
                }
                .take(3)
                .map { entry ->
                    val qr =
                        (
                            entry.key shr 8
                            ) and 0xf

                    val qg =
                        (
                            entry.key shr 4
                            ) and 0xf

                    val qb =
                        entry.key and
                            0xf

                    val r =
                        (
                            qr *
                                32 +
                                16
                            )
                            .coerceAtMost(
                                255
                            )

                    val g =
                        (
                            qg *
                                32 +
                                16
                            )
                            .coerceAtMost(
                                255
                            )

                    val b =
                        (
                            qb *
                                32 +
                                16
                            )
                            .coerceAtMost(
                                255
                            )

                    String.format(
                        Locale.US,
                        "#%02X%02X%02X",
                        r,
                        g,
                        b,
                    )
                }
                .joinToString(
                    " • "
                )

        val styleClass =
            when {
                monochromeScore >= 82 ->
                    "Monochrome"

                cinematicScore >= 82 ->
                    "Cinematic"

                amoledScore >= 84 &&
                    lowLightScore >= 76 ->
                    "AMOLED Dark"

                vibrantScore >= 83 ->
                    "Vibrant"

                pastelScore >= 78 ->
                    "Pastel"

                lowLightScore >= 76 ->
                    "Low Light"

                busyScore <= 24 &&
                    centerBalance >= 72 ->
                    "Minimal"

                brightness >= .70f ->
                    "Bright"

                else ->
                    "Balanced"
            }

        return Profile(
            quality = quality,
            brightness = brightness,
            saturation = saturation,
            contrast = contrast,
            warmth = warmth,
            darkRatio = darkRatio,
            brightRatio = brightRatio,
            colorfulness = colorfulness,
            edgeDensity = edgeDensity,
            busyScore = busyScore,
            centerBalance = centerBalance,
            topReadability = topReadability,
            bottomReadability = bottomReadability,
            lockReadability = lockReadability,
            cropSafety = cropSafety,
            amoledScore = amoledScore,
            cinematicScore = cinematicScore,
            vibrantScore = vibrantScore,
            pastelScore = pastelScore,
            monochromeScore = monochromeScore,
            lowLightScore = lowLightScore,
            dominantHue = dominantHue,
            palette = palette,
            styleClass = styleClass,
            width = originalWidth,
            height = originalHeight,
        )
    }

    private fun complementary(
        first: String,
        second: String,
    ): Boolean =
        setOf(
            first,
            second,
        ) in setOf(
            setOf(
                "red",
                "cyan",
            ),
            setOf(
                "yellow",
                "blue",
            ),
            setOf(
                "green",
                "magenta",
            ),
        )

    private fun luminance(
        color: Int,
    ): Double {
        val r =
            Color.red(color) /
                255.0

        val g =
            Color.green(color) /
                255.0

        val b =
            Color.blue(color) /
                255.0

        return 0.2126 * r +
            0.7152 * g +
            0.0722 * b
    }

    private fun candidateId(
        file: File,
    ): String? {
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
                .getProperty("id")
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
        }.getOrNull()
    }

    private fun token(
        id: String,
    ): String {
        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    id.toByteArray(
                        Charsets.UTF_8
                    )
                )

        return bytes
            .take(12)
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
